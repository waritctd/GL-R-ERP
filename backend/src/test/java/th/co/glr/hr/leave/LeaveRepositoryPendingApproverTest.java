package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * feat/pending-approver-info: unit tests for {@link LeaveRepository#resolvePendingApproverRole}/
 * {@link LeaveRepository#resolvePendingApproverName} -- plain Mockito-on-a-mocked-{@link ResultSet}
 * tests, not a real-DB integration test. This is deliberate and within scope per CLAUDE.md's authz
 * evidence rule: these two methods are READ-ONLY informational resolvers (they describe who the
 * real gate -- {@code LeaveService#approve}/{@code #reject}, {@code REVIEW_ALL_ROLES}, {@code
 * isDirectManager} -- would accept; they never themselves decide who may act), so this is not an
 * authorization change and does not require {@code AbstractPostgresIntegrationTest}. The SQL text
 * (nickname fallback, single-active-match ambiguity resolution in {@code PendingApproverSql}) is
 * not exercised here -- that is plain SQL, not branching Java logic, and is covered by the class
 * Javadoc's "stay in lockstep with DivisionAccessPolicy" contract instead; a follow-up
 * integration test against real Postgres would be a reasonable addition if a reviewer wants one.
 *
 * <p>{@link LeaveRepository} is instantiated with a null {@code NamedParameterJdbcTemplate} and
 * other null collaborators -- both methods under test never touch the constructor-injected fields,
 * only the {@link ResultSet} argument, so this is safe (no NPE reachable from these two methods).
 */
class LeaveRepositoryPendingApproverTest {

    private final LeaveRepository repository = new LeaveRepository(null);

    private ResultSet resultSet(String status, Long managerId, boolean managerActive,
            String managerDisplayName, String hrSingleApproverName) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("status")).thenReturn(status);
        if (managerId == null) {
            when(rs.getLong("reports_to_employee_id")).thenReturn(0L);
            when(rs.wasNull()).thenReturn(true);
        } else {
            when(rs.getLong("reports_to_employee_id")).thenReturn(managerId);
            when(rs.wasNull()).thenReturn(false);
        }
        when(rs.getBoolean("manager_is_active")).thenReturn(managerActive);
        when(rs.getString("manager_display_name")).thenReturn(managerDisplayName);
        when(rs.getString("hr_single_approver_name")).thenReturn(hrSingleApproverName);
        return rs;
    }

    @Test
    void submittedWithActiveManager_resolvesToManagerAndTheirName() throws SQLException {
        ResultSet rs = resultSet("SUBMITTED", 99L, true, "เอ็ม", "ไม่ควรถูกอ่าน");

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("manager");
        assertThat(repository.resolvePendingApproverName(rs)).isEqualTo("เอ็ม");
    }

    @Test
    void submittedWithNoManager_fallsBackToHrGenericRole() throws SQLException {
        ResultSet rs = resultSet("SUBMITTED", null, false, null, "สมศรี");

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("hr");
        assertThat(repository.resolvePendingApproverName(rs)).isEqualTo("สมศรี");
    }

    @Test
    void submittedWithInactiveManager_fallsBackToHrGenericRole() throws SQLException {
        // A stored reports_to_employee_id whose target is no longer active is NOT a live reviewer.
        // Deliberately a DIFFERENT check from LeaveService#isDirectManager's own active flag (that
        // one reads the REQUESTER's active status, not the manager's) -- see
        // LeaveRepository#resolvePendingApproverRole's javadoc.
        ResultSet rs = resultSet("SUBMITTED", 99L, false, "เอ็ม (inactive)", "สมศรี");

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("hr");
        assertThat(repository.resolvePendingApproverName(rs)).isEqualTo("สมศรี");
    }

    @Test
    void hrRoleWithMultipleActiveHrEmployees_omitsNameButKeepsRole() throws SQLException {
        // Ambiguity case: PendingApproverSql#SINGLE_ACTIVE_HR_NAME_SQL resolves to SQL NULL when
        // more than one active hr-role employee exists -- this test stands in for that by having
        // the (mocked) SQL layer already report null, and asserts the Java side does not invent a
        // name when the column comes back null.
        ResultSet rs = resultSet("SUBMITTED", null, false, null, null);

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("hr");
        assertThat(repository.resolvePendingApproverName(rs)).isNull();
    }

    @Test
    void approvedStatus_hasNoPendingApprover() throws SQLException {
        ResultSet rs = resultSet("APPROVED", 99L, true, "เอ็ม", "สมศรี");

        assertThat(repository.resolvePendingApproverRole(rs)).isNull();
        assertThat(repository.resolvePendingApproverName(rs)).isNull();
    }

    @Test
    void rejectedStatus_hasNoPendingApprover() throws SQLException {
        ResultSet rs = resultSet("REJECTED", null, false, null, "สมศรี");

        assertThat(repository.resolvePendingApproverRole(rs)).isNull();
        assertThat(repository.resolvePendingApproverName(rs)).isNull();
    }
}
