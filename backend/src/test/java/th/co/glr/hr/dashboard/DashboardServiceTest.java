package th.co.glr.hr.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import th.co.glr.hr.auth.UserPrincipal;

class DashboardServiceTest {
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final Instant FIXED_NOW = Instant.parse("2026-07-05T02:00:00Z");

    private final DashboardRepository repository = mock(DashboardRepository.class);
    private final DashboardService service = new DashboardService(
        repository,
        Clock.fixed(FIXED_NOW, BANGKOK)
    );

    @BeforeEach
    void stubRepository() {
        when(repository.headcount(any())).thenAnswer(invocation -> {
            DashboardQueryScope scope = invocation.getArgument(0);
            return HeadcountSummaryDto.empty(scope.label());
        });
        when(repository.attendance(any(), any(), any())).thenAnswer(invocation -> {
            DashboardQueryScope scope = invocation.getArgument(0);
            return AttendanceSummaryDto.empty(scope.label());
        });
        when(repository.pendingApprovals(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            DashboardQueryScope scope = invocation.getArgument(0);
            return PendingApprovalsSummaryDto.of(scope.label(), 0, 0, 0, 0, 0);
        });
        when(repository.tickets(any(), any(), any())).thenAnswer(invocation -> {
            DashboardQueryScope scope = invocation.getArgument(0);
            return TicketSummaryDto.empty(scope.label());
        });
        when(repository.notifications(anyLong())).thenReturn(new NotificationSummaryDto(2, 3, 5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "ceo", "sales_manager"})
    void cashReceivedThisMonthIsTheSumOfThePerRepRowsForTheMoneyRoles(String role) {
        when(repository.cashReceivedByRep(LocalDate.of(2026, 7, 1))).thenReturn(List.of(
            new CashByRepDto("สมชาย ขายดี", new BigDecimal("90000.50")),
            new CashByRepDto("สมหญิง ขายเก่ง", new BigDecimal("35000.00"))));

        DashboardSummaryDto summary = service.summary(user(role, 40L, null, false));

        assertThat(summary.cashReceivedThisMonth()).isEqualByComparingTo("125000.50");
        assertThat(summary.cashReceivedByRep()).hasSize(2);
    }

    private UserPrincipal user(String role, Long employeeId, Long divisionId, boolean manager) {
        return new UserPrincipal(
            employeeId == null ? 99L : employeeId,
            role + "@glr.co.th",
            role,
            role,
            employeeId,
            true,
            LocalDate.of(2026, 1, 1),
            false,
            divisionId,
            manager
        );
    }
}
