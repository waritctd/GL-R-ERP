package th.co.glr.hr.orderconfirmation;

import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.ticket.TicketDto;

public final class OrderConfirmationDtos {
    private OrderConfirmationDtos() {}

    /** The bridge action's own result: the ticket, post-TicketService.confirmCustomer, alongside the pricing
     * request's own current view — so the frontend never needs a second round trip to see both halves of what just
     * happened. */
    public record OrderConfirmationResultDto(TicketDto ticket, PricingRequestSummaryDto pricingRequest) {}
}
