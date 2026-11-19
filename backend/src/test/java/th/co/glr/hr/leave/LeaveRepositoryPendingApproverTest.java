package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** feat/pending-approver-info: unit tests for LeaveRepository#resolvePendingApproverRole/
 * LeaveRepository#resolvePendingApproverName -- plain Mockito-on-a-mocked-ResultSet tests, not a real-DB
 * integration test. */
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

}
