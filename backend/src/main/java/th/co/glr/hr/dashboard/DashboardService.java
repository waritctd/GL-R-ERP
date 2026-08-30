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
        DashboardQueryScope leaveScope = leaveScope(user);
        DashboardPendingVisibility pendingVisibility = pendingVisibility(user);

        TicketSummaryDto tickets = dashboardRepository.tickets(ticketScope, monthStart, overdueBefore);
        NotificationSummaryDto notifications = user.employeeId() == null
            ? NotificationSummaryDto.empty()
            : dashboardRepository.notifications(user.employeeId());
        Long latestPayrollPeriodId = user.employeeId() == null
            ? null
            : dashboardRepository.latestPayrollPeriodId(user.employeeId()).orElse(null);

        return DashboardSummaryDto.of(
            user.role(),
            user.employeeId(),
            user.divisionId(),
            user.manager(),
            generatedAt,
            dashboardRepository.headcount(headcountScope),
            dashboardRepository.pendingApprovals(pendingEmployeeScope, pendingVisibility, commissionScope, ticketScope, leaveScope),
            dashboardRepository.attendance(attendanceScope, today, monthStart),
            latestPayrollPeriodId,
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

    /**
     * Unlike {@link #pendingEmployeeScope}, this is NOT gated by {@link #isDivisionManager}. It used
     * to be: a "division manager" per {@code DivisionAccessPolicy.isManager} is a POSITION-TITLE
     * match (contains "ผู้จัดการ"), which routed a title match to {@code reportsTo(...)} and
     * everyone else to {@code self(...)} -- so a real supervisor whose title happened not to contain
     * ผู้จัดการ got a dashboard badge of 0 while their own {@code /leave} review queue had items
     * (PR #846 defect D2). Leave review authority is "is anyone's {@code reports_to_employee_id}" --
     * an independent, department-normalized fact (see
     * {@code LeaveApproverOrgNormalizationIntegrationTest}), not a title string -- so the title gate
     * is removed here rather than repaired: EVERY non-hr/non-ceo user now gets the SAME
     * {@link DashboardQueryScope#ownOrDirectReports} scope. Someone with no active direct reports
     * simply matches only their own leave, exactly what {@code self(...)} used to give them, so
     * this is not a widening for the common case -- only for the real-reviewer-without-title-match
     * case D2 was about.
     *
     * <p>{@link DashboardQueryScope#ownOrDirectReports} deliberately matches the {@code /leave} LIST
     * predicate this badge navigates to ({@code LeaveRepository#findRequests},
     * {@code LeaveRepository}'s {@code lr.employee_id = :me OR e.reports_to_employee_id = :me}),
     * plus the {@code is_active} guard {@code LeaveRepository#countReviewableSubmitted} and
     * {@code LeaveService#isDirectManager} both also require (PR #846 defect D3) -- NOT
     * {@code countReviewableSubmitted}'s reviewer-only predicate, which deliberately EXCLUDES the
     * caller's own requests (a manager cannot review their own leave). This dashboard count is a
     * VIEW figure that must agree with the page it links to, not an approval-eligibility count, so a
     * manager's own SUBMITTED leave counts again here (PR #846 defect D4) even though it would not
     * count toward {@code countReviewableSubmitted}. Do not "fix" this back toward
     * {@code countReviewableSubmitted}'s shape -- a badge that leads to a page showing a different
     * number IS the bug.
     *
     * <p>hr keeps {@code all()} ({@code LeaveService#canReviewAll}, {@code REVIEW_ALL_ROLES} is
     * {@code {hr}} only). ceo is folded into the same {@code all()} branch, matching the
     * pre-existing {@link #pendingEmployeeScope} pattern and {@code LeaveService#canViewAll}
     * ({@code VIEW_ALL_ROLES} is {@code {hr, ceo}}) -- ceo can view every division's requests but,
     * per {@code REVIEW_ALL_ROLES}, cannot actually action one unless they also happen to be that
     * employee's direct manager; this dashboard count is a visibility figure, not a claim that ceo
     * can approve everything it counts. Overtime genuinely does route ฝ่าย manager -> CEO, so
     * {@link #pendingEmployeeScope} keeps {@link DashboardQueryScope#division} for it; leave never
     * shared that routing, hence the divergence.
     */
    private DashboardQueryScope leaveScope(UserPrincipal user) {
        if (HR_APPROVAL_ROLES.contains(user.role()) || "ceo".equals(user.role())) {
            return DashboardQueryScope.all();
        }
        return DashboardQueryScope.ownOrDirectReports(user.employeeId());
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
