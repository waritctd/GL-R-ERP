package th.co.glr.hr.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
        when(repository.pendingApprovals(any(), any(), any(), any())).thenAnswer(invocation -> {
            DashboardQueryScope scope = invocation.getArgument(0);
            return PendingApprovalsSummaryDto.of(scope.label(), 0, 0, 0, 0, 0);
        });
        when(repository.tickets(any(), any(), any())).thenAnswer(invocation -> {
            DashboardQueryScope scope = invocation.getArgument(0);
            return TicketSummaryDto.empty(scope.label());
        });
        when(repository.notifications(anyLong())).thenReturn(new NotificationSummaryDto(2, 3, 5));
    }

    @Test
    void hrSummaryUsesCompanyScopeWithoutTicketLeak() {
        DashboardSummaryDto summary = service.summary(user("hr", 10L, null, false));

        assertThat(summary.role()).isEqualTo("hr");
        assertThat(summary.generatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-05T09:00:00+07:00"));
        assertThat(summary.notifications().unread()).isEqualTo(2);

        ArgumentCaptor<DashboardQueryScope> headcountScope = ArgumentCaptor.forClass(DashboardQueryScope.class);
        verify(repository).headcount(headcountScope.capture());
        assertThat(headcountScope.getValue().isAll()).isTrue();

        ArgumentCaptor<DashboardQueryScope> attendanceScope = ArgumentCaptor.forClass(DashboardQueryScope.class);
        verify(repository).attendance(attendanceScope.capture(), eq(LocalDate.of(2026, 7, 5)), eq(LocalDate.of(2026, 7, 1)));
        assertThat(attendanceScope.getValue().isAll()).isTrue();

        ArgumentCaptor<DashboardQueryScope> pendingScope = ArgumentCaptor.forClass(DashboardQueryScope.class);
        ArgumentCaptor<DashboardPendingVisibility> visibility = ArgumentCaptor.forClass(DashboardPendingVisibility.class);
        ArgumentCaptor<DashboardQueryScope> ticketScope = ArgumentCaptor.forClass(DashboardQueryScope.class);
        verify(repository).pendingApprovals(pendingScope.capture(), visibility.capture(), any(), ticketScope.capture());
        assertThat(pendingScope.getValue().isAll()).isTrue();
        assertThat(visibility.getValue().profileRequests()).isTrue();
        assertThat(visibility.getValue().overtime()).isTrue();
        assertThat(visibility.getValue().leave()).isTrue();
        assertThat(visibility.getValue().tickets()).isFalse();
        assertThat(ticketScope.getValue().isNone()).isTrue();
    }

    @Test
    void ceoSummaryGetsCompanyAndTicketScopes() {
        service.summary(user("ceo", 20L, null, false));

        ArgumentCaptor<DashboardQueryScope> ticketScope = ArgumentCaptor.forClass(DashboardQueryScope.class);
        verify(repository).tickets(ticketScope.capture(), eq(LocalDate.of(2026, 7, 1)), any());
        assertThat(ticketScope.getValue().isAll()).isTrue();
    }

    @Test
    void managerSummaryUsesDivisionScopeAndHidesProfileApprovals() {
        service.summary(user("employee", 11L, 5L, true));

        ArgumentCaptor<DashboardQueryScope> headcountScope = ArgumentCaptor.forClass(DashboardQueryScope.class);
        verify(repository).headcount(headcountScope.capture());
        assertThat(headcountScope.getValue().isDivision()).isTrue();
        assertThat(headcountScope.getValue().divisionId()).isEqualTo(5L);

        ArgumentCaptor<DashboardQueryScope> pendingScope = ArgumentCaptor.forClass(DashboardQueryScope.class);
        ArgumentCaptor<DashboardPendingVisibility> visibility = ArgumentCaptor.forClass(DashboardPendingVisibility.class);
        verify(repository).pendingApprovals(pendingScope.capture(), visibility.capture(), any(), any());
        assertThat(pendingScope.getValue().isDivision()).isTrue();
        assertThat(visibility.getValue().profileRequests()).isFalse();
        assertThat(visibility.getValue().overtime()).isTrue();
        assertThat(visibility.getValue().leave()).isTrue();
        assertThat(visibility.getValue().tickets()).isFalse();
    }

    @Test
    void employeeSummaryUsesSelfScopeAndNoBroadHeadcountOrTickets() {
        DashboardSummaryDto summary = service.summary(user("employee", 12L, 7L, false));

        assertThat(summary.headcount().scope()).isEqualTo("none");
        assertThat(summary.headcount().active()).isNull();
        assertThat(summary.totalOpen()).isZero();

        ArgumentCaptor<DashboardQueryScope> attendanceScope = ArgumentCaptor.forClass(DashboardQueryScope.class);
        verify(repository).attendance(attendanceScope.capture(), eq(LocalDate.of(2026, 7, 5)), eq(LocalDate.of(2026, 7, 1)));
        assertThat(attendanceScope.getValue().isSelf()).isTrue();
        assertThat(attendanceScope.getValue().employeeId()).isEqualTo(12L);

        ArgumentCaptor<DashboardQueryScope> ticketScope = ArgumentCaptor.forClass(DashboardQueryScope.class);
        verify(repository).tickets(ticketScope.capture(), eq(LocalDate.of(2026, 7, 1)), any());
        assertThat(ticketScope.getValue().isNone()).isTrue();
    }

    @Test
    void salesSummaryKeepsTicketsScopedToOwnEmployee() {
        service.summary(user("sales", 30L, 9L, false));

        ArgumentCaptor<DashboardQueryScope> ticketScope = ArgumentCaptor.forClass(DashboardQueryScope.class);
        verify(repository).tickets(ticketScope.capture(), eq(LocalDate.of(2026, 7, 1)), any());
        assertThat(ticketScope.getValue().isSelf()).isTrue();
        assertThat(ticketScope.getValue().employeeId()).isEqualTo(30L);
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
