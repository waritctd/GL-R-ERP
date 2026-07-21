package th.co.glr.hr.orderconfirmation;

import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.ticket.TicketDto;

public final class OrderConfirmationDtos {
    private OrderConfirmationDtos() {}

    /**
     * The bridge action's own result: the ticket, post-{@code TicketService.confirmCustomer}
     * (status/paymentStatus/dealStage all reflect the bridge), alongside the pricing request's own
     * current view (now carrying {@code orderConfirmedAt}) — so the frontend never needs a second
     * round trip to see both halves of what just happened.
     */
    public record OrderConfirmationResultDto(TicketDto ticket, PricingRequestSummaryDto pricingRequest) {}
}
