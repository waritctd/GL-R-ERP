package th.co.glr.hr.importrequest;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.BrandLines;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.TicketSnapshot;
import th.co.glr.hr.importrequest.ImportRequestRequests.ImportRequestItemInput;
import th.co.glr.hr.importrequest.ImportRequestRequests.IssueImportRequestRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.SetRequiredByNoteRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateImportRequestRequest;

/**
 * The ใบขอซื้อ (F-SM-001) for a deal — one form per brand, per owner ruling.
 *
 * <p><b>TWO paths, and the difference matters.</b>
 *
 * <ul>
 *   <li><b>Preview</b> ({@link #render}, {@link #brands}, {@link #pageCount}) — generated live from
 *       the deal, storing nothing. No number is minted; the caller supplies one or leaves the rule
 *       blank. A PDF from here is <em>not</em> a recorded document and carries no uniqueness
 *       guarantee. It exists so a form can be produced before any IR is raised.
 *   <li><b>The stored aggregate</b> ({@link #createDrafts} onward) — a real row per (deal, brand),
 *       DRAFT → ISSUED → SUPERSEDED, with {@code IR<yy><nnn>} minted from the shared
 *       {@code sales.document_sequence} at issue and the body frozen from then on.
 *       {@link #renderStored} prints that row's own snapshot, so an issued form keeps saying what it
 *       said when it was signed even if the deal is later edited.
 * </ul>
 *
 * <p>Both render through the one {@link ImportRequestRenderer}; only the assembly differs
 * ({@link ImportRequestFormAssembler#assemble} vs {@link ImportRequestFormAssembler#fromStored}), so
 * the two paths cannot drift on layout.
 *
 * <p><b>Authorisation.</b> {@link #IR_ROLES} is deliberately the same pair as
 * {@code TicketService.FULFILMENT_ROLES} ({@code import}, {@code ceo}), because this document is
 * the paperwork for exactly the transitions that constant already gates. It is NOT widened to the
 * deal's sales owner: the form is import's internal purchasing instrument, and sales already reads
 * the same quantities on the deal page.
 *
 * <p><b>The one exception</b> is {@link #setRequiredByNote}: "กำหนดวันที่ต้องการของ" is SALES's field
 * by owner ruling, so it carries its own, different gate. See that method.
 */
@Service
public class ImportRequestService {

    /** Mirrors {@code TicketService.FULFILMENT_ROLES}. Keep the two in step. */
    static final Set<String> IR_ROLES = Set.of("import", "ceo");

    /**
     * Who may set the deal-level "กำหนดวันที่ต้องการของ" — the deal's own {@code sales} rep, plus
     * the CEO.
     *
     * <p>{@code sales_manager} is absent for the rule {@code ROLE_PERMISSIONS} states: it is
     * read+comment oversight only and "must never be added to" the write permissions — the same
     * reasoning {@code TicketService.isFulfilmentOrOwningRep} records. Import is absent too, and
     * deliberately: the owner ruled this field is Sales's, and having import fill it in would be the
     * very thing {@code DealFulfilmentPanel} refuses to offer.
     */
    private static final Set<String> REQUIRED_BY_ROLES = Set.of("sales", "ceo");

    /**
     * The form's "Request date" is a Thai business date, so it is resolved in Bangkok explicitly —
     * matching {@code AttendanceService.DEFAULT_WORK_DATE_ZONE}. A bare {@code LocalDate.now()}
     * would take the JVM default and print yesterday's date on a UTC host for the first 7 hours of
     * every Thai day.
     */
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final ImportRequestQueryRepository repository;
    private final ImportRequestRenderer renderer;
    private final ImportRequestRepository stored;

    public ImportRequestService(ImportRequestQueryRepository repository,
                                ImportRequestRenderer renderer,
                                ImportRequestRepository stored) {
        this.repository = repository;
        this.renderer = renderer;
        this.stored = stored;
    }

    /** Brands on this deal, i.e. how many separate F-SM-001 forms it needs. */
    public List<String> brands(long ticketId, UserPrincipal actor) {
        requireRole(actor);
        requireTicket(ticketId);
        return repository.brandLinesForTicket(ticketId).stream()
            .map(BrandLines::brand)
            .filter(b -> b != null && !b.isBlank())
            .toList();
    }

    /**
     * Renders one brand's form.
     *
     * @param docNumber  "ReF. No." — supplied by the caller; blank leaves the rule empty to be
     *                   written by hand, which is how the owner's own IR69068 carries it.
     * @param requiredBy "กำหนดวันที่ต้องการของ" — free text ("Within 21/5/26"), sales-supplied.
     */
    public byte[] render(long ticketId, String brand, String docNumber, String requiredBy,
                         UserPrincipal actor) {
        requireRole(actor);
        TicketSnapshot snapshot = requireTicket(ticketId);
        if (brand == null || brand.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ต้องระบุแบรนด์ — ใบขอซื้อหนึ่งใบต่อหนึ่งแบรนด์");
        }

        List<BrandLines> groups = repository.brandLinesForTicket(ticketId);
        // Refuse rather than print a form whose Brand header cannot be filled. A deal line with no
        // brand is a data problem on the deal, and quietly bucketing it under the requested brand
        // would put the wrong thing on a controlled document.
        if (groups.stream().anyMatch(g -> g.brand() == null)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มีรายการสินค้าที่ยังไม่ได้ระบุแบรนด์ — กรุณาระบุแบรนด์ให้ครบก่อนออกใบขอซื้อ");
        }
        BrandLines group = groups.stream()
            .filter(g -> brand.equals(g.brand()))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "ไม่พบรายการสินค้าของแบรนด์ " + brand + " ในดีลนี้"));

        ImportRequestFormData data = ImportRequestFormAssembler.assemble(
            snapshot, group, docNumber, requiredBy, LocalDate.now(BANGKOK), actor.name());

        try {
            return renderer.render(data);
        } catch (IOException e) {
            // ApiException carries no cause, so the stack is logged here rather than swallowed —
            // a template that fails to load must not surface as a bare 500 with nothing to debug.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "สร้างไฟล์ใบขอซื้อไม่สำเร็จ: " + e.getMessage());
        }
    }

    /** Sheets the form will print on — lets a client warn before download. */
    public int pageCount(long ticketId, String brand, String requiredBy, UserPrincipal actor) {
        requireRole(actor);
        TicketSnapshot snapshot = requireTicket(ticketId);
        BrandLines group = repository.brandLinesForTicket(ticketId).stream()
            .filter(g -> g.brand() != null && g.brand().equals(brand))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "ไม่พบรายการสินค้าของแบรนด์ " + brand + " ในดีลนี้"));
        return ImportRequestRenderer.pagesRequired(ImportRequestFormAssembler.assemble(
            snapshot, group, null, requiredBy, LocalDate.now(BANGKOK), actor.name()));
    }

    // ── The stored aggregate ──────────────────────────────────────────────────────────────────

    public List<ImportRequestDto> list(long ticketId, UserPrincipal actor) {
        requireRole(actor);
        requireTicket(ticketId);
        return withPageCounts(stored.findByTicket(ticketId));
    }

    public ImportRequestDto get(long id, UserPrincipal actor) {
        requireRole(actor);
        return withPageCount(requireStored(id));
    }

    /**
     * Raises one DRAFT per brand on the deal that does not already have a live one.
     *
     * <p>Idempotent in the way that matters: a brand with a DRAFT or an ISSUED form is skipped rather
     * than duplicated, so calling this twice does not produce two forms for the same brand — and the
     * two partial unique indexes in V154 refuse it in the database even if this check were wrong.
     * Refuses outright if every brand is already covered, rather than returning "created nothing"
     * and letting a caller believe it worked.
     */
    @Transactional
    public List<ImportRequestDto> createDrafts(long ticketId, UserPrincipal actor) {
        requireRole(actor);
        TicketSnapshot snapshot = requireTicket(ticketId);
        requireDealActive(snapshot);

        List<BrandLines> groups = repository.brandLinesForTicket(ticketId);
        if (groups.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลนี้ยังไม่มีรายการสินค้า");
        }
        requireEveryLineBranded(groups);

        Set<String> alreadyCovered = stored.findByTicket(ticketId).stream()
            .filter(r -> ImportRequestStatus.isLive(r.status()))
            .map(ImportRequestDto::brand)
            .collect(java.util.stream.Collectors.toSet());

        int created = 0;
        for (BrandLines group : groups) {
            if (alreadyCovered.contains(group.brand())) {
                continue;
            }
            long id = stored.insertDraft(ticketId, group.brand(),
                stored.highestVersion(ticketId, group.brand()) + 1, snapshot, actor.id(), actor.name());
            stored.replaceItems(id, toItemInputs(group));
            created++;
        }
        if (created == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ทุกแบรนด์ในดีลนี้มีใบขอซื้อที่ยังใช้งานอยู่แล้ว");
        }
        return withPageCounts(stored.findByTicket(ticketId));
    }

    /**
     * PATCH semantics — an absent field is left alone, never blanked. Body fields move only while the
     * form is a DRAFT; the import-owned footer moves on an ISSUED form too, because those blocks are
     * filled in by hand AFTER the form is raised. The repository's {@code WHERE} clauses enforce both,
     * so a wrong status is a 409 rather than a silent no-op.
     */
    @Transactional
    public ImportRequestDto update(long id, UpdateImportRequestRequest request, UserPrincipal actor) {
        requireRole(actor);
        ImportRequestDto existing = requireStored(id);
        if (ImportRequestStatus.SUPERSEDED.equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อฉบับนี้ถูกแทนที่แล้ว แก้ไขไม่ได้");
        }
        boolean draft = ImportRequestStatus.DRAFT.equals(existing.status());

        boolean wantsBodyChange = request.projectName() != null || request.customerName() != null
            || request.requestedByName() != null || request.items() != null;
        if (wantsBodyChange && !draft) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบขอซื้อที่ออกเลขแล้วแก้ไขเนื้อหาไม่ได้ — ต้องออกฉบับแก้ไขใหม่");
        }
        if (draft) {
            stored.updateDraftBody(id, request.projectName(), request.customerName(),
                request.requestedByName());
            if (request.items() != null) {
                if (request.items().isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องมีรายการสินค้าอย่างน้อย 1 รายการ");
                }
                stored.replaceItems(id, request.items());
            }
        }
        stored.updateFooter(id, request.vesselEtaNote(), request.checkedByName(),
            request.checkedDate(), request.approvedByName(), request.approvedDate());
        return withPageCount(requireStored(id));
    }

    /**
     * DRAFT → ISSUED: mints (or accepts) the number, stamps the date, and supersedes the version this
     * one replaces.
     *
     * <p>Order is load-bearing. The number is taken INSIDE this transaction so a refused issue rolls
     * it back rather than burning a sequence value — the property
     * {@code DepositNoticeRepository.nextDocNumber} documents. The compare-and-set is what actually
     * decides: 0 rows means someone else issued or superseded this form first, and that is a 409, not
     * a success.
     *
     * <p><b>Superseding happens BEFORE the issue, and that is forced.</b> The natural order is the
     * one {@code CustomerQuotationService.issue} uses — issue first, "a failed issue must not retire
     * the old offer" — and it was written that way first. It cannot work here:
     * {@code ux_import_request_ticket_brand_issued} permits only ONE ISSUED row per (deal, brand), and
     * a unique index is checked per statement, so the two versions cannot both be ISSUED even for the
     * instant between the two writes. Postgres cannot defer this (a partial unique CONSTRAINT, which
     * is what {@code DEFERRABLE} needs, does not exist).
     *
     * <p>So the same guarantee rests on the TRANSACTION instead of on statement order: both writes are
     * in one, and a failed issue rolls the supersede back. Note the suite-wide trap on
     * {@code AbstractPostgresIntegrationTest} — {@code @Transactional} is inert there because services
     * are hand-wired, so the integration tests CANNOT demonstrate that rollback; it is a production
     * property only, and that is stated rather than implied by a green test.
     */
    @Transactional
    public ImportRequestDto issue(long id, IssueImportRequestRequest request, UserPrincipal actor) {
        requireRole(actor);
        ImportRequestDto draft = requireStored(id);
        if (!ImportRequestStatus.DRAFT.equals(draft.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อฉบับนี้ออกเลขไปแล้ว");
        }
        if (draft.items().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อยังไม่มีรายการสินค้า");
        }

        String override = request == null || request.docNumber() == null
            || request.docNumber().isBlank() ? null : request.docNumber().strip();
        // An override does NOT advance the sequence. A number typed ahead of the counter therefore
        // cannot be minted a second time later — ux_import_request_doc_number refuses the duplicate —
        // and this pre-check turns that race into a clear message instead of a constraint violation.
        if (override != null && stored.docNumberExists(override)) {
            throw new ApiException(HttpStatus.CONFLICT, "เลขที่ใบขอซื้อ " + override + " ถูกใช้แล้ว");
        }
        LocalDate today = LocalDate.now(BANGKOK);
        String docNumber = override != null ? override : stored.nextDocNumber(thaiYear(today));

        // Order forced by ux_import_request_ticket_brand_issued — see this method's Javadoc.
        supersedePreviousVersion(draft, id);
        int issued = stored.issue(id, docNumber, today, actor.id(), actor.name());
        if (issued == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อถูกเปลี่ยนแปลงโดยผู้ใช้อื่น");
        }
        return withPageCount(requireStored(id));
    }

    /**
     * Prepares a correction: a new DRAFT at the next version for the same (deal, brand), copying the
     * issued form's body so the corrector edits from what was actually sent rather than from the
     * deal's current state.
     *
     * <p>The form being corrected STAYS ISSUED until the replacement is issued. That is why V154
     * enforces uniqueness with two partial indexes rather than one over "not superseded" — see that
     * migration.
     */
    @Transactional
    public ImportRequestDto revise(long id, UserPrincipal actor) {
        requireRole(actor);
        ImportRequestDto issued = requireStored(id);
        if (!ImportRequestStatus.ISSUED.equals(issued.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ออกฉบับแก้ไขได้เฉพาะใบขอซื้อที่ออกเลขแล้ว");
        }
        TicketSnapshot snapshot = requireTicket(issued.ticketId());
        long revisionId = stored.insertDraft(issued.ticketId(), issued.brand(),
            stored.highestVersion(issued.ticketId(), issued.brand()) + 1,
            snapshot, actor.id(), actor.name());
        stored.replaceItems(revisionId, issued.items().stream()
            .map(it -> new ImportRequestItemInput(it.ticketItemId(), it.code(), it.size(),
                it.qty(), it.unit(), it.note()))
            .toList());
        return withPageCount(requireStored(revisionId));
    }

    /** A draft that should not exist is deleted, not tombstoned — it has no number and no audit weight. */
    @Transactional
    public void deleteDraft(long id, UserPrincipal actor) {
        requireRole(actor);
        requireStored(id);
        if (stored.deleteDraft(id) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ลบได้เฉพาะใบขอซื้อที่ยังไม่ออกเลข");
        }
    }

    /** Prints a stored form from ITS OWN snapshot, not from the deal's current state. */
    public byte[] renderStored(long id, UserPrincipal actor) {
        requireRole(actor);
        ImportRequestDto row = requireStored(id);
        try {
            return renderer.render(ImportRequestFormAssembler.fromStored(row));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "สร้างไฟล์ใบขอซื้อไม่สำเร็จ: " + e.getMessage());
        }
    }

    /**
     * "กำหนดวันที่ต้องการของ" on the DEAL — SALES's field, by owner ruling, which is why this is the
     * one method here not gated on {@link #IR_ROLES}.
     *
     * <p>Gated on {@link #REQUIRED_BY_ROLES} AND deal ownership AND a stage floor of
     * {@code ORDER_RECEIVED}: the owner's instruction was that sales fills this in "when the order is
     * already confirmed". Free text, and nullable so it can be cleared.
     */
    @Transactional
    public void setRequiredByNote(long ticketId, SetRequiredByNoteRequest request, UserPrincipal actor) {
        TicketSnapshot snapshot = requireTicket(ticketId);
        if (!REQUIRED_BY_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if ("sales".equals(actor.role()) && !repository.isDealOwner(ticketId, actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        requireDealActive(snapshot);
        if (!repository.stageAtLeastOrderReceived(ticketId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ระบุกำหนดวันที่ต้องการของได้หลังจากยืนยันคำสั่งซื้อแล้ว");
        }
        String note = request == null || request.requiredByNote() == null
            || request.requiredByNote().isBlank() ? null : request.requiredByNote().strip();
        stored.setRequiredByNote(ticketId, note);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private void supersedePreviousVersion(ImportRequestDto justIssued, long newId) {
        stored.findByTicket(justIssued.ticketId()).stream()
            .filter(r -> r.id() != newId
                && justIssued.brand().equals(r.brand())
                && ImportRequestStatus.ISSUED.equals(r.status()))
            .forEach(previous -> stored.supersede(previous.id(), newId));
    }

    private static List<ImportRequestItemInput> toItemInputs(BrandLines group) {
        return group.lines().stream()
            .map(line -> new ImportRequestItemInput(line.ticketItemId(), line.code(), line.size(),
                line.qty(), line.unit(), null))
            .toList();
    }

    /** Buddhist year — the document number's own convention ({@code IR69068} = 2569). */
    private static int thaiYear(LocalDate date) {
        return date.getYear() + 543;
    }

    private ImportRequestDto requireStored(long id) {
        return stored.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบขอซื้อนี้"));
    }

    private void requireDealActive(TicketSnapshot snapshot) {
        if (!"ACTIVE".equals(snapshot.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลนี้ไม่ได้อยู่ในสถานะที่ดำเนินการได้");
        }
    }

    private void requireEveryLineBranded(List<BrandLines> groups) {
        if (groups.stream().anyMatch(g -> g.brand() == null)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มีรายการสินค้าที่ยังไม่ได้ระบุแบรนด์ — กรุณาระบุแบรนด์ให้ครบก่อนออกใบขอซื้อ");
        }
    }

    private List<ImportRequestDto> withPageCounts(List<ImportRequestDto> rows) {
        return rows.stream().map(this::withPageCount).toList();
    }

    /** Fills the DTO's {@code pageCount}, which the repository leaves at 0 — layout is not its job. */
    private ImportRequestDto withPageCount(ImportRequestDto r) {
        int pages = ImportRequestRenderer.pagesRequired(ImportRequestFormAssembler.fromStored(r));
        return new ImportRequestDto(r.id(), r.ticketId(), r.ticketCode(), r.brand(), r.version(),
            r.status(), r.docNumber(), r.issueDate(), r.customerName(), r.projectName(),
            r.requestedByName(), r.requiredByNote(), r.depositReceivedDate(), r.vesselEtaNote(),
            r.checkedByName(), r.checkedDate(), r.approvedByName(), r.approvedDate(),
            r.createdById(), r.createdByName(), r.issuedById(), r.issuedByName(),
            r.supersededById(), r.createdAt(), r.updatedAt(), r.issuedAt(), pages, r.items());
    }

    private void requireRole(UserPrincipal actor) {
        if (!IR_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private TicketSnapshot requireTicket(long ticketId) {
        return repository.loadTicketSnapshot(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
    }
}
