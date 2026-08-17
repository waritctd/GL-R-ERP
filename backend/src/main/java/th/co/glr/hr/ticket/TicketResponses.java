package th.co.glr.hr.ticket;

import java.util.List;

public final class TicketResponses {
    private TicketResponses() {}

    public record TicketListResponse(List<TicketSummaryDto> tickets, int page, int size, int total) {}
    public record TicketDetailResponse(TicketDto ticket) {}
    public record QuotationResponse(QuotationDto quotation) {}
    public record TicketActionsResponse(TicketActionState currentState, List<TicketActionDto> availableActions,
                                        List<StageDecisionDto> stageDecisions) {}

    /**
     * One verdict per pipeline stage for the calling user on this deal — see
     * {@code TicketService.stageDecisions}, which produces every field by running the real gates.
     *
     * @param stage          the {@link DealStage} code
     * @param no             1-based DISPLAY sequence (see {@code DealStage.displayNoOf}); NOT the
     *                       S-number, which is {@code businessCode} on the stage catalog
     * @param allowed        whether a manual {@code updateStage} to this stage would be accepted
     * @param requiresReason whether the move would additionally need a written justification —
     *                       reported independently of {@code allowed}, because the note rule does
     *                       not depend on the gates
     * @param blockedReason  the refused call's own Thai message when {@code allowed} is false,
     *                       else null. Deliberately server-supplied copy: only the service knows
     *                       why a given stage is refused.
     */
    public record StageDecisionDto(String stage, int no, boolean allowed, boolean requiresReason,
                                   String blockedReason) {}
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
