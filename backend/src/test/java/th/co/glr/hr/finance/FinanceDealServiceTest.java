package th.co.glr.hr.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.RemainingInvoiceRepository;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketResponses.TicketActionsResponse;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * The scope-EXIT case, deterministically: an authorized, scoped account action that leaves the deal outside
 * account's list scope must still answer with the updated finance view. (A real deal cannot easily be driven out
 * of scope by a permitted action -- deposit-paid auto-advances the stage into scope -- so the scope predicate is
 * scripted here; the real-DB refusal side is FinanceDealLockdownIntegrationTest.)
 */
class FinanceDealServiceTest {

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final FinanceDealService service = new FinanceDealService(tickets, mock(FinanceDealRepository.class),
        mock(DepositNoticeRepository.class), mock(RemainingInvoiceRepository.class), mock(AttachmentRepository.class),
        ticketService);
    private final UserPrincipal account = new UserPrincipal(5L, "a@glr.co.th", "a", "account", 5L, true,
        LocalDate.of(2020, 1, 1), false, null, false);

    @Test
    void anActionThatMovesTheDealOutOfScope_stillReturnsTheUpdatedView_notA403() {
        when(tickets.findSummaryById(anyLong())).thenReturn(Optional.of(summary()));
        // in scope for the up-front access check, OUT of scope once the action has run
        when(tickets.isInAccountScope(anyLong())).thenReturn(true, false);
        // the scope-checking action list would now refuse; the pre-authorised variant must be used instead
        when(ticketService.financeActions(anyLong(), any()))
            .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้"));
        when(ticketService.financeActionsAlreadyScoped(anyLong(), any()))
            .thenReturn(new TicketActionsResponse(null, List.of(), List.of()));

        FinanceDealDto dto = service.confirmDepositPaid(10L, account);

        assertThat(dto).isNotNull();
        assertThat(dto.availableActions()).isEmpty();
    }

    private static TicketSummaryDto summary() {
        return new TicketSummaryDto(1L, "PR-T", null, "t", "draft", "NORMAL", 1L, null, null, null, "cust", null,
            null, null, null, null, null, null, null, null, 0, false, null, null, "NEGOTIATION", null, null, null,
            "ACTIVE", null, null, null, null);
    }
}
