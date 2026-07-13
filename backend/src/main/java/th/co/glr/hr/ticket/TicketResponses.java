package th.co.glr.hr.ticket;

import java.util.List;
import th.co.glr.hr.pricing.PriceBreakdownItemDto;

public final class TicketResponses {
    private TicketResponses() {}

    public record TicketListResponse(List<TicketSummaryDto> tickets, int page, int size, int total) {}
    public record TicketDetailResponse(TicketDto ticket) {}
    public record QuotationResponse(QuotationDto quotation) {}
    public record CalculatePricesResponse(TicketDto ticket, List<PriceBreakdownItemDto> breakdown) {}
}
