package th.co.glr.hr.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms the dashboard's pending-leave count against the real service and the real SQL.
 *
 * <p>{@code DashboardRepository#countLeave} must scope a division manager by {@code
 * reports_to_employee_id}, NOT {@code division_id} -- leave review authority is {@code
 * LeaveService#canReviewEmployee} = {@code canReviewAll(user)} (hr ONLY -- {@code
 * REVIEW_ALL_ROLES} is {@code {hr}}, NOT ceo) OR {@code isDirectManager}, and {@code
 * isDirectManager} reads {@code hr.employee.reports_to_employee_id} (see {@code
 * LeaveRepository#findEmployeeAccess} and its {@code e.reports_to_employee_id} predicates). ceo is
 * routed to the {@code all()} branch by {@code DashboardService#leaveScope}, but that branch is
 * DEAD for ceo: {@code DashboardService#pendingVisibility} gates leave on {@code isHr || manager ||
 * employeeSelf}, all three false for ceo, so {@code countLeave} is never called and the ceo leave
 * badge is always 0. There is deliberately no ceo case below, because there is no ceo leave count
 * to assert. {@code DashboardRepository#countOvertime} correctly stays division-scoped --
 * overtime genuinely routes ฝ่าย manager -&gt; CEO -- so this class also pins that {@code
 * countOvertime} is untouched, on the SAME fixture, so a regression that scoped BOTH counters the
 * same way (undoing the fix) or NEITHER (breaking overtime) would both be visible here.
 *
 * <p>This exists because CLAUDE.md's "Permission changes must ship evidence" requires a real-DB
 * integration test through the real service for any scope/filter change -- a unit test on {@code
 * DashboardService#leaveScope} (see {@code DashboardServiceTest}) proves the right branch is
 * CHOSEN, only this proves the decision survives into the WHERE clause and actually filters rows.
 * It also exists because {@code DashboardRepositoryIntegrationTest}'s fixture never populates
 * {@code reports_to_employee_id} (its {@code insertEmployee} helper has no such parameter) and
 * never exercises a division-manager persona through {@code pendingApprovals}' leave counter, so
 * that suite cannot see this class of bug -- see the comment left in that file's leave-related
 * assertions for why its fixture was deliberately left as-is rather than extended.
 *
 * <p>Every case here asks the question the wrong way round -- can this manager see leave they are
 * NOT the reviewer for -- rather than confirming they can see their own direct report's leave. An
 * assertion that the manager sees their direct report is not evidence; the assertion that they
 * cannot see the same-division employee who reports elsewhere is.
 *
 * <p>Extended for PR #846's follow-up review, which found the original fix directionally right but
 * incomplete in BOTH directions:
 * <ul>
 *   <li>{@code nonTitleSupervisorSeesTheirDirectReportsPendingLeaveEvenWithoutAManagerTitle} (D2,
 *       HIGH) -- {@code leaveScope}'s old gate was {@code isDivisionManager}, a POSITION-TITLE
 *       match, but leave review authority is "is anyone's {@code reports_to_employee_id}", an
 *       independent fact. A real supervisor whose title lacked ผู้จัดการ saw a dashboard badge of
 *       0 while their {@code /leave} review queue had items. This is the one case here asked the
 *       RIGHT way round on purpose: D2 is an under-count (a false negative), so the test that
 *       matters proves they CAN see what they should, not that they cannot see what they should
 *       not (that is D2's sibling test below and the class's original two tests).</li>
 *   <li>{@code inactiveDirectReportsPendingLeaveIsNotCounted} (D3, MEDIUM) -- the predicate emitted
 *       only {@code reports_to_employee_id = :id}, missing the {@code is_active = TRUE} guard
 *       {@code LeaveRepository#countReviewableSubmitted} and {@code LeaveService#isDirectManager}
 *       both require (pinned on the reviewer-authority side by
 *       {@code LeaveReviewSummaryIntegrationTest#aManagerOfAnInactiveReportOnlyIsNotAReviewer}).</li>
 *   <li>{@code managersOwnPendingLeaveIsCountedAlongsideTheirDirectReports} (D4, MEDIUM) -- moving
 *       off division scope (which always included the manager's own row) onto a reports-to-only
 *       predicate silently dropped a manager's own pending leave from their own badge.</li>
 * </ul>
 * All three are fixed by ONE predicate change: {@code DashboardQueryScope.ownOrDirectReports}, which
 * deliberately matches the {@code /leave} LIST predicate this badge links to
 * ({@code LeaveRepository}'s {@code lr.employee_id = :me OR e.reports_to_employee_id = :me}) plus
 * the {@code is_active} guard -- see {@code DashboardService#leaveScope}'s Javadoc for the full
 * reasoning, including why this deliberately diverges from
 * {@code LeaveRepository#countReviewableSubmitted} (which excludes the caller's own requests).
 */
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
        // A second direct report who files nothing -- proves an idle direct report doesn't skew
        // the count either way, matching the manager-has-more-reports-than-filers shape of the
        // real bug report.
        insertEmployee("R002", division, manager);
        long otherManager = insertEmployee("M002", division, null);
        // SAME division as `manager`, but reviewed by a DIFFERENT manager -- exactly the shape
        // division_id scoping would wrongly let `manager` see, and reports_to_employee_id scoping
        // must not.
        outOfChainEmployee = insertEmployee("X001", division, otherManager);

        insertSubmittedLeave(directReport);
        insertSubmittedLeave(outOfChainEmployee);

        // Overtime genuinely IS division-scoped (ฝ่าย manager -> CEO): both requests sit in the
        // SAME division so countOvertime and countLeave can be told apart on the very fixture that
        // proves countLeave changed, exactly as DivisionManagerOverview and the dashboard card
        // disagreeing on the same screen described in the bug report.
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
        // Same manager/directReport pair as the class fixture, but manager=false on the
        // UserPrincipal -- simulating a real supervisor whose position title does NOT contain
        // ผู้จัดการ (DivisionAccessPolicy.isManager is a title-string match, an INDEPENDENT fact
        // from "is anyone's reports_to_employee_id" -- PR #846 defect D2). Asked the RIGHT way
        // round on purpose (see class Javadoc): this is an under-count bug, so the test that
        // matters is that they DO see it, not that they don't.
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
        // A direct report who is no longer active but still carries a stale SUBMITTED row must
        // NOT be counted (PR #846 defect D3) -- mirrors LeaveRepository#countReviewableSubmitted's
        // `AND e.is_active = TRUE` guard and
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
        // A manager's own SUBMITTED leave silently vanished when the scope moved off division
        // (division always included the manager's own row) onto reports_to_employee_id alone (a
        // manager does not report to themselves) -- PR #846 defect D4. The badge must count it
        // again: it mirrors the /leave LIST predicate (lr.employee_id = :me OR ...), which DOES
        // include the caller's own rows -- NOT countReviewableSubmitted's reviewer-only predicate
        // (which deliberately excludes own; a manager cannot review their own leave).
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
        // status defaults to 'SUBMITTED' (V13__leave_management_schema.sql) -- not set explicitly,
        // mirroring DashboardRepositoryIntegrationTest#insertLeave.
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
