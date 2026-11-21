package th.co.glr.hr.importrequest;

import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.SizeUnit;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestCoverageResponse;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestSplitPreview;
import th.co.glr.hr.importrequest.ImportRequestDtos.SplitPreviewFactory;
import th.co.glr.hr.importrequest.ImportRequestDtos.SplitPreviewLine;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.BrandLines;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.LineFactoryCandidate;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.TicketSnapshot;
import th.co.glr.hr.importrequest.ImportRequestRepository.IssuedFactoryRow;
import th.co.glr.hr.importrequest.ImportRequestRepository.PredecessorStep;
import th.co.glr.hr.importrequest.ImportRequestRequests.AdvanceImportStepRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.CreateImportRequestsRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.ImportRequestItemInput;
import th.co.glr.hr.importrequest.ImportRequestRequests.IssueImportRequestRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.NewFactoryCountryInput;
import th.co.glr.hr.importrequest.ImportRequestRequests.SetLeadTimeRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.SetRequiredByNoteRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateImportRequestRequest;
import th.co.glr.hr.notification.DealNoticeText;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * The ใบขอซื้อ (F-SM-001) for a deal.
 *
 * <p><b>TWO paths, and the difference matters.</b>
 *
 * <ul>
 *   <li><b>Preview</b> ({@link #render}, {@link #brands}, {@link #pageCount}) — generated live from
 *       the deal, storing nothing, still grouped PER BRAND via {@link
 *       ImportRequestQueryRepository#brandLinesForTicket}. Gated on {@link #IR_ROLES}
 *       ({@code import}/{@code ceo}). These routes are being demoted to {@code SERVER_ONLY}
 *       (import-request-per-factory-PLAN.md) and superseded by the stored aggregate's own
 *       {@code /file} route, so their per-brand grain is not rebased onto factories.
 *   <li><b>The stored aggregate</b> ({@link #createDrafts} onward) — a real row per (deal,
 *       FACTORY) (V184), DRAFT → ISSUED → SUPERSEDED, with {@code IR<yy><nnn>}
 *       minted from the shared {@code sales.document_sequence} at issue, per-factory progress
 *       (S12-S17, {@link ImportRequestStep}), and its own role model — see {@link
 *       #FULL_WRITE_ROLES}/{@link #FOOTER_ONLY_ROLES}/{@link #ADVANCE_STEP_ROLES} below.
 * </ul>
 *
 * <p><b>Authorisation on the STORED path</b> (an authorization surface per CLAUDE.md: changes need
 * real-DB wrong-way-round tests and mutation checks):
 * <ul>
 *   <li>the deal's OWNING sales rep and the CEO: create, edit the BODY (project/customer/rep/items/
 *       lead time while DRAFT), issue, revise, delete;
 *   <li>the CEO ALONE: the printed form's footer (Checked By/date, Approve By/date, vessel ETA);
 *   <li>{@code import}: read, advance steps, the post-issue lead-time route, edit/mark-sent on the
 *       order-email draft (a DIFFERENT "footer"-shaped surface from the printed footer;
 *       see {@link #requireFooterWrite}'s own Javadoc);
 *   <li>{@code sales_manager}: read only.
 * </ul>
 */
@Service
public class ImportRequestService {

    /** PREVIEW-path role gate only — see this class's own Javadoc for why it stays as-is. */
    static final Set<String> IR_ROLES = Set.of("import", "ceo");

    private static final Set<String> REQUIRED_BY_ROLES = Set.of("sales", "ceo");

    // ── STORED aggregate roles ────────────────────────────────────────────────────────────────────
    /** Create / edit the BODY / issue / revise / delete. Sales here means the DEAL OWNER only. The
     * printed form's FOOTER is not part of this set's write surface — see {@link
     * #requirePrintedFooterWrite} (CEO only). */
    private static final Set<String> FULL_WRITE_ROLES = Set.of("sales", "ceo");
    /** The ORDER-EMAIL draft's edit/mark-sent gate, in addition to {@link #FULL_WRITE_ROLES} — see
     * {@link #requireFooterWrite}'s Javadoc for why this is a DIFFERENT surface from the printed
     * form's footer (which {@link #requirePrintedFooterWrite} gates, CEO only). */
    private static final Set<String> FOOTER_ONLY_ROLES = Set.of("import");
    /** Advance a step. Global — import has no per-deal "ownership" concept, unlike sales. */
    static final Set<String> ADVANCE_STEP_ROLES = Set.of("import", "ceo");
    /** Read-only, no ownership scoping. {@code sales} also reads, but ownership-scoped — see {@link #canRead}. */
    private static final Set<String> READ_ONLY_ROLES = Set.of("import", "sales_manager");

    /** The form's "Request date" is a Thai business date, so it is resolved in Bangkok explicitly — matching
     * AttendanceService.DEFAULT_WORK_DATE_ZONE. A bare LocalDate.now() would take the JVM default and print
     * yesterday's date on a UTC host for the first 7 hours of every Thai day. */
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final ImportRequestQueryRepository repository;
    private final ImportRequestRenderer renderer;
    private final ImportRequestRepository stored;
    private final FactoryConfigRepository factories;
    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final NotificationRepository notifications;
    private final ShipmentRoundRepository rounds;

    private th.co.glr.hr.deliveryrequest.PrePortal prePortal;

    /** Setter-injected so hand-wired tests are unaffected. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void wirePrePortal(th.co.glr.hr.deliveryrequest.PrePortal prePortal) {
        this.prePortal = prePortal;
    }

    public ImportRequestService(ImportRequestQueryRepository repository,
                                ImportRequestRenderer renderer,
                                ImportRequestRepository stored,
                                FactoryConfigRepository factories,
                                TicketRepository tickets,
                                TicketService ticketService,
                                NotificationRepository notifications,
                                ShipmentRoundRepository rounds) {
        this.repository = repository;
        this.renderer = renderer;
        this.stored = stored;
        this.factories = factories;
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.notifications = notifications;
        this.rounds = rounds;
        // TicketService cannot hold this service (it is built with this one's dependency): it calls back through a hook
        ticketService.wireImportReconciler(this::reconcileGoodsReceived);
    }

    /** A deal returning to ACTIVE (resumed or reopened): if every factory reached RECEIVED while it was paused and the
     * goods-received rollup never ran, run it once now. Silent: no event for the rep, nothing replayed when it already ran. */
    public void reconcileGoodsReceived(long ticketId, UserPrincipal actor) {
        if (allFactoriesReceived(ticketId) && !tickets.hasReceivedGoods(ticketId)) {
            ticketService.applyImportRequestRollup(ticketId, actor);
        }
    }

    // ── PREVIEW (per-brand, unchanged by V184) ───────────────────────────────────────────────────

    /** Brands on this deal, i.e. how many separate F-SM-001 forms it needs. */
    public List<String> brands(long ticketId, UserPrincipal actor) {
        requireRole(actor);
        requireTicket(ticketId);
        return repository.brandLinesForTicket(ticketId).stream()
            .map(BrandLines::brand)
            .filter(b -> b != null && !b.isBlank())
            .toList();
    }

    /** Renders one brand's form. */
    public byte[] render(long ticketId, String brand, String docNumber, String requiredBy,
                         UserPrincipal actor) {
        requireRole(actor);
        TicketSnapshot snapshot = requireTicket(ticketId);
        if (brand == null || brand.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ต้องระบุแบรนด์ — ใบขอซื้อหนึ่งใบต่อหนึ่งแบรนด์");
        }

        List<BrandLines> groups = repository.brandLinesForTicket(ticketId);
        // Refuse rather than print a form whose Brand header cannot be filled. A deal line with no brand is a data
        // problem on the deal, and quietly bucketing it under the requested brand would put the wrong thing on a
        // controlled document.
        if (groups.stream().anyMatch(g -> g.brand() == null)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มีรายการสินค้าที่ยังไม่ได้ระบุแบรนด์ — กรุณาระบุแบรนด์ให้ครบก่อนออกใบขอซื้อ");
        }
        BrandLines group = groups.stream()
            .filter(g -> brand.equals(g.brand()))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "ไม่พบรายการสินค้าของแบรนด์ " + brand + " ในงานนี้"));

        ImportRequestFormData data = ImportRequestFormAssembler.assemble(
            snapshot, group, docNumber, requiredBy, LocalDate.now(BANGKOK), actor.name());

        try {
            return renderer.render(data);
        } catch (IOException e) {
            // ApiException carries no cause, so the stack is logged here rather than swallowed — a template that fails to
            // load must not surface as a bare 500 with nothing to debug.
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
                "ไม่พบรายการสินค้าของแบรนด์ " + brand + " ในงานนี้"));
        return ImportRequestRenderer.pagesRequired(ImportRequestFormAssembler.assemble(
            snapshot, group, null, requiredBy, LocalDate.now(BANGKOK), actor.name()));
    }

    // ── The stored aggregate (per-factory as of V184) ────────────────────────────────────────────

    public List<ImportRequestDto> list(long ticketId, UserPrincipal actor) {
        requireTicket(ticketId);
        requireRead(ticketId, actor);
        return withPageCounts(stored.findByTicket(ticketId));
    }

    /**
     * Read-only twin of {@link #createDrafts}'s coverage check, for the coverage route: is there a factory
     * on this deal that still needs an IR, and which ones? Uses the same {@link ImportRequestQueryRepository
     * #factoryResolutionCandidates} cascade, but creates no factory. A typed name that matches no
     * factory (or a line with no factory at all) counts as uncovered; only a LIVE (DRAFT / ISSUED) row
     * covers a factory — a SUPERSEDED one does not. A deal with nothing left to order is not uncovered.
     *
     * <p>The names (for the create confirm) are distinct, in line order: a resolved factory by its stored
     * name, an unresolved typed name as typed (trimmed); a line with no factory at all has no name to
     * give — the caller adds the factory the rep picks for it.
     */
    public ImportRequestCoverageResponse coverage(long ticketId, UserPrincipal actor) {
        return coverage(ticketId, List.of(), actor);
    }

    public ImportRequestCoverageResponse coverage(long ticketId,
            List<ImportRequestRequests.ItemFactoryInput> itemFactories, UserPrincipal actor) {
        requireTicket(ticketId);
        requireRead(ticketId, actor);
        Set<Long> covered = stored.findByTicket(ticketId).stream()
            .filter(r -> ImportRequestStatus.isLive(r.status()))
            .map(ImportRequestDto::factoryId)
            .collect(Collectors.toSet());
        List<FactoryConfigDto> allFactories = factories.findAll();
        Map<String, Long> idByNormalizedName = allFactories.stream()
            .collect(Collectors.toMap(f -> normalizeFactoryName(f.factoryName()), FactoryConfigDto::id, (x, y) -> x));
        Map<Long, String> nameById = allFactories.stream()
            .collect(Collectors.toMap(FactoryConfigDto::id, FactoryConfigDto::factoryName, (x, y) -> x));
        boolean uncovered = false;
        // Keyed the way createDrafts groups: a resolved factory by id, an unresolved name by its normalized form
        // (so "Rex" and "rex " are ONE new factory), keeping the first display name.
        Map<String, String> names = new LinkedHashMap<>();
        for (LineFactoryCandidate c : repository.factoryResolutionCandidates(ticketId)) {
            Long factoryId = c.directFactoryId() != null ? c.directFactoryId()
                : c.candidateFactoryName() != null ? idByNormalizedName.get(normalizeFactoryName(c.candidateFactoryName()))
                : null;
            if (factoryId != null && covered.contains(factoryId)) {
                continue;
            }
            uncovered = true;
            String name = factoryId != null ? nameById.get(factoryId)
                : c.candidateFactoryName() != null ? c.candidateFactoryName().strip() : null;
            // strip() keeps NBSP / BOM, so a name of only those would print as a blank entry in the confirm.
            if (name != null && !name.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xFEFF)) {
                names.putIfAbsent(factoryId != null ? "id:" + factoryId : "name:" + normalizeFactoryName(name), name);
            }
        }
        // Slice 6c: the split preview only for createDrafts' own gate (CEO, the owning/keying rep) — exactly who can
        // open the confirm it feeds. import and sales_manager read this route for the covered flag and get null.
        ImportRequestSplitPreview preview = canFullWrite(ticketId, actor)
            ? splitPreview(ticketId, itemFactories == null ? List.of() : itemFactories) : null;
        return new ImportRequestCoverageResponse(uncovered, List.copyOf(names.values()), preview);
    }

    public ImportRequestDto get(long id, UserPrincipal actor) {
        ImportRequestDto row = requireStored(id);
        requireReadRow(row, actor);
        return withPageCount(row);
    }

    /** The read gate of ONE stored row: a deal IR follows its deal; a no-project order is read by its requester, the
     * sales manager, import and the CEO, and (a SHOWROOM order) by holders of the showroom grant. */
    private void requireReadRow(ImportRequestDto row, UserPrincipal actor) {
        if (row.ticketId() != null) {
            requireRead(row.ticketId(), actor);
            return;
        }
        String role = actor.role();
        var meta = stored.findNoProjectMeta(row.id()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบขอซื้อนี้"));
        if ("ceo".equals(role) || READ_ONLY_ROLES.contains(role) || actor.id() == meta.requesterId()
            || th.co.glr.hr.auth.OversightReader.holder(actor, tickets::holdsReadAllSalesGrant) // read only, like the deal reads
            || ("SHOWROOM".equals(meta.purpose()) && tickets.holdsShowroomOrdersGrant(actor.id()))) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }

    /**
     * Raises AND ISSUES one ใบขอซื้อ per FACTORY on the deal that does not already have a live one
     * (no DRAFT is left behind; each row comes back ISSUED with a doc number and step
     * CONTACTED, and the first one moves the deal to PROCUREMENT). All-or-nothing: a missing lead time,
     * an empty item list or the payment gate refuses the whole call with a 409 before anything is
     * written. Resolves each
     * line's factory per {@link ImportRequestQueryRepository#factoryResolutionCandidates}'s cascade —
     * see that method's Javadoc for the order and {@link #resolveFactoryGroups} for the match-or-create
     * step (auto-create requires {@code request}'s {@code newFactoryCountries}).
     *
     * <p>Idempotent in the way that matters: a factory with a (leftover) DRAFT or an ISSUED form is skipped
     * rather than duplicated, so calling this twice does not produce two forms for the same factory —
     * and the two partial unique indexes in V184 refuse it in the database even if this check were
     * wrong. Refuses outright if every factory is already covered, rather than returning "created
     * nothing" and letting a caller believe it worked.
     */
    @Transactional
    public List<ImportRequestDto> createDrafts(long ticketId, CreateImportRequestsRequest request,
                                               UserPrincipal actor) {
        requireTicket(ticketId); // 404 first
        requireFullWrite(ticketId, actor);
        if (prePortal != null) {
            prePortal.ensureOrderConfirmed(ticketId, null, actor); // an old deal's order is confirmed by its first IR; before the row lock
        }
        // Row lock, then re-read the deal: an undo of the order confirmation (DealQuotationService#undoOrderConfirmation)
        // holds this lock, so a create that checked the state without it could survive the undo. Row lock only.
        tickets.lockTicketForUpdate(ticketId);
        TicketSnapshot snapshot = requireTicket(ticketId);
        requireDealActive(snapshot);
        if (!repository.stageAtLeastOrderReceived(ticketId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "สร้างใบขอซื้อได้หลังจากยืนยันคำสั่งซื้อแล้ว (ORDER_RECEIVED) เท่านั้น");
        }
        TicketSummaryDto summary = requireTicketSummary(ticketId);
        if (ticketService.procurementSourceAwaitingRep(summary)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "กด \"จัดซื้อสินค้า\" เพื่อเลือกที่มาสินค้าก่อนออกใบขอซื้อ (IR)");
        }

        // The payment gate applies at create now (widened gate, same as issue()) and refuses FIRST, so a
        // deal with no paid deposit is never walked through the country / lead-time prompts.
        ticketService.requireImportRequestIssuable(summary, true);
        ticketService.recordProcurementSourceImplied(summary, actor.id());

        // 400 before anything is written (the picker and auto-create below both write).
        Map<String, ImportRequestRequests.NewFactoryLeadTimeInput> suppliedLeadTimes =
            validatedLeadTimes(request);
        applyItemFactoryChoices(ticketId, request);
        List<FactoryGroup> groups = resolveFactoryGroups(ticketId, actor.id(), request);
        if (groups.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                "งานนี้ไม่มีรายการที่ต้องออกใบขอซื้อ (ยังไม่มีรายการสินค้า หรือครอบคลุมจากสต็อกทั้งหมด)");
        }

        Set<Long> alreadyCovered = stored.findByTicket(ticketId).stream()
            .filter(r -> ImportRequestStatus.isLive(r.status()))
            .map(ImportRequestDto::factoryId)
            .collect(Collectors.toSet());

        // Each factory's IR takes the lead time on the deal's current QUOTATION items.
        List<ImportRequestQueryRepository.QuotationLeadTimeRow> quotationLeadTimes =
            repository.currentQuotationLeadTimes(ticketId);

        // ALL-OR-NOTHING: create also ISSUES, so every issue check runs for every
        // NEW factory BEFORE the first write. (Not left to the @Transactional rollback alone: it is
        // inert in the hand-wired integration suite, and the checks are cheap.)
        record NewRow(FactoryGroup group, LeadTimeDefaults.Range derived, LeadTimeDefaults.Range leadTime) { }
        List<NewRow> newRows = new ArrayList<>();
        List<String> missingLeadTime = new ArrayList<>();
        // planNewRows is the SAME function the coverage route's split preview calls (slice 6c).
        for (PlannedRow planned : planNewRows(groups, alreadyCovered, quotationLeadTimes)) {
            FactoryGroup group = planned.group();
            LeadTimeDefaults.Range derived = planned.derived();
            LeadTimeDefaults.Range leadTime = planned.leadTime();
            if (group.items().isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อยังไม่มีรายการสินค้า");
            }
            if (leadTime == null) {
                // A caller-supplied lead time (keyed by factory name) fills it in.
                var supplied = suppliedLeadTimes.get(normalizeFactoryName(group.factoryName()));
                if (supplied == null) {
                    missingLeadTime.add(group.factoryName());
                    continue;
                }
                leadTime = new LeadTimeDefaults.Range(supplied.minDays(), supplied.maxDays());
            }
            newRows.add(new NewRow(group, derived, leadTime));
        }
        if (!missingLeadTime.isEmpty()) {
            // Parseable by the panel (like the missing-country prefix); lists ONLY the factories still lacking one.
            throw new ApiException(HttpStatus.CONFLICT,
                MISSING_LEAD_TIME_PREFIX + String.join(", ", missingLeadTime));
        }
        if (newRows.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ทุกโรงงานในงานนี้มีใบขอซื้อที่ยังใช้งานอยู่แล้ว");
        }
        for (NewRow n : newRows) {
            FactoryGroup group = n.group();
            long id = stored.insertDraft(ticketId, group.factoryId(), group.factoryName(),
                group.brandLabel(), stored.highestVersion(ticketId, group.factoryId()) + 1,
                snapshot, n.leadTime().minDays(), n.leadTime().maxDays(), null, // nothing to carry, fresh row
                null, // nothing to carry either -- requiredByNote comes from the deal snapshot only.
                actor.id(), actor.name());
            stored.replaceItems(id, group.items());
            if (n.derived() != null) {
                stored.setDerivedLeadTime(id, n.derived().minDays(), n.derived().maxDays());
            }
            issueStoredDraft(requireStored(id), null, snapshot, actor);
        }
        return withPageCounts(stored.findByTicket(ticketId));
    }

    /**
     * PATCH semantics — an absent field is left alone, never blanked. Body fields ({@link
     * #FULL_WRITE_ROLES} only) move only while the form is a DRAFT.
     *
     * <p><b>The printed footer (Checked By/date, Approve By/date, vessel ETA) is CEO ONLY.</b>
     * Import advances steps, uses the post-issue lead-time route, and marks the
     * order-email sent; none of those go through this method. {@code vesselEtaNote} is still
     * auto-prefilled by the SYSTEM at {@link #issue} — that is a service-internal write, not a
     * user-supplied one, and is unaffected by this gate.
     *
     * <p>Body and footer are checked and applied INDEPENDENTLY of each other — a single PATCH may
     * carry both, and each half is authorised on its own terms rather than the request's authz
     * being decided by whichever half happens to be present. (A single body-change check deciding
     * which authz ran would apply a body edit's footer fields with no footer-specific check — the
     * footer and body role sets are disjoint.) The repository's {@code WHERE} clauses enforce the
     * DRAFT-only body rule too, not
     * just this method, so a wrong status is a 409 rather than a silent no-op.
     */
    @Transactional
    public ImportRequestDto update(long id, UpdateImportRequestRequest request, UserPrincipal actor) {
        ImportRequestDto existing = requireDealStored(id);
        boolean wantsLeadTimeChange = request.leadTimeMinDays() != null || request.leadTimeMaxDays() != null;
        // requiredByNote is a BODY field (this form's own "กำหนดวันที่ต้องการของ",
        // editable by the owning rep/CEO while DRAFT) — see UpdateImportRequestRequest#requiredByNote's
        // own Javadoc for why this is separate from SetRequiredByNoteRequest (the DEAL-level value new
        // drafts snapshot FROM).
        boolean wantsBodyChange = request.projectName() != null || request.customerName() != null
            || request.requestedByName() != null || request.requiredByNote() != null
            || request.items() != null || wantsLeadTimeChange;
        boolean wantsFooterChange = request.vesselEtaNote() != null || request.checkedByName() != null
            || request.checkedDate() != null || request.approvedByName() != null
            || request.approvedDate() != null;
        if (wantsBodyChange) {
            requireFullWrite(existing.ticketId(), actor);
        }
        if (wantsFooterChange) {
            requirePrintedFooterWrite(actor);
        }
        if (!wantsBodyChange && !wantsFooterChange) {
            // An entirely empty PATCH changes nothing, but must still be scoped to someone who may at least SEE this row —
            // read access, the lowest bar any branch above already clears.
            // The read-all grant is NOT enough here: this PATCH is a write verb, so it never admits a reader.
            requireRead(existing.ticketId(), actor, false);
        }
        // Refuse on an inactive deal (LOST/ON_HOLD/DORMANT) like every legacy
        // ticket-mutating method (TicketService#requireActive) — a stored ใบขอซื้อ must
        // not be editable once the deal it belongs to is no longer live.
        requireDealActive(requireTicket(existing.ticketId()));

        if (ImportRequestStatus.SUPERSEDED.equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อฉบับนี้ถูกแทนที่แล้ว แก้ไขไม่ได้");
        }
        boolean draft = ImportRequestStatus.DRAFT.equals(existing.status());
        if (wantsBodyChange && !draft) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบขอซื้อที่ออกเลขแล้วแก้ไขเนื้อหาไม่ได้ — ต้องออกฉบับแก้ไขใหม่");
        }
        if (draft && wantsBodyChange) {
            stored.updateDraftBody(id, request.projectName(), request.customerName(),
                request.requestedByName(), request.requiredByNote());
            if (request.items() != null) {
                if (request.items().isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องมีรายการสินค้าอย่างน้อย 1 รายการ");
                }
                stored.replaceItems(id, request.items());
            }
            if (wantsLeadTimeChange) {
                requireValidLeadTimePair(request.leadTimeMinDays(), request.leadTimeMaxDays());
                stored.updateLeadTime(id, request.leadTimeMinDays(), request.leadTimeMaxDays());
                // A DRAFT has no firstIssuedDate yet (only
                // set at #issue), so there is no anchor here to re-derive a fresh CONCRETE
                // vesselEtaNote string the way #setLeadTime can on an already-ISSUED row. But a
                // carried-forward note that is still exactly an AUTO-DERIVED text (for ANY range —
                // see #looksAutoDerivedVesselEta) is now stale for the range just written above, and
                // #issue's own re-derivation only fires when the stored text matches THIS draft's
                // CURRENT range exactly -- a stale-but-still-auto text would otherwise be mistaken
                // for a CEO-typed custom note and survive issue() unchanged (e.g. v1
                // 75-90 -> revise -> v2 edited to 60-70 -> issue keeps "(75-90 วัน)"). Blanking it
                // here (an explicit "" is a real COALESCE-safe write, unlike null) lets #issue's own
                // null-or-blank branch derive it fresh, with the correct anchor, at issue time. A
                // genuinely CEO-typed custom note never matches the auto pattern, so it is left
                // exactly alone. This is a SYSTEM side effect of the lead-time edit, not a
                // footer-write the ACTOR is exercising, so it bypasses #requirePrintedFooterWrite —
                // the same reasoning #issue's own unconditional vesselEtaNote write already relies
                // on.
                if (looksAutoDerivedVesselEta(existing.vesselEtaNote())) {
                    stored.updateFooter(id, "", null, null, null, null);
                }
            }
        }
        if (wantsFooterChange) {
            stored.updateFooter(id, request.vesselEtaNote(), request.checkedByName(),
                request.checkedDate(), request.approvedByName(), request.approvedDate());
        }
        return withPageCount(requireStored(id));
    }

    /**
     * Sets lead time on an ISSUED row — {@code import}/{@code ceo} only (the DRAFT-stage edit for
     * the owning rep/CEO goes through {@link #update} instead — see {@link
     * ImportRequestRequests.SetLeadTimeRequest}'s own Javadoc): the lead time is editable by the
     * owning rep/CEO while DRAFT, and by import/CEO after issue.
     *
     * <p>A new range moves {@code expectedArrivalFrom}/{@code To} (both
     * derived — {@link #withPageCount}), so the two OTHER places that quote the same estimate must
     * move with it or they go stale: {@code vesselEtaNote}, if it is STILL exactly the text {@link
     * #defaultVesselEtaNote} would have produced for the OLD range (i.e. nobody, CEO included, has
     * ever typed a custom one), is re-derived for the NEW range; and the order-email draft's body,
     * if it has not yet been marked sent, is regenerated outright (it quotes the same "Expected
     * arrival" line — see {@link #buildEmailDraft}). A CEO-typed custom {@code vesselEtaNote}, or a
     * draft already marked sent, is left exactly alone in both cases.
     */
    @Transactional
    public ImportRequestDto setLeadTime(long id, SetLeadTimeRequest request, UserPrincipal actor) {
        if (!ADVANCE_STEP_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        ImportRequestDto existing = requireDealStored(id);
        requireDealActive(requireTicket(existing.ticketId()));
        if (!ImportRequestStatus.ISSUED.equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "แก้ไขระยะเวลานำเข้าได้เฉพาะใบขอซื้อที่ออกเลขแล้ว");
        }
        requireValidLeadTimePair(request.leadTimeMinDays(), request.leadTimeMaxDays());
        stored.updateLeadTime(id, request.leadTimeMinDays(), request.leadTimeMaxDays());

        // Re-derive whenever the OLD vesselEtaNote LOOKS
        // auto-derived (matches the generic template for ANY range/anchor — see
        // #looksAutoDerivedVesselEta), not only when it equals the exact text this method itself
        // would have produced for the OLD range. A CEO-typed custom note never matches the pattern,
        // so it is never touched here, matching #issue's identical rule for the same field.
        if (looksAutoDerivedVesselEta(existing.vesselEtaNote())) {
            String newDerived = defaultVesselEtaNote(existing.firstIssuedDate(),
                request.leadTimeMinDays(), request.leadTimeMaxDays());
            stored.updateFooter(id, newDerived, null, null, null, null);
        }

        // The unsent order-email draft must not be regenerated unconditionally or re-signed as
        // THIS method's own caller (import/CEO editing an estimate): that would clobber a
        // hand-edit made via {@link #updateEmailDraft}, and misattribute the email to whoever
        // touched the lead time rather than whoever actually issued the form. So:
        //  1. Regenerate ONLY when the stored body is STILL exactly what generation would have
        //     produced for the OLD lead time — i.e. nobody has hand-edited it since issue (or since
        //     the last regeneration). A hand-edited draft is left exactly alone.
        //  2. Always sign with the ORIGINAL issuer ({@code issuedById}/{@code issuedByName}), never
        //     this call's own {@code actor} — an import staffer nudging an estimate must not
        //     silently re-sign an email as themselves.
        if (existing.emailSentAt() == null) {
            FactoryConfigDto factory = freshFactoryLookup(existing.factoryId()).orElse(null);
            // The ORIGINAL signer is a STORED SNAPSHOT (issuedByName/issuedByEmail, V184)
            // taken at #issue, never re-resolved fresh here — a fresh lookup of
            // "the issuing user's CURRENT email" could legitimately differ from what was actually
            // embedded in the text this method compares against (an employee's email can change,
            // and a UserPrincipal's email is not guaranteed to equal today's hr.employee.email row),
            // which would make the "still equals the previously generated text" check below
            // spuriously fail and leave a genuinely stale (not hand-edited) draft un-regenerated.
            String signerName = existing.issuedByName();
            String signerEmail = existing.issuedByEmail();
            String previouslyGeneratedBody = buildEmailDraft(existing, existing.docNumber(),
                existing.issueDate(), existing.firstIssuedDate(), existing.requiredByNote(), factory,
                signerName, signerEmail);
            if (previouslyGeneratedBody.equals(existing.emailBody())) {
                ImportRequestDto afterLeadTime = requireStored(id);
                String regeneratedBody = buildEmailDraft(afterLeadTime, afterLeadTime.docNumber(),
                    afterLeadTime.issueDate(), afterLeadTime.firstIssuedDate(),
                    afterLeadTime.requiredByNote(), factory, signerName, signerEmail);
                stored.updateEmailDraft(id, null, null, regeneratedBody);
            }
        }
        return withPageCount(requireStored(id));
    }

    /**
     * Whether {@code note} matches the GENERIC shape {@link
     * #defaultVesselEtaNote} always produces, for ANY anchor/range — not only the one specific
     * string a caller happens to have computed to compare against. Comparing to "the text
     * derived for THIS draft's own CURRENT range" would miss a carried-forward note
     * auto-derived for a DIFFERENT (the predecessor's, or an earlier edit's) range: after a
     * lead-time change the note's literal text no longer matches a freshly-recomputed derivation, so
     * an auto note would be wrongly treated as a CEO-typed custom one and left stale. A
     * human typing a genuinely custom note is not going to accidentally match this rigid,
     * machine-generated template.
     */
    private static final java.util.regex.Pattern AUTO_VESSEL_ETA_PATTERN = java.util.regex.Pattern.compile(
        "^ประมาณ \\d{2}/\\d{2}/\\d{2} – \\d{2}/\\d{2}/\\d{2} \\(\\d+–\\d+ วัน\\)$");

    private static boolean looksAutoDerivedVesselEta(String note) {
        return note != null && AUTO_VESSEL_ETA_PATTERN.matcher(note).matches();
    }

    /** Cross-field check neither bean validation on UpdateImportRequestRequest (both optional, so @Min/@Max alone
     * cannot enforce "both or neither") nor on SetLeadTimeRequest (both @NotNull, so "neither" cannot happen, but
     * min <= max still needs a cross-field check) can express alone. */
    private static void requireValidLeadTimePair(Integer min, Integer max) {
        if ((min == null) != (max == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ต้องระบุระยะเวลานำเข้าทั้งค่าต่ำสุดและสูงสุดพร้อมกัน");
        }
        if (min != null && min > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ระยะเวลานำเข้าต่ำสุดต้องไม่มากกว่าค่าสูงสุด");
        }
    }

    /** DRAFT → ISSUED: mints (or accepts) the number, stamps the date, sets/carries-forward the per-factory
     * progress step, and supersedes the version this one replaces. Order is load-bearing. */
    @Transactional
    public ImportRequestDto issue(long id, IssueImportRequestRequest request, UserPrincipal actor) {
        ImportRequestDto draft = requireDealStored(id);
        requireFullWrite(draft.ticketId(), actor);
        TicketSnapshot dealSnapshot = requireTicket(draft.ticketId());
        requireDealActive(dealSnapshot);
        if (!ImportRequestStatus.DRAFT.equals(draft.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อฉบับนี้ออกเลขไปแล้ว");
        }
        if (draft.items().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อยังไม่มีรายการสินค้า");
        }
        // A draft made before the order was confirmed can name a line that is now covered from stock; issuing it
        // would order stock the customer already has from the factory.
        if (stored.anyItemCoveredFromStock(id)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบขอซื้อนี้มีรายการที่ครอบคลุมจากสต็อกแล้ว — ไม่ต้องสั่งนำเข้า กรุณาลบฉบับร่างนี้แล้วสร้างใบขอซื้อใหม่");
        }
        // "อื่นๆ/unknown" lead times must be typed by sales — a form with no estimate
        // at all may not be issued. Both-or-neither is already the DB invariant
        // (chk_import_request_lead_time), so checking one implies the other.
        if (draft.leadTimeMinDays() == null || draft.leadTimeMaxDays() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                "กรุณาระบุระยะเวลานำเข้า (วัน) ของโรงงาน " + draft.factoryName());
        }

        TicketSummaryDto ticketSummary = requireTicketSummary(draft.ticketId());
        // The STORED per-factory path uses the WIDENED gate (any payment status
        // at or after deposit-ready, including AWAITING_FINAL_PAYMENT/FULLY_PAID) — a deal's second,
        // third, ... factory's first issue, or ANY factory's revision issue, must not be blocked just
        // because the customer finished paying in the meantime. The legacy one-shot
        // TicketService#issueImportRequest keeps the narrower gate
        // for the deal's very-first-IR semantics — see TicketService#requireImportRequestIssuable's
        // own Javadoc for why the two share one predicate with a widening flag rather than diverging.
        ticketService.requireImportRequestIssuable(ticketSummary, true);

        String override = request == null || request.docNumber() == null
            || request.docNumber().isBlank() ? null : request.docNumber().strip();
        return issueStoredDraft(draft, override, dealSnapshot, actor);
    }

    /**
     * The write half of {@link #issue}: everything AFTER the issuability checks (status, items, lead
     * time, payment gate). Shared with {@link #createDrafts}, which pre-checks every new factory and
     * then issues each freshly inserted row through here, so the two paths cannot drift.
     */
    private ImportRequestDto issueStoredDraft(ImportRequestDto draft, String override,
                                              TicketSnapshot dealSnapshot, UserPrincipal actor) {
        long id = draft.id();
        // An override does NOT advance the sequence. A number typed ahead of the counter therefore cannot be minted a
        // second time later — ux_import_request_doc_number refuses the duplicate — and this pre-check turns that race
        // into a clear message instead of a constraint violation.
        if (override != null && stored.docNumberExists(override)) {
            throw new ApiException(HttpStatus.CONFLICT, "เลขที่ใบขอซื้อ " + override + " ถูกใช้แล้ว");
        }
        LocalDate today = LocalDate.now(BANGKOK);
        String docNumber = override != null ? override : stored.nextDocNumber(thaiYear(today));

        // Lock order: the predecessor's rounds first, then its row.
        rounds.issuedIdOf(draft.ticketId(), draft.factoryId())
            .ifPresent(pid -> rounds.lockRounds(rounds.roundIdsOf(pid)));
        Optional<PredecessorStep> predecessor =
            stored.lockIssuedPredecessor(draft.ticketId(), draft.factoryId());
        String step;
        LocalDate stepAt;
        Long stepById;
        String stepByName;
        String stepNote;
        LocalDate firstIssuedDate;
        if (predecessor.isPresent()) {
            PredecessorStep p = predecessor.get();
            step = p.importStep();
            stepAt = p.importStepAt();
            stepById = p.importStepById();
            stepByName = p.importStepByName();
            stepNote = p.importStepNote();
            // Carried forward UNCHANGED — a revision's
            // issue must never move the arrival anchor, even a same-day typo correction.
            firstIssuedDate = p.firstIssuedDate();
            stored.supersede(p.id(), id);
        } else {
            step = ImportRequestStep.CONTACTED;
            stepAt = today;
            stepById = actor.id();
            stepByName = actor.name();
            stepNote = null;
            firstIssuedDate = today;
        }

        // vesselEtaNote defaults to the derived arrival estimate when the draft's OWN value is either
        // unset, or LOOKS auto-derived (for this draft's own, possibly since-edited by update() or
        // carried forward unchanged by revise(), anchor+range). Anchored on
        // firstIssuedDate, matching expectedArrivalFrom/To so the printed note and the derived
        // fields never disagree. A CEO-typed CUSTOM note — anything that does NOT match the
        // auto-derived shape — is left exactly alone; {@link #setLeadTime}
        // applies the identical rule for the post-issue lead-time edit.
        String derivedVesselEtaNote = defaultVesselEtaNote(firstIssuedDate, draft.leadTimeMinDays(),
            draft.leadTimeMaxDays());
        // looksAutoDerivedVesselEta, not an exact-string comparison with this draft's own current
        // derivation — see that method's Javadoc for why the latter misses a stale-but-still-auto note.
        String vesselEtaNote = (draft.vesselEtaNote() == null || draft.vesselEtaNote().isBlank()
            || looksAutoDerivedVesselEta(draft.vesselEtaNote()))
            ? derivedVesselEtaNote : draft.vesselEtaNote();

        // This FORM's own requiredByNote is re-snapshotted from the DEAL's
        // current value at issue time whenever it is still blank — the owning rep/CEO may instead
        // have already typed one directly onto this draft via update() (UpdateImportRequestRequest
        // #requiredByNote), which is never overwritten here.
        String requiredByNote = draft.requiredByNote() != null && !draft.requiredByNote().isBlank()
            ? draft.requiredByNote() : dealSnapshot.requiredByNote();

        FactoryConfigDto factory = freshFactoryLookup(draft.factoryId()).orElse(null);
        // Doc number + "GL&R" ONLY -- no project name. A project name often
        // identifies the customer (e.g. "Ban Khun Somchai"), and this line goes to a supplier who
        // must never learn who GL&R's customer is (the same reasoning behind blanking the FACTORY
        // copy's customer/deposit fields -- see ImportRequestFormAssembler#fromStored's factoryCopy
        // param). The project name must never reach this subject line, not even conditionally.
        String emailSubject = "Purchase order " + docNumber + " - GL&R";
        // Pass the ALREADY-RESOLVED requiredByNote (the
        // draft's own typed value, or the fresh deal snapshot when it was still blank) rather than
        // letting buildEmailDraft re-read draft.requiredByNote() itself -- that value could be
        // BLANK on the draft even though the deal-snapshot fallback above is what actually
        // gets written onto the issued row, so the printed form and the "Required by:" email line
        // could silently disagree.
        String emailBody = buildEmailDraft(draft, docNumber, today, firstIssuedDate, requiredByNote,
            factory, actor.name(), actor.email());

        int issued = stored.issue(id, docNumber, today, actor.id(), actor.name(), actor.email(),
            step, stepAt, stepById, stepByName, stepNote, firstIssuedDate, requiredByNote,
            factory == null ? null : factory.email(), emailSubject, emailBody);
        if (issued == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อถูกเปลี่ยนแปลงโดยผู้ใช้อื่น");
        }
        predecessor.ifPresent(p -> moveLotsToRevision(p.id(), draft));
        if (vesselEtaNote != null) {
            stored.updateFooter(id, vesselEtaNote, null, null, null, null);
        }
        if (predecessor.isPresent()) {
            rederiveRevisionStep(draft, id, docNumber, step, stepAt, stepById, stepByName, stepNote, firstIssuedDate, actor);
        }
        ticketService.recordFirstImportRequestIssued(draft.ticketId(), id, actor);
        notifyImportOfIssuedIr(draft, docNumber, dealSnapshot, actor, predecessor.isPresent());
        return withPageCount(requireStored(id));
    }

    /** Hand-off: tell import a new IR is waiting (or that an issued one was revised). */
    private void notifyImportOfIssuedIr(ImportRequestDto draft, String docNumber, TicketSnapshot deal,
                                        UserPrincipal actor, boolean revision) {
        String message = revision
            ? actor.name() + " ออกใบขอซื้อ (IR) ฉบับแก้ไข " + docNumber + " สำหรับโรงงาน " + draft.factoryName()
                + " แทนฉบับเดิม\n" + DealNoticeText.dealLabel(deal.ticketCode(), deal.customerName())
                + "\nสิ่งที่ต้องทำต่อ: ตรวจรายการในฉบับแก้ไข หากสั่งซื้อจากฉบับเดิมไปแล้ว กรุณาแจ้งโรงงานให้แก้ไขตาม"
            : actor.name() + " ออกใบขอซื้อ (IR) " + docNumber + " สำหรับโรงงาน " + draft.factoryName() + " แล้ว\n"
                + DealNoticeText.dealLabel(deal.ticketCode(), deal.customerName())
                + "\nสิ่งที่ต้องทำต่อ: ส่งอีเมลสั่งซื้อถึงโรงงาน แล้วอัปเดตขั้นตอนในใบขอซื้อ";
        notifications.notifyByRoleAtLink("import",
            revision ? TicketEventKind.IMPORT_REQUEST_REVISED : TicketEventKind.IMPORT_REQUEST_ISSUED,
            message, "/import/deals/" + draft.ticketId());
    }

    /** Hand-off: tell the deal's rep how the import is going — once per deal: at the start (first IR off CONTACTED) and when all goods are received. */
    private void notifyRepOfImportProgress(TicketSummaryDto deal, String type, String firstLine, String lastLine) {
        notifications.notifyEmployeeAtLink(deal.createdById(), type,
            firstLine + "\n" + DealNoticeText.dealLabel(deal.code(), deal.customerName()) + "\n" + lastLine,
            "/tickets/" + deal.id());
    }

    /**
     * "ประมาณ dd/MM/yy – dd/MM/yy (min–max วัน)" — the default {@code vesselEtaNote},
     * from the SAME anchor+range {@link #withPageCount} derives {@code
     * expectedArrivalFrom}/{@code To} from, so the printed note and the DTO's derived fields can
     * never read two different estimates for the same row. Null (nothing to default) when either
     * bound is unset — unreachable in practice since {@link #issue} already refuses to issue with a
     * null lead time, kept here only so this helper stays correct if that guard is ever relaxed.
     */
    private static String defaultVesselEtaNote(LocalDate anchor, Integer minDays, Integer maxDays) {
        if (anchor == null || minDays == null || maxDays == null) {
            return null;
        }
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy");
        return "ประมาณ " + anchor.plusDays(minDays).format(fmt) + " – " + anchor.plusDays(maxDays).format(fmt)
            + " (" + minDays + "–" + maxDays + " วัน)";
    }

    /**
     * The per-factory order-email draft — mirrors the numbered-block layout of the factory
     * price-request email ({@code FactoryQuoteService#emailBody}/{@code #appendItemBlock}): a
     * greeting, one numbered block per line, then required-by/
     * expected-arrival/attachment/signature. {@code FactoryQuoteService}'s
     * item shape ({@code PricingRequestItemDto}, with separate brand/model/specialRequirement
     * fields) is different enough from this class's {@code ImportRequestItemDto} that a shared
     * helper would need its own abstraction over both, which is more machinery than copying four
     * short lines of formatting logic; this method COPIES the approach (block shape, blank-skipping,
     * no-trailing-zero quantities, indented multi-line note) rather than extracting one.
     *
     * <p>Import-facing: no price, discount, pricing mode or wastage — {@link
     * ImportRequestDtosNoPricingFieldsTest} pins the DTO shapes, and {@code
     * ImportRequestEmailDraftIntegrationTest} pins this method's actual body CONTENT.
     */
    private String buildEmailDraft(ImportRequestDto draft, String docNumber, LocalDate issueDate,
            LocalDate firstIssuedDate, String requiredByNote, FactoryConfigDto factory,
            String signerName, String signerEmail) {
        String factoryName = factory != null && factory.factoryName() != null
            ? factory.factoryName() : draft.factoryName();
        StringBuilder body = new StringBuilder();
        body.append("Dear ").append(factoryName).append(" team,\n\n");
        body.append("Please confirm this order, referencing our purchase order ")
            .append(docNumber).append(":\n\n");
        int lineNo = 1;
        List<ImportRequestDtos.ImportRequestItemDto> items = draft.items();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                body.append("\n");
            }
            appendItemBlock(body, lineNo++, items.get(i));
        }
        // The CALLER resolves requiredByNote (draft's own
        // value, or the deal-snapshot fallback) and passes it in — see #issue's own comment for why
        // reading draft.requiredByNote() directly here could disagree with what actually gets
        // snapshotted onto the row.
        if (requiredByNote != null && !requiredByNote.isBlank()) {
            body.append("\nRequired by: ").append(requiredByNote).append("\n");
        }
        if (draft.leadTimeMinDays() != null && draft.leadTimeMaxDays() != null) {
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
            body.append("Expected arrival: approx. ")
                .append(firstIssuedDate.plusDays(draft.leadTimeMinDays()).format(fmt))
                .append(" - ").append(firstIssuedDate.plusDays(draft.leadTimeMaxDays()).format(fmt))
                .append("\n");
        }
        // Must equal the REAL download
        // filename, or a human copying this line into their mail client names an attachment that
        // does not match what they actually downloaded. A SHARED helper ({@link
        // #downloadFileName}) — ImportRequestController
        // #storedFile calls the very same method, so the two cannot drift independently. The
        // FACTORY name used is this row's OWN stored snapshot ({@code draft.factoryName()}), not the
        // (possibly since-renamed) fresh lookup used for the greeting above — the download route
        // itself only ever reads the snapshot, so the filename must match that, not today's factory
        // master.
        body.append("\nAttached: ")
            .append(downloadFileName(docNumber, draft.id(), draft.factoryName(), draft.brand(), true))
            .append("\n");
        body.append("\nThank you,\n");
        body.append(signerName == null ? "GL&R" : signerName);
        if (signerEmail != null && !signerEmail.isBlank()) {
            body.append("\n").append(signerEmail);
        }
        body.append("\nGL&R\n");
        return body.toString();
    }

    /**
     * The stored form's download filename — SHARED by {@link ImportRequestController#storedFile}
     * and {@link #buildEmailDraft}'s "Attached:" line, so a human
     * copying that line into a mail client always names the exact file the download route hands
     * them; the "Attached:" line always names the FACTORY copy specifically ({@code
     * factoryCopy=true}), the one actually meant to go out with the order email.
     */
    static String downloadFileName(String docNumber, long id, String factoryName, String brand,
            boolean factoryCopy) {
        return "IR-" + (docNumber == null ? "draft-" + id : docNumber)
            + "-" + (factoryName != null ? factoryName : brand)
            + (factoryCopy ? "" : "-internal") + ".pdf";
    }

    /** Renders one numbered item block for #buildEmailDraft — a Brand/Model (Code) header, then a
     * Colour/Surface/Size detail line, then Quantity, then the free-text note, each SKIPPED outright when blank
     * (never a "-" placeholder, never the literal string "null"). */
    private void appendItemBlock(StringBuilder body, int lineNo, ImportRequestDtos.ImportRequestItemDto item) {
        body.append(lineNo).append(". ").append(itemHeader(item.brand(), item.model(), item.code())).append("\n");

        List<String> details = new ArrayList<>();
        if (item.color() != null && !item.color().isBlank()) {
            details.add("Colour: " + item.color());
        }
        if (item.texture() != null && !item.texture().isBlank()) {
            details.add("Surface: " + item.texture());
        }
        if (item.size() != null && !item.size().isBlank()) {
            details.add("Size: " + SizeUnit.withCmUnit(item.size()));
        }
        if (!details.isEmpty()) {
            body.append("   ").append(String.join(" | ", details)).append("\n");
        }

        if (item.qty() != null) {
            // No trailing zeros -- "120" rather than "120.00", matching FactoryQuoteService's own formatQty for the same
            // reason: a factory-facing quantity is read as a whole count.
            body.append("   Quantity: ").append(item.qty().stripTrailingZeros().toPlainString());
            if (item.unit() != null && !item.unit().isBlank()) {
                body.append(" ").append(item.unit());
            }
            body.append("\n");
        }

        // Skip the note line outright when it is EXACTLY the auto-derived
        // "สี X · ผิว Y" colour/surface sentence (ImportRequestLinePrefill#buildColorSurfaceNote) --
        // that is already shown one line up as "Colour: X | Surface: Y", so printing it again here
        // would say the same thing twice in an email a factory actually reads. The printed Thai
        // FORM keeps the note sub-row regardless (ImportRequestFormAssembler never calls this
        // method) — this trim is specific to the order-email's own layout. A business-typed note
        // (anything that is NOT exactly the derived sentence) is never skipped.
        String derivedNote = ImportRequestLinePrefill.buildColorSurfaceNote(item.color(), item.texture());
        if (item.note() != null && !item.note().isBlank() && !item.note().equals(derivedNote)) {
            // Multi-line text stays indented inside this item's own block, matching FactoryQuoteService#appendItemBlock's
            // specialRequirement handling.
            body.append("   Note: ")
                .append(item.note().replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n   "))
                .append("\n");
        }
    }

    /**
     * "<Brand> <Model>  (Code: X)" — mirrors {@code
     * FactoryQuoteService#appendItemBlock}'s own header/fallback shape: brand+model together when
     * either is present, the code appended in parentheses only when it is ALSO present (never a
     * bare, empty "()"); when NEITHER brand nor model is known, the code stands alone with no
     * parentheses ("Code-only"); when nothing at all is known, the last-resort literal "Item" (never
     * blank — a line with no identifying text at all must still print SOMETHING a human can point
     * a factory reply at).
     */
    private static String itemHeader(String brand, String model, String code) {
        String brandModel = java.util.stream.Stream.of(brand, model)
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.joining(" "));
        boolean hasCode = code != null && !code.isBlank();
        // Skip the "(Code: X)" suffix when X is IDENTICAL to the model
        // already shown a few words earlier in brandModel — "... Lithos Nero  (Code: Lithos Nero)"
        // tells a factory the same thing twice for no reason. A code that genuinely differs (the
        // common catalog-linked case) still prints.
        if (hasCode && model != null && code.strip().equalsIgnoreCase(model.strip())) {
            hasCode = false;
        }
        if (!brandModel.isBlank()) {
            return hasCode ? brandModel + "  (Code: " + code + ")" : brandModel;
        }
        return hasCode ? code : "Item";
    }

    /**
     * Prepares a correction: a new DRAFT at the next version for the same (deal, factory), copying
     * the issued form's body so the corrector edits from what was actually sent rather than from the
     * deal's current state.
     *
     * <p>Refused once the predecessor has reached {@link ImportRequestStep#RECEIVED}
     * — the goods are already in, so there is nothing
     * left a correction could still affect operationally, only the paper record.
     *
     * <p>The form being corrected STAYS ISSUED until the replacement is issued — see {@link #issue}.
     */
    @Transactional
    public ImportRequestDto revise(long id, UserPrincipal actor) {
        ImportRequestDto issued = requireDealStored(id);
        requireFullWrite(issued.ticketId(), actor);
        TicketSnapshot snapshot = requireTicket(issued.ticketId());
        requireDealActive(snapshot);
        if (!ImportRequestStatus.ISSUED.equals(issued.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ออกฉบับแก้ไขได้เฉพาะใบขอซื้อที่ออกเลขแล้ว");
        }
        if (ImportRequestStep.RECEIVED.equals(issued.importStep())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อที่ได้รับสินค้าแล้ว (RECEIVED) แก้ไขไม่ได้");
        }
        // Lead time is CARRIED FORWARD from the predecessor's actual (possibly hand-edited) values,
        // not re-autofilled from the factory's country — a correction should not silently discard
        // an estimate import already refined (same "carried forward" rule
        // the progress step follows just above). vesselEtaNote is carried forward the same
        // way — issue() re-derives it only if it is STILL exactly the
        // auto-derived text for this draft's own range at that point; a CEO-typed custom note
        // survives the revision untouched.
        // The issued form's OWN requiredByNote must carry
        // forward too — leaving it out falls back to `insertDraft`'s
        // snap.requiredByNote() default (the DEAL's CURRENT value), silently dropping anything the
        // rep had typed directly onto THIS form (e.g. "Before Songkran") the moment it is revised.
        // The lead time follows the deal's current QUOTATION. The quotation-derived
        // value an IR was built from is stored on it (derived_lead_time_*); on revise a freshly derived
        // value that EQUALS the stored one means the quotation did not change, so the predecessor's
        // possibly hand-edited lead time (POST /import-requests/{id}/lead-time) is carried forward;
        // a DIFFERENT derived value means a new quotation changed it, so that wins.
        // No quotation lead time at all (or a row that predates the column) also carries forward. An
        // approved lead-time change with no new quotation therefore never reaches the IR.
        LeadTimeDefaults.Range freshDerived = leadTimeFromQuotation(
            repository.currentQuotationLeadTimes(issued.ticketId()), issued.factoryId(),
            issued.items().stream()
                .map(it -> new ImportRequestItemInput(it.ticketItemId(), it.code(), it.size(),
                    it.qty(), it.unit(), it.note(), it.color(), it.texture(), it.brand(), it.model()))
                .toList());
        int[] storedDerived = stored.findDerivedLeadTime(issued.id()).orElse(null);
        boolean quotationChanged;
        if (freshDerived == null) {
            quotationChanged = false;
        } else if (storedDerived != null) {
            quotationChanged = storedDerived[0] != freshDerived.minDays() || storedDerived[1] != freshDerived.maxDays();
        } else {
            // Nothing records what this IR was built from (pre-V198 rows, and IRs first built from the country default).
            // Its live lead time only counts as a HAND edit if it differs from that country default; an unedited default
            // must not shadow a quotation value forever.
            LeadTimeDefaults.Range countryDefault = LeadTimeDefaults.forCountry(
                freshFactoryLookup(issued.factoryId()).map(FactoryConfigDto::country).orElse(null));
            boolean handEdited = issued.leadTimeMinDays() != null && issued.leadTimeMaxDays() != null
                && (countryDefault == null || issued.leadTimeMinDays() != countryDefault.minDays()
                    || issued.leadTimeMaxDays() != countryDefault.maxDays());
            quotationChanged = !handEdited;
        }
        Integer revisedMin = quotationChanged ? freshDerived.minDays() : issued.leadTimeMinDays();
        Integer revisedMax = quotationChanged ? freshDerived.maxDays() : issued.leadTimeMaxDays();
        long revisionId = stored.insertDraft(issued.ticketId(), issued.factoryId(), issued.factoryName(),
            issued.brand(), stored.highestVersion(issued.ticketId(), issued.factoryId()) + 1,
            snapshot, revisedMin, revisedMax, issued.vesselEtaNote(),
            issued.requiredByNote(), actor.id(), actor.name());
        if (freshDerived != null) {
            stored.setDerivedLeadTime(revisionId, freshDerived.minDays(), freshDerived.maxDays());
        }
        stored.replaceItems(revisionId, issued.items().stream()
            .map(it -> new ImportRequestItemInput(it.ticketItemId(), it.code(), it.size(),
                it.qty(), it.unit(), it.note(), it.color(), it.texture(), it.brand(), it.model()))
            .toList());
        return withPageCount(requireStored(revisionId));
    }

    /** A draft that should not exist is deleted, not tombstoned — it has no number and no audit weight. */
    @Transactional
    public void deleteDraft(long id, UserPrincipal actor) {
        ImportRequestDto existing = requireDealStored(id);
        requireFullWrite(existing.ticketId(), actor);
        // Like every other write in this class, refuse on an inactive deal.
        requireDealActive(requireTicket(existing.ticketId()));
        if (stored.deleteDraft(id) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ลบได้เฉพาะใบขอซื้อที่ยังไม่ออกเลข");
        }
    }

    // ── order-email draft ───────────────────────────────────────────────────────────────────────

    /** Edits the drafted order-email. */
    @Transactional
    public ImportRequestDto updateEmailDraft(long id, ImportRequestRequests.UpdateEmailDraftRequest request,
            UserPrincipal actor) {
        ImportRequestDto existing = requireDealStored(id);
        requireFooterWrite(existing.ticketId(), actor);
        requireDealActive(requireTicket(existing.ticketId()));
        if (!ImportRequestStatus.ISSUED.equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "แก้ไขอีเมลได้เฉพาะใบขอซื้อที่ออกเลขแล้ว");
        }
        if (existing.emailSentAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "อีเมลนี้ถูกส่งไปแล้ว แก้ไขไม่ได้");
        }
        // The repository's WHERE clause ALSO compare-and-sets on
        // status/unsent (see its own Javadoc) — checking 0 here, rather than ignoring it, is what
        // actually makes that guard mean something instead of a silently-swallowed lost update.
        if (stored.updateEmailDraft(id, request == null ? null : request.emailTo(),
                request == null ? null : request.emailSubject(),
                request == null ? null : request.emailBody()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "อีเมลนี้ถูกส่งไปแล้ว หรือถูกเปลี่ยนแปลงโดยผู้ใช้อื่น");
        }
        return withPageCount(requireStored(id));
    }

    /**
     * Records that a human has sent the drafted order-email by hand — import/CEO OR the deal's own
     * owning sales rep.
     * Idempotent: a second call on an already-sent draft 409s rather than re-stamping who/when, the
     * same compare-and-set discipline every other write in this class follows.
     */
    @Transactional
    public ImportRequestDto markEmailSent(long id, UserPrincipal actor) {
        ImportRequestDto existing = requireDealStored(id);
        if (!canFullWrite(existing.ticketId(), actor) && !FOOTER_ONLY_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        requireDealActive(requireTicket(existing.ticketId()));
        if (!ImportRequestStatus.ISSUED.equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ทำเครื่องหมายส่งอีเมลได้เฉพาะใบขอซื้อที่ออกเลขแล้ว");
        }
        if (stored.markEmailSent(id, actor.id(), actor.name()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "อีเมลนี้ถูกส่งไปแล้ว");
        }
        tickets.addEventWithDocument(existing.ticketId(), actor.id(), actor.name(),
            TicketEventKind.IMPORT_REQUEST_EMAIL_SENT, null, null,
            "ส่งอีเมลสั่งซื้อของโรงงาน " + existing.factoryName() + " แล้ว",
            th.co.glr.hr.ticket.RelatedDocumentType.IMPORT_REQUEST, id);
        return withPageCount(requireStored(id));
    }

    /**
     * Advances an ISSUED row's per-factory progress by ONE step (V184, S12-S17) —
     * {@link #ADVANCE_STEP_ROLES} ({@code import}/{@code ceo}) only.
     *
     * <p>Forward-only, and a skip is allowed (forward skips allowed, never
     * backwards — correct via revision), so the target need only be STRICTLY AHEAD of the row's
     * current step, not the immediate next one.
     *
     * <p>Compare-and-set at the repository ({@link ImportRequestRepository#advanceStep}): 0 rows
     * means the row is no longer the live ISSUED version, or another caller already moved the step
     * past what this caller last saw — both 409, matching this class's other compare-and-set writes.
     *
     * <p>Reaching {@link ImportRequestStep#RECEIVED} re-derives, under a lock on the DEAL's own row
     * (so two concurrent "last factory" advances on the same deal cannot both miss each other), "does
     * every factory this deal actually needs an import for now have an ISSUED row at RECEIVED" — see
     * {@link #allFactoriesReceived} — and if so calls {@link
     * TicketService#applyImportRequestRollup}.
     */
    @Transactional
    public ImportRequestDto advanceStep(long id, AdvanceImportStepRequest request, UserPrincipal actor) {
        if (!ADVANCE_STEP_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        ImportRequestDto existing = requireDealStored(id);
        requireDealActive(requireTicket(existing.ticketId()));
        if (!ImportRequestStatus.ISSUED.equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "อัปเดตขั้นตอนได้เฉพาะใบขอซื้อที่ออกเลขแล้ว");
        }
        String target = request == null ? null : request.targetStep();
        if (!ImportRequestStep.isValid(target)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ขั้นตอนไม่ถูกต้อง: " + target);
        }
        int currentIdx = ImportRequestStep.indexOf(existing.importStep());
        int targetIdx = ImportRequestStep.indexOf(target);
        if (targetIdx <= currentIdx) {
            throw new ApiException(HttpStatus.CONFLICT,
                "เลื่อนขั้นตอนได้เฉพาะไปข้างหน้าเท่านั้น — แก้ไขย้อนหลังด้วยการออกฉบับแก้ไข");
        }
        LocalDate eventDate = request.eventDate() != null ? request.eventDate() : LocalDate.now(BANGKOK);
        // A backdatable step may still not be dated in the FUTURE (Asia/Bangkok,
        // matching this class's own BANGKOK zone), nor before the row's own first-issue date — a
        // step cannot have happened before the factory was even contacted.
        if (eventDate.isAfter(LocalDate.now(BANGKOK))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ของขั้นตอนต้องไม่เป็นวันที่ในอนาคต");
        }
        if (existing.firstIssuedDate() != null && eventDate.isBefore(existing.firstIssuedDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "วันที่ของขั้นตอนต้องไม่ก่อนวันที่ออกใบขอซื้อครั้งแรก (" + existing.firstIssuedDate() + ")");
        }
        OptionalLong soleRound = lockSoleRound(id);
        // RECEIVED needs the round's documents: an IR with no round, or on a portal round, gets there on the round page.
        // Checked here, under the locks and after the role check; MIGRATED and BACKFILL rounds keep working.
        if (ImportRequestStep.RECEIVED.equals(target) && (soleRound.isEmpty() || rounds.hasPortalRound(id))) {
            throw new ApiException(HttpStatus.CONFLICT, "กรุณาเลื่อนไปถึงโกดังที่หน้ารอบขนส่ง");
        }
        int rows = stored.advanceStep(id, existing.importStep(), target, eventDate,
            actor.id(), actor.name(), request.note());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ขั้นตอนถูกเปลี่ยนแปลงโดยผู้ใช้อื่น หรือฉบับนี้ถูกแทนที่แล้ว");
        }
        if (soleRound.isPresent()) {
            rounds.advanceRound(soleRound.getAsLong(), target, eventDate, actor.id(), actor.name(), request.note(), null);
        } else {
            rounds.createPortalRound(id, existing.factoryId(), existing.factoryName(), target, eventDate,
                existing.importStep(), existing.importStepAt(), actor.id(), actor.name(), request.note());
        }
        afterStepChanges(existing.ticketId(), List.of(new StepChange(existing, existing.importStep(), target)),
            actor, request.note());
        return withPageCount(requireStored(id));
    }

    /** One bell (no email) to the deal's rep when a lot of theirs reaches ถึงโกดัง: which IR and how much. */
    public void notifyRepOfReceivedLot(long ticketId, String docNumber, String factoryName, String qtyText) {
        TicketSummaryDto deal = requireTicketSummary(ticketId);
        notifications.notifyEmployeeBellOnly(deal.createdById(), "IMPORT_LOT_RECEIVED",
            "ใบขอซื้อ (IR) " + docNumber + " จากโรงงาน " + factoryName + (qtyText.startsWith("ถึงโกดัง") ? " " : " ถึงโกดังแล้ว ") + qtyText + "\n"
                + DealNoticeText.dealLabel(deal.code(), deal.customerName()) + "\nดูความคืบหน้าที่แท็บ ส่งของ",
            "/tickets/" + deal.id() + "?tab=ship");
    }

    /** One bell (no email) to the requester of a no-project order when a lot of it reaches ถึงโกดัง; they read it on ของที่ฉันสั่ง. */
    public void notifyRequesterOfReceivedLot(long requesterId, String docNumber, String factoryName, String qtyText) {
        notifications.notifyEmployeeBellOnly(requesterId, "IMPORT_LOT_RECEIVED",
            "ใบขอซื้อ (IR) " + docNumber + " จากโรงงาน " + factoryName + (qtyText.startsWith("ถึงโกดัง") ? " " : " ถึงโกดังแล้ว ")
                + qtyText + "\nสิ่งที่ต้องทำต่อ: ดูที่ ของที่ฉันสั่ง", "/my-orders");
    }

    /** A revision's lots followed it from the predecessor, so its step is re-derived from them (an IR with no lots keeps
     * the carried-forward step), with the same aftermath as any step change (events, the all-received rollup). */
    private void rederiveRevisionStep(ImportRequestDto draft, long id, String docNumber, String step, LocalDate stepAt,
                                      Long stepById, String stepByName, String stepNote, LocalDate firstIssuedDate,
                                      UserPrincipal actor) {
        if (rounds.lotsOf(id).isEmpty()) {
            return;
        }
        var ir = new ShipmentRoundRepository.IrLock(id, draft.ticketId(), docNumber, draft.factoryName(), step, stepAt,
            stepById, stepByName, stepNote, firstIssuedDate, true, draft.factoryId(), null);
        var d = ShipmentStepDerivation.derive(ir, rounds.derivedInputs(List.of(id)));
        if (d.step().equals(step)) {
            return;
        }
        ImportRequestDto before = requireStored(id);
        boolean own = d.byName() != null;
        stored.setDerivedStep(id, d.step(), d.at(), own ? d.byId() : actor.id(), own ? d.byName() : actor.name(),
            own ? d.note() : null);
        afterStepChanges(draft.ticketId(), List.of(new StepChange(before, step, d.step())), actor, null);
    }

    /** One IR whose step changed, with the step it left. */
    public record StepChange(ImportRequestDto ir, String from, String to) {}

    /** What follows any change of IR steps, shared by the IR-level advance and the shipment-round advance: one
     * IMPORT_STEP_ADVANCED event per changed IR, then (once per deal) the all-received rollup or the first-start
     * notice. The IR rows must already hold their new steps. The ticket is locked FIRST, before any event insert:
     * the event's FK takes a KEY SHARE lock on the ticket, and two callers upgrading to FOR UPDATE would deadlock. */
    public boolean afterStepChanges(long ticketId, List<StepChange> changes, UserPrincipal actor, String note) {
        String noteSuffix = note != null && !note.isBlank() ? " — " + note : "";
        tickets.lockTicketForUpdate(ticketId);
        for (StepChange c : changes) {
            tickets.addEventWithDocument(ticketId, actor.id(), actor.name(),
                TicketEventKind.IMPORT_STEP_ADVANCED, null, null,
                "โรงงาน " + c.ir().factoryName() + ": " + ImportRequestStep.thaiLabel(c.from())
                    + " → " + ImportRequestStep.thaiLabel(c.to()) + noteSuffix,
                th.co.glr.hr.ticket.RelatedDocumentType.IMPORT_REQUEST, c.ir().id());
        }
        Set<Long> changedIds = changes.stream().map(c -> c.ir().id()).collect(Collectors.toSet());
        StepChange received = changes.stream().filter(c -> ImportRequestStep.RECEIVED.equals(c.to())).findFirst().orElse(null);
        if (received != null && allFactoriesReceived(ticketId)) {
            String ir = "ใบขอซื้อ (IR) " + received.ir().docNumber();
            ticketService.applyImportRequestRollup(ticketId, actor);
            notifyRepOfImportProgress(requireTicketSummary(ticketId), TicketEventKind.IMPORT_GOODS_RECEIVED_ALL,
                "สินค้าตาม" + ir + " จากโรงงาน " + received.ir().factoryName() + " เข้าคลังแล้ว และครบทุกใบขอซื้อของดีลนี้",
                "สิ่งที่ต้องทำต่อ: นัดวันส่งสินค้ากับลูกค้า");
            return true;
        }
        StepChange started = changes.stream().filter(c -> ImportRequestStep.CONTACTED.equals(c.from())
            && !ImportRequestStep.CONTACTED.equals(c.to())).findFirst().orElse(null);
        if (started != null
            && stored.findIssuedByTicket(ticketId).stream()
                .noneMatch(o -> !changedIds.contains(o.id()) && !ImportRequestStep.CONTACTED.equals(o.importStep()))
            // An undo back to CONTACTED, then a re-advance, must not tell the rep twice (review M4).
            && !stored.importStartedNoticeSent(ticketId, requireTicketSummary(ticketId).createdById())) {
            String ir = "ใบขอซื้อ (IR) " + started.ir().docNumber();
            notifyRepOfImportProgress(requireTicketSummary(ticketId), TicketEventKind.IMPORT_STARTED,
                actor.name() + " เริ่มดำเนินการ" + ir + " กับโรงงาน " + started.ir().factoryName()
                    + " แล้ว ขั้นตอนล่าสุด: " + ImportRequestStep.thaiLabel(started.to()),
                "แจ้งเพื่อทราบ ระบบจะแจ้งอีกครั้งเมื่อสินค้าเข้าคลัง");
        }
        return false;
    }

    /** How long after an advance its actor may still undo it (spec 2026-11-15 §7.11 item 3, the /fulfilment snackbar). */
    static final int STEP_UNDO_WINDOW_SECONDS = 30;

    /** The service-side window check's clock (review M3); the repository's guarded UPDATE uses the database clock. */
    private java.time.Clock clock = java.time.Clock.systemUTC();

    /** Test seam for the service-side window check. */
    void useClock(java.time.Clock clock) {
        this.clock = clock;
    }

    /** When the latest advance's undo window closes (its database stamp + {@link #STEP_UNDO_WINDOW_SECONDS}), or null
     * when there is nothing to undo. Read straight after an advance so the snackbar counts down to SERVER time. */
    public java.time.Instant stepUndoUntil(long id) {
        return stored.findStepUndoState(id)
            .filter(s -> s.undoFrom() != null && s.advancedAt() != null)
            .map(s -> s.advancedAt().plusSeconds(STEP_UNDO_WINDOW_SECONDS))
            .orElse(null);
    }

    /**
     * Undoes the caller's OWN latest step advance within {@link #STEP_UNDO_WINDOW_SECONDS} (V216), new in slice 5.
     *
     * <p>Authorisation: the SAME role gate as {@link #advanceStep} ({@link #ADVANCE_STEP_ROLES}, 403), then
     * actor-only: the advance must have been made by this caller (403 otherwise; the CEO cannot undo import's).
     * Refused with 409: not ISSUED; nothing to undo (never advanced, or already undone: one level only); the row is no
     * longer at {@code expectedStep} (a later advance by anyone ends the window); the advance reached RECEIVED (it may
     * have rolled the deal up and notified the rep, so it is corrected by revision); the window has passed (database
     * clock). Restores step, date, by and note exactly, and records an IMPORT_STEP_ADVANCED event saying "เลิกทำ" (no
     * new event kind, so V216 need not re-declare chk_event_kind; DealHistoryPanel labels it from that marker).
     *
     * <p>Locks in EXACTLY {@link #advanceStep}'s order: the row (the guarded UPDATE) first, then the deal, so an
     * advance and an undo racing on one row cannot deadlock (review H1; ImportStepLockOrderIntegrationTest). The
     * window is checked twice, independently: here against this service's clock, and again in the UPDATE against the
     * database clock; a refusal by the UPDATE alone is the lost-race 409.
     *
     * <p>The rep is told nothing about an undo (deliberate, review M4): the advance itself notifies only on its first
     * exit from CONTACTED or on the final RECEIVED, and neither is re-sent: RECEIVED is never undoable, and a
     * re-advance out of CONTACTED skips an IMPORT_STARTED the rep already has.
     */
    @Transactional
    public ImportRequestDto undoStep(long id, ImportRequestRequests.UndoImportStepRequest request, UserPrincipal actor) {
        if (!ADVANCE_STEP_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        String expected = request == null ? null : request.expectedStep();
        if (!ImportRequestStep.isValid(expected)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุขั้นตอนที่จะเลิกทำ");
        }
        ImportRequestDto existing = requireDealStored(id);
        requireDealActive(requireTicket(existing.ticketId()));
        var state = stored.findStepUndoState(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบขอซื้อ"));
        if (!ImportRequestStatus.ISSUED.equals(state.status()) || state.undoFrom() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ไม่มีการเลื่อนขั้นตอนให้เลิกทำ");
        }
        if (!expected.equals(state.importStep())) {
            throw new ApiException(HttpStatus.CONFLICT, "ขั้นตอนถูกเปลี่ยนไปแล้ว จึงเลิกทำไม่ได้");
        }
        if (state.advancedById() == null || state.advancedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เลิกทำได้เฉพาะผู้ที่เลื่อนขั้นตอนนี้");
        }
        if (ImportRequestStep.RECEIVED.equals(state.importStep())) {
            throw new ApiException(HttpStatus.CONFLICT, "รับสินค้าเข้าคลังแล้ว เลิกทำไม่ได้ — แก้ไขด้วยการออกฉบับแก้ไข");
        }
        if (state.advancedAt() == null
            || clock.instant().isAfter(state.advancedAt().plusSeconds(STEP_UNDO_WINDOW_SECONDS))) {
            throw new ApiException(HttpStatus.CONFLICT, "เลยเวลาเลิกทำแล้ว (" + STEP_UNDO_WINDOW_SECONDS + " วินาที)");
        }
        // Rounds, then the row, then the deal: advanceStep's order (review H1).
        OptionalLong soleRound = lockSoleRound(id);
        if (stored.undoStep(id, expected, actor.id(), STEP_UNDO_WINDOW_SECONDS) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "มีผู้อื่นเปลี่ยนขั้นตอนนี้ไปก่อน หรือเลยเวลาเลิกทำแล้ว จึงเลิกทำไม่ได้");
        }
        ImportRequestDto after = requireStored(id);
        soleRound.ifPresent(r -> {
            if (rounds.createdByAdvance(r)) {
                rounds.deleteRound(r); // the advance made this round: undoing it returns the IR to waiting
            } else {
                if (rounds.undoRound(r, expected, actor.id(), rounds.advanceIdOf(r), STEP_UNDO_WINDOW_SECONDS) == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "ขั้นตอนถูกเปลี่ยนแปลงโดยผู้ใช้อื่น จึงเลิกทำไม่ได้");
                }
            }
        });
        tickets.lockTicketForUpdate(existing.ticketId());
        tickets.addEventWithDocument(existing.ticketId(), actor.id(), actor.name(),
            TicketEventKind.IMPORT_STEP_ADVANCED, null, null,
            "โรงงาน " + existing.factoryName() + ": เลิกทำ " + ImportRequestStep.thaiLabel(expected)
                + " → " + ImportRequestStep.thaiLabel(after.importStep()),
            th.co.glr.hr.ticket.RelatedDocumentType.IMPORT_REQUEST, id);
        return withPageCount(after);
    }

    private static final String SHARED_ROUND_MESSAGE =
        "ใบขอซื้อนี้อยู่ในรอบขนส่งร่วมหรือแบ่งล็อต กรุณาเลื่อนขั้นตอนที่รอบขนส่ง";

    /** Locks the rounds this import request rides on, re-reads them under the lock, and returns the round that carries
     * only this request in full. Empty = no round yet; a shared or split request is refused. */
    private OptionalLong lockSoleRound(long importRequestId) {
        List<Long> ids = rounds.roundIdsOf(importRequestId);
        rounds.lockRounds(ids);
        if (ids.isEmpty()) {
            // no round to lock: take the IR row, so a planner's lot that commits first is seen by the recheck below
            rounds.lockImportRequests(List.of(), List.of(importRequestId));
        }
        if (!ids.equals(rounds.roundIdsOf(importRequestId))) {
            throw new ApiException(HttpStatus.CONFLICT, "ขั้นตอนถูกเปลี่ยนแปลงโดยผู้ใช้อื่น หรือฉบับนี้ถูกแทนที่แล้ว");
        }
        if (ids.isEmpty()) {
            return OptionalLong.empty();
        }
        if (ids.size() > 1 || !rounds.isSoleWhole(importRequestId, ids.get(0))) {
            throw new ApiException(HttpStatus.CONFLICT, SHARED_ROUND_MESSAGE);
        }
        return OptionalLong.of(ids.get(0));
    }

    /** The one new line this lot belongs to: same deal line, else (no deal line) same code and size. Never by position. */
    private static ImportRequestDtos.ImportRequestItemDto matchLine(ShipmentRoundRepository.LotRow lot,
                                                                    List<ImportRequestDtos.ImportRequestItemDto> items) {
        java.util.function.Predicate<ImportRequestDtos.ImportRequestItemDto> sameProduct = it ->
            java.util.Objects.equals(it.code(), lot.code()) && java.util.Objects.equals(it.size(), lot.size());
        List<ImportRequestDtos.ImportRequestItemDto> found = lot.ticketItemId() == null
            ? items.stream().filter(sameProduct).toList()
            : items.stream().filter(it -> lot.ticketItemId().equals(it.ticketItemId())).toList();
        if (found.size() > 1) {
            found = found.stream().filter(sameProduct).toList();
        }
        if (found.size() != 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ฉบับแก้ไขตัดรายการที่แบ่งล็อตส่งแล้วออก (" + lot.code() + ") กรุณาแก้ที่รอบขนส่งก่อน");
        }
        return found.get(0);
    }

    /** A revision's items are new rows, so the superseded row's lots follow onto them (same rounds, same quantities). */
    private void moveLotsToRevision(long oldId, ImportRequestDto revision) {
        List<ShipmentRoundRepository.LotRow> lots = rounds.lotsOf(oldId);
        if (lots.isEmpty()) {
            return;
        }
        List<Long> roundIds = rounds.roundIdsOf(oldId);
        boolean whole = roundIds.size() == 1 && rounds.wholeOn(oldId, roundIds.get(0));
        // line -> round -> {qty, received}; the whole case is every new line, in full, on the one round. received is what
        // really arrived (a short arrival carries over: summed when lots merge, capped at the new quantity).
        Map<Long, Map<Long, java.math.BigDecimal[]>> plan = new java.util.LinkedHashMap<>();
        if (whole) {
            // A round that has left: what was shipped and what arrived stay as they were; an increase stays unplanned
            // (waiting), a decrease below the lot is refused. Before departure the lot simply takes the revised quantity.
            boolean left = ImportRequestStep.indexOf(rounds.stepOfRound(roundIds.get(0))) >= ImportRequestStep.indexOf(ImportRequestStep.IN_TRANSIT);
            for (var it : revision.items()) {
                var old = oldLotFor(lots, it);
                if (left) {
                    if (old.isEmpty()) {
                        continue;
                    }
                    var lot = old.get();
                    if (it.qty().compareTo(lot.qty()) < 0) {
                        throw new ApiException(HttpStatus.CONFLICT,
                            "ฉบับแก้ไขลดจำนวนรายการที่ " + it.seq() + " ต่ำกว่าจำนวนที่จัดลงรอบขนส่งแล้ว กรุณาแก้ที่รอบขนส่งก่อน");
                    }
                    plan.computeIfAbsent(it.id(), k -> new java.util.LinkedHashMap<>()).put(roundIds.get(0),
                        new java.math.BigDecimal[] {lot.qty(), lot.receivedQty()});
                    continue;
                }
                java.math.BigDecimal received = old.map(ShipmentRoundRepository.LotRow::receivedQty).orElse(null);
                plan.computeIfAbsent(it.id(), k -> new java.util.LinkedHashMap<>()).put(roundIds.get(0),
                    new java.math.BigDecimal[] {it.qty(), received == null ? it.qty() : received.min(it.qty())});
            }
        } else {
            for (var lot : lots) {
                var match = matchLine(lot, revision.items());
                java.math.BigDecimal got = lot.receivedQty() != null ? lot.receivedQty() : lot.qty();
                plan.computeIfAbsent(match.id(), k -> new java.util.LinkedHashMap<>())
                    .merge(lot.roundId(), new java.math.BigDecimal[] {lot.qty(), got},
                        (a, b) -> new java.math.BigDecimal[] {a[0].add(b[0]), a[1].add(b[1])});
            }
            for (var it : revision.items()) {
                java.math.BigDecimal lotted = plan.getOrDefault(it.id(), Map.of()).values().stream()
                    .map(v -> v[0]).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                if (it.qty().compareTo(lotted) < 0) {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "ฉบับแก้ไขลดจำนวนรายการที่ " + it.seq() + " ต่ำกว่าจำนวนที่จัดลงรอบขนส่งแล้ว กรุณาแก้ที่รอบขนส่งก่อน");
                }
            }
        }
        rounds.deleteLotsOf(oldId);
        plan.forEach((itemId, byRound) -> byRound.forEach((roundId, v) ->
            rounds.insertLot(roundId, revision.id(), itemId, v[0], v[1] == null || v[1].compareTo(v[0]) == 0 ? null : v[1])));
    }

    /** The predecessor's lot of the line a revised item stands for: same deal line, else same code and size. */
    private static Optional<ShipmentRoundRepository.LotRow> oldLotFor(List<ShipmentRoundRepository.LotRow> lots,
                                                                       ImportRequestDtos.ImportRequestItemDto item) {
        var byTicketItem = lots.stream().filter(l -> l.ticketItemId() != null && l.ticketItemId().equals(item.ticketItemId())).findFirst();
        return byTicketItem.isPresent() ? byTicketItem : lots.stream().filter(l -> l.ticketItemId() == null
            && java.util.Objects.equals(l.code(), item.code()) && java.util.Objects.equals(l.size(), item.size())).findFirst();
    }

    /** Prints the INTERNAL stored form from ITS OWN snapshot — everything, unlike the FACTORY copy.
     * See {@link #renderStored(long, UserPrincipal, boolean)}. */
    public byte[] renderStored(long id, UserPrincipal actor) {
        return renderStored(id, actor, false);
    }

    /**
     * Prints a stored form from ITS OWN snapshot, not from the deal's current state.
     *
     * <p>The copy actually attached to the factory-facing order
     * email must not show "สั่งมาให้" (customer name), the project name, or "วันที่ได้รับมัดจำ"
     * (deposit received date) — a supplier has no business knowing who GL&R's customer is, and a
     * project name usually identifies them. {@code factoryCopy=true} blanks exactly those three fields ({@link ImportRequestFormAssembler#fromStored(ImportRequestDto,
     * boolean)}); everything else on the form — factory, lines, quantities, required-by, vessel ETA,
     * doc number, footer — is IDENTICAL between the two copies. Same read-authorisation as the
     * internal copy ({@link #requireRead}) — which copy is requested is not a permission question.
     */
    public byte[] renderStored(long id, UserPrincipal actor, boolean factoryCopy) {
        ImportRequestDto row = requireStored(id);
        requireReadRow(row, actor);
        try {
            return renderer.render(ImportRequestFormAssembler.fromStored(row, factoryCopy));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "สร้างไฟล์ใบขอซื้อไม่สำเร็จ: " + e.getMessage());
        }
    }

    /**
     * "กำหนดวันที่ต้องการของ" on the DEAL — SALES's field, which is why this is the
     * one method here not gated on the stored-aggregate roles above.
     *
     * <p>Gated on {@link #REQUIRED_BY_ROLES} AND deal ownership AND a stage floor of
     * {@code ORDER_RECEIVED}: sales fills this in "when the order is
     * already confirmed". Free text, and nullable so it can be cleared.
     */
    @Transactional
    public void setRequiredByNote(long ticketId, SetRequiredByNoteRequest request, UserPrincipal actor) {
        TicketSnapshot snapshot = requireTicket(ticketId);
        if (!REQUIRED_BY_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if ("sales".equals(actor.role()) && !ownsDeal(ticketId, actor)) {
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

    // ── factory resolution ────────────────────────────────────────────────────────────────────────

    /** One factory's group of resolved lines, ready to become one DRAFT row. {@code factoryId} is null only in the
     * split PREVIEW, for a typed name createDrafts would auto-create (it never reaches an insert there). */
    private record FactoryGroup(Long factoryId, String factoryName, String countryCode, String brandLabel,
                                List<ImportRequestItemInput> items) {}

    /** One row createDrafts would create: the group, its quotation-derived lead time (null when no current
     * quotation item carries one) and the lead time it uses before any caller-supplied value (the derived one,
     * else the country default, else null — createDrafts then needs a supplied one or 409s). */
    private record PlannedRow(FactoryGroup group, LeadTimeDefaults.Range derived, LeadTimeDefaults.Range leadTime) {}

    /**
     * The pure "what would be created" step of {@link #createDrafts}, shared by it and by the coverage route's split
     * preview ({@link #splitPreview}) so the two cannot disagree: every group whose factory has no LIVE row yet, in
     * group order, with its lead time chosen exactly as createDrafts chooses it. Reads and writes nothing.
     */
    private static List<PlannedRow> planNewRows(List<FactoryGroup> groups, Set<Long> alreadyCovered,
            List<ImportRequestQueryRepository.QuotationLeadTimeRow> quotationLeadTimes) {
        List<PlannedRow> planned = new ArrayList<>();
        for (FactoryGroup group : groups) {
            if (group.factoryId() != null && alreadyCovered.contains(group.factoryId())) {
                continue;
            }
            LeadTimeDefaults.Range derived = leadTimeFromQuotation(quotationLeadTimes, group.factoryId(), group.items());
            LeadTimeDefaults.Range leadTime = derived != null ? derived : LeadTimeDefaults.forCountry(group.countryCode());
            planned.add(new PlannedRow(group, derived, leadTime));
        }
        return planned;
    }

    /** Phase 1 of {@link #resolveFactoryGroups}'s cascade — READ-ONLY, never throws, creates nothing. Shared by
     * createDrafts (which then refuses the first unplaced line and creates the pending factories) and the split
     * preview (which reports both instead). The maps are mutable: createDrafts' phase 3 extends them. */
    private record CandidateSplit(Map<Long, List<LineFactoryCandidate>> byFactory,
                                  Map<String, List<LineFactoryCandidate>> pendingByNormalizedName,
                                  Map<String, String> pendingDisplayNames,
                                  List<LineFactoryCandidate> unplaced,
                                  Map<String, Long> normalizedNameToId,
                                  Map<Long, String> resolvedNameById,
                                  Map<Long, String> countryCodeById) {}

    private CandidateSplit splitCandidates(List<LineFactoryCandidate> candidates, List<FactoryConfigDto> master) {
        Map<Long, FactoryConfigDto> factoriesById = master.stream()
            .collect(Collectors.toMap(FactoryConfigDto::id, f -> f, (a, b) -> a, LinkedHashMap::new));
        Map<String, Long> normalizedNameToId = factoriesById.values().stream()
            .collect(Collectors.toMap(f -> normalizeFactoryName(f.factoryName()), FactoryConfigDto::id,
                (a, b) -> a));
        Map<Long, String> resolvedNameById = new LinkedHashMap<>(factoriesById.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().factoryName())));
        Map<Long, String> countryCodeById = new LinkedHashMap<>(factoriesById.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().country())));

        // LinkedHashMap: deterministic, deal-line-order grouping — matches how brandLinesForTicket's own LinkedHashMap
        // keeps printed order.
        Map<Long, List<LineFactoryCandidate>> byFactory = new LinkedHashMap<>();
        // Everything resolvable WITHOUT creating anything, plus the pending (needs-create) set, keyed by normalized
        // name — grouping by normalized name here IS grouping by the factory-to-be, since normalization is exactly
        // the matching function.
        Map<String, List<LineFactoryCandidate>> pendingByNormalizedName = new LinkedHashMap<>();
        Map<String, String> pendingDisplayNames = new LinkedHashMap<>();
        List<LineFactoryCandidate> unplaced = new ArrayList<>();

        for (LineFactoryCandidate c : candidates) {
            if (c.directFactoryId() == null && c.candidateFactoryName() == null) {
                unplaced.add(c);
                continue;
            }
            if (c.directFactoryId() != null) {
                long factoryId = c.directFactoryId();
                // A direct id always comes from a real FK, so it is guaranteed to exist -- but it may not be in the findAll
                // snapshot above if concurrently created between that read and here; fall back to a fresh lookup rather than
                // risk a null name/country on the row.
                resolvedNameById.computeIfAbsent(factoryId, fid -> freshFactoryLookup(fid)
                    .map(FactoryConfigDto::factoryName).orElse(null));
                countryCodeById.computeIfAbsent(factoryId, fid -> freshFactoryLookup(fid)
                    .map(FactoryConfigDto::country).orElse(null));
                byFactory.computeIfAbsent(factoryId, k -> new java.util.ArrayList<>()).add(c);
                continue;
            }
            String normalized = normalizeFactoryName(c.candidateFactoryName());
            Long existingId = normalizedNameToId.get(normalized);
            if (existingId != null) {
                byFactory.computeIfAbsent(existingId, k -> new java.util.ArrayList<>()).add(c);
                continue;
            }
            pendingByNormalizedName.computeIfAbsent(normalized, k -> new java.util.ArrayList<>()).add(c);
            pendingDisplayNames.putIfAbsent(normalized, c.candidateFactoryName().strip());
        }
        return new CandidateSplit(byFactory, pendingByNormalizedName, pendingDisplayNames, unplaced,
            normalizedNameToId, resolvedNameById, countryCodeById);
    }

    /** One group's DRAFT shape from its lines — shared by createDrafts and the split preview. */
    private static FactoryGroup groupOf(Long factoryId, String factoryName, String countryCode,
                                        List<LineFactoryCandidate> lines) {
        String brandLabel = lines.stream().map(LineFactoryCandidate::brand)
            .filter(b -> b != null && !b.isBlank())
            .distinct()
            .collect(Collectors.joining(", "));
        // The note sub-row is auto-derived from colour/surface at
        // creation (mergeNote against a null existing note always resolves to the derived one
        // here — see ImportRequestLinePrefill#mergeNote's own Javadoc for why the merge is
        // still written generally rather than inlined as "just use the derived value").
        List<ImportRequestItemInput> items = lines.stream()
            .map(l -> new ImportRequestItemInput(l.ticketItemId(), l.code(), l.size(),
                l.remainingQty(), l.unit(),
                ImportRequestLinePrefill.mergeNote(null,
                    ImportRequestLinePrefill.buildColorSurfaceNote(l.color(), l.texture())),
                l.color(), l.texture(), l.brand(), l.model()))
            .toList();
        return new FactoryGroup(factoryId, factoryName, countryCode, brandLabel.isBlank() ? null : brandLabel, items);
    }

    /** 409 prefix the panel parses to open its lead-time popup (mirrors the missing-country prefix). */
    public static final String MISSING_LEAD_TIME_PREFIX = "กรุณาระบุระยะเวลานำเข้าให้กับโรงงาน: ";

    /** Supplied per-factory lead times keyed by normalized name; same bounds as setLeadTime, else 400. */
    private static Map<String, ImportRequestRequests.NewFactoryLeadTimeInput> validatedLeadTimes(
            CreateImportRequestsRequest request) {
        Map<String, ImportRequestRequests.NewFactoryLeadTimeInput> out = new LinkedHashMap<>();
        if (request == null || request.leadTimes() == null) {
            return out;
        }
        for (var in : request.leadTimes()) {
            if (in == null || in.factoryName() == null || in.factoryName().isBlank()
                || in.minDays() == null || in.maxDays() == null
                || in.minDays() < 1 || in.maxDays() > 365 || in.minDays() > in.maxDays()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ระยะเวลานำเข้าต้องเป็น 1–365 วัน และขั้นต่ำต้องไม่เกินขั้นสูง"
                        + (in == null || in.factoryName() == null ? "" : " (โรงงาน " + in.factoryName() + ")"));
            }
            out.put(normalizeFactoryName(in.factoryName()), in);
        }
        return out;
    }

    /** A caller-supplied country for a factory about to be auto-created, already validated. */
    private record ValidatedCountry(String code, String other) {}

    /**
     * Resolves every deal line with remaining (non-stock-covered) quantity to a factory, per {@link
     * ImportRequestQueryRepository#factoryResolutionCandidates}'s cascade, and groups them. Refuses
     * with 409, naming the line, when NEITHER a direct factory id NOR a name is found anywhere for it
     * (never derive or create a factory from {@code brand} — see
     * {@link ImportRequestQueryRepository#factoryResolutionCandidates}'s own Javadoc for why).
     *
     * <p><strong>Two-phase for the auto-create path</strong> (sales is forced to select a country,
     * or อื่นๆ with a typed-in name when it is unknown). Every line whose typed name matches NOTHING existing is collected FIRST (read-only —
     * nothing written yet), matched against {@code request.newFactoryCountries()} by the SAME {@link
     * #normalizeFactoryName}, and validated ({@link #requireValidNewFactoryCountry}: country
     * required, {@code 'ZZ'} paired with {@code countryOther}). If even ONE pending name has no
     * matching entry or an invalid one, the WHOLE call refuses before any row — factory or draft — is
     * written. Only once every pending name has a validated country does creation happen. This is
     * what makes "auto-create requires a country, or nothing is inserted" hold even when a deal needs
     * several new factories and the caller supplied countries for only some of them.
     */
    private List<FactoryGroup> resolveFactoryGroups(long ticketId, long actorId,
            CreateImportRequestsRequest request) {
        List<LineFactoryCandidate> candidates = repository.factoryResolutionCandidates(ticketId);
        if (candidates.isEmpty()) {
            return List.of();
        }
        // Phase 1 (read-only, shared with the split preview): resolve without creating anything.
        CandidateSplit split = splitCandidates(candidates, factories.findAll());
        if (!split.unplaced().isEmpty()) {
            // The FIRST unplaced line in deal-line order — the one the inline loop used to stop at (phase 1 only reads,
            // so finishing it before refusing changes nothing observable).
            LineFactoryCandidate c = split.unplaced().get(0);
            throw new ApiException(HttpStatus.CONFLICT,
                "รายการนี้ยังไม่ได้ระบุโรงงาน กรุณาระบุโรงงานก่อน: " + c.code()
                    + (c.size() != null && !c.size().isBlank() ? " (" + c.size() + ")" : ""));
        }
        Map<Long, List<LineFactoryCandidate>> byFactory = split.byFactory();
        Map<String, List<LineFactoryCandidate>> pendingByNormalizedName = split.pendingByNormalizedName();
        Map<String, String> pendingDisplayNames = split.pendingDisplayNames();
        Map<String, Long> normalizedNameToId = split.normalizedNameToId();
        Map<Long, String> resolvedNameById = split.resolvedNameById();
        Map<Long, String> countryCodeById = split.countryCodeById();

        if (!pendingByNormalizedName.isEmpty()) {
            Map<String, NewFactoryCountryInput> suppliedByNormalizedName = new LinkedHashMap<>();
            if (request != null && request.newFactoryCountries() != null) {
                for (NewFactoryCountryInput in : request.newFactoryCountries()) {
                    if (in.factoryName() != null) {
                        suppliedByNormalizedName.put(normalizeFactoryName(in.factoryName()), in);
                    }
                }
            }
            // Phase 2: validate EVERY pending name before creating ANY of them.
            List<String> missing = pendingByNormalizedName.keySet().stream()
                .filter(n -> !suppliedByNormalizedName.containsKey(n))
                .map(pendingDisplayNames::get)
                .toList();
            if (!missing.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "กรุณาระบุประเทศให้กับโรงงานใหม่: " + String.join(", ", missing));
            }
            Map<String, ValidatedCountry> validatedByNormalizedName = new LinkedHashMap<>();
            for (String normalized : pendingByNormalizedName.keySet()) {
                validatedByNormalizedName.put(normalized, requireValidNewFactoryCountry(
                    pendingDisplayNames.get(normalized), suppliedByNormalizedName.get(normalized)));
            }

            // Phase 3: create — every pending name has a validated country by this point.
            for (Map.Entry<String, List<LineFactoryCandidate>> entry : pendingByNormalizedName.entrySet()) {
                String normalized = entry.getKey();
                long factoryId = resolveOrCreateFactory(normalized, pendingDisplayNames.get(normalized),
                    validatedByNormalizedName.get(normalized), actorId,
                    normalizedNameToId, resolvedNameById, countryCodeById);
                byFactory.computeIfAbsent(factoryId, k -> new java.util.ArrayList<>()).addAll(entry.getValue());
            }
        }

        List<FactoryGroup> groups = new java.util.ArrayList<>();
        for (Map.Entry<Long, List<LineFactoryCandidate>> entry : byFactory.entrySet()) {
            long factoryId = entry.getKey();
            groups.add(groupOf(factoryId, resolvedNameById.get(factoryId), countryCodeById.get(factoryId),
                entry.getValue()));
        }
        return groups;
    }

    /**
     * Slice 6c: the dry run of createDrafts' split for the "ยืนยันออกใบขอซื้อ" confirm. The same steps createDrafts
     * runs — the rep's picks ({@link #itemFactoryChoice}), the cascade ({@link #splitCandidates}), the grouping
     * ({@link #groupOf}), the skip of covered factories and the lead-time choice ({@link #planNewRows}) — with the
     * three writes left out: a pick is applied to the candidate in memory instead of to {@code ticket_item.factory},
     * a typed name that matches no factory stays a group with no id instead of being created, and nothing is
     * inserted. Not part of it: createDrafts' gates (stage, payment, source), the supplied lead times and countries
     * (the popups that follow the confirm), and the issue itself.
     */
    private ImportRequestSplitPreview splitPreview(long ticketId, List<ImportRequestRequests.ItemFactoryInput> picks) {
        List<LineFactoryCandidate> candidates = repository.factoryResolutionCandidates(ticketId);
        Map<Long, LineFactoryCandidate> byItemId = candidates.stream()
            .collect(Collectors.toMap(LineFactoryCandidate::ticketItemId, c -> c, (a, b) -> a));
        // ONE read of the factory master per request (fix round L2): it is both the picker's offered set and the
        // cascade's match table below — never one read per pair.
        List<FactoryConfigDto> master = factories.findAll();
        Map<Long, FactoryConfigDto> offered = master.stream()
            .collect(Collectors.toMap(FactoryConfigDto::id, f -> f, (a, b) -> a));
        // Fix round L1 (owner ruling): a pick counts only where the confirm step's picker could have produced it.
        // DealFulfilmentPanel shows the picker only on a deal with a live direct quotation (summary.liveDirectQuotation,
        // the same predicate as TicketRepository#hasLiveDirectQuotation), only for a line with no factory anywhere
        // (itemFactoryChoice's own condition), and offers the factory master (FactoryConfigController#names). Any
        // other pick is ignored exactly like an inapplicable one.
        boolean pickerShown = !picks.isEmpty() && tickets.hasLiveDirectQuotation(ticketId);
        Map<Long, String> pickedNames = new LinkedHashMap<>();
        for (ImportRequestRequests.ItemFactoryInput pick : pickerShown ? picks : List.<ImportRequestRequests.ItemFactoryInput>of()) {
            if (pick == null || pick.ticketItemId() == null || pick.factoryId() == null) {
                continue;
            }
            // First pick for a line wins, like setItemFactoryIfBlank's "only while blank" guard.
            itemFactoryChoice(ticketId, pick, byItemId, false, id -> Optional.ofNullable(offered.get(id)))
                .ifPresent(name -> pickedNames.putIfAbsent(pick.ticketItemId(), name));
        }
        List<LineFactoryCandidate> effective = candidates.stream()
            .map(c -> pickedNames.containsKey(c.ticketItemId())
                ? new LineFactoryCandidate(c.ticketItemId(), c.brand(), c.code(), c.model(), c.size(),
                    c.remainingQty(), c.unit(), null, pickedNames.get(c.ticketItemId()), c.color(), c.texture())
                : c)
            .toList();

        CandidateSplit split = splitCandidates(effective, master);
        List<FactoryGroup> groups = new ArrayList<>();
        split.byFactory().forEach((factoryId, lines) -> groups.add(groupOf(factoryId,
            split.resolvedNameById().get(factoryId), split.countryCodeById().get(factoryId), lines)));
        // createDrafts appends each auto-created factory after the existing ones, in first-seen order — same here.
        split.pendingByNormalizedName().forEach((normalized, lines) -> groups.add(groupOf(null,
            split.pendingDisplayNames().get(normalized), null, lines)));
        Set<Long> covered = stored.findByTicket(ticketId).stream()
            .filter(r -> ImportRequestStatus.isLive(r.status()))
            .map(ImportRequestDto::factoryId)
            .collect(Collectors.toSet());

        List<SplitPreviewFactory> factoriesOut = planNewRows(groups, covered,
                repository.currentQuotationLeadTimes(ticketId)).stream()
            .map(p -> new SplitPreviewFactory(p.group().factoryName(), p.group().factoryId() == null,
                p.group().items().size(),
                p.leadTime() == null ? null : p.leadTime().minDays(),
                p.leadTime() == null ? null : p.leadTime().maxDays(),
                p.derived() != null ? SplitPreviewFactory.LEAD_TIME_FROM_QUOTATION
                    : p.leadTime() != null ? SplitPreviewFactory.LEAD_TIME_COUNTRY_DEFAULT : null))
            .toList();
        List<SplitPreviewLine> unplaced = split.unplaced().stream()
            .map(c -> new SplitPreviewLine(c.ticketItemId(), c.code(), c.size()))
            .toList();
        return new ImportRequestSplitPreview(factoriesOut, unplaced);
    }

    /**
     * The LONGEST lead time among this factory's current-quotation items -- {@code min = max
     * of the mins}, {@code max = max of the maxes}, each independently -- or {@code null} when none of
     * them carries one (the caller then falls back to the country default / the predecessor's value).
     * A PR-route row counts when its ticket item belongs to {@code items}; a direct-route row counts
     * when its catalog factory is {@code factoryId}.
     */
    private static LeadTimeDefaults.Range leadTimeFromQuotation(
            List<ImportRequestQueryRepository.QuotationLeadTimeRow> rows, Long factoryId,
            List<ImportRequestItemInput> items) {
        Set<Long> ticketItemIds = items.stream().map(ImportRequestItemInput::ticketItemId)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Integer min = null;
        Integer max = null;
        for (ImportRequestQueryRepository.QuotationLeadTimeRow row : rows) {
            // equals, not ==, now that both are boxed; a null factoryId (a preview-only new factory) matches no
            // direct-route row, exactly as the freshly created factory could have none.
            boolean ours = row.ticketItemId() != null ? ticketItemIds.contains(row.ticketItemId())
                : row.factoryId() != null && row.factoryId().equals(factoryId);
            if (!ours) {
                continue;
            }
            min = min == null ? row.minDays() : Math.max(min, row.minDays());
            max = max == null ? row.maxDays() : Math.max(max, row.maxDays());
        }
        return min == null ? null : new LeadTimeDefaults.Range(min, max);
    }

    /**
     * Sales' factory pick for a deal line that has none (free-text line on a direct quotation).
     * Validated first (unknown factory / a line outside this deal -> 400), applied
     * only to a line that resolves to no factory at all — a catalogue, PCR or typed-name line keeps
     * its own. The name is written to {@code ticket_item.factory} so the normal cascade resolves it to
     * the existing factory; a line still without one afterwards hits today's 409.
     */
    private void applyItemFactoryChoices(long ticketId, CreateImportRequestsRequest request) {
        if (request == null || request.itemFactories() == null || request.itemFactories().isEmpty()) {
            return;
        }
        Map<Long, LineFactoryCandidate> candidates = repository.factoryResolutionCandidates(ticketId).stream()
            .collect(Collectors.toMap(LineFactoryCandidate::ticketItemId, c -> c, (a, b) -> a));
        for (ImportRequestRequests.ItemFactoryInput choice : request.itemFactories()) {
            // Decided by the same function the split preview applies in memory (slice 6c); only this caller writes.
            itemFactoryChoice(ticketId, choice, candidates, true, this::freshFactoryLookup)
                .ifPresent(name -> repository.setItemFactoryIfBlank(ticketId, choice.ticketItemId(), name));
        }
    }

    /**
     * One pick's outcome: the factory name to give the line, or empty when the pick changes nothing (the line
     * already resolves to a factory, or is not an import candidate). An unknown factory, or a line of another deal,
     * is a 400 when {@code refuseInvalid} (createDrafts) and simply ignored otherwise (the split preview, where a bad
     * pick must not fail the coverage read the panel depends on). Reads only.
     */
    private Optional<String> itemFactoryChoice(long ticketId, ImportRequestRequests.ItemFactoryInput choice,
            Map<Long, LineFactoryCandidate> candidates, boolean refuseInvalid,
            java.util.function.LongFunction<Optional<FactoryConfigDto>> factoryLookup) {
        // createDrafts passes a fresh read per pick (unchanged); the preview passes its one per-request master read.
        Optional<FactoryConfigDto> factory = factoryLookup.apply(choice.factoryId());
        if (factory.isEmpty()) {
            if (refuseInvalid) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบโรงงานที่เลือก");
            }
            return Optional.empty();
        }
        LineFactoryCandidate line = candidates.get(choice.ticketItemId());
        if (line == null) {
            // Not an import candidate (e.g. fully reserved from stock): nothing to order, so the pick is
            // ignored. A line of ANOTHER deal is still a bad request.
            if (refuseInvalid && !repository.ticketHasItem(ticketId, choice.ticketItemId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "รายการที่เลือกไม่ได้อยู่ในงานนี้");
            }
            return Optional.empty();
        }
        return line.directFactoryId() == null && line.candidateFactoryName() == null
            ? Optional.of(factory.get().factoryName()) : Optional.empty();
    }

    private Optional<FactoryConfigDto> freshFactoryLookup(long factoryId) {
        return factories.findAll().stream().filter(f -> f.id() == factoryId).findFirst();
    }

    /**
     * Validates one pending factory name's caller-supplied country: required,
     * must exist in {@code price_catalog.country}, and {@code 'ZZ'} (อื่นๆ) paired with a non-blank,
     * ≤100-char {@code countryOther} — the same rule {@code price_catalog.factories}' own paired
     * CHECK constraints backstop (V184) and {@code PriceImportService#requireValidCountryOther}
     * enforces for the factory-config screen. Deliberately duplicated rather than shared across
     * packages — {@code catalog.importer} and {@code importrequest} have no dependency on each
     * other today, and both call sites are short enough that a shared abstraction would cost more
     * than it saves.
     */
    private ValidatedCountry requireValidNewFactoryCountry(String displayName, NewFactoryCountryInput input) {
        String code = input.countryCode() == null ? "" : input.countryCode().strip().toUpperCase(Locale.ROOT);
        if (code.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุประเทศของโรงงาน: " + displayName);
        }
        if (!factories.countryExists(code)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบรหัสประเทศนี้: " + code);
        }
        String other = input.countryOther() == null || input.countryOther().isBlank()
            ? null : input.countryOther().strip();
        if ("ZZ".equals(code) && other == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "กรุณาระบุชื่อประเทศเมื่อเลือก อื่นๆ: " + displayName);
        }
        if (!"ZZ".equals(code) && other != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ระบุชื่อประเทศเพิ่มเติมได้เฉพาะเมื่อเลือก อื่นๆ เท่านั้น: " + displayName);
        }
        if (other != null && other.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ชื่อประเทศยาวเกินไป: " + displayName);
        }
        return new ValidatedCountry(code, other);
    }

    /** Matches normalizedName against the factory master ONE MORE TIME (a concurrent creator may have won the race
     * since phase 1's read), or creates it with its now-validated country. */
    private long resolveOrCreateFactory(String normalizedName, String displayName, ValidatedCountry country,
            long actorId, Map<String, Long> normalizedNameToId, Map<Long, String> resolvedNameById,
            Map<Long, String> countryCodeById) {
        factories.lockFactoryName(normalizedName);
        // Re-check AFTER the lock: another transaction may have created this exact factory while this one was waiting
        // for the lock, and the caller's in-memory map (built before the lock) cannot see it.
        Optional<FactoryConfigDto> recheck = factories.findAll().stream()
            .filter(f -> normalizedName.equals(normalizeFactoryName(f.factoryName())))
            .findFirst();
        if (recheck.isPresent()) {
            long id = recheck.get().id();
            normalizedNameToId.put(normalizedName, id);
            resolvedNameById.put(id, recheck.get().factoryName());
            countryCodeById.put(id, recheck.get().country());
            return id;
        }
        long id = factories.createFromImportRequest(displayName, country.code(), country.other(), actorId);
        normalizedNameToId.put(normalizedName, id);
        resolvedNameById.put(id, displayName);
        countryCodeById.put(id, country.code());
        return id;
    }

    /**
     * Normalizes a factory name for matching: NFC Unicode normalization (Thai
     * text may be entered with combining marks in either composed or decomposed form), trim, collapse
     * internal whitespace, lower-case. Deliberately Java, not SQL — Postgres has no built-in Unicode
     * NFC normalization without an extension this codebase does not otherwise need, and the factory
     * master is small enough (a handful of rows) that comparing in memory is not a real cost.
     */
    static String normalizeFactoryName(String raw) {
        if (raw == null) {
            return null;
        }
        String n = Normalizer.normalize(raw, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        return n.toLowerCase(Locale.ROOT);
    }

    // ── rollup support ────────────────────────────────────────────────────────────────────────────

    /** Does every factory this deal actually needs an import for now have an ISSUED row at
     * ImportRequestStep#RECEIVED? */
    private boolean allFactoriesReceived(long ticketId) {
        List<IssuedFactoryRow> issued = stored.findIssuedByTicket(ticketId);
        if (issued.isEmpty()) {
            return false;
        }
        if (!issued.stream().allMatch(r -> ImportRequestStep.RECEIVED.equals(r.importStep()))) {
            return false;
        }
        Set<Long> issuedFactoryIds = issued.stream().map(IssuedFactoryRow::factoryId)
            .collect(Collectors.toSet());
        return issuedFactoryIds.containsAll(requiredFactoryIds(ticketId));
    }

    /** Read-only mirror of #resolveFactoryGroups's resolution cascade, WITHOUT creating a factory on the fly — the
     * rollup must never mutate the factory master, only read it. */
    private Set<Long> requiredFactoryIds(long ticketId) {
        List<LineFactoryCandidate> candidates = repository.factoryResolutionCandidates(ticketId);
        if (candidates.isEmpty()) {
            return Set.of();
        }
        Map<String, Long> normalizedNameToId = factories.findAll().stream()
            .collect(Collectors.toMap(f -> normalizeFactoryName(f.factoryName()), FactoryConfigDto::id,
                (a, b) -> a));
        Set<Long> required = new LinkedHashSet<>();
        for (LineFactoryCandidate c : candidates) {
            if (c.directFactoryId() != null) {
                required.add(c.directFactoryId());
            } else if (c.candidateFactoryName() != null) {
                Long match = normalizedNameToId.get(normalizeFactoryName(c.candidateFactoryName()));
                if (match != null) {
                    required.add(match);
                }
            }
        }
        return required;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /** Buddhist year — the document number's own convention ({@code IR69068} = 2569). */
    private static int thaiYear(LocalDate date) {
        return date.getYear() + 543;
    }

    /** A stored row that belongs to a deal: every deal-scoped action (edit, issue, revise, lead time, order email, step) answers
     * 409 for a no-project order instead of dereferencing a missing ticket. */
    private ImportRequestDto requireDealStored(long id) {
        ImportRequestDto row = requireStored(id);
        if (row.ticketId() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบขอซื้อนี้เป็นคำสั่งซื้อที่ไม่มีโครงการ — ดำเนินการที่หน้า การขนส่ง (รอบขนส่ง) หรือ ของที่ฉันสั่ง");
        }
        return row;
    }

    public ImportRequestDto requireStored(long id) {
        return stored.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบขอซื้อนี้"));
    }

    private void requireDealActive(TicketSnapshot snapshot) {
        if (!"ACTIVE".equals(snapshot.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "งานนี้ไม่ได้อยู่ในสถานะที่ดำเนินการได้");
        }
    }

    private List<ImportRequestDto> withPageCounts(List<ImportRequestDto> rows) {
        return rows.stream().map(this::withPageCount).toList();
    }

    /** First issue date plus a lead-time bound; null when either is unset. */
    static LocalDate expectedArrival(LocalDate firstIssued, Integer days) {
        return firstIssued != null && days != null ? firstIssued.plusDays(days) : null;
    }

    /** Fills the DTO's {@code pageCount}, which the repository leaves at 0 — layout is not its job. */
    private ImportRequestDto withPageCount(ImportRequestDto r) {
        int pages = ImportRequestRenderer.pagesRequired(ImportRequestFormAssembler.fromStored(r));
        // Derived, not stored: null while DRAFT (no firstIssuedDate yet) or
        // while either lead-time bound is unset — both are the "not estimated yet" state, not zero
        // days. This is the one place both derived fields are computed; see ImportRequestDto's own
        // Javadoc.
        //
        // Anchored on firstIssuedDate
        // — the FIRST issue date of this (deal, factory)'s whole revision chain — NOT r.issueDate(),
        // which is re-stamped to "today" on every issue including a same-day correction. Anchoring
        // on issueDate would silently push a customer-facing arrival estimate out by however long a
        // typo revision took to raise; see V184's comment on first_issued_date for the full reasoning.
        LocalDate expectedFrom = expectedArrival(r.firstIssuedDate(), r.leadTimeMinDays());
        LocalDate expectedTo = expectedArrival(r.firstIssuedDate(), r.leadTimeMaxDays());
        return new ImportRequestDto(r.id(), r.ticketId(), r.ticketCode(), r.brand(),
            r.factoryId(), r.factoryName(), r.version(),
            r.status(), r.docNumber(), r.issueDate(), r.firstIssuedDate(), r.customerName(), r.projectName(),
            r.requestedByName(), r.requiredByNote(), r.depositReceivedDate(), r.vesselEtaNote(),
            r.checkedByName(), r.checkedDate(), r.approvedByName(), r.approvedDate(),
            r.importStep(), r.importStepAt(), r.importStepById(), r.importStepByName(), r.importStepNote(),
            r.leadTimeMinDays(), r.leadTimeMaxDays(), expectedFrom, expectedTo,
            r.emailTo(), r.emailSubject(), r.emailBody(), r.emailSentAt(), r.emailSentById(), r.emailSentByName(),
            r.createdById(), r.createdByName(), r.issuedById(), r.issuedByName(), r.issuedByEmail(),
            r.supersededById(), r.createdAt(), r.updatedAt(), r.issuedAt(), pages, r.items());
    }

    /** PREVIEW-path gate only ({@link #IR_ROLES}). */
    private void requireRole(UserPrincipal actor) {
        if (!IR_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    /** STORED-aggregate read gate: CEO/import/sales_manager unrestricted; sales
     * only the deal's own owner. */
    private void requireRead(long ticketId, UserPrincipal actor) {
        requireRead(ticketId, actor, true);
    }

    private void requireRead(long ticketId, UserPrincipal actor, boolean allowReadAllGrant) {
        String role = actor.role();
        if ("ceo".equals(role) || READ_ONLY_ROLES.contains(role)) {
            return;
        }
        if (ownsDeal(ticketId, actor)
            || allowReadAllGrant && th.co.glr.hr.auth.OversightReader.holder(actor, tickets::holdsReadAllSalesGrant)) {
            return; // the read-all grant: read only; requireFullWrite/canFullWrite never count it
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }

    /** STORED-aggregate full-write gate: CEO, or the deal's own owning sales rep. */
    private void requireFullWrite(long ticketId, UserPrincipal actor) {
        if (!canFullWrite(ticketId, actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private boolean canFullWrite(long ticketId, UserPrincipal actor) {
        String role = actor.role();
        return "ceo".equals(role) || ownsDeal(ticketId, actor);
    }

    /** The deal's owning/keying sales rep, or a live {@code can_manage_all_sales_documents} holder (the grant counts only for the sales / sales_manager roles). */
    private boolean ownsDeal(long ticketId, UserPrincipal actor) {
        return ("sales".equals(actor.role()) && repository.isDealOwner(ticketId, actor.id()))
            || tickets.holdsAllSalesDocumentsGrant(actor);
    }

    /**
     * The ORDER-EMAIL draft's write gate — full-write roles, PLUS
     * import. Not the gate for the PRINTED form's footer
     * (Checked/Approved + vessel ETA — see {@link #requirePrintedFooterWrite}), a different surface
     * that happens to share the word "footer" only because of the printed form's own layout. Do
     * NOT reuse this for the printed footer: import must not write it.
     */
    private void requireFooterWrite(long ticketId, UserPrincipal actor) {
        if (canFullWrite(ticketId, actor) || FOOTER_ONLY_ROLES.contains(actor.role())) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }

    /**
     * The PRINTED form's footer (Checked By/date, Approve By/date, vessel ETA) — CEO ONLY. Neither the deal's
     * owning sales rep, nor {@code import}, nor {@code sales_manager} may write these fields;
     * reads are unaffected — {@link #requireRead} governs those. See {@link
     * #requireFooterWrite} for the DIFFERENT gate that governs the order-email draft.
     */
    private void requirePrintedFooterWrite(UserPrincipal actor) {
        if (!"ceo".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private TicketSnapshot requireTicket(long ticketId) {
        return repository.loadTicketSnapshot(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนี้"));
    }

    private TicketSummaryDto requireTicketSummary(long ticketId) {
        return tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนี้"))
            .summary();
    }
}
