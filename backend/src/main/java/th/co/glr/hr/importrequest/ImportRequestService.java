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
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
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
 *       ({@code import}/{@code ceo}) exactly as before V184. Deliberately UNTOUCHED by this
 *       migration: these routes are being demoted to {@code SERVER_ONLY} (owner decision 4,
 *       import-request-per-factory-PLAN.md) and superseded by the stored aggregate's own
 *       {@code /file} route rather than rewritten, so rebasing their grain onto factories is not
 *       this branch's job and would only add risk to a surface already being retired.
 *   <li><b>The stored aggregate</b> ({@link #createDrafts} onward) — a real row per (deal,
 *       FACTORY) as of V184 (was per-brand), DRAFT → ISSUED → SUPERSEDED, with {@code IR<yy><nnn>}
 *       minted from the shared {@code sales.document_sequence} at issue, per-factory progress
 *       (S12-S17, {@link ImportRequestStep}), and a rewritten role model — see {@link
 *       #FULL_WRITE_ROLES}/{@link #FOOTER_ONLY_ROLES}/{@link #ADVANCE_STEP_ROLES} below.
 * </ul>
 *
 * <p><b>Authorisation on the STORED path changed (owner decision 1, 2026-09-18; footer tightened by
 * owner decision 09-19) — an AUTHORIZATION CHANGE per CLAUDE.md, shipped with real-DB
 * wrong-way-round tests and mutation checks.</b> It used to be the same {@code import}/{@code ceo}
 * pair as the preview path. It is now:
 * <ul>
 *   <li>the deal's OWNING sales rep and the CEO: create, edit the BODY (project/customer/rep/items/
 *       lead time while DRAFT), issue, revise, delete;
 *   <li>the CEO ALONE: the printed form's footer (Checked By/date, Approve By/date, vessel ETA —
 *       owner decision 09-19, 2026-09-19, supersedes decision 1's original wording that had let the
 *       owning rep and import touch it too);
 *   <li>{@code import}: read, advance steps, the post-issue lead-time route, edit/mark-sent on the
 *       order-email draft (owner decision 09-18 #3 §B — a DIFFERENT "footer"-shaped surface,
 *       untouched by 09-19; see {@link #requireFooterWrite}'s own Javadoc);
 *   <li>{@code sales_manager}: read only.
 * </ul>
 */
@Service
public class ImportRequestService {

    /** PREVIEW-path role gate only — see this class's own Javadoc for why it stays as-is. */
    static final Set<String> IR_ROLES = Set.of("import", "ceo");

    private static final Set<String> REQUIRED_BY_ROLES = Set.of("sales", "ceo");

    // ── STORED aggregate roles (owner decision 1, footer tightened by owner decision 09-19) ───────
    /** Create / edit the BODY / issue / revise / delete. Sales here means the DEAL OWNER only. The
     * printed form's FOOTER is no longer part of this set's write surface — see {@link
     * #requirePrintedFooterWrite} (CEO only, owner decision 09-19). */
    private static final Set<String> FULL_WRITE_ROLES = Set.of("sales", "ceo");
    /** The ORDER-EMAIL draft's edit/mark-sent gate, in addition to {@link #FULL_WRITE_ROLES} — see
     * {@link #requireFooterWrite}'s Javadoc for why this is a DIFFERENT surface from the printed
     * form's footer (which {@link #requirePrintedFooterWrite} now gates, CEO only). */
    private static final Set<String> FOOTER_ONLY_ROLES = Set.of("import");
    /** Advance a step. Global — import has no per-deal "ownership" concept, unlike sales. */
    private static final Set<String> ADVANCE_STEP_ROLES = Set.of("import", "ceo");
    /** Read-only, no ownership scoping. {@code sales} also reads, but ownership-scoped — see {@link #canRead}. */
    private static final Set<String> READ_ONLY_ROLES = Set.of("import", "sales_manager");

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
    private final FactoryConfigRepository factories;
    private final TicketRepository tickets;
    private final TicketService ticketService;

    public ImportRequestService(ImportRequestQueryRepository repository,
                                ImportRequestRenderer renderer,
                                ImportRequestRepository stored,
                                FactoryConfigRepository factories,
                                TicketRepository tickets,
                                TicketService ticketService) {
        this.repository = repository;
        this.renderer = renderer;
        this.stored = stored;
        this.factories = factories;
        this.tickets = tickets;
        this.ticketService = ticketService;
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

    // ── The stored aggregate (per-factory as of V184) ────────────────────────────────────────────

    public List<ImportRequestDto> list(long ticketId, UserPrincipal actor) {
        requireTicket(ticketId);
        requireRead(ticketId, actor);
        return withPageCounts(stored.findByTicket(ticketId));
    }

    public ImportRequestDto get(long id, UserPrincipal actor) {
        ImportRequestDto row = requireStored(id);
        requireRead(row.ticketId(), actor);
        return withPageCount(row);
    }

    /**
     * Raises one DRAFT per FACTORY on the deal that does not already have a live one, resolving each
     * line's factory per {@link ImportRequestQueryRepository#factoryResolutionCandidates}'s cascade —
     * see that method's Javadoc for the order and {@link #resolveFactoryGroups} for the match-or-create
     * step (owner decision 2, revised 09-18: auto-create now requires {@code request}'s {@code
     * newFactoryCountries}).
     *
     * <p>Idempotent in the way that matters: a factory with a DRAFT or an ISSUED form is skipped
     * rather than duplicated, so calling this twice does not produce two forms for the same factory —
     * and the two partial unique indexes in V184 refuse it in the database even if this check were
     * wrong. Refuses outright if every factory is already covered, rather than returning "created
     * nothing" and letting a caller believe it worked.
     */
    @Transactional
    public List<ImportRequestDto> createDrafts(long ticketId, CreateImportRequestsRequest request,
                                               UserPrincipal actor) {
        TicketSnapshot snapshot = requireTicket(ticketId);
        requireFullWrite(ticketId, actor);
        requireDealActive(snapshot);
        if (!repository.stageAtLeastOrderReceived(ticketId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "สร้างใบขอซื้อได้หลังจากยืนยันคำสั่งซื้อแล้ว (ORDER_RECEIVED) เท่านั้น");
        }

        List<FactoryGroup> groups = resolveFactoryGroups(ticketId, actor.id(), request);
        if (groups.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลนี้ไม่มีรายการที่ต้องออกใบขอซื้อ (ยังไม่มีรายการสินค้า หรือครอบคลุมจากสต็อกทั้งหมด)");
        }

        Set<Long> alreadyCovered = stored.findByTicket(ticketId).stream()
            .filter(r -> ImportRequestStatus.isLive(r.status()))
            .map(ImportRequestDto::factoryId)
            .collect(Collectors.toSet());

        int created = 0;
        for (FactoryGroup group : groups) {
            if (alreadyCovered.contains(group.factoryId())) {
                continue;
            }
            LeadTimeDefaults.Range leadTime = LeadTimeDefaults.forCountry(group.countryCode());
            long id = stored.insertDraft(ticketId, group.factoryId(), group.factoryName(),
                group.brandLabel(), stored.highestVersion(ticketId, group.factoryId()) + 1,
                snapshot, leadTime == null ? null : leadTime.minDays(),
                leadTime == null ? null : leadTime.maxDays(), null, // S-E: nothing to carry, fresh draft
                null, // REVIEW ROUND 3, item 2: nothing to carry either -- a fresh draft's own
                      // requiredByNote comes from the deal snapshot only, same as before.
                actor.id(), actor.name());
            stored.replaceItems(id, group.items());
            created++;
        }
        if (created == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ทุกโรงงานในดีลนี้มีใบขอซื้อที่ยังใช้งานอยู่แล้ว");
        }
        return withPageCounts(stored.findByTicket(ticketId));
    }

    /**
     * PATCH semantics — an absent field is left alone, never blanked. Body fields ({@link
     * #FULL_WRITE_ROLES} only) move only while the form is a DRAFT.
     *
     * <p><b>The printed footer (Checked By/date, Approve By/date, vessel ETA) is CEO ONLY</b> (owner
     * decision 09-19, supersedes decision 1's original footer wording — import previously shared
     * this write). Import keeps advancing steps, the post-issue lead-time route, and marking the
     * order-email sent; none of those go through this method. {@code vesselEtaNote} is still
     * auto-prefilled by the SYSTEM at {@link #issue} — that is a service-internal write, not a
     * user-supplied one, and is unaffected by this gate.
     *
     * <p>Body and footer are checked and applied INDEPENDENTLY of each other — a single PATCH may
     * carry both, and each half is authorised on its own terms rather than the request's authz
     * being decided by whichever half happens to be present. (Previously a single {@code
     * wantsBodyChange} branch decided which check ran, which meant a body edit's footer fields,
     * if any were also sent in the same call, were applied with no footer-specific check at all —
     * harmless while footer roles were a superset of body roles, but not any more now that they are
     * disjoint.) The repository's {@code WHERE} clauses enforce the DRAFT-only body rule too, not
     * just this method, so a wrong status is a 409 rather than a silent no-op.
     */
    @Transactional
    public ImportRequestDto update(long id, UpdateImportRequestRequest request, UserPrincipal actor) {
        ImportRequestDto existing = requireStored(id);
        boolean wantsLeadTimeChange = request.leadTimeMinDays() != null || request.leadTimeMaxDays() != null;
        // REVIEW ROUND 2, S-F: requiredByNote is a BODY field (this form's own "กำหนดวันที่ต้องการของ",
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
            // An entirely empty PATCH changes nothing, but must still be scoped to someone who may
            // at least SEE this row — read access, the lowest bar any branch above already clears.
            requireRead(existing.ticketId(), actor);
        }
        // REVIEW ROUND 1, S4: refuse on an inactive deal (LOST/ON_HOLD/DORMANT) like every legacy
        // ticket-mutating method already does (TicketService#requireActive) — a stored ใบขอซื้อ must
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
        if (draft) {
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
                // REVIEW ROUND 3, item 1 (S-E regression): a DRAFT has no firstIssuedDate yet (only
                // set at #issue), so there is no anchor here to re-derive a fresh CONCRETE
                // vesselEtaNote string the way #setLeadTime can on an already-ISSUED row. But a
                // carried-forward note that is still exactly an AUTO-DERIVED text (for ANY range —
                // see #looksAutoDerivedVesselEta) is now stale for the range just written above, and
                // #issue's own re-derivation only fires when the stored text matches THIS draft's
                // CURRENT range exactly -- a stale-but-still-auto text would otherwise be mistaken
                // for a CEO-typed custom note and survive issue() unchanged (the exact bug: v1
                // 75-90 -> revise -> v2 edited to 60-70 -> issue kept "(75-90 วัน)"). Blanking it
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
     * ImportRequestRequests.SetLeadTimeRequest}'s own Javadoc). Owner decision 09-18 #2's post-issue
     * half of "editable by the owning rep/CEO while DRAFT, and by import/CEO after issue".
     *
     * <p><b>REVIEW ROUND 2, S-D:</b> a new range moves {@code expectedArrivalFrom}/{@code To} (both
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
        ImportRequestDto existing = requireStored(id);
        requireDealActive(requireTicket(existing.ticketId())); // REVIEW ROUND 1, S4
        if (!ImportRequestStatus.ISSUED.equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "แก้ไขระยะเวลานำเข้าได้เฉพาะใบขอซื้อที่ออกเลขแล้ว");
        }
        requireValidLeadTimePair(request.leadTimeMinDays(), request.leadTimeMaxDays());
        stored.updateLeadTime(id, request.leadTimeMinDays(), request.leadTimeMaxDays());

        // S-D, tightened by REVIEW ROUND 3 item 1: re-derive whenever the OLD vesselEtaNote LOOKS
        // auto-derived (matches the generic template for ANY range/anchor — see
        // #looksAutoDerivedVesselEta), not only when it equals the exact text this method itself
        // would have produced for the OLD range. A CEO-typed custom note never matches the pattern,
        // so it is never touched here, matching #issue's identical rule for the same field.
        if (looksAutoDerivedVesselEta(existing.vesselEtaNote())) {
            String newDerived = defaultVesselEtaNote(existing.firstIssuedDate(),
                request.leadTimeMinDays(), request.leadTimeMaxDays());
            stored.updateFooter(id, newDerived, null, null, null, null);
        }

        // REVIEW ROUND 3, item 3 (S-D regression): the PREVIOUS code regenerated the unsent
        // order-email draft UNCONDITIONALLY whenever unsent, and re-signed it as THIS method's own
        // caller (import/CEO editing an estimate) — silently clobbering a hand-edit made via {@link
        // #updateEmailDraft}, and misattributing the email to whoever happened to touch the lead
        // time rather than whoever actually issued the form. Fixed two ways:
        //  1. Regenerate ONLY when the stored body is STILL exactly what generation would have
        //     produced for the OLD lead time — i.e. nobody has hand-edited it since issue (or since
        //     the last regeneration). A hand-edited draft is left exactly alone.
        //  2. Always sign with the ORIGINAL issuer ({@code issuedById}/{@code issuedByName}), never
        //     this call's own {@code actor} — an import staffer nudging an estimate must not
        //     silently re-sign an email as themselves.
        if (existing.emailSentAt() == null) {
            FactoryConfigDto factory = freshFactoryLookup(existing.factoryId()).orElse(null);
            // The ORIGINAL signer is a STORED SNAPSHOT (issuedByName/issuedByEmail, V184 REVIEW
            // ROUND 3 item 3) taken at #issue, never re-resolved fresh here — a fresh lookup of
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
     * REVIEW ROUND 3, item 1: whether {@code note} matches the GENERIC shape {@link
     * #defaultVesselEtaNote} always produces, for ANY anchor/range — not only the one specific
     * string a caller happens to have computed to compare against. The narrower "equals the text
     * derived for THIS draft's own CURRENT range" check missed the case where a carried-forward note
     * was auto-derived for a DIFFERENT (the predecessor's, or an earlier edit's) range: after a
     * lead-time change the note's literal text no longer matches a freshly-recomputed derivation, so
     * the narrower check wrongly treated an auto note as a CEO-typed custom one and left it stale. A
     * human typing a genuinely custom note is not going to accidentally match this rigid,
     * machine-generated template.
     */
    private static final java.util.regex.Pattern AUTO_VESSEL_ETA_PATTERN = java.util.regex.Pattern.compile(
        "^ประมาณ \\d{2}/\\d{2}/\\d{2} – \\d{2}/\\d{2}/\\d{2} \\(\\d+–\\d+ วัน\\)$");

    private static boolean looksAutoDerivedVesselEta(String note) {
        return note != null && AUTO_VESSEL_ETA_PATTERN.matcher(note).matches();
    }

    /**
     * Cross-field check neither bean validation on {@link UpdateImportRequestRequest} (both
     * optional, so {@code @Min}/{@code @Max} alone cannot enforce "both or neither") nor on {@link
     * SetLeadTimeRequest} (both {@code @NotNull}, so "neither" cannot happen, but {@code min <= max}
     * still needs a cross-field check) can express alone. Mirrors {@code
     * chk_import_request_lead_time}'s own shape as a clean 400 instead of a raw DB CHECK violation.
     */
    private static void requireValidLeadTimePair(Integer min, Integer max) {
        if ((min == null) != (max == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ต้องระบุระยะเวลานำเข้าทั้งค่าต่ำสุดและสูงสุดพร้อมกัน");
        }
        if (min != null && min > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ระยะเวลานำเข้าต่ำสุดต้องไม่มากกว่าค่าสูงสุด");
        }
    }

    /**
     * DRAFT → ISSUED: mints (or accepts) the number, stamps the date, sets/carries-forward the
     * per-factory progress step, and supersedes the version this one replaces.
     *
     * <p>Order is load-bearing. The number is taken INSIDE this transaction so a refused issue rolls
     * it back rather than burning a sequence value — the property
     * {@code DepositNoticeRepository.nextDocNumber} documents. The compare-and-set is what actually
     * decides: 0 rows means someone else issued or superseded this form first, and that is a 409, not
     * a success.
     *
     * <p><b>Superseding happens BEFORE the issue, and that is forced</b> — same reasoning V154's own
     * Javadoc gives at length: {@code ux_import_request_ticket_factory_issued} permits only ONE
     * ISSUED row per (deal, factory), checked per statement, so the two versions cannot both be
     * ISSUED even for the instant between the two writes.
     *
     * <p><b>Progress step (V184):</b> {@link ImportRequestRepository#lockIssuedPredecessor} both
     * LOCKS the predecessor row ({@code FOR UPDATE}) and returns its step fields in one call — first
     * issue for this (deal, factory) gets {@link ImportRequestStep#CONTACTED} with today's date and
     * this actor; a revision's issue COPIES the predecessor's current step fields forward instead
     * (import-request-per-factory-PLAN.md §3), so revising a form does not reset progress that has
     * already been made.
     *
     * <p><b>Deal-level side effects</b> ({@link TicketService#requireImportRequestIssuable} — the
     * deposit/status gate — and, after a successful issue, {@link
     * TicketService#recordFirstImportRequestIssued}) are shared EXACTLY with the legacy one-shot
     * {@code TicketService#issueImportRequest}, so the deal cannot tell which path raised its first
     * import request.
     */
    @Transactional
    public ImportRequestDto issue(long id, IssueImportRequestRequest request, UserPrincipal actor) {
        ImportRequestDto draft = requireStored(id);
        requireFullWrite(draft.ticketId(), actor);
        TicketSnapshot dealSnapshot = requireTicket(draft.ticketId());
        requireDealActive(dealSnapshot); // REVIEW ROUND 1, S4
        if (!ImportRequestStatus.DRAFT.equals(draft.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อฉบับนี้ออกเลขไปแล้ว");
        }
        if (draft.items().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อยังไม่มีรายการสินค้า");
        }
        // Owner decision 09-18 #2: "อื่นๆ/unknown must be typed by sales" — a form with no estimate
        // at all may not be issued. Both-or-neither is already the DB invariant
        // (chk_import_request_lead_time), so checking one implies the other.
        if (draft.leadTimeMinDays() == null || draft.leadTimeMaxDays() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                "กรุณาระบุระยะเวลานำเข้า (วัน) ของโรงงาน " + draft.factoryName());
        }

        TicketSummaryDto ticketSummary = requireTicketSummary(draft.ticketId());
        // REVIEW ROUND 2, S-C: the STORED per-factory path uses the WIDENED gate (any payment status
        // at or after deposit-ready, including AWAITING_FINAL_PAYMENT/FULLY_PAID) — a deal's second,
        // third, ... factory's first issue, or ANY factory's revision issue, must not be blocked just
        // because the customer finished paying in the meantime. The legacy one-shot
        // TicketService#issueImportRequest keeps the ORIGINAL narrower gate (its own call, unchanged)
        // for the deal's very-first-IR semantics — see TicketService#requireImportRequestIssuable's
        // own Javadoc for why the two share one predicate with a widening flag rather than diverging.
        ticketService.requireImportRequestIssuable(ticketSummary, true);

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
            // REVIEW ROUND 1, S7 (owner default, flagged): carried forward UNCHANGED — a revision's
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

        // Owner decision 09-18 #3 §A, re-derivation rule tightened by REVIEW ROUND 2 S-E: vesselEtaNote
        // defaults to the derived arrival estimate when the draft's OWN value is either unset, or is
        // STILL exactly what auto-derivation would produce for this draft's own (possibly since-edited
        // by update(), or carried forward unchanged by revise()) anchor+range. Anchored on
        // firstIssuedDate, matching expectedArrivalFrom/To (S7) so the printed note and the derived
        // fields never disagree. A CEO-typed CUSTOM note — anything that does NOT match what
        // auto-derivation would currently produce — is left exactly alone; {@link #setLeadTime} (S-D)
        // applies the identical rule for the post-issue lead-time edit.
        String derivedVesselEtaNote = defaultVesselEtaNote(firstIssuedDate, draft.leadTimeMinDays(),
            draft.leadTimeMaxDays());
        // REVIEW ROUND 3, item 1: {@code looksAutoDerivedVesselEta} replaces the narrower "equals
        // THIS draft's own current derivation" check — see that method's own Javadoc for why an
        // exact-string comparison missed a stale-but-still-auto note surviving a lead-time edit.
        String vesselEtaNote = (draft.vesselEtaNote() == null || draft.vesselEtaNote().isBlank()
            || looksAutoDerivedVesselEta(draft.vesselEtaNote()))
            ? derivedVesselEtaNote : draft.vesselEtaNote();

        // REVIEW ROUND 2, S-F: this FORM's own requiredByNote is re-snapshotted from the DEAL's
        // current value at issue time whenever it is still blank — the owning rep/CEO may instead
        // have already typed one directly onto this draft via update() (UpdateImportRequestRequest
        // #requiredByNote), which is never overwritten here.
        String requiredByNote = draft.requiredByNote() != null && !draft.requiredByNote().isBlank()
            ? draft.requiredByNote() : dealSnapshot.requiredByNote();

        FactoryConfigDto factory = freshFactoryLookup(draft.factoryId()).orElse(null);
        // OWNER DECISION 09-19 #2: doc number + "GL&R" ONLY -- no project name. A project name often
        // identifies the customer (e.g. "Ban Khun Somchai"), and this line goes to a supplier who
        // must never learn who GL&R's customer is (the same reasoning behind blanking the FACTORY
        // copy's customer/deposit fields -- see ImportRequestFormAssembler#fromStored's factoryCopy
        // param). This USED to read "Purchase order <doc> - GL&R (<project>)"; the parenthetical is
        // gone, not merely conditional, because a project name is exactly the thing that must never
        // reach this subject line.
        String emailSubject = "Purchase order " + docNumber + " - GL&R";
        // REVIEW ROUND 3, item 4 (S-F email half): pass the ALREADY-RESOLVED requiredByNote (the
        // draft's own typed value, or the fresh deal snapshot when it was still blank) rather than
        // letting buildEmailDraft re-read draft.requiredByNote() itself -- that read a value that
        // could be BLANK on the draft even though the deal-snapshot fallback above is what actually
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
        if (vesselEtaNote != null) {
            stored.updateFooter(id, vesselEtaNote, null, null, null, null);
        }
        ticketService.recordFirstImportRequestIssued(draft.ticketId(), id, actor);
        return withPageCount(requireStored(id));
    }

    /**
     * "ประมาณ dd/MM/yy – dd/MM/yy (min–max วัน)" — the default {@code vesselEtaNote} (owner decision
     * 09-18 #3 §A), from the SAME anchor+range {@link #withPageCount} derives {@code
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
     * The per-factory order-email draft (owner decision 09-18 #3 §B; item layout redone in the
     * 09-18 #3 second pass) — mirrors the numbered-block layout that merged for the factory
     * price-request email ({@code FactoryQuoteService#emailBody}/{@code #appendItemBlock} on
     * {@code origin/develop}, PR #1003): a greeting, one numbered block per line, then required-by/
     * expected-arrival/attachment/signature. {@code FactoryQuoteService} itself is untouched — its
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
        // REVIEW ROUND 3, item 4 (S-F email half): the CALLER resolves requiredByNote (draft's own
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
        // Cheap nit (REVIEW ROUND 2, tightened by REVIEW ROUND 3): must equal the REAL download
        // filename, or a human copying this line into their mail client names an attachment that
        // does not match what they actually downloaded. Now a SHARED helper ({@link
        // #downloadFileName}) rather than a second hand-copied literal — ImportRequestController
        // #storedFile calls the very same method, so the two can no longer drift independently. The
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
     * and {@link #buildEmailDraft}'s "Attached:" line (cheap nit, REVIEW ROUND 3), so a human
     * copying that line into a mail client always names the exact file the download route hands
     * them; the "Attached:" line always names the FACTORY copy specifically ({@code
     * factoryCopy=true}), the one actually meant to go out with the order email (owner decision
     * 09-19 #2).
     */
    static String downloadFileName(String docNumber, long id, String factoryName, String brand,
            boolean factoryCopy) {
        return "IR-" + (docNumber == null ? "draft-" + id : docNumber)
            + "-" + (factoryName != null ? factoryName : brand)
            + (factoryCopy ? "" : "-internal") + ".pdf";
    }

    /**
     * Renders one numbered item block for {@link #buildEmailDraft} — a Brand/Model (Code) header,
     * then a Colour/Surface/Size detail line, then Quantity, then the free-text note, each SKIPPED
     * outright when blank (never a "-" placeholder, never the literal string "null"). Copies {@code
     * FactoryQuoteService#appendItemBlock}'s shape rather than sharing code with it — see {@link
     * #buildEmailDraft}'s Javadoc for why.
     */
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
            details.add("Size: " + item.size());
        }
        if (!details.isEmpty()) {
            body.append("   ").append(String.join(" | ", details)).append("\n");
        }

        if (item.qty() != null) {
            // No trailing zeros -- "120" rather than "120.00", matching FactoryQuoteService's own
            // formatQty for the same reason: a factory-facing quantity is read as a whole count.
            body.append("   Quantity: ").append(item.qty().stripTrailingZeros().toPlainString());
            if (item.unit() != null && !item.unit().isBlank()) {
                body.append(" ").append(item.unit());
            }
            body.append("\n");
        }

        // REVIEW ROUND 2, S-A: skip the note line outright when it is EXACTLY the auto-derived
        // "สี X · ผิว Y" colour/surface sentence (ImportRequestLinePrefill#buildColorSurfaceNote) --
        // that is already shown one line up as "Colour: X | Surface: Y", so printing it again here
        // would say the same thing twice in an email a factory actually reads. The printed Thai
        // FORM keeps the note sub-row regardless (ImportRequestFormAssembler never calls this
        // method) — this trim is specific to the order-email's own layout. A business-typed note
        // (anything that is NOT exactly the derived sentence) is never skipped.
        String derivedNote = ImportRequestLinePrefill.buildColorSurfaceNote(item.color(), item.texture());
        if (item.note() != null && !item.note().isBlank() && !item.note().equals(derivedNote)) {
            // Multi-line text stays indented inside this item's own block, matching
            // FactoryQuoteService#appendItemBlock's specialRequirement handling.
            body.append("   Note: ")
                .append(item.note().replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n   "))
                .append("\n");
        }
    }

    /**
     * "<Brand> <Model>  (Code: X)" (REVIEW ROUND 2, S-A) — mirrors {@code
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
        // Cheap nit (REVIEW ROUND 3): skip the "(Code: X)" suffix when X is IDENTICAL to the model
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
     * <p>Refused once the predecessor has reached {@link ImportRequestStep#RECEIVED} (owner decision
     * 4 default: "refuse revising after RECEIVED") — the goods are already in, so there is nothing
     * left a correction could still affect operationally, only the paper record.
     *
     * <p>The form being corrected STAYS ISSUED until the replacement is issued — see {@link #issue}.
     */
    @Transactional
    public ImportRequestDto revise(long id, UserPrincipal actor) {
        ImportRequestDto issued = requireStored(id);
        requireFullWrite(issued.ticketId(), actor);
        TicketSnapshot snapshot = requireTicket(issued.ticketId());
        requireDealActive(snapshot); // REVIEW ROUND 1, S4
        if (!ImportRequestStatus.ISSUED.equals(issued.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ออกฉบับแก้ไขได้เฉพาะใบขอซื้อที่ออกเลขแล้ว");
        }
        if (ImportRequestStep.RECEIVED.equals(issued.importStep())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อที่ได้รับสินค้าแล้ว (RECEIVED) แก้ไขไม่ได้");
        }
        // Lead time is CARRIED FORWARD from the predecessor's actual (possibly hand-edited) values,
        // not re-autofilled from the factory's country — a correction should not silently discard
        // an estimate import already refined (owner decision 09-18 #2, same "carried forward" rule
        // the progress step already follows just above). vesselEtaNote is carried forward the same
        // way (REVIEW ROUND 2, S-E) — issue() re-derives it only if it is STILL exactly the
        // auto-derived text for this draft's own range at that point; a CEO-typed custom note
        // survives the revision untouched.
        // REVIEW ROUND 3, item 2 (S-F regression): the issued form's OWN requiredByNote must carry
        // forward too — leaving this out fell all the way back to `insertDraft`'s
        // snap.requiredByNote() default (the DEAL's CURRENT value), silently dropping anything the
        // rep had typed directly onto THIS form (e.g. "Before Songkran") the moment it was revised.
        long revisionId = stored.insertDraft(issued.ticketId(), issued.factoryId(), issued.factoryName(),
            issued.brand(), stored.highestVersion(issued.ticketId(), issued.factoryId()) + 1,
            snapshot, issued.leadTimeMinDays(), issued.leadTimeMaxDays(), issued.vesselEtaNote(),
            issued.requiredByNote(), actor.id(), actor.name());
        stored.replaceItems(revisionId, issued.items().stream()
            .map(it -> new ImportRequestItemInput(it.ticketItemId(), it.code(), it.size(),
                it.qty(), it.unit(), it.note(), it.color(), it.texture(), it.brand(), it.model()))
            .toList());
        return withPageCount(requireStored(revisionId));
    }

    /** A draft that should not exist is deleted, not tombstoned — it has no number and no audit weight. */
    @Transactional
    public void deleteDraft(long id, UserPrincipal actor) {
        ImportRequestDto existing = requireStored(id);
        requireFullWrite(existing.ticketId(), actor);
        // Cheap nit (REVIEW ROUND 2): matches every other write in this class — deleteDraft was the
        // one write left without the REVIEW ROUND 1 S4 inactive-deal refusal.
        requireDealActive(requireTicket(existing.ticketId()));
        if (stored.deleteDraft(id) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ลบได้เฉพาะใบขอซื้อที่ยังไม่ออกเลข");
        }
    }

    // ── order-email draft (owner decision 09-18 #3 §B) ──────────────────────────────────────────

    /**
     * Edits the drafted order-email — owning rep/CEO/import, while it has not yet been marked sent.
     * {@link #FOOTER_ONLY_ROLES} (import) is reused here rather than a new role set: the email draft
     * is, like the footer, filled in by hand after the form is raised, and the same three parties
     * (owning rep, CEO, import) are the ones who would ever touch it.
     */
    @Transactional
    public ImportRequestDto updateEmailDraft(long id, ImportRequestRequests.UpdateEmailDraftRequest request,
            UserPrincipal actor) {
        ImportRequestDto existing = requireStored(id);
        requireFooterWrite(existing.ticketId(), actor);
        requireDealActive(requireTicket(existing.ticketId()));
        if (!ImportRequestStatus.ISSUED.equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "แก้ไขอีเมลได้เฉพาะใบขอซื้อที่ออกเลขแล้ว");
        }
        if (existing.emailSentAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "อีเมลนี้ถูกส่งไปแล้ว แก้ไขไม่ได้");
        }
        // Cheap nit (REVIEW ROUND 2): the repository's WHERE clause now ALSO compare-and-sets on
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
     * owning sales rep (owner decision 09-18 #3 §B: "POST mark-sent (import/CEO + owning rep)").
     * Idempotent: a second call on an already-sent draft 409s rather than re-stamping who/when, the
     * same compare-and-set discipline every other write in this class already follows.
     */
    @Transactional
    public ImportRequestDto markEmailSent(long id, UserPrincipal actor) {
        ImportRequestDto existing = requireStored(id);
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
     * Advances an ISSUED row's per-factory progress by ONE step (V184, GLA-100/S12-S17) —
     * {@link #ADVANCE_STEP_ROLES} ({@code import}/{@code ceo}) only.
     *
     * <p>Forward-only, and a skip is allowed (owner decision 4: "forward skips allowed, never
     * backwards — correct via revision"), so the target need only be STRICTLY AHEAD of the row's
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
        ImportRequestDto existing = requireStored(id);
        requireDealActive(requireTicket(existing.ticketId())); // REVIEW ROUND 1, S4
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
        // REVIEW ROUND 1 nit: a backdatable step may still not be dated in the FUTURE (Asia/Bangkok,
        // matching this class's own BANGKOK zone), nor before the row's own first-issue date — a
        // step cannot have happened before the factory was even contacted.
        if (eventDate.isAfter(LocalDate.now(BANGKOK))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ของขั้นตอนต้องไม่เป็นวันที่ในอนาคต");
        }
        if (existing.firstIssuedDate() != null && eventDate.isBefore(existing.firstIssuedDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "วันที่ของขั้นตอนต้องไม่ก่อนวันที่ออกใบขอซื้อครั้งแรก (" + existing.firstIssuedDate() + ")");
        }
        int rows = stored.advanceStep(id, existing.importStep(), target, eventDate,
            actor.id(), actor.name(), request.note());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ขั้นตอนถูกเปลี่ยนแปลงโดยผู้ใช้อื่น หรือฉบับนี้ถูกแทนที่แล้ว");
        }
        String noteSuffix = request.note() != null && !request.note().isBlank()
            ? " — " + request.note() : "";
        // REVIEW ROUND 1, S5: the ticket must be LOCKED (FOR UPDATE) BEFORE the event insert below,
        // not after — addEventWithDocument's FK to sales.ticket takes a KEY SHARE lock on the ticket
        // row, and two concurrent advanceStep calls that both later try to upgrade to FOR UPDATE
        // (the RECEIVED branch, previously below) would each be waiting on the other's KEY SHARE,
        // a classic FK lock-order deadlock. Locking FIRST, unconditionally, establishes one strict
        // order (lock, then insert) so at most one caller can ever be in that position at a time —
        // the second simply blocks until the first commits and releases the lock.
        tickets.lockTicketForUpdate(existing.ticketId());
        tickets.addEventWithDocument(existing.ticketId(), actor.id(), actor.name(),
            TicketEventKind.IMPORT_STEP_ADVANCED, null, null,
            "โรงงาน " + existing.factoryName() + ": " + ImportRequestStep.thaiLabel(existing.importStep())
                + " → " + ImportRequestStep.thaiLabel(target) + noteSuffix,
            th.co.glr.hr.ticket.RelatedDocumentType.IMPORT_REQUEST, id);

        if (ImportRequestStep.RECEIVED.equals(target)) {
            if (allFactoriesReceived(existing.ticketId())) {
                ticketService.applyImportRequestRollup(existing.ticketId(), actor);
            }
        }
        return withPageCount(requireStored(id));
    }

    /** Prints the INTERNAL stored form from ITS OWN snapshot — everything, as before OWNER DECISION
     * 09-19 #2 added the FACTORY copy. See {@link #renderStored(long, UserPrincipal, boolean)}. */
    public byte[] renderStored(long id, UserPrincipal actor) {
        return renderStored(id, actor, false);
    }

    /**
     * Prints a stored form from ITS OWN snapshot, not from the deal's current state.
     *
     * <p><b>OWNER DECISION 09-19 #2:</b> the copy actually attached to the factory-facing order
     * email must not show "สั่งมาให้" (customer name), the project name, or "วันที่ได้รับมัดจำ"
     * (deposit received date) — a supplier has no business knowing who GL&R's customer is, and a
     * project name usually identifies them. {@code factoryCopy=true} blanks exactly those three fields ({@link ImportRequestFormAssembler#fromStored(ImportRequestDto,
     * boolean)}); everything else on the form — factory, lines, quantities, required-by, vessel ETA,
     * doc number, footer — is IDENTICAL between the two copies. Same read-authorisation as the
     * internal copy ({@link #requireRead}) — which copy is requested is not a permission question.
     */
    public byte[] renderStored(long id, UserPrincipal actor, boolean factoryCopy) {
        ImportRequestDto row = requireStored(id);
        requireRead(row.ticketId(), actor);
        try {
            return renderer.render(ImportRequestFormAssembler.fromStored(row, factoryCopy));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "สร้างไฟล์ใบขอซื้อไม่สำเร็จ: " + e.getMessage());
        }
    }

    /**
     * "กำหนดวันที่ต้องการของ" on the DEAL — SALES's field, by owner ruling, which is why this is the
     * one method here not gated on the stored-aggregate roles above.
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

    // ── factory resolution (owner decision 2) ────────────────────────────────────────────────────

    /** One factory's group of resolved lines, ready to become one DRAFT row. */
    private record FactoryGroup(long factoryId, String factoryName, String countryCode, String brandLabel,
                                List<ImportRequestItemInput> items) {}

    /** A caller-supplied country for a factory about to be auto-created, already validated. */
    private record ValidatedCountry(String code, String other) {}

    /**
     * Resolves every deal line with remaining (non-stock-covered) quantity to a factory, per {@link
     * ImportRequestQueryRepository#factoryResolutionCandidates}'s cascade, and groups them. Refuses
     * with 409, naming the line, when NEITHER a direct factory id NOR a name is found anywhere for it
     * (2026-09-18 correction to the plan: never derive or create a factory from {@code brand} — see
     * {@link ImportRequestQueryRepository#factoryResolutionCandidates}'s own Javadoc for why).
     *
     * <p><strong>Two-phase for the auto-create path</strong> (owner decision 2, revised 09-18:
     * "Sales should be forced to select country ... if there is unknown make it อื่นๆ and let the
     * input"). Every line whose typed name matches NOTHING existing is collected FIRST (read-only —
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
        Map<Long, FactoryConfigDto> factoriesById = factories.findAll().stream()
            .collect(Collectors.toMap(FactoryConfigDto::id, f -> f, (a, b) -> a, LinkedHashMap::new));
        Map<String, Long> normalizedNameToId = factoriesById.values().stream()
            .collect(Collectors.toMap(f -> normalizeFactoryName(f.factoryName()), FactoryConfigDto::id,
                (a, b) -> a));
        Map<Long, String> resolvedNameById = new LinkedHashMap<>(factoriesById.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().factoryName())));
        Map<Long, String> countryCodeById = new LinkedHashMap<>(factoriesById.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().country())));

        // LinkedHashMap: deterministic, deal-line-order grouping — matches how brandLinesForTicket's
        // own LinkedHashMap keeps printed order.
        Map<Long, List<LineFactoryCandidate>> byFactory = new LinkedHashMap<>();
        // Phase 1: everything resolvable WITHOUT creating anything, plus the pending (needs-create)
        // set, keyed by normalized name — grouping by normalized name here IS grouping by the
        // factory-to-be, since normalization is exactly the matching function.
        Map<String, List<LineFactoryCandidate>> pendingByNormalizedName = new LinkedHashMap<>();
        Map<String, String> pendingDisplayNames = new LinkedHashMap<>();

        for (LineFactoryCandidate c : candidates) {
            if (c.directFactoryId() == null && c.candidateFactoryName() == null) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "รายการนี้ยังไม่ได้ระบุโรงงาน กรุณาระบุโรงงานก่อน: " + c.code()
                        + (c.size() != null && !c.size().isBlank() ? " (" + c.size() + ")" : ""));
            }
            if (c.directFactoryId() != null) {
                long factoryId = c.directFactoryId();
                // A direct id always comes from a real FK (pricing_request_item.resolved_factory_id or
                // product_prices.factory_id), so it is guaranteed to exist -- but it may not be in the
                // findAll() snapshot above if concurrently created between that read and here; fall
                // back to a fresh lookup rather than risk a null name/country on the row.
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
            List<LineFactoryCandidate> lines = entry.getValue();
            String brandLabel = lines.stream().map(LineFactoryCandidate::brand)
                .filter(b -> b != null && !b.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
            // Owner decision 09-18 #3 §A: the note sub-row is auto-derived from colour/surface at
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
            groups.add(new FactoryGroup(factoryId, resolvedNameById.get(factoryId),
                countryCodeById.get(factoryId), brandLabel.isBlank() ? null : brandLabel, items));
        }
        return groups;
    }

    private Optional<FactoryConfigDto> freshFactoryLookup(long factoryId) {
        return factories.findAll().stream().filter(f -> f.id() == factoryId).findFirst();
    }

    /**
     * Validates one pending factory name's caller-supplied country (owner decision 09-18): required,
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

    /**
     * Matches {@code normalizedName} against the factory master ONE MORE TIME (a concurrent creator
     * may have won the race since phase 1's read), or creates it with its now-validated country.
     * Locks the normalized name first ({@link FactoryConfigRepository#lockFactoryName}) so two
     * concurrent callers naming "the same" new factory in two different transactions cannot both
     * create it — see that method's own Javadoc for why a lock was chosen over a unique index.
     */
    private long resolveOrCreateFactory(String normalizedName, String displayName, ValidatedCountry country,
            long actorId, Map<String, Long> normalizedNameToId, Map<Long, String> resolvedNameById,
            Map<Long, String> countryCodeById) {
        factories.lockFactoryName(normalizedName);
        // Re-check AFTER the lock: another transaction may have created this exact factory while this
        // one was waiting for the lock, and the caller's in-memory map (built before the lock) cannot
        // see it.
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
     * Normalizes a factory name for matching (owner decision 2b): NFC Unicode normalization (Thai
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

    /**
     * Does every factory this deal actually needs an import for now have an ISSUED row at {@link
     * ImportRequestStep#RECEIVED}? Two conditions, both required (import-request-per-factory-PLAN.md
     * §6): (a) every currently-ISSUED row for the deal is RECEIVED, and (b) every factory the deal's
     * remaining lines resolve to — read-only, via {@link #requiredFactoryIds}, which does NOT create
     * a factory on the fly (creation only ever happens from {@link #createDrafts}) — actually has an
     * ISSUED row at all. (b) is what stops a rollup firing early just because the factories that DO
     * have rows all happen to be RECEIVED, while another factory's lines have no IR yet at all.
     */
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

    /**
     * Read-only mirror of {@link #resolveFactoryGroups}'s resolution cascade, WITHOUT creating a
     * factory on the fly — the rollup must never mutate the factory master, only read it. A line that
     * cannot be resolved to an EXISTING factory (a candidate name matching nothing yet) is excluded
     * from the required set rather than treated as blocking: if it truly needs its own IR, {@code
     * createDrafts} would have created (and thereby resolved) its factory already; a name that still
     * matches nothing existing at rollup time can only be a NEW line added to the deal after drafts
     * were raised, which {@code createDrafts} can be re-run to pick up — the rollup itself does not
     * invent a factory to demand an IR for.
     */
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

    private ImportRequestDto requireStored(long id) {
        return stored.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบขอซื้อนี้"));
    }

    private void requireDealActive(TicketSnapshot snapshot) {
        if (!"ACTIVE".equals(snapshot.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลนี้ไม่ได้อยู่ในสถานะที่ดำเนินการได้");
        }
    }

    private List<ImportRequestDto> withPageCounts(List<ImportRequestDto> rows) {
        return rows.stream().map(this::withPageCount).toList();
    }

    /** Fills the DTO's {@code pageCount}, which the repository leaves at 0 — layout is not its job. */
    private ImportRequestDto withPageCount(ImportRequestDto r) {
        int pages = ImportRequestRenderer.pagesRequired(ImportRequestFormAssembler.fromStored(r));
        // Derived, not stored (owner decision 09-18 #2): null while DRAFT (no firstIssuedDate yet) or
        // while either lead-time bound is unset — both are the "not estimated yet" state, not zero
        // days. This is the one place both derived fields are computed; see ImportRequestDto's own
        // Javadoc.
        //
        // REVIEW ROUND 1, S7 (owner default, flagged for confirmation): anchored on firstIssuedDate
        // — the FIRST issue date of this (deal, factory)'s whole revision chain — NOT r.issueDate(),
        // which is re-stamped to "today" on every issue including a same-day correction. Anchoring
        // on issueDate would silently push a customer-facing arrival estimate out by however long a
        // typo revision took to raise; see V184's comment on first_issued_date for the full reasoning.
        LocalDate expectedFrom = (r.firstIssuedDate() != null && r.leadTimeMinDays() != null)
            ? r.firstIssuedDate().plusDays(r.leadTimeMinDays()) : null;
        LocalDate expectedTo = (r.firstIssuedDate() != null && r.leadTimeMaxDays() != null)
            ? r.firstIssuedDate().plusDays(r.leadTimeMaxDays()) : null;
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

    /** STORED-aggregate read gate (owner decision 1): CEO/import/sales_manager unrestricted; sales
     * only the deal's own owner. */
    private void requireRead(long ticketId, UserPrincipal actor) {
        String role = actor.role();
        if ("ceo".equals(role) || READ_ONLY_ROLES.contains(role)) {
            return;
        }
        if ("sales".equals(role) && repository.isDealOwner(ticketId, actor.id())) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }

    /** STORED-aggregate full-write gate (owner decision 1): CEO, or the deal's own owning sales rep. */
    private void requireFullWrite(long ticketId, UserPrincipal actor) {
        if (!canFullWrite(ticketId, actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private boolean canFullWrite(long ticketId, UserPrincipal actor) {
        String role = actor.role();
        return "ceo".equals(role) || ("sales".equals(role) && repository.isDealOwner(ticketId, actor.id()));
    }

    /**
     * The ORDER-EMAIL draft's write gate (owner decision 09-18 #3 §B) — full-write roles, PLUS
     * import. Unaffected by owner decision 09-19: that ruling is about the PRINTED form's footer
     * (Checked/Approved + vessel ETA — see {@link #requirePrintedFooterWrite}), a different surface
     * that happens to share the word "footer" only because of the printed form's own layout. Do
     * NOT reuse this for the printed footer — that is exactly the bug 09-19 fixes.
     */
    private void requireFooterWrite(long ticketId, UserPrincipal actor) {
        if (canFullWrite(ticketId, actor) || FOOTER_ONLY_ROLES.contains(actor.role())) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }

    /**
     * The PRINTED form's footer (Checked By/date, Approve By/date, vessel ETA) — CEO ONLY (owner
     * decision 09-19, 2026-09-19, supersedes decision 1's original footer wording, which had let
     * the owning sales rep and import touch it too). Neither the deal's owning sales rep, nor
     * {@code import}, nor {@code sales_manager} may write these fields any more; only reads a role
     * already had are unaffected — {@link #requireRead} still governs those. See {@link
     * #requireFooterWrite} for the DIFFERENT gate that still governs the order-email draft.
     */
    private void requirePrintedFooterWrite(UserPrincipal actor) {
        if (!"ceo".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private TicketSnapshot requireTicket(long ticketId) {
        return repository.loadTicketSnapshot(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
    }

    private TicketSummaryDto requireTicketSummary(long ticketId) {
        return tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"))
            .summary();
    }
}
