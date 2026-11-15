package th.co.glr.hr.salesboard.receipts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.receipts.ReceiptFileParser.ParseResult;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.CandidateDto;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.ImportSummary;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.LatestImport;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.UnmatchedRowDto;
import th.co.glr.hr.salesboard.receipts.ReceiptImportRepository.CandidateDeal;
import th.co.glr.hr.salesboard.receipts.ReceiptImportRepository.OpenExpected;
import th.co.glr.hr.salesboard.receipts.ReceiptImportRepository.QueuedRow;
import th.co.glr.hr.salesboard.receipts.ReceiptMatcher.Match;
import th.co.glr.hr.salesboard.receipts.ReceiptMatcher.Reason;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * The Express receipts upload (spec 2026-11-15 §7.8, plan task 3.3). Who may do what — every gate is here:
 * <ul>
 *   <li>upload, latest, template — account, ceo;</li>
 *   <li>the unmatched queue — sales (only rows one of their OWN deals could be, with only their own deals offered),
 *       sales_manager / ceo / account (every row, every candidate);</li>
 *   <li>resolve (attach to a deal) — sales only onto their own deal AND only a deal the queue offers them for that
 *       row; sales_manager / ceo / account onto any ACTIVE deal;</li>
 *   <li>ignore — sales_manager / ceo / account (a rep cannot hide money from the queue).</li>
 * </ul>
 *
 * <p>Each row of a file is, in order: invalid (reported by line, nothing written); a duplicate — its document number
 * is already a receipt of any source, or already queued, or repeated earlier in the same file (nothing written: this
 * is what makes re-uploading idempotent and keeps a hand-typed receipt from counting twice); matched
 * ({@link ReceiptMatcher}) and placed ({@link ReceiptPlacement}: an EXPRESS receipt, the expected payment marked
 * RECEIVED, the deposit advanced); or queued as UNMATCHED with the matcher's reason — also when placement refuses it
 * (OVERPAYMENT, DEAL_NOT_ACTIVE). Never a guess.
 *
 * <p>Not one transaction on purpose: each placement commits alone (its own @Transactional bean), so a refused row
 * cannot roll back the rows before it, and a re-upload simply skips what already landed.
 */
@Service
public class ReceiptImportService {
    static final Set<String> UPLOAD_ROLES = Set.of("account", "ceo");
    static final Set<String> QUEUE_ROLES = Set.of("sales", "sales_manager", "ceo", "account");
    static final Set<String> QUEUE_ALL_ROLES = Set.of("sales_manager", "ceo", "account");
    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";

    private final ReceiptImportRepository imports;
    private final ReceiptPlacement placement;
    private final TicketRepository tickets;

    public ReceiptImportService(ReceiptImportRepository imports, ReceiptPlacement placement, TicketRepository tickets) {
        this.imports = imports;
        this.placement = placement;
        this.tickets = tickets;
    }

    public ImportSummary upload(String fileName, byte[] bytes, UserPrincipal actor) {
        requireRole(actor, UPLOAD_ROLES);
        String name = fileName == null || fileName.isBlank() ? "upload" : fileName.trim();
        ParseResult parsed = ReceiptFileParser.parse(name, bytes);
        long importId = imports.insertImport(name, actor.id());
        List<CandidateDeal> deals = imports.activeCandidates();
        List<ReceiptMatcher.Candidate> candidates = deals.stream().map(CandidateDeal::candidate).toList();

        Tally matched = new Tally();
        Tally duplicates = new Tally();
        Tally unmatched = new Tally();
        Set<String> seen = new HashSet<>();
        for (ParsedReceipt row : parsed.rows()) {
            String key = row.docNo().trim().toUpperCase(Locale.ROOT);
            if (!seen.add(key) || imports.isKnownDocNo(row.docNo())) {
                duplicates.add(row);
                continue;
            }
            Match match = ReceiptMatcher.match(row, candidates);
            if (!match.matched()) {
                imports.insertUnmatched(importId, row, match.reason().name());
                unmatched.add(row);
                continue;
            }
            try {
                placement.place(match.ticketId(), row, actor);
                matched.add(row);
            } catch (ApiException e) {
                if (e.getStatus() != HttpStatus.CONFLICT) {
                    throw e;
                }
                String message = e.getMessage() == null ? "" : e.getMessage();
                if (message.contains("ถูกบันทึกแล้ว")) { // the same number landed meanwhile (a concurrent upload)
                    duplicates.add(row);
                    continue;
                }
                Reason reason = message.contains("เกินยอด") ? Reason.OVERPAYMENT : Reason.DEAL_NOT_ACTIVE;
                imports.insertUnmatched(importId, row, reason.name());
                unmatched.add(row);
            }
        }
        int invalid = parsed.errors().size();
        int total = parsed.rows().size() + invalid;
        imports.finishImport(importId, total, matched.count, matched.amount, duplicates.count, duplicates.amount,
            unmatched.count, unmatched.amount, invalid);
        ImportSummary stored = imports.findImport(importId).orElseThrow();
        return new ImportSummary(stored.importId(), stored.fileName(), stored.uploadedAt(), stored.uploadedByName(),
            stored.totalRows(), stored.matched(), stored.matchedAmount(), stored.duplicates(),
            stored.duplicateAmount(), stored.unmatched(), stored.unmatchedAmount(), stored.invalid(), parsed.errors());
    }

    public LatestImport latest(UserPrincipal actor) {
        requireRole(actor, UPLOAD_ROLES);
        return new LatestImport(imports.latestImport().orElse(null), imports.countUnmatched());
    }

    public byte[] template(UserPrincipal actor) {
        requireRole(actor, UPLOAD_ROLES);
        return ReceiptFileParser.templateCsv();
    }

    public List<UnmatchedRowDto> unmatched(UserPrincipal actor) {
        requireRole(actor, QUEUE_ROLES);
        List<CandidateDeal> deals = imports.activeCandidates();
        List<UnmatchedRowDto> out = new ArrayList<>();
        for (QueuedRow row : imports.findUnmatched()) {
            List<CandidateDto> offered = offeredTo(row, deals, actor);
            if (!QUEUE_ALL_ROLES.contains(actor.role()) && offered.isEmpty()) {
                continue;
            }
            out.add(toDto(row, offered));
        }
        return out;
    }

    public UnmatchedRowDto resolve(long rowId, Long ticketId, UserPrincipal actor) {
        requireRole(actor, QUEUE_ROLES);
        if (ticketId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาเลือกดีล");
        }
        QueuedRow row = imports.findRow(rowId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการนี้"));
        tickets.findById(ticketId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
        if (!QUEUE_ALL_ROLES.contains(actor.role())) {
            // A rep attaches only onto a deal the queue offers THEM for this row (their own, a fitting customer).
            boolean offered = offeredTo(row, imports.activeCandidates(), actor).stream()
                .anyMatch(c -> c.ticketId() == ticketId);
            if (!offered) {
                throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
            }
        }
        placement.resolve(rowId, ticketId, actor);
        return toDto(imports.findRow(rowId).orElseThrow(), List.of());
    }

    public UnmatchedRowDto ignore(long rowId, UserPrincipal actor) {
        requireRole(actor, QUEUE_ALL_ROLES);
        QueuedRow row = imports.findRow(rowId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการนี้"));
        if (imports.markIgnored(rowId, actor.id()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้จับคู่หรือข้ามไปแล้ว");
        }
        return toDto(imports.findRow(row.rowId()).orElseThrow(), List.of());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * The deals worth offering for a queued row: whatever the matcher would point at (the ambiguous pair, or the
     * customer's deals). A sales rep gets only their own (owner or keyer) of those.
     */
    private List<CandidateDto> offeredTo(QueuedRow row, List<CandidateDeal> deals, UserPrincipal actor) {
        Match match = ReceiptMatcher.match(row.receipt(), deals.stream().map(CandidateDeal::candidate).toList());
        Map<Long, CandidateDeal> byId = deals.stream()
            .collect(Collectors.toMap(d -> d.candidate().ticketId(), Function.identity()));
        boolean rep = "sales".equals(actor.role());
        List<CandidateDto> out = new ArrayList<>();
        for (Long id : match.candidateIds()) {
            CandidateDeal d = byId.get(id);
            if (d == null || (rep && d.ownerId() != actor.id() && !Long.valueOf(actor.id()).equals(d.keyedById()))) {
                continue;
            }
            out.add(candidate(d, row.receipt(), match));
        }
        return out;
    }

    private static CandidateDto candidate(CandidateDeal d, ParsedReceipt row, Match match) {
        OpenExpected nearest = d.openExpected().stream()
            .min(Comparator.comparing((OpenExpected e) -> e.amount().subtract(row.amount()).abs()))
            .orElse(null);
        boolean amountFits = nearest != null && ReceiptMatcher.withinTolerance(row.amount(), nearest.amount());
        String hint = match.rule() == ReceiptMatcher.Rule.INVOICE_NO || match.rule() == ReceiptMatcher.Rule.QUOTATION_NO
            ? "เลขอ้างอิงตรง" : amountFits ? "ลูกค้า + ยอดตรง" : "ลูกค้าตรง";
        return new CandidateDto(d.candidate().ticketId(), d.title(), d.candidate().customerName(), d.ownerId(),
            d.ownerName(), hint, nearest == null ? null : nearest.amount(), nearest == null ? null : nearest.week(),
            nearest == null ? null : nearest.kind());
    }

    private static UnmatchedRowDto toDto(QueuedRow row, List<CandidateDto> candidates) {
        ParsedReceipt r = row.receipt();
        return new UnmatchedRowDto(row.rowId(), row.importId(), r.docNo(), r.docDate(), r.customerName(), r.amount(),
            r.reference(), row.reason(), row.status(), row.resolvedTicketId(), candidates);
    }

    private static void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
    }

    private static final class Tally {
        int count;
        BigDecimal amount = BigDecimal.ZERO;

        void add(ParsedReceipt row) {
            count++;
            amount = amount.add(row.amount());
        }
    }
}
