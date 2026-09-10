package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * GET /api/leave/balances/team (manager team-quota summary, 2026-09): real-Postgres coverage of
 * {@link LeaveService#teamBalances}'s scope -- direct reports only, never the whole division, and
 * never a colleague's team.
 *
 * <p>Written wrong-way-round throughout (CLAUDE.md's "Permission changes must ship evidence"): every
 * case here asserts an employee the actor should NOT see is genuinely ABSENT from the response, not
 * merely that the actor's own reports are present. {@link #teamBalances} deliberately reuses {@link
 * LeaveRepository#findEmployeeOptions} -- the SAME method {@link LeaveService#employeeOptions} (GET
 * /api/leave/employees) already calls -- for the scope decision, so this class is also the
 * mutation-check target: dropping the reused call in favour of a second, independently-written
 * predicate is exactly the {@code managesEmployee}-three-restatements defect this codebase has
 * already been bitten by once. See PR body for the mutation-check run (temporarily widen the scope
 * to every active employee, confirm {@link #managerCannotSeeAnotherManagersReport} and only that
 * test goes red, then restore by hand).
 */
class LeaveTeamBalancesIntegrationTest extends AbstractPostgresIntegrationTest {

    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaborators() {
        LeaveRepository leaveRepository = new LeaveRepository(jdbc);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class));
    }

    @Test
    void managerSeesTheirOwnDirectReport() {
        long manager = insertEmployee("TQ-MGR-A", null, true);
        long report = insertEmployee("TQ-RPT-A", manager, true);

        List<LeaveTeamMemberBalanceDto> team = leaveService.teamBalances(employee(manager), 2026);

        assertThat(team).extracting(LeaveTeamMemberBalanceDto::employeeId).containsExactly(report);
        LeaveTeamMemberBalanceDto reportEntry = team.get(0);
        assertThat(reportEntry.balances()).isNotEmpty();
        assertThat(reportEntry.balances())
            .as("remaining-days shape parity with the single-employee /balances endpoint")
            .allSatisfy(balance -> assertThat(balance.leaveTypeCode()).isNotBlank());
    }

    /**
     * The test that matters (CLAUDE.md: "wrong-way-round"). Two managers, each with their own
     * report. Calling as manager1 must return manager1's report and must NOT return manager2's --
     * asserting absence, not just that manager1's own report is present.
     */
    @Test
    void managerCannotSeeAnotherManagersReport() {
        long manager1 = insertEmployee("TQ-MGR-B1", null, true);
        long manager2 = insertEmployee("TQ-MGR-B2", null, true);
        long report1 = insertEmployee("TQ-RPT-B1", manager1, true);
        long report2 = insertEmployee("TQ-RPT-B2", manager2, true);

        List<LeaveTeamMemberBalanceDto> team = leaveService.teamBalances(employee(manager1), 2026);

        assertThat(team).extracting(LeaveTeamMemberBalanceDto::employeeId).containsExactly(report1);
        assertThat(team).extracting(LeaveTeamMemberBalanceDto::employeeId)
            .as("a colleague's report must never leak into this manager's team summary")
            .doesNotContain(report2);
    }

    @Test
    void managerDoesNotSeeThemselvesInTheirOwnTeamList() {
        long manager = insertEmployee("TQ-MGR-C", null, true);
        insertEmployee("TQ-RPT-C", manager, true);

        List<LeaveTeamMemberBalanceDto> team = leaveService.teamBalances(employee(manager), 2026);

        assertThat(team).extracting(LeaveTeamMemberBalanceDto::employeeId).doesNotContain(manager);
    }

    @Test
    void inactiveReportIsExcluded() {
        long manager = insertEmployee("TQ-MGR-D", null, true);
        insertEmployee("TQ-RPT-D-INACTIVE", manager, false);

        List<LeaveTeamMemberBalanceDto> team = leaveService.teamBalances(employee(manager), 2026);

        assertThat(team).isEmpty();
    }

    @Test
    void anEmployeeWithNoDirectReportsGetsAnEmptyListNotAnError() {
        long plainEmployee = insertEmployee("TQ-PLAIN-D", null, true);

        List<LeaveTeamMemberBalanceDto> team = leaveService.teamBalances(employee(plainEmployee), 2026);

        assertThat(team).isEmpty();
    }

    @Test
    void hrStillSeesEveryone() {
        long manager1 = insertEmployee("TQ-MGR-E1", null, true);
        long manager2 = insertEmployee("TQ-MGR-E2", null, true);
        long report1 = insertEmployee("TQ-RPT-E1", manager1, true);
        long report2 = insertEmployee("TQ-RPT-E2", manager2, true);

        List<LeaveTeamMemberBalanceDto> team = leaveService.teamBalances(hr(), 2026);

        assertThat(team).extracting(LeaveTeamMemberBalanceDto::employeeId)
            .as("HR keeps its existing whole-company reach -- this endpoint must not regress it")
            .contains(manager1, manager2, report1, report2);
    }

    @Test
    void ceoStillSeesEveryone() {
        long manager = insertEmployee("TQ-MGR-F", null, true);
        long report = insertEmployee("TQ-RPT-F", manager, true);

        List<LeaveTeamMemberBalanceDto> team = leaveService.teamBalances(ceo(), 2026);

        assertThat(team).extracting(LeaveTeamMemberBalanceDto::employeeId).contains(manager, report);
    }

    // --- helpers ------------------------------------------------------------

    private long insertEmployee(String code, Long reportsToId, boolean active) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("reportsTo", reportsToId);
        params.put("active", active);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee
                (employee_code, first_name_th, last_name_th,
                 reports_to_employee_id, is_active, hire_date)
            VALUES (:code, 'ทดสอบ', :code, :reportsTo, :active, DATE '2020-01-01')
            RETURNING employee_id
            """, new MapSqlParameterSource(params), Long.class);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(9001L, "hr@glr.co.th", "HR", "hr", 9001L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(9002L, "ceo@glr.co.th", "CEO", "ceo", 9002L, true, LocalDate.now(), false, null, false);
    }
}
