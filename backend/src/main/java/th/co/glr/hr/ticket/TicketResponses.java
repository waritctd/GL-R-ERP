package th.co.glr.hr.ticket;

import java.util.List;

public final class TicketResponses {
    private TicketResponses() {}

    public record TicketListResponse(List<TicketSummaryDto> tickets, int page, int size, int total) {}
    public record TicketDetailResponse(TicketDto ticket) {}
    public record QuotationResponse(QuotationDto quotation) {}
    public record TicketActionsResponse(TicketActionState currentState, List<TicketActionDto> availableActions,
                                        List<StageDecisionDto> stageDecisions) {}

    /** One verdict per pipeline stage for the calling user on this deal — see TicketService.stageDecisions, which
     * produces every field by running the real gates. */
    public record StageDecisionDto(String stage, int no, boolean allowed, boolean requiresReason,
                                   String blockedReason, boolean onRoute) {}
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
