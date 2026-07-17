package th.co.glr.hr.ticket;

import java.util.List;
import th.co.glr.hr.pricing.PriceBreakdownItemDto;

public final class TicketResponses {
    private TicketResponses() {}

    public record TicketListResponse(List<TicketSummaryDto> tickets, int page, int size, int total) {}
    public record TicketDetailResponse(TicketDto ticket) {}
    public record QuotationResponse(QuotationDto quotation) {}
    public record CalculatePricesResponse(TicketDto ticket, List<PriceBreakdownItemDto> breakdown) {}
    public record TicketActionsResponse(TicketActionState currentState, List<TicketActionDto> availableActions) {}
    public record TicketActionState(String lifecycle, String salesStage, String paymentStatus,
                                    String fulfillmentStatus, String status) {}
    public record TicketActionDto(String action, String kind, String label, String targetStage,
                                  List<String> requiredFields) {
        public TicketActionDto(String action, String kind, String label) {
            this(action, kind, label, null, List.of());
        }
        public TicketActionDto(String action, String kind, String label, List<String> requiredFields) {
            this(action, kind, label, null, requiredFields);
        }
        public TicketActionDto(String action, String kind, String label, String targetStage) {
            this(action, kind, label, targetStage, List.of());
        }
    }
}
