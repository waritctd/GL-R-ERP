package th.co.glr.hr.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ArGlrSequence;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * ใบวางบิล (billing note, GLA-99 step 3) — a CUSTOMER-level cover sheet rolling several already-
 * ISSUED documents (remaining invoices, deposit notices, possibly across several of the customer's
 * deals) into one billed total for one credit cycle. DRAFT -&gt; ISSUED (mint number, freeze every
 * line) -&gt; {@code SUPERSEDED} (via {@link #revise}) | {@code CANCELLED} (via {@link #cancel},
 * lines released for re-billing) | {@code SETTLED} — reached automatically (see {@link
 * BillingNoteRepository#reconcileSettlementForCustomer}'s own Javadoc for the recompute-on-read
 * mechanism and why no ticket/audit trail is written for it) OR explicitly via {@link
 * #markSettled} (owner ruling C1, 2026-09-20: an ALL-MANUAL note, e.g. ค่าขนส่ง, can never
 * auto-settle — it needs a human to mark it paid). Numbered on the SAME shared {@code AR_GLR}
 * sequence {@link th.co.glr.hr.deposit.RemainingInvoiceService} draws from, via {@link
 * ArGlrSequence}.
 *
 * <p><b>Many ISSUED notes of the same (customer, type) coexist — one per credit cycle (owner
 * ruling B1, 2026-09-20).</b> Live-uniqueness (at most one live DRAFT, at most one live ISSUED) is
 * scoped to the REVISION CHAIN a note belongs to, not to {@code (customerId, type)}: {@link
 * #createDraft} starts a brand-new chain (no predecessor check at all — several may be in progress
 * at once); {@link #revise} is the only thing that ties a draft to a specific predecessor, via
 * {@link BillingNoteDocumentDto#revisionOfId}, and {@link #issue} finds ITS OWN predecessor by
 * following that pointer, never by scanning for "the" ISSUED note of this customer+type (there may
 * now be several). See V189's own header comment for the full reasoning and the replaced indexes.
 *
 * <p><b>Authorisation (owner rulings, 2026-09-19/20) is a NEW, standalone gate — not reused from
 * any existing sales-shaped role set</b>, because this grant must work for a caller in ANY role,
 * including plain {@code employee} (ภิญญดา, QC&amp;ISO):
 * <ul>
 *   <li><b>Write</b> (create/update/issue/revise/cancel/mark-received): {@code ceo}, or any
 *       employee holding the live {@link EmployeeAuthRepository#canIssueBillingNote} grant. No
 *       other role, however sales-shaped, is admitted — a plain {@code sales} or {@code
 *       sales_manager} row WITHOUT the grant is refused exactly like {@code import}/{@code hr}.</li>
 *   <li><b>Read</b>: the write set, plus {@code account} and {@code sales_manager} unconditionally
 *       (finance/sales-management oversight of billing), plus a {@code sales} rep who owns at
 *       least one of the deals a note's own lines reference — decided here (the plan left this
 *       open): a rep can see a billing note that touches their own deal's documents, mirroring the
 *       existing "sales sees their own deals" pattern ({@code
 *       DepositNoticeService#requireTicketViewer}) rather than inventing a new rule.</li>
 * </ul>
 */
@Service
public class BillingNoteService {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final Set<String> TYPES = Set.of("GOODS", "FREIGHT");
    private static final Set<String> READ_ROLES = Set.of("account", "sales_manager", "ceo");
    private static final Set<String> SOURCE_TYPES = Set.of("REMAINING_INVOICE", "DEPOSIT_NOTICE", "MANUAL");
    // S6 (Opus review, GLA-99 step 3 round 2): the real Postgres unique-index name V189's own
    // "no double billing" invariant is enforced by — used to NARROW every catch (DataIntegrityViolationException e)
    // below to the ONE violation that genuinely means "already claimed elsewhere", rather than
    // blindly reporting every DataIntegrityViolationException (e.g. a value too long for a VARCHAR
    // column) as a misleading "เอกสารนี้อยู่ในใบวางบิลอื่นอยู่แล้ว" double-billing conflict.
    private static final String DOUBLE_BILLING_CONSTRAINT = "ux_billing_note_line_source_live";

    private final BillingNoteRepository stored;
    private final CustomerRepository customers;
    private final TicketRepository tickets;
    private final EmployeeAuthRepository employeeAuth;
    private final BillingNoteRenderer renderer;
    private final NamedParameterJdbcTemplate jdbc;

    public BillingNoteService(BillingNoteRepository stored, CustomerRepository customers, TicketRepository tickets,
            EmployeeAuthRepository employeeAuth, BillingNoteRenderer renderer, NamedParameterJdbcTemplate jdbc) {
        this.stored = stored;
        this.customers = customers;
        this.tickets = tickets;
        this.employeeAuth = employeeAuth;
        this.renderer = renderer;
        this.jdbc = jdbc;
    }

    // ── Authorisation ────────────────────────────────────────────────────────────────────────

    private boolean hasWriteGrant(UserPrincipal actor) {
        return "ceo".equals(actor.role()) || employeeAuth.canIssueBillingNote(actor.id());
    }

    private void requireWriteAccess(UserPrincipal actor) {
        if (!hasWriteGrant(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์ออกหรือแก้ไขใบวางบิล");
        }
    }

    private void requireReadAccess(BillingNoteDocumentDto note, UserPrincipal actor) {
        if (READ_ROLES.contains(actor.role()) || hasWriteGrant(actor)) {
            return;
        }
        if ("sales".equals(actor.role()) && ownsAnyReferencedTicket(note, actor)) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงใบวางบิลนี้");
    }

    private boolean ownsAnyReferencedTicket(BillingNoteDocumentDto note, UserPrincipal actor) {
        return note.lines().stream()
            .map(BillingNoteLineDto::ticketId)
            .filter(Objects::nonNull)
            .distinct()
            .anyMatch(ticketId -> tickets.findById(ticketId)
                .map(t -> t.summary().createdById() == actor.id())
                .orElse(false));
    }

    /** S6 (round 2): true only for the ONE constraint this class's own catches actually mean to
     * handle. A caller that does not match this must re-throw {@code e} unchanged, letting {@code
     * DataAccessException}'s generic handler report the real DB error instead of a misleading
     * double-billing message. */
    private boolean isDoubleBillingViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String msg = cause != null ? cause.getMessage() : null;
        return msg != null && msg.contains(DOUBLE_BILLING_CONSTRAINT);
    }

    // ── Reads ────────────────────────────────────────────────────────────────────────────────

    public List<BillingNoteDocumentDto> list(long customerId, UserPrincipal actor) {
        // S7 (Opus review, GLA-99 step 3 round 2): the role-only part of the gate runs BEFORE
        // requireCustomer, same fix F2 (round 1) already made for every write method — otherwise a
        // caller with NO access at all (neither the write grant/READ_ROLES nor `sales`) could tell
        // "customer does not exist" (404) apart from "customer exists but I cannot see it" (403)
        // without ever having been entitled to either answer, an existence oracle. A `sales`/
        // full-access caller's own requireCustomer 404 stays exactly as before — the oracle concern
        // is specifically about a role with NO possible access to this endpoint at all.
        boolean fullAccess = READ_ROLES.contains(actor.role()) || hasWriteGrant(actor);
        boolean possibleSalesAccess = "sales".equals(actor.role());
        if (!fullAccess && !possibleSalesAccess) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงใบวางบิลของลูกค้ารายนี้");
        }
        requireCustomer(customerId);
        List<BillingNoteDocumentDto> all = stored.findByCustomer(customerId);
        if (fullAccess) {
            return all;
        }
        // Batched (review nit, round 2): ONE ticket-ownership query for every distinct ticket
        // referenced across ALL of this customer's notes, instead of {@link
        // #ownsAnyReferencedTicket}'s own one-tickets.findById-per-ticket-per-note (an N+1 for a
        // sales rep customer with many notes/deals).
        Set<Long> allTicketIds = all.stream()
            .flatMap(n -> distinctTicketIds(n).stream())
            .collect(Collectors.toSet());
        Map<Long, Long> createdByByTicket = tickets.findCreatedByIds(allTicketIds);
        return all.stream()
            .filter(n -> distinctTicketIds(n).stream()
                .anyMatch(ticketId -> actor.id() == createdByByTicket.getOrDefault(ticketId, -1L)))
            .toList();
    }

    public BillingNoteDocumentDto get(long id, UserPrincipal actor) {
        BillingNoteDocumentDto note = requireStored(id);
        requireReadAccess(note, actor);
        return note;
    }

    /** Nit fix (Opus review, GLA-99 step 3 round 1): returns the doc_number the filename needs
     * ALONGSIDE the rendered bytes, in the SAME {@code requireStored}/{@code requireReadAccess}
     * pass, so {@link BillingNoteController#file} no longer has to call {@link #get} first just to
     * learn the filename — that loaded and authorized this exact row a second time. */
    public RenderedFile file(long id, UserPrincipal actor) {
        BillingNoteDocumentDto note = requireStored(id);
        requireReadAccess(note, actor);
        try {
            return new RenderedFile(renderer.toXlsx(toRenderable(note)), note.docNumber());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "สร้างไฟล์ Excel ไม่สำเร็จ: " + e.getMessage());
        }
    }

    /** Candidate ISSUED remaining invoices / deposit notices for a NEW line on this customer,
     * split into {@code candidates} (outstanding &gt; 0, not already claimed) and {@code
     * alreadyBilled} (outstanding &gt; 0 but a DIFFERENT live billing note already holds it — named
     * so the caller can explain the exclusion, per the plan's own "report which note"). Fully paid
     * docs are silently excluded from both lists. */
    public BillingNoteCandidatesDto candidates(long customerId, UserPrincipal actor) {
        // S7 (round 2): role check before requireCustomer — same existence-oracle fix as list()'s
        // own above (no `sales` partial-access branch exists here, so this is a plain reorder).
        if (!(READ_ROLES.contains(actor.role()) || hasWriteGrant(actor))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์ดูรายการที่วางบิลได้ของลูกค้ารายนี้");
        }
        CustomerDto customer = requireCustomer(customerId);
        List<BillingNoteCandidateDto> eligible = new ArrayList<>();
        List<BillingNoteCandidateDto> alreadyBilled = new ArrayList<>();
        // candidatesForCustomer runs the B2 settlement reconcile FIRST (see its own Javadoc), so
        // the claims map read next already reflects any note that just settled and released.
        List<BillingNoteRepository.CandidateRow> rows = stored.candidatesForCustomer(customerId);
        // Batched — one query for every row's claim status, not one findLiveClaim call per row
        // (review nit, GLA-99 step 3 round 1: this was an N+1 for a customer with many candidates).
        Map<String, BillingNoteRepository.ClaimedBy> liveClaims = stored.findLiveClaimsForCustomer(customerId);
        for (BillingNoteRepository.CandidateRow row : rows) {
            if (row.outstandingAmount() == null || row.outstandingAmount().signum() <= 0) {
                continue; // fully paid — excluded from both lists
            }
            BillingNoteRepository.ClaimedBy claim = liveClaims.get(row.sourceType() + ":" + row.sourceId());
            if (claim != null) {
                alreadyBilled.add(new BillingNoteCandidateDto(row.sourceType(), row.sourceId(), row.ticketId(),
                    row.docNumber(), row.docDate(), row.dueDate(), row.outstandingAmount(),
                    claim.billingNoteId(), claim.docNumber()));
            } else {
                eligible.add(new BillingNoteCandidateDto(row.sourceType(), row.sourceId(), row.ticketId(),
                    row.docNumber(), row.docDate(), row.dueDate(), row.outstandingAmount(), null, null));
            }
        }
        return new BillingNoteCandidatesDto(customerId, customer.name(), eligible, alreadyBilled);
    }

    // ── Writes ───────────────────────────────────────────────────────────────────────────────

    /** B1: a brand-new draft starts its OWN chain — no predecessor check at all, since many ISSUED
     * notes of this same (customer, type) may already legitimately exist (one per credit cycle).
     * The two refusals this method used to run here ("already has an ISSUED note, revise instead"
     * and "already has a DRAFT") were both customer+type-scoped and are GONE per B1 — several
     * brand-new drafts of the same customer+type may now be in progress at once (e.g. preparing
     * next cycle's bill while the current one is still ISSUED-but-unsettled). The only live-
     * uniqueness left is per CHAIN, and a brand-new draft (revisionOfId {@code null}) is not part
     * of one yet — {@link #revise} is where the per-chain refusal actually lives. */
    @Transactional
    public BillingNoteDocumentDto createDraft(long customerId, BillingNoteDraftRequest req, UserPrincipal actor) {
        requireWriteAccess(actor);
        CustomerDto customer = requireCustomer(customerId);
        String type = normaliseType(req == null ? null : req.type());

        long id = stored.insertDraft(customerId, type, null, req == null ? null : req.billDate(),
            req == null ? null : req.paymentDueNote(), req == null ? null : req.note(),
            customer.name(), customer.taxId(), customer.branch(), customer.address(), actor.id(), actor.name());

        List<BillingNoteLineSelection> selections = req == null || req.lines() == null ? List.of() : req.lines();
        writeLines(id, customerId, null, selections);
        return requireStored(id);
    }

    @Transactional
    public BillingNoteDocumentDto updateDraft(long id, BillingNoteDraftRequest req, UserPrincipal actor) {
        // F2 (Opus review, GLA-99 step 3 round 1): authz BEFORE the existence/state check in every
        // write method below — today's ordering let an unauthorized caller distinguish "no such
        // note" (404) from "exists but wrong state" (409) without ever passing the write gate, an
        // existence/state oracle. requireWriteAccess needs only `actor`, so it can always run
        // first with no loss of information the caller was entitled to anyway.
        requireWriteAccess(actor);
        BillingNoteDocumentDto existing = requireDraft(id);

        LocalDate billDate = req != null && req.billDate() != null ? req.billDate() : existing.billDate();
        String paymentDueNote = req != null && req.paymentDueNote() != null ? req.paymentDueNote() : existing.paymentDueNote();
        String note = req != null && req.note() != null ? req.note() : existing.note();
        // This guarded UPDATE also holds the parent row lock until the transaction commits.
        // If issuance won the race after requireDraft, do not replace its now-frozen lines.
        if (stored.updateDraftFields(id, billDate, paymentDueNote, note) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบวางบิลนี้ไม่ได้อยู่ในสถานะร่างแล้ว กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }

        // Refresh the customer snapshot in case the master record changed since the draft was
        // created — mirrors RemainingInvoiceService#updateDraft's own re-snapshot discipline.
        CustomerDto customer = requireCustomer(existing.customerId());
        stored.updateCustomerSnapshot(id, customer.name(), customer.taxId(), customer.branch(), customer.address());

        if (req != null && req.lines() != null) {
            writeLines(id, existing.customerId(), id, req.lines());
        }
        return requireStored(id);
    }

    /** B1: finds ITS OWN predecessor via {@link BillingNoteDocumentDto#revisionOfId} — never by
     * scanning for "the" ISSUED note of this customer+type, since several may now legitimately
     * coexist across credit cycles. {@code revisionOfId} is set only by {@link #revise}, so a
     * brand-new draft (the common case) mints a first-ever base_number with no predecessor at all;
     * a correction draft names its predecessor explicitly and this method refuses to issue it if
     * that specific predecessor is no longer {@code ISSUED} (superseded or cancelled out from
     * under it by a concurrent operation) rather than silently minting a wrong number. */
    @Transactional
    public BillingNoteDocumentDto issue(long id, UserPrincipal actor) {
        requireWriteAccess(actor);
        BillingNoteDocumentDto draft = requireDraft(id);
        if (draft.lines().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบวางบิลนี้ยังไม่มีรายการ");
        }

        Optional<BillingNoteDocumentDto> predecessor = Optional.empty();
        if (draft.revisionOfId() != null) {
            BillingNoteDocumentDto pred = stored.findById(draft.revisionOfId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ไม่พบใบวางบิลต้นฉบับที่จะแก้ไข"));
            if (!"ISSUED".equals(pred.status())) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "ใบวางบิลต้นฉบับไม่ได้อยู่ในสถานะออกแล้วอีกต่อไป ไม่สามารถออกฉบับแก้ไขนี้ได้");
            }
            predecessor = Optional.of(pred);
        }

        LocalDate today = LocalDate.now(BANGKOK);
        int thaiYear = today.getYear() + 543;
        String baseNumber;
        int version;
        if (predecessor.isPresent()) {
            baseNumber = predecessor.get().baseNumber();
            version = predecessor.get().version() + 1;
        } else {
            baseNumber = ArGlrSequence.next(jdbc, thaiYear);
            version = 1;
        }
        String docNumber = baseNumber + "-" + version;

        // Supersede the predecessor BEFORE this row's own compare-and-set — forced ordering, same
        // reasoning V154's own header comment gives at length (and RemainingInvoiceService#issue
        // already follows): ux_billing_note_base_number_issued (B1's replacement for the old
        // per-customer+type index) allows at most ONE ISSUED row per CHAIN, so the two rows must
        // never both read ISSUED, not even for the instant between two separate writes. Issuing
        // this row FIRST would trip that very index against its own still-ISSUED predecessor.
        predecessor.ifPresent(p -> stored.supersede(p.id(), id));

        int rows = stored.issue(id, baseNumber, version, docNumber, today, actor.id(), actor.name());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบวางบิลนี้ถูกออกไปแล้วโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }

        for (long ticketId : distinctTicketIds(draft)) {
            tickets.findById(ticketId).ifPresent(t -> {
                TicketSummaryDto s = t.summary();
                tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.DOCUMENT_ISSUED,
                    s.status(), s.status(), "ใบวางบิล " + docNumber + " ออกแล้ว");
            });
        }
        return requireStored(id);
    }

    /** Prepares a correction: a new DRAFT, tied to its predecessor via {@code revisionOfId} (B1),
     * copying the ISSUED note's own body/lines VERBATIM (not a live re-resolution — {@link
     * #updateDraft} is how a corrector refreshes content before re-issuing). The predecessor stays
     * {@code ISSUED} until the replacement is actually issued ({@link #issue} does the supersede),
     * but its LINES release their live claim immediately — see V189's own note on {@code
     * billing_note_line.note_status} for why this differs from the remaining invoice's identical-
     * looking revise: the invariant here is per-LINE (no double billing of one source document),
     * and a note correcting its own prior claim on its own same lines must not trip that same
     * guard. */
    @Transactional
    public BillingNoteDocumentDto revise(long id, UserPrincipal actor) {
        requireWriteAccess(actor);
        BillingNoteDocumentDto issued = requireIssued(id);
        if (stored.findLiveDraftForRevision(id).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "มีร่างฉบับแก้ไขของใบวางบิลนี้อยู่แล้ว");
        }

        long newId = stored.insertDraft(issued.customerId(), issued.type(), id, issued.billDate(),
            issued.paymentDueNote(), issued.note(), issued.customerName(), issued.customerTaxId(),
            issued.customerBranch(), issued.customerAddress(), actor.id(), actor.name());

        stored.releaseLines(id); // free the predecessor's claimed sources for the new draft below
        List<BillingNoteRepository.ResolvedLine> copied = issued.lines().stream()
            .map(l -> new BillingNoteRepository.ResolvedLine(l.sourceType(), l.sourceId(), l.ticketId(),
                l.docNumber(), l.docDate(), l.dueDate(), l.amount(), l.note()))
            .toList();
        try {
            stored.replaceLines(newId, copied);
        } catch (DataIntegrityViolationException e) {
            // S6 (round 2): narrowed to the ONE constraint this message actually describes — any
            // OTHER DataIntegrityViolationException (e.g. a value too long for a column) must not
            // be misreported as "another note already claimed this", so it re-throws unchanged.
            if (!isDoubleBillingViolation(e)) {
                throw e;
            }
            // A concurrent note grabbed one of these sources in the instant between the release
            // above and this insert — the whole transaction rolls back, which restores the
            // predecessor's own release too. Same "map the race to 409" discipline
            // RemainingInvoiceService#createDraft/#revise already follow.
            throw new ApiException(HttpStatus.CONFLICT,
                "รายการในใบวางบิลนี้ถูกนำไปใช้ในใบวางบิลอื่นไปแล้วระหว่างการแก้ไข กรุณาลองอีกครั้ง");
        }
        return requireStored(newId);
    }

    @Transactional
    public BillingNoteDocumentDto cancel(long id, BillingNoteCancelRequest req, UserPrincipal actor) {
        requireWriteAccess(actor);
        requireIssued(id);
        if (req == null || req.reason() == null || req.reason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุเหตุผลการยกเลิก");
        }
        int rows = stored.cancel(id, req.reason(), actor.id(), actor.name());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ยกเลิกได้เฉพาะใบวางบิลที่ออกแล้ว (ISSUED)");
        }
        return requireStored(id);
    }

    @Transactional
    public BillingNoteDocumentDto markReceived(long id, BillingNoteMarkReceivedRequest req, UserPrincipal actor) {
        requireWriteAccess(actor);
        requireIssued(id);
        if (req == null || req.receivedByName() == null || req.receivedByName().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุชื่อผู้รับวางบิล");
        }
        int rows = stored.markReceived(id, req.receivedByName(), req.paymentAppointmentDate());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "บันทึกผู้รับวางบิลได้เฉพาะใบวางบิลที่ออกแล้ว (ISSUED)");
        }
        return requireStored(id);
    }

    /** C1 (owner ruling, 2026-09-20) — the ONLY caller-triggered path into {@code SETTLED}: an
     * all-MANUAL note (e.g. ค่าขนส่ง) can never satisfy the automatic path's own "at least one
     * non-MANUAL line" requirement (see {@link BillingNoteRepository#reconcileSettlementForCustomer}'s
     * own Javadoc), so a human marks it paid instead. Same write gate as every other mutation —
     * NOT restricted to all-MANUAL notes specifically; a grant holder may also use this to settle a
     * mixed note early (e.g. the non-MANUAL side is known-paid through an out-of-band channel
     * before the next recompute-on-read observes it). Records who/when, unlike the automatic path,
     * which deliberately leaves {@code settledById}/{@code settledByName}/{@code settledAt} null. */
    @Transactional
    public BillingNoteDocumentDto markSettled(long id, UserPrincipal actor) {
        requireWriteAccess(actor);
        requireIssued(id);
        if (stored.markSettled(id, actor.id(), actor.name()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "บันทึกว่าชำระครบได้เฉพาะใบวางบิลที่ออกแล้ว (ISSUED)");
        }
        return requireStored(id);
    }

    /** A draft that should not exist is deleted, not tombstoned. If it was a correction-in-progress
     * (B1: its OWN {@code revisionOfId} names the predecessor, never a customer+type scan — several
     * ISSUED notes of this customer+type may now exist and only one of them is this draft's own),
     * the predecessor's lines are restored to their live {@code ISSUED} claim first, so abandoning
     * a correction never leaves the original note's own sources un-claimed.
     *
     * <p>F3 (Opus review, GLA-99 step 3 round 1): restoring those lines can itself lose a race — if
     * some OTHER note claimed the exact same source in the window between this correction's own
     * {@link #revise} (which released them) and this delete, {@code restoreLinesToIssued} trips
     * {@code ux_billing_note_line_source_live} exactly like {@link #revise}'s own {@code
     * replaceLines} race above. Mapped to the same 409 shape, not left to surface as an unmapped
     * 500. */
    @Transactional
    public void deleteDraft(long id, UserPrincipal actor) {
        requireWriteAccess(actor);
        BillingNoteDocumentDto existing = requireDraft(id);
        // Look up the predecessor (if any) BEFORE deleting — deleteDraft's own cascade removes
        // this draft's line rows, and only AFTER that happens can the predecessor's own lines be
        // restored to ISSUED without tripping ux_billing_note_line_source_live against this
        // draft's own still-existing (note_status = 'DRAFT') claim on the exact same sources.
        Optional<BillingNoteDocumentDto> predecessor = existing.revisionOfId() != null
            ? stored.findById(existing.revisionOfId()) : Optional.empty();
        if (stored.deleteDraft(id) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ลบได้เฉพาะใบวางบิลที่ยังไม่ออกเลข (DRAFT)");
        }
        if (predecessor.isPresent()) {
            try {
                stored.restoreLinesToIssued(predecessor.get().id());
            } catch (DataIntegrityViolationException e) {
                // S6 (round 2): see revise()'s own comment on the identical narrowing.
                if (!isDoubleBillingViolation(e)) {
                    throw e;
                }
                throw new ApiException(HttpStatus.CONFLICT,
                    "ไม่สามารถคืนสถานะรายการของใบวางบิลต้นฉบับได้ เนื่องจากเอกสารอ้างอิงถูกใช้ในใบวางบิลอื่นไปแล้วระหว่างการลบ");
            }
        }
    }

    // ── Line resolution ──────────────────────────────────────────────────────────────────────

    /** Resolves every requested line against the customer's live candidate pool (ERP-sourced) or
     * the request's own fields (MANUAL), enforces the template's row capacity, then persists —
     * shared by {@link #createDraft} (excludeNoteId {@code null}) and {@link #updateDraft}
     * (excludeNoteId = the draft's own id, so re-saving a draft's OWN already-claimed lines never
     * looks like a conflict with itself). */
    private void writeLines(long billingNoteId, long customerId, Long excludeNoteId, List<BillingNoteLineSelection> selections) {
        if (selections.size() > BillingNoteRenderer.MAX_LINE_ROWS) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบวางบิลมีรายการ " + selections.size() + " รายการ เกินความจุของแบบฟอร์ม "
                + "(สูงสุด " + BillingNoteRenderer.MAX_LINE_ROWS + " แถว) กรุณาลดจำนวนรายการหรือออกเอกสารหลายฉบับ");
        }
        Map<String, BillingNoteRepository.CandidateRow> byKey = new HashMap<>();
        for (BillingNoteRepository.CandidateRow row : stored.candidatesForCustomer(customerId)) {
            byKey.put(row.sourceType() + ":" + row.sourceId(), row);
        }

        List<BillingNoteRepository.ResolvedLine> resolved = new ArrayList<>();
        for (BillingNoteLineSelection sel : selections) {
            resolved.add(resolveLine(sel, byKey, excludeNoteId));
        }
        try {
            stored.replaceLines(billingNoteId, resolved);
        } catch (DataIntegrityViolationException e) {
            // S6 (round 2): see revise()'s own comment on the identical narrowing.
            if (!isDoubleBillingViolation(e)) {
                throw e;
            }
            throw new ApiException(HttpStatus.CONFLICT,
                "รายการหนึ่งในใบวางบิลนี้ถูกเพิ่มในใบวางบิลอื่นไปแล้ว กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
    }

    private BillingNoteRepository.ResolvedLine resolveLine(BillingNoteLineSelection sel,
            Map<String, BillingNoteRepository.CandidateRow> byKey, Long excludeNoteId) {
        String type = sel.sourceType() == null ? "" : sel.sourceType().trim().toUpperCase();
        if (!SOURCE_TYPES.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับประเภทรายการ '" + sel.sourceType() + "'");
        }
        if ("MANUAL".equals(type)) {
            if (sel.sourceId() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "รายการที่พิมพ์เองต้องไม่ระบุเอกสารอ้างอิง");
            }
            if (sel.manualDocNumber() == null || sel.manualDocNumber().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "รายการที่พิมพ์เองต้องระบุเลขที่เอกสาร");
            }
            if (sel.manualAmount() == null
                    || sel.manualAmount().setScale(2, RoundingMode.HALF_UP).signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "รายการที่พิมพ์เองต้องระบุจำนวนเงินมากกว่า 0");
            }
            return new BillingNoteRepository.ResolvedLine("MANUAL", null, null,
                sel.manualDocNumber(), sel.manualDocDate(), sel.manualDueDate(), sel.manualAmount(), sel.manualNote());
        }

        if (sel.sourceId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุเอกสารอ้างอิงสำหรับรายการประเภทนี้");
        }
        BillingNoteRepository.CandidateRow candidate = byKey.get(type + ":" + sel.sourceId());
        if (candidate == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ไม่พบเอกสารนี้สำหรับลูกค้ารายนี้ หรือเอกสารยังไม่ได้ออกเลข");
        }
        if (candidate.outstandingAmount() == null || candidate.outstandingAmount().signum() <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "เอกสาร " + candidate.docNumber() + " ชำระครบแล้ว ไม่สามารถวางบิลได้");
        }
        Optional<BillingNoteRepository.ClaimedBy> claim = stored.findLiveClaim(type, sel.sourceId());
        if (claim.isPresent() && !(excludeNoteId != null && claim.get().billingNoteId() == excludeNoteId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "เอกสาร " + candidate.docNumber() + " อยู่ในใบวางบิลอื่นอยู่แล้ว"
                + (claim.get().docNumber() != null ? " (เลขที่ " + claim.get().docNumber() + ")" : " (ร่าง)"));
        }
        LocalDate dueDate = sel.manualDueDate() != null ? sel.manualDueDate() : candidate.dueDate();
        return new BillingNoteRepository.ResolvedLine(type, candidate.sourceId(), candidate.ticketId(),
            candidate.docNumber(), candidate.docDate(), dueDate, candidate.outstandingAmount(), sel.manualNote());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** Converts a stored snapshot into the shape {@link BillingNoteRenderer} knows how to render —
     * the one conversion point, mirroring {@code RemainingInvoiceService#toRenderable}. */
    private BillingNoteDto toRenderable(BillingNoteDocumentDto doc) {
        List<BillingNoteLineRenderDto> lines = doc.lines().stream()
            .map(l -> new BillingNoteLineRenderDto(l.docNumber(), l.docDate(), l.dueDate(), l.amount(), l.note()))
            .toList();
        return new BillingNoteDto(doc.docNumber(), doc.billDate(), doc.customerName(), doc.customerBranch(),
            doc.customerAddress(), doc.customerTaxId(), doc.note(), doc.totalAmount(), lines);
    }

    private List<Long> distinctTicketIds(BillingNoteDocumentDto doc) {
        return doc.lines().stream().map(BillingNoteLineDto::ticketId).filter(Objects::nonNull).distinct().toList();
    }

    private String normaliseType(String type) {
        String t = type == null ? "" : type.trim().toUpperCase();
        if (!TYPES.contains(t)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุประเภทใบวางบิลเป็น GOODS หรือ FREIGHT");
        }
        return t;
    }

    private CustomerDto requireCustomer(long customerId) {
        return customers.findById(customerId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบลูกค้ารายนี้"));
    }

    private BillingNoteDocumentDto requireStored(long id) {
        return stored.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบวางบิลนี้"));
    }

    private BillingNoteDocumentDto requireDraft(long id) {
        BillingNoteDocumentDto doc = requireStored(id);
        if (!"DRAFT".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบวางบิลนี้ไม่ได้อยู่ในสถานะร่าง (DRAFT)");
        }
        return doc;
    }

    private BillingNoteDocumentDto requireIssued(long id) {
        BillingNoteDocumentDto doc = requireStored(id);
        if (!"ISSUED".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดำเนินการได้เฉพาะใบวางบิลที่ออกแล้ว (ISSUED)");
        }
        return doc;
    }

    /** {@link #file}'s own return shape — bytes plus the doc_number the caller needs for a
     * filename, so a controller never has to load/authorize the same row twice just to learn it. */
    public record RenderedFile(byte[] bytes, String docNumber) {}
}
