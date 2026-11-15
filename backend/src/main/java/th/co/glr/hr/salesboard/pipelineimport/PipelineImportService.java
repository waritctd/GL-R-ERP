package th.co.glr.hr.salesboard.pipelineimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.salesboard.SalesNames;
import th.co.glr.hr.salesboard.pipelineimport.PipelineFields.Parsed;
import th.co.glr.hr.salesboard.pipelineimport.PipelineImportDtos.PipelineImportResult;
import th.co.glr.hr.salesboard.pipelineimport.PipelineImportDtos.RowIssue;
import th.co.glr.hr.salesboard.pipelineimport.PipelineImportDtos.RowPlan;
import th.co.glr.hr.salesboard.pipelineimport.PipelineImportRepository.ExistingDeal;
import th.co.glr.hr.salesboard.pipelineimport.PipelineImportRepository.Fill;
import th.co.glr.hr.salesboard.pipelineimport.PipelineImportRepository.OwnerDeal;
import th.co.glr.hr.salesboard.pipelineimport.PipelineImportRepository.Rep;
import th.co.glr.hr.salesboard.pipelineimport.PipelineSheet.Column;
import th.co.glr.hr.salesboard.pipelineimport.PipelineSheet.SheetRow;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * The one-time pipeline import (spec 2026-11-15 §7.9, plan Task 4.1, Decision 5): one standard template, a dry run
 * that writes nothing, then a commit.
 *
 * <p><b>Commit is all-or-nothing per file.</b> The commit re-reads and re-analyses the uploaded file inside ONE
 * transaction (never trusting a plan the client echoes back), holds an advisory lock so two commits cannot interleave,
 * and writes the rows the dry run marked CREATE / UPDATE. Rows the dry run marked SKIP are never written — that is
 * reported, not silent — and any failure while writing rolls back every row of the file. Chosen over per-row commits
 * because a half-imported file is the one outcome the office could not reason about: re-running it is always safe
 * (idempotent), whereas a partial write leaves "which rows made it?" to guess.
 *
 * <p><b>Idempotency</b> (Review Focus 5): a row matches an existing deal OF THE SAME OWNER by quotation number (the
 * imported one, or one of the deal's own quotations), else by normalised project title — and then only fills blank
 * fields. A re-run of the same file therefore finds every deal and creates nothing.
 *
 * <p><b>What it deliberately does not do</b> (review round 1, documented for the office and the PR):
 * <ul>
 *   <li>It never moves an EXISTING deal's stage, and it lands a NEW deal only on a stage whose facts hold for a deal
 *   with no documents (S3 without a quotation in the app → S2). S7–S9 rows are skipped: a deposit, an IR or a
 *   delivery is recorded through its real document, never from a sheet.</li>
 *   <li>It creates no quotations, payments or documents — the sheet's quotation number and value are stored as
 *   {@code imported_quotation_no} / {@code imported_value} only (the board and forecast show the value until a real
 *   quotation exists).</li>
 *   <li>The import's STAGE_CHANGED event is a touch for the 14-day quiet rule, so a freshly imported deal is not
 *   "quiet" until 14 days after the import, whatever the sheet's own history.</li>
 *   <li>A customer it creates holds a name only (branch = สำนักงานใหญ่); tax id and address are completed later.</li>
 * </ul>
 */
@Service
public class PipelineImportService {
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int MAX_ROWS = 2000;
    static final String STAGE_NOTE = "นำเข้าจากไฟล์ pipeline";
    static final String FILL_NOTE = "เติมข้อมูลจากไฟล์ pipeline: ";
    static final String NEEDS_DOCUMENT =
        "ดีลที่ได้มัดจำ/ส่งของแล้ว ต้องบันทึกผ่านเอกสารจริง (ใบแจ้งมัดจำ/IR) ไม่นำเข้าจากชีท";
    static final String NEEDS_CUSTOMER = "ต้องมีผู้ซื้อหรือเจ้าของโครงการ";

    /** The app's stage-fact gate for the deal the import is about to create: a brand-new deal has no payment track,
     * no deliveries and no quotations, so every fact-gated stage is refused (H1, review round 1). The SAME function
     * TicketService#requireStageFactsHold throws from, not a copy. */
    static final java.util.function.UnaryOperator<String> FRESH_DEAL_FACTS =
        stage -> TicketService.stageFactRefusal(null, null, Set.of(), stage);

    private static final Set<String> ROLES = Set.of("sales_manager", "ceo");
    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";

    private final PipelineImportRepository repo;
    private final TicketService ticketService;
    private final TicketRepository tickets;
    private final CustomerRepository customers;
    private final ProjectRepository projects;

    public PipelineImportService(PipelineImportRepository repo, TicketService ticketService, TicketRepository tickets,
                                 CustomerRepository customers, ProjectRepository projects) {
        this.repo = repo;
        this.ticketService = ticketService;
        this.tickets = tickets;
        this.customers = customers;
        this.projects = projects;
    }

    @Transactional(readOnly = true)
    public PipelineImportResult dryRun(MultipartFile file, UserPrincipal actor) {
        requireRole(actor);
        PipelineSheet.ParsedSheet sheet = read(file);
        return result(analyse(sheet.rows()), sheet.unknownHeaders(), false);
    }

    @Transactional
    public PipelineImportResult commit(MultipartFile file, UserPrincipal actor) {
        requireRole(actor);
        PipelineSheet.ParsedSheet sheet = read(file);
        repo.lockForCommit();
        List<Planned> plan = analyse(sheet.rows());
        Map<String, Long> createdCustomers = new HashMap<>();
        for (Planned p : plan) {
            if (PipelineImportDtos.CREATE.equals(p.action)) {
                create(p, actor, createdCustomers);
            } else if (PipelineImportDtos.UPDATE.equals(p.action)) {
                repo.fillBlanks(p.ticketId, p.fill);
                tickets.addEvent(p.ticketId, actor.id(), actor.name(), TicketEventKind.EDITED, null, null,
                    FILL_NOTE + String.join(", ", p.changes));
            }
        }
        return result(plan, sheet.unknownHeaders(), true);
    }

    public byte[] template(UserPrincipal actor) {
        requireRole(actor);
        return PipelineTemplate.xlsx();
    }

    // ── analysis ─────────────────────────────────────────────────────────────

    /** One analysed row; mutable only while the commit fills in the created deal's id. */
    private static final class Planned {
        final int row;
        String action;
        String project;
        String designer;
        String ownerName;
        String repCode;
        BigDecimal value;
        Long ticketId;
        String ticketCode;
        List<String> changes = List.of();
        final List<RowIssue> issues = new ArrayList<>();
        // what a CREATE writes
        long repId;
        String channel;
        String stage;
        String customerName;
        Long customerId;
        String newCustomer;
        boolean needsDocument;
        String note;
        Fill fill;

        Planned(int row) {
            this.row = row;
        }

        void error(String message) {
            issues.add(new RowIssue(row, PipelineImportDtos.ERROR, message));
        }

        void warn(String message) {
            issues.add(new RowIssue(row, PipelineImportDtos.WARNING, message));
        }

        boolean failed() {
            return issues.stream().anyMatch(i -> PipelineImportDtos.ERROR.equals(i.severity()));
        }
    }

    /** Per-analysis lookups, loaded once: an owner's deals, the customer register. */
    private final class Lookups {
        final Map<Long, List<OwnerDeal>> deals = new HashMap<>();
        List<PipelineImportRepository.CustomerName> customerNames;

        List<OwnerDeal> deals(long ownerId) {
            return deals.computeIfAbsent(ownerId, repo::ownerDeals);
        }

        List<PipelineImportRepository.CustomerName> customers() {
            if (customerNames == null) {
                customerNames = repo.customerNames();
            }
            return customerNames;
        }
    }

    private List<Planned> analyse(List<SheetRow> rows) {
        List<Planned> out = new ArrayList<>();
        Map<String, Integer> seenQuotation = new HashMap<>();
        Map<String, Integer> seenTitle = new HashMap<>();
        Map<Long, Integer> seenDeal = new HashMap<>();
        Map<String, Optional<Rep>> repCache = new HashMap<>();
        Set<String> plannedNewCustomers = new java.util.HashSet<>();
        Lookups lookups = new Lookups();
        for (SheetRow r : rows) {
            Planned p = new Planned(r.rowNumber());
            out.add(p);
            p.project = r.text(Column.PROJECT);
            p.designer = r.text(Column.DESIGNER);
            String owner = r.text(Column.OWNER);
            String buyer = r.text(Column.BUYER);
            String quotationNo = r.text(Column.QUOTATION_NO);
            List<String> bases = PipelineFields.quotationBases(quotationNo);
            p.note = r.text(Column.NOTE);

            Rep rep = null;
            String repText = r.text(Column.REP);
            if (repText == null) {
                p.error("ไม่มีพนักงานขาย (รหัสพนักงานหรืออีเมล)");
            } else {
                Optional<Rep> found = repCache.computeIfAbsent(repText.toLowerCase(java.util.Locale.ROOT),
                    k -> repo.findRep(repText));
                if (found.isEmpty()) {
                    p.error("ไม่พบพนักงานขาย '" + repText + "' — ใช้รหัสพนักงานหรืออีเมลในระบบ");
                } else if (!found.get().eligible()) {
                    p.error("'" + repText + "' ไม่ใช่พนักงานขาย — ดีลต้องเป็นของพนักงานขาย");
                } else {
                    rep = found.get();
                    p.repId = rep.employeeId();
                    p.ownerName = rep.name();
                }
            }
            if (p.project == null) {
                p.error("แถว " + p.row + " ไม่มีชื่อโครงการ");
            }
            Parsed<String> code = PipelineFields.repCode(r.text(Column.STATUS));
            if (code.error() != null) {
                p.error(code.error());
            } else if (PipelineStages.needsDocument(code.value())) {
                // H1: S7–S9 claim a deposit received or goods moved — facts only the real documents record.
                p.needsDocument = true;
                p.error(NEEDS_DOCUMENT);
            }
            Parsed<BigDecimal> value = PipelineFields.value(r.cell(Column.VALUE).text(),
                r.cell(Column.VALUE).numeric());
            if (value.error() != null) {
                p.error(value.error());
            }
            p.value = value.value();
            Parsed<Integer> probability = PipelineFields.probability(r.cell(Column.PROBABILITY).text(),
                r.cell(Column.PROBABILITY).numeric(), r.cell(Column.PROBABILITY).percent());
            if (probability.error() != null) {
                p.error(probability.error());
            }
            Parsed<String> quarter = PipelineFields.quarter(r.cell(Column.QUARTER).text(),
                r.cell(Column.QUARTER).date());
            if (quarter.error() != null) {
                p.error(quarter.error());
            }
            p.channel = PipelineStages.channelFor(p.designer, owner, buyer);
            // M6: the customer is the buyer, else the project owner — never the designer.
            p.customerName = buyer != null ? buyer : owner;
            p.fill = new Fill(p.designer, owner, buyer, quarter.value(), probability.value(), value.value(),
                quotationNo, p.note);

            if (rep == null || p.project == null) {
                p.action = PipelineImportDtos.SKIP;
                continue;
            }
            // In-file duplicates: an overlapping quotation base, or the same title, of the same owner.
            String titleKey = rep.employeeId() + "|" + PipelineFields.normalise(p.project);
            Integer earlier = seenTitle.get(titleKey);
            for (String base : bases) {
                Integer seen = seenQuotation.get(rep.employeeId() + "|" + base);
                if (seen != null && (earlier == null || seen < earlier)) {
                    earlier = seen;
                }
            }
            if (earlier != null) {
                p.error("ซ้ำกับแถว " + earlier + " — ดีลเดียวกันอยู่ในไฟล์สองครั้ง");
            }
            if (p.failed()) {
                p.action = PipelineImportDtos.SKIP;
                continue;
            }
            for (String base : bases) {
                seenQuotation.putIfAbsent(rep.employeeId() + "|" + base, p.row);
            }
            seenTitle.putIfAbsent(titleKey, p.row);

            List<OwnerDeal> matches = match(lookups.deals(rep.employeeId()), bases, p.project);
            if (matches.isEmpty()) {
                List<String> closed = closedMatches(lookups.deals(rep.employeeId()), bases, p.project);
                if (!closed.isEmpty()) {
                    // H3: only ACTIVE deals are matched; a row that only finds a closed/lost deal is never re-created.
                    p.error("ดีลนี้ปิดหรือเสียงานไปแล้ว (" + String.join(", ", closed) + ")");
                    p.action = PipelineImportDtos.SKIP;
                    continue;
                }
            }
            if (matches.size() > 1) {
                p.error("ตรงกับหลายดีลในระบบ (" + String.join(", ", matches.stream()
                    .map(d -> d.deal().code()).toList()) + ") — รวมหรือแก้ในระบบก่อน");
                p.action = PipelineImportDtos.SKIP;
                continue;
            }
            if (matches.size() == 1) {
                OwnerDeal d = matches.get(0);
                Integer otherRow = seenDeal.putIfAbsent(d.deal().ticketId(), p.row);
                if (otherRow != null) {
                    p.error("ซ้ำกับแถว " + otherRow + " — ตรงกับดีล " + d.deal().code() + " เดียวกัน");
                    p.action = PipelineImportDtos.SKIP;
                    continue;
                }
                List<String> theirs = PipelineFields.quotationBases(d.quotationNumbers());
                if (!bases.isEmpty() && !theirs.isEmpty() && theirs.stream().noneMatch(bases::contains)) {
                    p.warn("เลขที่ใบเสนอราคาไม่ตรงกับดีล " + d.deal().code() + " (ในระบบ " + String.join(", ", theirs)
                        + ") — ถือเป็นดีลเดียวกันตามชื่อโครงการ");
                }
                planUpdate(p, d.deal(), code.value());
                continue;
            }
            if (p.customerName == null) {
                p.error(NEEDS_CUSTOMER);
                p.action = PipelineImportDtos.SKIP;
                continue;
            }
            planCustomer(p, lookups, plannedNewCustomers);
            PipelineStages.StageChoice choice = PipelineStages.stageFor(code.value(), p.channel, FRESH_DEAL_FACTS);
            p.stage = choice.stage();
            p.repCode = DealStage.repCodeOf(choice.stage());
            if (choice.warning() != null) {
                p.warn(choice.warning());
            }
            p.action = PipelineImportDtos.CREATE;
        }
        return out;
    }

    /**
     * The owner's ACTIVE deals this row is (H3: never a closed or lost one). The list is already one owner's. By
     * quotation BASE first — revision suffix stripped, case and spaces folded, every number in the cell against every
     * number on the deal (imported, or its own quotations) — so a "-7" → "-8" bump is the same deal (H2). Else by
     * normalised title or project name; a title match whose quotation base differs is still the same deal (the
     * caller warns) — it never creates a second one.
     */
    private static List<OwnerDeal> match(List<OwnerDeal> ownerDeals, List<String> bases, String project) {
        List<OwnerDeal> active = ownerDeals.stream().filter(PipelineImportService::active).toList();
        List<OwnerDeal> byQuotation = byQuotation(active, bases);
        return byQuotation.isEmpty() ? byTitle(active, project) : byQuotation;
    }

    /** Codes of the non-ACTIVE deals this row would have matched — reported, never re-created (H3). */
    private static List<String> closedMatches(List<OwnerDeal> ownerDeals, List<String> bases, String project) {
        List<OwnerDeal> closed = ownerDeals.stream().filter(d -> !active(d)).toList();
        return java.util.stream.Stream.concat(byQuotation(closed, bases).stream(), byTitle(closed, project).stream())
            .map(d -> d.deal().code()).distinct().toList();
    }

    private static List<OwnerDeal> byQuotation(List<OwnerDeal> deals, List<String> bases) {
        if (bases.isEmpty()) {
            return List.of();
        }
        return deals.stream()
            .filter(d -> PipelineFields.quotationBases(d.quotationNumbers()).stream().anyMatch(bases::contains))
            .toList();
    }

    private static List<OwnerDeal> byTitle(List<OwnerDeal> deals, String project) {
        String title = PipelineFields.normalise(project);
        return deals.stream()
            .filter(d -> title.equals(PipelineFields.normalise(d.title()))
                || (d.projectName() != null && title.equals(PipelineFields.normalise(d.projectName()))))
            .toList();
    }

    private static boolean active(OwnerDeal d) {
        return DealLifecycle.ACTIVE.equals(d.deal().lifecycle());
    }

    /** M6: reuse an existing customer whose name folds the same ({@link SalesNames#normaliseCustomer}); warn when
     * several do (the lowest id is used, and named — never a silent pick); otherwise announce the new customer. */
    private static void planCustomer(Planned p, Lookups lookups, Set<String> plannedNew) {
        String key = SalesNames.normaliseCustomer(p.customerName);
        List<PipelineImportRepository.CustomerName> same = lookups.customers().stream()
            .filter(c -> key.equals(SalesNames.normaliseCustomer(c.name()))).toList();
        if (same.isEmpty()) {
            if (plannedNew.add(key)) {
                p.newCustomer = p.customerName;
            }
            return;
        }
        PipelineImportRepository.CustomerName used = same.get(0);
        p.customerId = used.id();
        if (same.size() > 1) {
            p.warn("พบลูกค้าชื่อซ้ำ " + same.size() + " ราย ใช้ " + used.name() + " (#" + used.id()
                + ") — รวมลูกค้าในระบบภายหลังได้");
        }
    }

    private static void planUpdate(Planned p, ExistingDeal d, String fileCode) {
        p.ticketId = d.ticketId();
        p.ticketCode = d.code();
        p.repCode = DealStage.repCodeOf(d.salesStage());
        Fill f = p.fill;
        List<String> changes = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        compare(f.designerName(), d.designerName(), "ผู้ออกแบบ", changes, kept);
        compare(f.ownerName(), d.ownerName(), "เจ้าของโครงการ", changes, kept);
        compare(f.buyerName(), d.buyerName(), "ผู้ซื้อ", changes, kept);
        compare(f.expectedQuarter(), d.expectedQuarter(), "ช่วงเวลา", changes, kept);
        compare(f.winProbability() == null ? null : String.valueOf(f.winProbability()),
            d.winProbability() == null ? null : String.valueOf(d.winProbability()), "โอกาสขาย", changes, kept);
        compareMoney(f.importedValue(), d.importedValue(), changes, kept);
        // A differing quotation number or note is not "kept" news (the QN mismatch has its own warning), but compare()
        // still needs a mutable sink: List.of() here threw UnsupportedOperationException → 500 (review round 1).
        compare(f.importedQuotationNo(), d.importedQuotationNo(), "เลขที่ใบเสนอราคา", changes, new ArrayList<>());
        compare(f.note(), d.note(), "หมายเหตุ", changes, new ArrayList<>());
        if (fileCode != null && !fileCode.equals(p.repCode)) {
            p.warn("สถานะในไฟล์ " + fileCode + " ต่างจากในระบบ " + p.repCode + " — ไม่เปลี่ยนสถานะดีลที่มีอยู่");
        }
        if (!kept.isEmpty()) {
            p.warn("คงค่าเดิมในระบบ: " + String.join(", ", kept));
        }
        if (!DealLifecycle.ACTIVE.equals(d.lifecycle())) {
            p.warn("ดีล " + d.code() + " ไม่ได้อยู่ระหว่างดำเนินการ (" + d.lifecycle() + ")");
        }
        p.changes = List.copyOf(changes);
        p.action = changes.isEmpty() ? PipelineImportDtos.UNCHANGED : PipelineImportDtos.UPDATE;
    }

    private static void compare(String fromFile, String existing, String label, List<String> changes,
                                List<String> kept) {
        if (fromFile == null) {
            return;
        }
        if (PipelineFields.text(existing) == null) {
            changes.add(label);
        } else if (!PipelineFields.normalise(existing).equals(PipelineFields.normalise(fromFile))) {
            kept.add(label);
        }
    }

    private static void compareMoney(BigDecimal fromFile, BigDecimal existing, List<String> changes,
                                     List<String> kept) {
        if (fromFile == null) {
            return;
        }
        if (existing == null) {
            changes.add("มูลค่า");
        } else if (existing.compareTo(fromFile) != 0) {
            kept.add("มูลค่า");
        }
    }

    // ── writes ───────────────────────────────────────────────────────────────

    /** Through {@link TicketService#createForPipelineImport} — the ordinary creation path — then the stage is set
     * directly with its STAGE_CHANGED event, and the tracking fields are filled. */
    private void create(Planned p, UserPrincipal actor, Map<String, Long> createdCustomers) {
        String key = SalesNames.normaliseCustomer(p.customerName);
        long customerId = p.customerId != null ? p.customerId
            // Name only; branch is the column's own default (V16). The office completes tax id / address later. A
            // second row naming the same new customer reuses the one this commit just created.
            : createdCustomers.computeIfAbsent(key,
                k -> customers.create(p.customerName, null, null, "สำนักงานใหญ่", null).id());
        long projectId = repo.projectByName(customerId, PipelineFields.normalise(p.project))
            .orElseGet(() -> projects.create(customerId, p.project).id());
        TicketDto created = ticketService.createForPipelineImport(new CreateTicketRequest(p.project, null,
            p.customerName, customerId, projectId, null, p.note, p.channel, List.of(), null, null, p.repId), actor);
        var summary = created.summary();
        long id = summary.id();
        // Belt and braces for H1: the stage was chosen for a fact-less new deal; re-ask the same gate against the deal
        // that now exists, and abort the whole file rather than write a stage whose fact does not hold.
        String refusal = TicketService.stageFactRefusal(summary.paymentStatus(), summary.fulfillmentStatus(),
            summary.quotedRecipients(), p.stage);
        if (refusal != null) {
            throw new IllegalStateException("pipeline import chose a stage whose fact does not hold: " + refusal);
        }
        if (!DealStage.LEAD_APPROACH.equals(p.stage)) {
            tickets.updateSalesStage(id, p.stage);
            tickets.addEvent(id, actor.id(), actor.name(), TicketEventKind.STAGE_CHANGED, DealStage.LEAD_APPROACH,
                p.stage, STAGE_NOTE);
        }
        repo.fillBlanks(id, p.fill);
        p.ticketId = id;
        p.ticketCode = summary.code();
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private static void requireRole(UserPrincipal actor) {
        if (actor == null || !ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
    }

    private static PipelineSheet.ParsedSheet read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่มีไฟล์ หรือไฟล์ว่างเปล่า");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ใหญ่เกิน 5 MB — แบ่งเป็นหลายไฟล์");
        }
        try {
            return PipelineSheet.readSheet(file.getOriginalFilename(), file.getBytes(), MAX_ROWS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PipelineImportResult result(List<Planned> plan, List<String> unknownHeaders, boolean committed) {
        int needsDocument = 0;
        int create = 0;
        int update = 0;
        int unchanged = 0;
        int skipped = 0;
        List<RowPlan> rows = new ArrayList<>();
        List<RowIssue> issues = new ArrayList<>();
        for (Planned p : plan) {
            switch (p.action) {
                case PipelineImportDtos.CREATE -> create++;
                case PipelineImportDtos.UPDATE -> update++;
                case PipelineImportDtos.UNCHANGED -> unchanged++;
                default -> skipped++;
            }
            if (p.needsDocument) {
                needsDocument++;
            }
            rows.add(new RowPlan(p.row, p.action, p.project, p.designer, p.ownerName, p.repCode, p.value,
                p.ticketId, p.ticketCode, p.changes, p.newCustomer));
            issues.addAll(p.issues);
        }
        if (!unknownHeaders.isEmpty()) {
            issues.add(new RowIssue(0, PipelineImportDtos.WARNING,
                "ไม่รู้จักคอลัมน์: " + String.join(", ", unknownHeaders) + " — ไม่ถูกนำเข้า"));
        }
        issues.sort(Comparator.comparingInt(RowIssue::row));
        return new PipelineImportResult(plan.size(), create, update, unchanged, skipped, needsDocument, committed,
            rows, issues);
    }

    /** The downloadable template: the header on the first sheet (what is read), invented examples on a second. */
    static final class PipelineTemplate {
        static byte[] xlsx() {
            try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                CellStyle bold = wb.createCellStyle();
                Font font = wb.createFont();
                font.setBold(true);
                bold.setFont(font);
                Sheet main = wb.createSheet("pipeline");
                header(main, bold);
                Sheet examples = wb.createSheet("ตัวอย่าง (ไม่ถูกนำเข้า)");
                header(examples, bold);
                example(examples, 1, "EMP-0000", "โรงแรมตัวอย่าง สาขาสมมุติ", "สตูดิโอออกแบบสมมุติ", "บริษัท เจ้าของตัวอย่าง จำกัด", "",
                    "QT-0000-0001",
                    "1,240,000", "S3", "60%", "2026-Q4", "ข้อมูลสมมุติ ลบแถวนี้ได้");
                example(examples, 2, "rep@example.com", "อาคารตัวอย่าง", "", "บริษัท เจ้าของสมมุติ จำกัด", "", "",
                    "500000", "ผู้ออกแบบตกลงสเปคให้", "30", "Q1/70", "สถานะพิมพ์เป็นข้อความได้");
                Row help = examples.createRow(4);
                help.createCell(0).setCellValue("สถานะ: S1 S2 S3 S5 S6 (ไม่มี S4; S7–S9 บันทึกผ่านเอกสารจริง ไม่นำเข้า)"
                    + " · ต้องมีผู้ซื้อหรือเจ้าของโครงการ · มูลค่าเป็นเงินบาท · ช่วงเวลา 2026-Q4 หรือ Q4/69"
                    + " · ใส่ข้อมูลที่ชีตแรกเท่านั้น");
                for (int i = 0; i < PipelineSheet.TEMPLATE_HEADERS.size(); i++) {
                    main.setColumnWidth(i, 22 * 256);
                    examples.setColumnWidth(i, 22 * 256);
                }
                wb.write(out);
                return out.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static void header(Sheet sheet, CellStyle bold) {
            Row h = sheet.createRow(0);
            for (int i = 0; i < PipelineSheet.TEMPLATE_HEADERS.size(); i++) {
                var c = h.createCell(i);
                c.setCellValue(PipelineSheet.TEMPLATE_HEADERS.get(i));
                c.setCellStyle(bold);
            }
        }

        private static void example(Sheet sheet, int index, String... values) {
            Row r = sheet.createRow(index);
            for (int i = 0; i < values.length; i++) {
                r.createCell(i).setCellValue(values[i]);
            }
        }

        private PipelineTemplate() {}
    }
}
