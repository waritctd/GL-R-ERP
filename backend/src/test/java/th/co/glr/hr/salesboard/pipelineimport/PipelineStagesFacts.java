package th.co.glr.hr.salesboard.pipelineimport;

import java.util.Set;
import java.util.function.UnaryOperator;
import th.co.glr.hr.ticket.TicketService;

/** Fact gates handed to PipelineStages#stageFor in tests: a brand-new deal's (the import's real case), and one where
 * every fact holds (to test the route rule alone). */
final class PipelineStagesFacts {
    static final UnaryOperator<String> FRESH_DEAL = stage -> TicketService.stageFactRefusal(null, null, Set.of(), stage);
    static final UnaryOperator<String> ALL_HOLD = stage -> null;

    private PipelineStagesFacts() {}
}
