package th.co.glr.hr.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Confirms the dashboard's pending-leave count against the real service and the real SQL. An assertion that
 * the manager sees their direct report is not evidence; the assertion that they cannot see the same-division
 * employee who reports elsewhere is. */
class DashboardLeaveScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 7, 5);
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 7, 1);

    private DashboardService service;

    private long division;
    private long manager;
    private long directReport;
    /** A same-division employee reporting to a DIFFERENT manager, NOT {@link #manager}. */
    private long outOfChainEmployee;

    @BeforeEach
    void wireRealCollaborators() {
        service = new DashboardService(new DashboardRepository(jdbc));

        division = insertDivision("SLS", "ฝ่ายขาย");
        manager = insertEmployee("M001", division, null);
        directReport = insertEmployee("R001", division, manager);
        // A second direct report who files nothing -- proves an idle direct report doesn't skew the count either way,
        // matching the manager-has-more-reports-than-filers shape of the real bug report.
        insertEmployee("R002", division, manager);
        long otherManager = insertEmployee("M002", division, null);
        // SAME division as `manager`, but reviewed by a DIFFERENT manager -- exactly the shape division_id scoping
        // would wrongly let `manager` see, and reports_to_employee_id scoping must not.
        outOfChainEmployee = insertEmployee("X001", division, otherManager);

        insertSubmittedLeave(directReport);
        insertSubmittedLeave(outOfChainEmployee);

        // Overtime genuinely IS division-scoped: both requests sit in the SAME division so countOvertime and
        // countLeave can be told apart on the very fixture that proves countLeave changed, exactly as
        // DivisionManagerOverview and the dashboard card disagreeing on the same screen described in the bug report.
        insertSubmittedOvertime(directReport);
        insertSubmittedOvertime(outOfChainEmployee);
    }

    @Test
    void managerCannotSeeLeaveFromASameDivisionEmployeeOutsideTheirReportsToChain() {
        PendingApprovalsSummaryDto pending = service.summary(managerPrincipal()).pendingApprovals();

        assertThat(pending.leave())
            .as("only directReport's SUBMITTED leave -- outOfChainEmployee's must NOT be counted "
                + "even though they share a division with the manager")
            .isEqualTo(1);
    }

    @Test
    void managerStillSeesOvertimeAcrossTheWholeDivision() {
        PendingApprovalsSummaryDto pending = service.summary(managerPrincipal()).pendingApprovals();

        assertThat(pending.overtime())
            .as("countOvertime is untouched by this change -- ฝ่าย manager -> CEO genuinely IS "
                + "division-scoped, so BOTH requests in the division count here")
            .isEqualTo(2);
    }

    @Test
    void hrSeesLeaveAcrossTheWholeCompanyRegardlessOfReportsToChain() {
        PendingApprovalsSummaryDto pending = service.summary(hrPrincipal()).pendingApprovals();

        assertThat(pending.leave()).isEqualTo(2);
    }

    @Test
    void ordinaryEmployeeSeesOnlyTheirOwnLeaveNotTheirManagersWholeChain() {
        PendingApprovalsSummaryDto pending = service.summary(employeePrincipal(directReport)).pendingApprovals();

        assertThat(pending.leave())
            .as("directReport's own card counts only their own SUBMITTED request")
            .isEqualTo(1);
    }

    @Test
    void nonTitleSupervisorSeesTheirDirectReportsPendingLeaveEvenWithoutAManagerTitle() {
        // Same manager/directReport pair as the class fixture, but manager=false on the UserPrincipal -- simulating a
        // real supervisor whose position title does NOT contain ผู้จัดการ.
        UserPrincipal nonTitleSupervisor = new UserPrincipal(4L, "sup@glr.co.th", "sup", "employee",
            manager, true, LocalDate.now(), false, division, false);

        PendingApprovalsSummaryDto pending = service.summary(nonTitleSupervisor).pendingApprovals();

        assertThat(pending.leave())
            .as("D2: a real supervisor without ผู้จัดการ in their title must still see their "
                + "active direct report's SUBMITTED leave -- must fail against the "
                + "isDivisionManager-gated branch this fix removes, exactly as it would have "
                + "before this fix even though managerPrincipal() (manager=true) already covers "
                + "the same fixture pair above")
            .isEqualTo(1);
    }

    @Test
    void inactiveDirectReportsPendingLeaveIsNotCounted() {
        // A direct report who is no longer active but still carries a stale SUBMITTED row must NOT be counted (PR #846
        // defect D3) -- mirrors LeaveRepository#countReviewableSubmitted's `AND e.is_active = TRUE` guard and
        // LeaveReviewSummaryIntegrationTest#aManagerOfAnInactiveReportOnlyIsNotAReviewer.
        long inactiveReport = insertEmployee("R003", division, manager, false);
        insertSubmittedLeave(inactiveReport);

        PendingApprovalsSummaryDto pending = service.summary(managerPrincipal()).pendingApprovals();

        assertThat(pending.leave())
            .as("only directReport's (active) SUBMITTED leave counts -- the inactive report's "
                + "stale SUBMITTED row must NOT inflate the count from 1 to 2")
            .isEqualTo(1);
    }

    @Test
    void managersOwnPendingLeaveIsCountedAlongsideTheirDirectReports() {
        // A manager's own SUBMITTED leave silently vanished when the scope moved off division (division always
        // included the manager's own row) onto reports_to_employee_id alone (a manager does not report to themselves)
        // -- PR #846 defect D4.
        insertSubmittedLeave(manager);

        PendingApprovalsSummaryDto pending = service.summary(managerPrincipal()).pendingApprovals();

        assertThat(pending.leave())
            .as("directReport's SUBMITTED leave (1) PLUS the manager's own (1) == 2")
            .isEqualTo(2);
    }

    // --- fixture helpers ------------------------------------------------

    private UserPrincipal managerPrincipal() {
        return new UserPrincipal(1L, "mgr@glr.co.th", "mgr", "employee", manager, true,
            LocalDate.now(), false, division, true);
    }

    private UserPrincipal hrPrincipal() {
        return new UserPrincipal(2L, "hr@glr.co.th", "hr", "hr", manager, true,
            LocalDate.now(), false, null, false);
    }

    private UserPrincipal employeePrincipal(long employeeId) {
        return new UserPrincipal(3L, "emp@glr.co.th", "emp", "employee", employeeId, true,
            LocalDate.now(), false, division, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("name", name),
            Long.class);
    }

    private long insertEmployee(String code, long divisionId, Long reportsToEmployeeId) {
        return insertEmployee(code, divisionId, reportsToEmployeeId, true);
    }

    private long insertEmployee(String code, long divisionId, Long reportsToEmployeeId, boolean active) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("code", code)
            .addValue("divisionId", divisionId)
            .addValue("reportsTo", reportsToEmployeeId)
            .addValue("active", active)
            .addValue("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, reports_to_employee_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :reportsTo, :hireDate, :active)
            RETURNING employee_id
            """, params, Long.class);
    }

    private void insertSubmittedLeave(long employeeId) {
        jdbc.update("""
            INSERT INTO hr.leave_request (
                employee_id, leave_type_code, start_date, end_date, total_days,
                quota_year, reason, quota_remaining_before, quota_remaining_after
            )
            VALUES (
                :employeeId, 'VACATION', :startDate, :endDate, 1,
                2026, 'Personal errand', 6, 5
            )
            """, new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("startDate", WORK_DATE)
                .addValue("endDate", WORK_DATE));
        // status defaults to 'SUBMITTED' (V13__leave_management_schema.sql) -- not set explicitly, mirroring
        // DashboardRepositoryIntegrationTest#insertLeave.
    }

    private void insertSubmittedOvertime(long employeeId) {
        jdbc.update("""
            INSERT INTO hr.overtime_request (
                employee_id, work_date, planned_start_at, planned_end_at,
                planned_minutes, reason, payroll_month
            )
            VALUES (
                :employeeId, :workDate, :startAt, :endAt,
                60, 'Month-end closing', :payrollMonth
            )
            """, new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("workDate", WORK_DATE)
                .addValue("startAt", OffsetDateTime.parse("2026-07-05T18:00:00+07:00"))
                .addValue("endAt", OffsetDateTime.parse("2026-07-05T19:00:00+07:00"))
                .addValue("payrollMonth", PAYROLL_MONTH));
        // status defaults to 'SUBMITTED', mirroring DashboardRepositoryIntegrationTest#insertOvertime.
    }
}
