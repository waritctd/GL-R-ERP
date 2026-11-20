package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** feat/pending-approver-info: unit tests for OvertimeRepository#resolvePendingApproverRole/
 * OvertimeRepository#resolvePendingApproverName -- plain Mockito-on-a-mocked-ResultSet tests, not a real-DB
 * integration test. */
class OvertimeRepositoryPendingApproverTest {

    private final OvertimeRepository repository = new OvertimeRepository(null);

    private ResultSet resultSet(String status, boolean hasManagerApprover,
            String divisionManagerSingleName, String ceoSingleName) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("status")).thenReturn(status);
        when(rs.getBoolean("has_manager_approver")).thenReturn(hasManagerApprover);
        when(rs.getString("division_manager_single_name")).thenReturn(divisionManagerSingleName);
        when(rs.getString("ceo_single_name")).thenReturn(ceoSingleName);
        return rs;
    }

    @Test
    void submittedWithoutManagerStage_resolvesToCeoDirectly() throws SQLException {
        // hasManagerApprover false -- manager-less ฝ่าย, or requester IS the manager (Rule 2 of
        // ManagerApproverRepository) -- routes straight to the CEO, mirroring OvertimeService#approve's
        // ceoDirectApprove branch.
        ResultSet rs = resultSet("SUBMITTED", false, null, "ราม");

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("ceo");
        assertThat(repository.resolvePendingApproverName(rs)).isEqualTo("ราม");
    }

}
