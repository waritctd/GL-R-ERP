package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * The STORED ใบแจ้งหนี้ส่วนที่เหลือ (GLA-99 step 2, {@code sales.remaining_invoice}) —
 * DRAFT → ISSUED → SUPERSEDED, minted on the shared {@code sales.document_sequence} (doc_type
 * {@code AR_GLR}, format {@code GLR<yy><5-digit seq>}, versioned {@code -<n>} suffix).
 *
 * <p><b>This class computes NOTHING new.</b> Every content rule (which quotation qualifies, where
 * items/the deposit deduction come from, VAT, the 22-row/6-note capacity ceilings, the negative-net
 * refusal) is {@link DepositNoticeService}'s own {@code resolveRemainingInvoice} pipeline, reused
 * here through the package-private {@link DepositNoticeService#resolveRemainingInvoiceSnapshot}
 * wrapper — the same one the stateless preview ({@code getRemainingInvoiceOptions}/
 * {@code getRemainingInvoiceXlsx}) already runs through. This class only decides WHEN to freeze
 * that computation's output into a durable row (create/update a DRAFT) and WHEN to mint a real
 * number over it (issue), then renders an already-issued document straight from its own frozen
 * snapshot ({@link #toRenderable}) rather than ever recomputing it live.
 *
 * <p><b>Authorisation (owner ruling 2026-09-20) is likewise not reinvented here</b> —
 * create/update/issue/revise/delete all go through {@link
 * DepositNoticeService#requireDepositNoticeIssueGate}, EXACTLY the predicate {@link
 * DepositNoticeService#issue} itself uses to gate issuing the sibling deposit-notice document
 * (sales-role + ticket-ownership); read/list/download go through {@link
 * DepositNoticeService#requireTicketViewer}, the existing viewer gate. Neither role set is copied
 * into this class — both are called on the {@link DepositNoticeService} instance directly.
 */
@Service
public class RemainingInvoiceService {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final RemainingInvoiceRepository stored;
    private final DepositNoticeService depositNoticeService;
    private final TicketRepository tickets;
    private final RemainingInvoiceRenderer renderer;

    public RemainingInvoiceService(RemainingInvoiceRepository stored, DepositNoticeService depositNoticeService,
            TicketRepository tickets, RemainingInvoiceRenderer renderer) {
        this.stored = stored;
        this.depositNoticeService = depositNoticeService;
        this.tickets = tickets;
        this.renderer = renderer;
    }

    // ── Reads (viewer gate) ──────────────────────────────────────────────────────────────────

    public List<RemainingInvoiceDocumentDto> list(long ticketId, UserPrincipal actor) {
        depositNoticeService.requireTicketViewer(ticketId, actor);
        return stored.findByTicket(ticketId);
    }

    public RemainingInvoiceDocumentDto get(long id, UserPrincipal actor) {
        RemainingInvoiceDocumentDto doc = requireStored(id);
        depositNoticeService.requireTicketViewer(doc.ticketId(), actor);
        return doc;
    }

    /** Renders THIS document's own frozen snapshot — never a live recompute, and available
     * regardless of status (a DRAFT downloads its own in-progress content for review; an ISSUED or
     * SUPERSEDED copy downloads exactly what was actually issued). See {@link #toRenderable}. */
    public byte[] file(long id, UserPrincipal actor) {
        RemainingInvoiceDocumentDto doc = requireStored(id);
        depositNoticeService.requireTicketViewer(doc.ticketId(), actor);
        try {
            return renderer.toXlsx(toRenderable(doc));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "สร้างไฟล์ Excel ไม่สำเร็จ: " + e.getMessage());
        }
    }

    // ── Writes (deposit-notice issue gate) ──────────────────────────────────────────────────

    /** Snapshots the EXISTING resolution ({@code quotationId} optional — {@code null} picks the
     * newest qualifying quotation, exactly as the stateless preview does) into a new DRAFT row.
     * Refuses (409) whatever the stateless preview would refuse — no qualifying source, a negative
     * net, over item/note capacity — and refuses (409) a second live (DRAFT or ISSUED) row on this
     * DEAL up front, as a clear message rather than a raw constraint violation. P1/P2 (Opus
     * review, GLA-99 step 2 review-round-2, 2026-09-20): that refusal is now also a REAL database
     * invariant (V188's {@code ux_remaining_invoice_ticket_draft}/{@code _issued}, one row of each
     * status per {@code ticket_id} — not per chain), so a race that slips past the checks below
     * still cannot leave two live rows; {@code insertDraft}'s own {@link
     * DataIntegrityViolationException} is caught and mapped to the same 409 {@link #revise} already
     * maps its own race to, rather than surfacing an unmapped 500. */
    @Transactional
    public RemainingInvoiceDocumentDto createDraft(long ticketId, RemainingInvoiceDraftRequest req, UserPrincipal actor) {
        depositNoticeService.requireDepositNoticeIssueGate(ticketId, actor);

        DepositNoticeService.RemainingInvoiceSnapshot snap =
            depositNoticeService.resolveRemainingInvoiceSnapshot(ticketId, req == null ? null : req.quotationId(), actor);
        depositNoticeService.requireActiveLifecycle(snap.ticketSummary());
        DepositNoticeService.ResolvedRemainingInvoice resolved = snap.resolved();
        if (resolved.blockingReason() != null) {
            throw new ApiException(HttpStatus.CONFLICT, resolved.blockingReason());
        }

        // The quotation actually used: a caller-supplied id (already validated against this
        // ticket's own qualifying set by resolveRemainingInvoiceSnapshot -> resolveRemainingInvoice
        // itself, 400 otherwise), or — when omitted — resolved.defaultQuotationId(), which is
        // computed as "the newest qualifying quotation" BEFORE resolveRemainingInvoice ever looks
        // at the caller's own id, i.e. is exactly what an omitted id resolves to. See
        // RemainingInvoiceOptionsDto's own quotationOptions/defaultQuotationId Javadoc.
        Long quotationId = (req != null && req.quotationId() != null) ? req.quotationId() : resolved.defaultQuotationId();

        List<RemainingInvoiceDocumentDto> existingForTicket = stored.findByTicket(ticketId);
        boolean sameChainLive = existingForTicket.stream()
            .anyMatch(d -> ("DRAFT".equals(d.status()) || "ISSUED".equals(d.status()))
                && Objects.equals(d.customerQuotationId(), quotationId));
        if (sameChainLive) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลนี้มีใบแจ้งหนี้ส่วนที่เหลือ (ร่างหรือออกแล้ว) สำหรับใบเสนอราคานี้อยู่แล้ว");
        }
        // O2 (owner ruling 2026-09-20): ONE live remaining invoice per DEAL, not per chain — a
        // DRAFT for a DIFFERENT quotation chain is also refused, even though issuing it would be
        // legal (issue() supersedes any other ISSUED sibling regardless of chain, see below).
        // Two unresolved DRAFTS on the same deal at once is what this blocks; an ISSUED sibling of
        // a different chain is fine to draft against (that IS the "new chain replaces the old
        // one" flow O2 describes) — sameChainLive above already refuses re-drafting the SAME
        // chain's own ISSUED/DRAFT row.
        boolean anotherDraftLive = existingForTicket.stream()
            .anyMatch(d -> "DRAFT".equals(d.status()) && !Objects.equals(d.customerQuotationId(), quotationId));
        if (anotherDraftLive) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลนี้มีร่างใบแจ้งหนี้ส่วนที่เหลืออยู่แล้ว (ใบเสนอราคาอื่น) กรุณาออกหรือลบร่างเดิมก่อน");
        }

        List<RemainingInvoiceItemDto> items = resolved.items();
        BigDecimal depositAmount = nz(resolved.depositAmount());
        requireCapacity(items, depositAmount);
        List<String> notes = req != null && req.notes() != null ? req.notes() : defaultNotes();
        requireNotesCapacity(notes);

        BigDecimal itemsTotal = sumAmounts(items);
        DepositNoticeService.RemainingInvoiceMoney money = depositNoticeService.computeRemainingInvoiceMoney(itemsTotal, depositAmount);

        DepositNoticeService.CustomerHeaderInfo header = snap.header();
        TicketSummaryDto s = snap.ticketSummary();
        String reference = req != null && req.reference() != null ? req.reference() : resolved.defaultReference();
        String depositReference = req != null && req.depositReference() != null
            ? req.depositReference() : resolved.defaultDepositReference();
        LocalDate docDate = req != null && req.docDate() != null ? req.docDate() : LocalDate.now(BANGKOK);

        // P2 (Opus review, GLA-99 step 2 review-round-2): this is the SAME SELECT-then-INSERT shape
        // #revise already documents a race for — the two checks above (sameChainLive/
        // anotherDraftLive) can both read "clear" before either of two concurrent createDraft calls
        // commits, and the SECOND insertDraft then trips one of V188's own partial unique indexes
        // (ux_remaining_invoice_ticket_draft, now per-DEAL since P1) as a raw Postgres unique
        // violation. Map it to the same 409 #revise's own race maps to, never an unmapped 500.
        long id;
        try {
            id = stored.insertDraft(ticketId, quotationId, resolved.matchedDepositNoticeId(),
                reference, depositReference, docDate, notes,
                s.customerName(), header.taxId(), header.branch(), header.addressOnly(), header.projectName(),
                itemsTotal, depositAmount, money.netAmount(), money.vatAmount(), money.grandTotal(), actor.id(), actor.name());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลนี้มีร่างใบแจ้งหนี้ส่วนที่เหลืออยู่แล้ว กรุณาออกหรือลบร่างเดิมก่อน");
        }
        stored.replaceItems(id, items);
        return requireStored(id);
    }

    /** Re-snapshots items/deposit-deduction/customer header from the draft's OWN already-chosen
     * source quotation (never re-selects a different one — plan: "re-snapshot items from latest
     * issued deposit notice"), then applies whichever dialog fields the request actually supplied,
     * carrying every omitted one forward from the existing row. */
    @Transactional
    public RemainingInvoiceDocumentDto updateDraft(long id, RemainingInvoiceDraftRequest req, UserPrincipal actor) {
        RemainingInvoiceDocumentDto existing = requireDraft(id);
        depositNoticeService.requireDepositNoticeIssueGate(existing.ticketId(), actor);

        DepositNoticeService.RemainingInvoiceSnapshot snap = depositNoticeService.resolveRemainingInvoiceSnapshot(
            existing.ticketId(), existing.customerQuotationId(), actor);
        DepositNoticeService.ResolvedRemainingInvoice resolved = snap.resolved();
        if (resolved.blockingReason() != null) {
            throw new ApiException(HttpStatus.CONFLICT, resolved.blockingReason());
        }

        List<RemainingInvoiceItemDto> items = resolved.items();
        BigDecimal depositAmount = nz(resolved.depositAmount());
        requireCapacity(items, depositAmount);
        List<String> notes = req != null && req.notes() != null ? req.notes() : existing.notes();
        requireNotesCapacity(notes);

        BigDecimal itemsTotal = sumAmounts(items);
        DepositNoticeService.RemainingInvoiceMoney money = depositNoticeService.computeRemainingInvoiceMoney(itemsTotal, depositAmount);

        DepositNoticeService.CustomerHeaderInfo header = snap.header();
        TicketSummaryDto s = snap.ticketSummary();
        stored.updateSnapshot(id, resolved.matchedDepositNoticeId(), s.customerName(), header.taxId(),
            header.branch(), header.addressOnly(), header.projectName(),
            itemsTotal, depositAmount, money.netAmount(), money.vatAmount(), money.grandTotal());
        stored.replaceItems(id, items);

        String reference = req != null && req.reference() != null ? req.reference() : existing.reference();
        String depositReference = req != null && req.depositReference() != null
            ? req.depositReference() : existing.depositReference();
        LocalDate docDate = req != null && req.docDate() != null ? req.docDate() : existing.docDate();
        stored.updateDraftFields(id, reference, depositReference, docDate, notes);

        return requireStored(id);
    }

    /** DRAFT → ISSUED: mints (first issue) or carries forward (revision) the {@code base_number},
     * bumps {@code version}, computes {@code doc_number = base_number + "-" + version}, and
     * supersedes the predecessor this issue replaces — same shape {@code
     * ImportRequestService#issue}/{@code DepositNoticeService#issue} already document, except
     * numbering: this document keeps ONE base_number across every revision (only the version
     * suffix moves), where those two mint an entirely new number on every issue.
     *
     * <p><b>O3 (owner ruling 2026-09-20):</b> before freezing, this method RE-SNAPSHOTS
     * items/deduction/customer/money from the latest ISSUED deposit notice (+ this draft's own
     * already-chosen accepted quotation — never re-selects a different chain, same discipline
     * {@link #updateDraft} already follows) through the exact same {@code
     * resolveRemainingInvoiceSnapshot} computation createDraft/updateDraft use — a draft that has
     * gone stale since it was created/last edited (the source deposit notice was re-issued at a
     * different amount, say) is never issued with its own outdated numbers. The negative-net/
     * capacity refusals this resolution can produce apply HERE, at issue, exactly as they do at
     * create/update time. Only the dialog fields the caller already typed (reference/
     * depositReference/docDate/notes) are preserved verbatim — those are not recomputed.
     *
     * <p><b>O2 (owner ruling 2026-09-20):</b> ONE live remaining invoice per DEAL — issuing
     * supersedes EVERY other currently-ISSUED remaining invoice on this ticket, not merely this
     * chain's own predecessor (which only {@link RemainingInvoiceRepository#lockIssuedPredecessor}
     * — used for NUMBERING, i.e. "is this a revision of the SAME chain" — can see). A sibling
     * chain's own ISSUED row is superseded even though it shares no {@code base_number} with this
     * one; the superseded row is untouched otherwise and stays downloadable. */
    @Transactional
    public RemainingInvoiceDocumentDto issue(long id, UserPrincipal actor) {
        RemainingInvoiceDocumentDto draft = requireDraft(id);
        depositNoticeService.requireDepositNoticeIssueGate(draft.ticketId(), actor);
        TicketSummaryDto s = requireTicketSummary(draft.ticketId());
        depositNoticeService.requireActiveLifecycle(s);

        // O3: refresh the snapshot from the current live state before freezing anything.
        DepositNoticeService.RemainingInvoiceSnapshot snap = depositNoticeService.resolveRemainingInvoiceSnapshot(
            draft.ticketId(), draft.customerQuotationId(), actor);
        DepositNoticeService.ResolvedRemainingInvoice resolved = snap.resolved();
        if (resolved.blockingReason() != null) {
            throw new ApiException(HttpStatus.CONFLICT, resolved.blockingReason());
        }
        List<RemainingInvoiceItemDto> items = resolved.items();
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบแจ้งหนี้ส่วนที่เหลือยังไม่มีรายการสินค้า");
        }
        BigDecimal depositAmount = nz(resolved.depositAmount());
        requireCapacity(items, depositAmount);
        requireNotesCapacity(draft.notes());

        BigDecimal itemsTotal = sumAmounts(items);
        DepositNoticeService.RemainingInvoiceMoney money = depositNoticeService.computeRemainingInvoiceMoney(itemsTotal, depositAmount);
        DepositNoticeService.CustomerHeaderInfo header = snap.header();
        TicketSummaryDto snapSummary = snap.ticketSummary();

        // Freeze the REFRESHED content onto this row before minting — the user-typed dialog
        // fields (reference/depositReference/docDate/notes) are untouched by this call.
        stored.updateSnapshot(id, resolved.matchedDepositNoticeId(), snapSummary.customerName(), header.taxId(),
            header.branch(), header.addressOnly(), header.projectName(),
            itemsTotal, depositAmount, money.netAmount(), money.vatAmount(), money.grandTotal());
        stored.replaceItems(id, items);

        LocalDate today = LocalDate.now(BANGKOK);
        int thaiYear = today.getYear() + 543;

        // Number is minted/carried INSIDE this transaction — same "a refused issue rolls the
        // number back, never wastes it" property DepositNoticeRepository#nextDocNumber documents.
        // lockIssuedPredecessor is scoped to THIS chain (same customer_quotation_id) — it answers
        // "is this a revision", not "is there any other live sibling" (that is O2's separate
        // supersede-everyone-else pass below).
        Optional<RemainingInvoiceRepository.PredecessorNumber> predecessor =
            stored.lockIssuedPredecessor(draft.ticketId(), draft.customerQuotationId());
        String baseNumber;
        int version;
        if (predecessor.isPresent()) {
            baseNumber = predecessor.get().baseNumber();
            version = predecessor.get().version() + 1;
        } else {
            baseNumber = stored.nextDocNumber(thaiYear);
            version = 1;
        }
        String docNumber = baseNumber + "-" + version;

        // O2: supersede EVERY other currently-ISSUED remaining invoice on this ticket, whatever
        // chain it belongs to — not just this chain's own predecessor. Done BEFORE this row's own
        // compare-and-set (same forced ordering V154's own Javadoc explains at length for the
        // same-chain case: two rows must never both read ISSUED, not even for the instant between
        // two separate writes).
        for (RemainingInvoiceDocumentDto sibling : stored.findByTicket(draft.ticketId())) {
            if (sibling.id() != id && "ISSUED".equals(sibling.status())) {
                stored.supersede(sibling.id(), id);
            }
        }

        int rows = stored.issue(id, baseNumber, version, docNumber, actor.id(), actor.name());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบแจ้งหนี้ส่วนที่เหลือนี้ถูกออกไปแล้วโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }

        tickets.addEvent(draft.ticketId(), actor.id(), actor.name(), TicketEventKind.DOCUMENT_ISSUED,
            s.status(), s.status(), "ใบแจ้งหนี้ส่วนที่เหลือ " + docNumber + " ออกแล้ว");

        return requireStored(id);
    }

    /** Prepares a correction: a new DRAFT copying the ISSUED document's own body/items VERBATIM
     * (not a live re-snapshot — {@link #updateDraft} is how a corrector then refreshes the content
     * before re-issuing). The form being corrected STAYS ISSUED until the replacement is actually
     * issued — see {@link #issue}.
     *
     * <p><b>P3 (Opus review, GLA-99 step 2 review-round-2, 2026-09-20):</b> refuses (409) a live
     * DRAFT for a DIFFERENT quotation chain on this same deal too, not only a same-chain one —
     * the identical cross-chain refusal {@link #createDraft}'s own {@code anotherDraftLive} check
     * applies, now enforceable as a real DB invariant since P1 scoped
     * {@code ux_remaining_invoice_ticket_draft} down to one DRAFT per {@code ticket_id}, not per
     * (ticket, quotation). Without this, revise() could insert a second DRAFT belonging to a
     * different chain than any existing one and immediately trip that index as an unmapped
     * constraint violation instead of the clear 409 below. */
    @Transactional
    public RemainingInvoiceDocumentDto revise(long id, UserPrincipal actor) {
        RemainingInvoiceDocumentDto issued = requireIssued(id);
        depositNoticeService.requireDepositNoticeIssueGate(issued.ticketId(), actor);
        TicketSummaryDto s = requireTicketSummary(issued.ticketId());
        depositNoticeService.requireActiveLifecycle(s);

        List<RemainingInvoiceDocumentDto> existingForTicket = stored.findByTicket(issued.ticketId());
        boolean hasLiveDraft = existingForTicket.stream()
            .anyMatch(d -> "DRAFT".equals(d.status()) && Objects.equals(d.customerQuotationId(), issued.customerQuotationId()));
        if (hasLiveDraft) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มีร่างฉบับแก้ไขของใบแจ้งหนี้ส่วนที่เหลือนี้อยู่แล้ว กรุณาออกหรือลบร่างเดิมก่อน");
        }
        // P3: cross-chain DRAFT refusal, mirroring createDraft's own anotherDraftLive check — O2 is
        // ONE live remaining invoice per DEAL, not per chain.
        boolean anotherDraftLive = existingForTicket.stream()
            .anyMatch(d -> "DRAFT".equals(d.status()) && !Objects.equals(d.customerQuotationId(), issued.customerQuotationId()));
        if (anotherDraftLive) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลนี้มีร่างใบแจ้งหนี้ส่วนที่เหลืออยู่แล้ว (ใบเสนอราคาอื่น) กรุณาออกหรือลบร่างเดิมก่อน");
        }

        // Nit fix (Opus review, GLA-99 step 2 review-round-1): the two checks above are a plain
        // SELECT-then-INSERT — two concurrent revise() calls (or a concurrent createDraft) can both
        // read "no live draft" before either commits, and the SECOND insertDraft then trips
        // ux_remaining_invoice_ticket_draft (V188, one DRAFT per ticket, per P1) as a raw Postgres
        // unique-violation. ApiExceptionHandler has no generic mapping for that, so it would
        // otherwise surface as an opaque 500 instead of the 409 this exact race deserves — the
        // loser simply lost a race for a slot the winner legitimately holds.
        long draftId;
        try {
            draftId = stored.insertDraft(issued.ticketId(), issued.customerQuotationId(), issued.depositNoticeId(),
                issued.reference(), issued.depositReference(), issued.docDate(), issued.notes(),
                issued.customerName(), issued.customerTaxId(), issued.customerBranch(), issued.customerAddress(),
                issued.projectName(), issued.itemsTotal(), issued.depositDeduction(), issued.netAmount(),
                issued.vatAmount(), issued.grandTotal(), actor.id(), actor.name());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มีร่างฉบับแก้ไขของใบแจ้งหนี้ส่วนที่เหลือนี้อยู่แล้ว กรุณาออกหรือลบร่างเดิมก่อน");
        }
        stored.replaceItems(draftId, issued.items().stream()
            .map(it -> new RemainingInvoiceItemDto(it.seq(), it.description(), it.qty(), it.unit(),
                it.unitPrice(), it.discountLabel(), it.netUnitPrice(), it.amount()))
            .toList());
        return requireStored(draftId);
    }

    /** A draft that should not exist is deleted, not tombstoned — it has no number and no audit
     * weight, matching {@code ImportRequestService#deleteDraft}'s own reasoning. */
    @Transactional
    public void deleteDraft(long id, UserPrincipal actor) {
        RemainingInvoiceDocumentDto existing = requireDraft(id);
        depositNoticeService.requireDepositNoticeIssueGate(existing.ticketId(), actor);
        if (stored.deleteDraft(id) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ลบได้เฉพาะใบแจ้งหนี้ส่วนที่เหลือที่ยังไม่ออกเลข (DRAFT)");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** Converts a stored snapshot back into the shape {@link RemainingInvoiceRenderer} already
     * knows how to render — the ONE conversion point, so the renderer itself never needs to know
     * whether it is rendering a live preview or a frozen stored snapshot. */
    private RemainingInvoiceDto toRenderable(RemainingInvoiceDocumentDto doc) {
        List<RemainingInvoiceItemDto> items = (doc.items() != null ? doc.items() : List.<RemainingInvoiceDocumentItemDto>of())
            .stream()
            .map(it -> new RemainingInvoiceItemDto(it.seq(), it.description(), it.qty(), it.unit(),
                it.unitPrice(), it.discountLabel(), it.netUnitPrice(), it.amount()))
            .toList();
        return new RemainingInvoiceDto(
            doc.docNumber() != null ? doc.docNumber() : "(ร่าง)",
            doc.docDate(), doc.reference(), doc.depositReference(),
            doc.customerName(), doc.customerBranch(), doc.customerAddress(), doc.customerTaxId(),
            doc.projectName(), nz(doc.depositDeduction()), doc.notes(), items);
    }

    private List<String> defaultNotes() {
        return depositNoticeService.getNoteTemplates().stream()
            .filter(DocumentNoteTemplateDto::defaultSelected)
            .map(DocumentNoteTemplateDto::text)
            .toList();
    }

    private void requireCapacity(List<RemainingInvoiceItemDto> items, BigDecimal depositAmount) {
        int depositRowCount = depositAmount.compareTo(BigDecimal.ZERO) != 0 ? 1 : 0;
        if (items.size() + depositRowCount > RemainingInvoiceRenderer.MAX_ITEM_ROWS) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบแจ้งหนี้ส่วนที่เหลือมีรายการ " + items.size() + " รายการ"
                + (depositRowCount > 0 ? " บวกแถวหักมัดจำ" : "")
                + " เกินความจุของแบบฟอร์ม (สูงสุด " + RemainingInvoiceRenderer.MAX_ITEM_ROWS + " แถว)"
                + " กรุณารวมรายการหรือออกเอกสารหลายฉบับ");
        }
    }

    private void requireNotesCapacity(List<String> notes) {
        if (RemainingInvoiceRenderer.wrapNotes(notes).size() > RemainingInvoiceRenderer.MAX_NOTE_LINES) {
            throw new ApiException(HttpStatus.CONFLICT,
                "หมายเหตุที่เลือกยาวเกินพื้นที่ในแบบฟอร์ม (สูงสุด " + RemainingInvoiceRenderer.MAX_NOTE_LINES
                + " บรรทัด) กรุณาลดจำนวนหรือย่อข้อความหมายเหตุ");
        }
    }

    private BigDecimal sumAmounts(List<RemainingInvoiceItemDto> items) {
        return items.stream().map(it -> it.amount() != null ? it.amount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private TicketSummaryDto requireTicketSummary(long ticketId) {
        return tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"))
            .summary();
    }

    private RemainingInvoiceDocumentDto requireStored(long id) {
        return stored.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบแจ้งหนี้ส่วนที่เหลือนี้"));
    }

    private RemainingInvoiceDocumentDto requireDraft(long id) {
        RemainingInvoiceDocumentDto doc = requireStored(id);
        if (!"DRAFT".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบแจ้งหนี้ส่วนที่เหลือนี้ไม่ได้อยู่ในสถานะร่าง (DRAFT)");
        }
        return doc;
    }

    private RemainingInvoiceDocumentDto requireIssued(long id) {
        RemainingInvoiceDocumentDto doc = requireStored(id);
        if (!"ISSUED".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ออกฉบับแก้ไขได้เฉพาะใบแจ้งหนี้ส่วนที่เหลือที่ออกเลขแล้ว");
        }
        return doc;
    }
}
