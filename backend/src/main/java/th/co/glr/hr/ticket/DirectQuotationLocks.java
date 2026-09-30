package th.co.glr.hr.ticket;

import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/**
 * Quotation ↔ deal linking, slice 1 (IA §7, owner decisions approved 2026-09-30): the ONE refusal
 * shared by the two deal writes that must not happen while the deal has a LIVE direct quotation —
 * editing the deal's own lines ({@code TicketService#editItems}) and opening a pricing request
 * ({@code PricingRequestService#createDraft}).
 *
 * <p>"Live" = a {@code DEAL_DIRECT} quotation on the deal whose {@code doc_status} is
 * {@code DRAFT}, {@code PENDING_APPROVAL} or {@code APPROVED}
 * ({@link TicketRepository#hasLiveDirectQuotation}). While one exists:
 * <ul>
 *   <li>the deal's lines live on that quotation (they become {@code sales.ticket_item} when the
 *       order is confirmed from it), so a hand edit here would silently diverge from the document
 *       the customer holds;</li>
 *   <li>the deal is priced by hand on that quotation, and a คำขอราคา on top would create the
 *       mixed-origin deal nobody has ever produced.</li>
 * </ul>
 * Cancelling (or the approval sweep superseding) the direct quotation lifts the lock.
 *
 * <p>This REPLACES GLA-136's {@code QuotationOnlyTickets.requirePipelineDeal}, which keyed the same
 * two refusals (plus stage / entry-channel / tender, now removed) on the {@code quotation_only}
 * flag. That flag is provenance only since this slice; nothing reads it.
 *
 * <p>A static helper taking the repository rather than a {@code TicketService} method because
 * {@code PricingRequestService} needs the same refusal and must not depend on {@code TicketService}
 * (that would be a bean cycle — see {@code TicketService}'s constructor comment). Both callers
 * already hold a {@link TicketRepository}. One predicate, one message, every caller.
 *
 * <p>Slice 2 adds the two refusals on CREATING a direct quotation, here for the same reasons (one
 * place, no bean cycle — {@code DealQuotationService} holds a {@link TicketRepository} too):
 * {@link #requireNoLivePricingRequest} (one pricing route per deal — the reverse direction of the
 * lock above) and {@link #requireNoLiveDirectQuotationForCreate} (N6, the same "live" definition as
 * the lock above, with the live row named in the 409 body).
 */
public final class DirectQuotationLocks {

    /** Shown verbatim to the rep (409). Says what to do, not only what is wrong. */
    public static final String REFUSAL_MESSAGE =
        "ดีลนี้มีใบเสนอราคาตรงที่ยังใช้งานอยู่ — แก้ไขรายการที่ใบเสนอราคา หรือยกเลิกใบเสนอราคาก่อน";

    private DirectQuotationLocks() {}

    /** 409 when {@code ticketId} has a live DEAL_DIRECT quotation; a no-op otherwise. Callers run
     * their own authz check FIRST, so a caller with no access gets the 403, never this 409. */
    public static void requireNoLiveDirectQuotation(TicketRepository tickets, long ticketId) {
        if (tickets.hasLiveDirectQuotation(ticketId)) {
            throw new ApiException(HttpStatus.CONFLICT, REFUSAL_MESSAGE);
        }
    }

    // ── Slice 2 — the two refusals on CREATING a direct quotation ─────────────────────────────────

    /** Shown verbatim to the rep (409) — word for word the frontend's
     * {@code quotationMeta.LIVE_PRICING_REQUEST_BLOCK_MESSAGE}, which disables บันทึกร่าง with it
     * before the rep ever reaches this refusal. */
    public static final String LIVE_PRICING_REQUEST_MESSAGE =
        "ดีลนี้มีคำขอราคาที่ยังดำเนินการอยู่ — ใช้ใบเสนอราคาจากคำขอราคา หรือยกเลิกคำขอราคาก่อน";

    /**
     * One pricing route per deal, the REVERSE direction of {@link #requireNoLiveDirectQuotation}
     * (owner ruling 2026-09-30): 409 when {@code ticketId} has a live pricing request
     * ({@link TicketRepository#hasLivePricingRequest} — any status but CANCELLED / SUPERSEDED); a
     * no-op otherwise. Called ONLY by {@code DealQuotationService#create} — a brand-new direct
     * quotation. A revision or reorder of an existing direct quotation, and
     * {@code createFromPricingRequest} (which IS the pricing route), never call it.
     */
    public static void requireNoLivePricingRequest(TicketRepository tickets, long ticketId) {
        if (tickets.hasLivePricingRequest(ticketId)) {
            throw new ApiException(HttpStatus.CONFLICT, LIVE_PRICING_REQUEST_MESSAGE);
        }
    }

    /** N6's sentence, with the live quotation's number filled in — word for word the frontend's
     * {@code quotationMeta.liveDirectQuotationBlockMessage}. */
    public static String liveDirectQuotationMessage(String number) {
        return "ดีลนี้มีใบเสนอราคาตรงที่ใช้งานอยู่ (" + number + ") — แก้ไขฉบับนั้น หรือสร้างฉบับแก้ไขแทนการออกเลขใหม่";
    }

    /**
     * N6 (S2-B3): 409 when {@code ticketId} already has a live direct quotation, naming the NEWEST
     * one ({@link TicketRepository#findLiveDirectQuotation} — the same row
     * {@link TicketSummaryDto#liveDirectQuotation} serves) in the error body's top-level
     * {@code liveQuotationId} / {@code number} / {@code docStatus}, so the client can offer
     * "แก้ไขฉบับนั้น" / "สร้างฉบับแก้ไข" instead of minting a second base number. A no-op otherwise.
     * Same "live" definition as {@link #requireNoLiveDirectQuotation}; called ONLY by
     * {@code DealQuotationService#create}.
     */
    public static void requireNoLiveDirectQuotationForCreate(TicketRepository tickets, long ticketId) {
        tickets.findLiveDirectQuotation(ticketId).ifPresent(live -> {
            java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("liveQuotationId", live.id());
            details.put("number", live.number());
            details.put("docStatus", live.docStatus());
            throw new ApiException(HttpStatus.CONFLICT, liveDirectQuotationMessage(live.number()), details);
        });
    }
}
