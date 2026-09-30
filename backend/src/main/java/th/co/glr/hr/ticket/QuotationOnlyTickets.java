package th.co.glr.hr.ticket;

import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/**
 * GLA-136 (owner ruling, Ploy 2026-09-30): the ONE refusal every manual pipeline write shares for
 * a quotation-only container ticket ({@code sales.ticket.quotation_only}, V193).
 *
 * <p>A direct (DEAL_DIRECT) quotation written at {@code /quotations/new} is quotation-only — NOT a
 * pipeline deal — but {@code sales.quotation.ticket_id} is NOT NULL, so it still hangs off a
 * {@code sales.ticket} row. That row must not be advanced by hand (a stage move, an item edit, a
 * pricing request, an entry-channel/tender change): the only way into the pipeline is promoting an
 * APPROVED direct quotation ({@code DealQuotationService#promoteToDeal}), which lands the deal at
 * ORDER_RECEIVED with the quotation's own lines. Quotation CRUD itself is deliberately NOT gated
 * here — writing, submitting and approving the quotation is the whole point of the container.
 *
 * <p>A static helper rather than a {@code TicketService} method because
 * {@code PricingRequestService} needs the same refusal and must not depend on
 * {@code TicketService} (that would be a bean cycle — see {@code TicketService}'s constructor
 * comment). One predicate, one message, every caller.
 */
public final class QuotationOnlyTickets {

    /** Shown verbatim to the rep (409). Says what to do, not only what is wrong. */
    public static final String REFUSAL_MESSAGE =
        "ดีลนี้เป็นดีลใบเสนอราคาเท่านั้น — ยังไม่เข้า pipeline กรุณาสร้างดีลจากใบเสนอราคาที่อนุมัติแล้วก่อน";

    private QuotationOnlyTickets() {}

    /** 409 when {@code summary} is a quotation-only container; a no-op for every pipeline deal. */
    public static void requirePipelineDeal(TicketSummaryDto summary) {
        if (summary.quotationOnly()) {
            throw new ApiException(HttpStatus.CONFLICT, REFUSAL_MESSAGE);
        }
    }
}
