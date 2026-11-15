package th.co.glr.hr.salesboard.receipts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.receipts.ReceiptFileParser.ParseResult;
import th.co.glr.hr.salesboard.receipts.ReceiptFileParser.RowError;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.Allocation;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.CandidateDto;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.DealOption;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.ImportSummary;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.LatestImport;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.ManagerView;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.ResolveRequest;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.UnmatchedRowDto;
import th.co.glr.hr.salesboard.receipts.ReceiptImportRepository.CandidateDeal;
import th.co.glr.hr.salesboard.receipts.ReceiptImportRepository.OpenExpected;
import th.co.glr.hr.salesboard.receipts.ReceiptImportRepository.QueuedRow;
import th.co.glr.hr.salesboard.receipts.ReceiptMatcher.Match;
import th.co.glr.hr.ticket.ExpressReceiptRefusal;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * The Express receipts upload (spec 2026-11-15 §7.8). Who may do what — every gate is here (review H2/H3):
 * <ul>
 *   <li>upload, latest, template, the queue (list, ignored list, resolve, split, ignore, un-ignore, flag for the
 *       manager), the deal picker — ACCOUNT ONLY. Not the CEO (segregation of duties: the role that approves and
 *       closes deals does not also put cash on them), not sales, not sales_manager;</li>
 *   <li>{@link #managerView} — sales_manager / ceo, read-only: how many rows wait, plus only the rows account flagged;</li>
 *   <li>everyone else — 403.</li>
 * </ul>
 * The whole upload is off unless {@code app.receipts.express-upload.enabled} (ReceiptImportSettings, H4).
 *
 * <p>Each row of a file is, in order: invalid (parser) · a non-cash document type (H4) · a duplicate (its number is a
 * receipt of any source, a linked manual receipt, already queued, or repeated in the file) · matched against the
 * CURRENT candidates (reloaded per row, M1) → the manual twin (H1: linked, nothing written) or placed
 * ({@link ReceiptPlacement}) · else queued UNMATCHED with the matcher's reason. A placement refusal is classified by
 * its code (ExpressReceiptRefusal, M2), never by message text: DUPLICATE (a concurrent upload landed it) counts as a
 * duplicate, OVERPAYMENT / DEAL_NOT_ACTIVE queue it. Any OTHER failure on a row is that row's error (M3); the stored
 * summary is written in a finally block so it is always what happened.
 */
@Service
public class ReceiptImportService {
    static final Set<String> ACCOUNT_ROLES = Set.of("account");
    static final Set<String> MANAGER_VIEW_ROLES = Set.of("sales_manager", "ceo");
    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";
    private static final Logger log = LoggerFactory.getLogger(ReceiptImportService.class);

    private final ReceiptImportRepository imports;
    private final ReceiptPlacement placement;
    private final TicketRepository tickets;
    private final ReceiptImportSettings settings;

    public ReceiptImportService(ReceiptImportRepository imports, ReceiptPlacement placement, TicketRepository tickets,
                                ReceiptImportSettings settings) {
        this.imports = imports;
        this.placement = placement;
        this.tickets = tickets;
        this.settings = settings;
    }

    /** Running tallies of one upload. */
    static final class Counts {
        final Tally matched = new Tally();
        final Tally possibleManual = new Tally();
        final Tally duplicates = new Tally();
        final Tally unmatched = new Tally();
        final Tally nonCash = new Tally();
        int invalid;
        int failed;

        int total() {
            return matched.count + possibleManual.count + duplicates.count + unmatched.count + nonCash.count
                + invalid + failed;
        }
    }

    static final class Tally {
        int count;
        BigDecimal amount = BigDecimal.ZERO;

        void add(ParsedReceipt row) {
            count++;
            amount = amount.add(row.amount());
        }
    }

    public ImportSummary upload(String fileName, byte[] bytes, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        if (!settings.uploadEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "ยังไม่เปิดใช้การอัปโหลดไฟล์ Express",
                Map.of("code", "UPLOAD_DISABLED"));
        }
        String name = fileName == null || fileName.isBlank() ? "upload" : fileName.trim();
        ParseResult parsed = ReceiptFileParser.parse(name, bytes);
        long importId = imports.insertImport(name, actor.id());
        Counts counts = new Counts();
        counts.invalid = parsed.errors().size();
        List<RowError> errors = new ArrayList<>(parsed.errors());
        Set<String> seen = new HashSet<>();
        try {
            for (ParsedReceipt row : parsed.rows()) {
                try {
                    placeRow(importId, row, seen, counts, actor);
                } catch (RuntimeException e) {
                    // Any failure on one row is that row's error, never the file's (review M3).
                    log.warn("receipt import {} line {} ({}) failed", importId, row.line(), row.docNo(), e);
                    counts.failed++;
                    errors.add(new RowError(row.line(), "บันทึกไม่สำเร็จ (" + row.docNo() + ") — ลองอัปโหลดใหม่"));
                }
            }
        } finally {
            imports.finishImport(importId, counts);
        }
        errors.sort(Comparator.comparingInt(RowError::line));
        ImportSummary s = imports.findImport(importId).orElseThrow();
        return new ImportSummary(s.importId(), s.fileName(), s.uploadedAt(), s.uploadedByName(), s.totalRows(),
            s.matched(), s.matchedAmount(), s.possibleManualDuplicates(), s.duplicates(), s.duplicateAmount(),
            s.unmatched(),
            s.unmatchedAmount(), s.nonCash(), s.invalid(), s.failed(), errors);
    }

    private void placeRow(long importId, ParsedReceipt row, Set<String> seen, Counts counts, UserPrincipal actor) {
        if (!settings.isCash(row.docType())) {
            counts.nonCash.add(row);
            return;
        }
        String key = row.docNo().trim().toUpperCase(Locale.ROOT);
        if (!seen.add(key) || imports.isKnownDocNo(row.docNo())) {
            counts.duplicates.add(row);
            return;
        }
        // Reloaded per row: what the rows above placed (a RECEIVED expected payment, a settled deposit) must count.
        Match match = ReceiptMatcher.match(row, candidatesOnly(imports.activeCandidates()));
        if (!match.matched()) {
            queue(importId, row, match.reason().name(), null, counts, counts.unmatched);
            return;
        }
        // Review N1: a hand-typed receipt that fits is only a POSSIBILITY — the same amount within a week can be a
        // different payment (probe Q3). Never linked here: queued for account to decide, nothing written.
        Optional<Long> twin = imports.findManualTwin(match.ticketId(), row.amount(), row.docDate());
        if (twin.isPresent()) {
            queue(importId, row, POSSIBLE_MANUAL_DUPLICATE, twin.get(), counts, counts.possibleManual);
            return;
        }
        try {
            placement.place(match.ticketId(), row, actor);
            counts.matched.add(row);
        } catch (ExpressReceiptRefusal refusal) {
            switch (refusal.reason()) {
                case DUPLICATE -> counts.duplicates.add(row);
                case OVERPAYMENT, DEAL_NOT_ACTIVE ->
                    queue(importId, row, refusal.reason().name(), null, counts, counts.unmatched);
            }
        }
    }

    public static final String POSSIBLE_MANUAL_DUPLICATE = "POSSIBLE_MANUAL_DUPLICATE";

    /**
     * Queues a row. If another upload queued the same number between this upload's check and its insert, the unique
     * doc-no index refuses it — that is a duplicate, not a failure (review N4).
     */
    private void queue(long importId, ParsedReceipt row, String reason, Long suggestedReceiptId, Counts counts,
                       Tally into) {
        try {
            imports.insertUnmatched(importId, row, reason, suggestedReceiptId);
            into.add(row);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            counts.duplicates.add(row);
        }
    }

    public LatestImport latest(UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        return new LatestImport(settings.uploadEnabled(), imports.latestImport().orElse(null),
            imports.countUnmatched());
    }

    public byte[] template(UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        return ReceiptFileParser.templateCsv();
    }

    public List<UnmatchedRowDto> unmatched(UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        List<CandidateDeal> deals = imports.activeCandidates();
        return imports.findByStatus(ReceiptImportDtos.UNMATCHED).stream()
            .map(row -> toDto(row, offered(row, deals))).toList();
    }

    private UnmatchedRowDto toDto(QueuedRow row, List<CandidateDto> candidates) {
        ReceiptImportDtos.PossibleManual manual = row.suggestedReceiptId() == null ? null
            : imports.manualReceipt(row.suggestedReceiptId()).orElse(null);
        ParsedReceipt r = row.receipt();
        return new UnmatchedRowDto(row.rowId(), row.importId(), r.docNo(), r.docDate(), r.customerName(), r.amount(),
            r.reference(), row.reason(), row.status(), row.resolvedTicketId(), row.managerFlaggedAt(),
            row.suggestedReceiptId(), manual, candidates);
    }

    public List<UnmatchedRowDto> ignored(UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        return imports.findByStatus(ReceiptImportDtos.IGNORED).stream().map(row -> toDto(row, List.of())).toList();
    }

    public ManagerView managerView(UserPrincipal actor) {
        requireRole(actor, MANAGER_VIEW_ROLES);
        return new ManagerView(imports.countUnmatched(), imports.flaggedUnmatched());
    }

    public List<DealOption> searchDeals(String q, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        return imports.searchDeals(q);
    }

    /**
     * Attaches a queued row: to one deal ({@code ticketId}), or split across several ({@code allocations}, whose
     * amounts must sum EXACTLY to the row, review M7). Every target must be ACTIVE and inside account's finance scope
     * (M4) — else 403.
     */
    public UnmatchedRowDto resolve(long rowId, ResolveRequest request, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        QueuedRow row = imports.findRow(rowId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการนี้"));
        List<Allocation> lines = allocations(request, row);
        for (Allocation a : lines) {
            tickets.findById(a.ticketId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
            if (!imports.isCandidateDeal(a.ticketId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
            }
        }
        placement.resolve(rowId, lines, actor);
        return toDto(imports.findRow(rowId).orElseThrow(), List.of());
    }

    private static List<Allocation> allocations(ResolveRequest request, QueuedRow row) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาเลือกดีล");
        }
        if (request.allocations() == null || request.allocations().isEmpty()) {
            if (request.ticketId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาเลือกดีล");
            }
            return List.of(new Allocation(request.ticketId(), row.receipt().amount()));
        }
        BigDecimal sum = BigDecimal.ZERO;
        Set<Long> distinct = new HashSet<>();
        for (Allocation a : request.allocations()) {
            if (a == null || a.ticketId() == null || a.amount() == null || a.amount().signum() <= 0
                || a.amount().stripTrailingZeros().scale() > 2 || !distinct.add(a.ticketId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "แต่ละดีลต้องมียอดมากกว่า 0 และไม่ซ้ำกัน");
            }
            sum = sum.add(a.amount());
        }
        if (sum.compareTo(row.receipt().amount()) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ยอดที่แบ่งรวมกันต้องเท่ากับยอดรายการพอดี (" + row.receipt().amount().toPlainString() + ")");
        }
        return List.copyOf(request.allocations());
    }

    public UnmatchedRowDto ignore(long rowId, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        requireRow(rowId);
        if (imports.markIgnored(rowId, actor.id()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้จับคู่หรือข้ามไปแล้ว");
        }
        return toDto(requireRow(rowId), List.of());
    }

    public UnmatchedRowDto unignore(long rowId, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        requireRow(rowId);
        if (imports.markUnignored(rowId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ไม่ได้อยู่ในรายการที่ข้ามไป");
        }
        return toDto(requireRow(rowId), List.of());
    }

    /**
     * "รายการเดียวกับที่บันทึกเอง" (review N1): account confirms a POSSIBLE_MANUAL_DUPLICATE row IS the hand-typed
     * receipt — it is linked to the Express number and nothing is written. "คนละรายการ" is {@link #resolve}.
     */
    public UnmatchedRowDto sameAsManual(long rowId, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        requireRow(rowId);
        placement.sameAsManual(rowId, actor);
        return toDto(requireRow(rowId), List.of());
    }

    /** "ส่งให้ผู้จัดการขายดู" (review M5): shows the row in the manager's read-only view; account still resolves it. */
    public UnmatchedRowDto flagForManager(long rowId, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        requireRow(rowId);
        if (imports.flagForManager(rowId, actor.id()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้จับคู่หรือข้ามไปแล้ว");
        }
        return toDto(requireRow(rowId), List.of());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private QueuedRow requireRow(long rowId) {
        return imports.findRow(rowId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการนี้"));
    }

    private static List<ReceiptMatcher.Candidate> candidatesOnly(List<CandidateDeal> deals) {
        return deals.stream().map(CandidateDeal::candidate).toList();
    }

    /** The deals worth offering for a queued row: whatever the matcher would point at. */
    private static List<CandidateDto> offered(QueuedRow row, List<CandidateDeal> deals) {
        Match match = ReceiptMatcher.match(row.receipt(), candidatesOnly(deals));
        Map<Long, CandidateDeal> byId = deals.stream()
            .collect(Collectors.toMap(d -> d.candidate().ticketId(), Function.identity()));
        List<CandidateDto> out = new ArrayList<>();
        for (Long id : match.candidateIds()) {
            CandidateDeal d = byId.get(id);
            if (d != null) {
                out.add(candidate(d, row.receipt(), match));
            }
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
        return new CandidateDto(d.candidate().ticketId(), d.title(),
            d.candidate().customerNames().stream().findFirst().orElse(null), d.ownerId(), d.ownerName(), hint,
            nearest == null ? null : nearest.amount(), nearest == null ? null : nearest.week(),
            nearest == null ? null : nearest.kind());
    }


    private static void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
    }
}
