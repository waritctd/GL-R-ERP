package th.co.glr.hr.dashboard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;

@Service
public class DashboardService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> COMPANY_VIEW_ROLES = Set.of("hr", "ceo");
    private static final Set<String> HR_APPROVAL_ROLES = Set.of("hr");
    private static final Set<String> COMMISSION_APPROVER_ROLES = Set.of("sales_manager", "ceo");
    private static final Set<String> TICKET_VIEW_ALL_ROLES = Set.of("import", "ceo");
    private static final Set<String> TICKET_OWN_ROLES = Set.of("sales");

    private final DashboardRepository dashboardRepository;
    private final Clock clock;

    @Autowired
    public DashboardService(DashboardRepository dashboardRepository) {
        this(dashboardRepository, Clock.system(BUSINESS_ZONE));
    }

    DashboardService(DashboardRepository dashboardRepository, Clock clock) {
        this.dashboardRepository = dashboardRepository;
        this.clock = clock;
    }

    public DashboardSummaryDto summary(UserPrincipal user) {
        OffsetDateTime generatedAt = OffsetDateTime.now(clock);
        LocalDate today = generatedAt.toLocalDate();
        LocalDate monthStart = today.withDayOfMonth(1);
        OffsetDateTime overdueBefore = generatedAt.minusDays(3);

        DashboardQueryScope headcountScope = headcountScope(user);
        DashboardQueryScope attendanceScope = attendanceScope(user);
        DashboardQueryScope pendingEmployeeScope = pendingEmployeeScope(user);
        DashboardQueryScope commissionScope = commissionScope(user);
        DashboardQueryScope ticketScope = ticketScope(user);
        DashboardPendingVisibility pendingVisibility = pendingVisibility(user);

        TicketSummaryDto tickets = dashboardRepository.tickets(ticketScope, monthStart, overdueBefore);
        NotificationSummaryDto notifications = user.employeeId() == null
            ? NotificationSummaryDto.empty()
            : dashboardRepository.notifications(user.employeeId());

        return DashboardSummaryDto.of(
            user.role(),
            user.employeeId(),
            user.divisionId(),
            user.manager(),
            generatedAt,
            dashboardRepository.headcount(headcountScope),
            dashboardRepository.pendingApprovals(pendingEmployeeScope, pendingVisibility, commissionScope, ticketScope),
            dashboardRepository.attendance(attendanceScope, today, monthStart),
            tickets,
            notifications
        );
    }

    private DashboardQueryScope headcountScope(UserPrincipal user) {
        if (canViewCompany(user)) {
            return DashboardQueryScope.all();
        }
        if (isDivisionManager(user)) {
            return DashboardQueryScope.division(user.divisionId());
        }
        return DashboardQueryScope.none();
    }

    private DashboardQueryScope attendanceScope(UserPrincipal user) {
        if (canViewCompany(user)) {
            return DashboardQueryScope.all();
        }
        if (isDivisionManager(user)) {
            return DashboardQueryScope.division(user.divisionId());
        }
        return DashboardQueryScope.self(user.employeeId());
    }

    private DashboardQueryScope pendingEmployeeScope(UserPrincipal user) {
        if (HR_APPROVAL_ROLES.contains(user.role()) || "ceo".equals(user.role())) {
            return DashboardQueryScope.all();
        }
        if (isDivisionManager(user)) {
            return DashboardQueryScope.division(user.divisionId());
        }
        return DashboardQueryScope.self(user.employeeId());
    }

    private DashboardPendingVisibility pendingVisibility(UserPrincipal user) {
        boolean isHr = HR_APPROVAL_ROLES.contains(user.role());
        boolean employeeSelf = user.employeeId() != null && !canViewCompany(user) && !isDivisionManager(user);
        boolean manager = isDivisionManager(user);
        return new DashboardPendingVisibility(
            isHr || employeeSelf,
            isHr || manager || employeeSelf,
            isHr || manager || employeeSelf,
            COMMISSION_APPROVER_ROLES.contains(user.role()) || "sales".equals(user.role()),
            canViewTickets(user)
        );
    }

    private DashboardQueryScope ticketScope(UserPrincipal user) {
        if (TICKET_VIEW_ALL_ROLES.contains(user.role())) {
            return DashboardQueryScope.all();
        }
        if (TICKET_OWN_ROLES.contains(user.role())) {
            return DashboardQueryScope.self(user.employeeId());
        }
        return DashboardQueryScope.none();
    }

    private DashboardQueryScope commissionScope(UserPrincipal user) {
        if (COMMISSION_APPROVER_ROLES.contains(user.role())) {
            return DashboardQueryScope.all();
        }
        if ("sales".equals(user.role())) {
            return DashboardQueryScope.self(user.employeeId());
        }
        return DashboardQueryScope.none();
    }

    private boolean canViewCompany(UserPrincipal user) {
        return COMPANY_VIEW_ROLES.contains(user.role());
    }

    private boolean isDivisionManager(UserPrincipal user) {
        return user.manager() && user.divisionId() != null && !canViewCompany(user);
    }

    private boolean canViewTickets(UserPrincipal user) {
        return TICKET_VIEW_ALL_ROLES.contains(user.role()) || TICKET_OWN_ROLES.contains(user.role());
    }
}
