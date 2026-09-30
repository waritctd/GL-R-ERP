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
}
