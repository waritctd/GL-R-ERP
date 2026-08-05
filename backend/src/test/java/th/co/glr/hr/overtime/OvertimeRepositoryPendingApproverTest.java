package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * feat/pending-approver-info: unit tests for {@link OvertimeRepository#resolvePendingApproverRole}/
 * {@link OvertimeRepository#resolvePendingApproverName} -- plain Mockito-on-a-mocked-{@link
 * ResultSet} tests, not a real-DB integration test. Read-only informational resolvers, not an
 * authorization decision (see {@link LeaveRepository}'s equivalent test class Javadoc in the
 * {@code leave} package for the full reasoning, which applies identically here) -- they mirror
 * {@code OvertimeService#approve}'s own SUBMITTED/MANAGER_APPROVED branching, never gate it.
 */
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
    void submittedWithManagerStage_resolvesToManagerAndTheirName() throws SQLException {
        ResultSet rs = resultSet("SUBMITTED", true, "เอ็ม", "ราม");

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("manager");
        assertThat(repository.resolvePendingApproverName(rs)).isEqualTo("เอ็ม");
    }

    @Test
    void submittedWithoutManagerStage_resolvesToCeoDirectly() throws SQLException {
        // hasManagerApprover false -- manager-less ฝ่าย, or requester IS the manager (Rule 2 of
        // ManagerApproverRepository) -- routes straight to the CEO, mirroring
        // OvertimeService#approve's ceoDirectApprove branch.
        ResultSet rs = resultSet("SUBMITTED", false, null, "ราม");

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("ceo");
        assertThat(repository.resolvePendingApproverName(rs)).isEqualTo("ราม");
    }

    @Test
    void managerApproved_alwaysResolvesToCeo() throws SQLException {
        ResultSet rs = resultSet("MANAGER_APPROVED", true, "เอ็ม", "ราม");

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("ceo");
        assertThat(repository.resolvePendingApproverName(rs)).isEqualTo("ราม");
    }

    @Test
    void multipleCeoCandidates_omitsNameButKeepsRole() throws SQLException {
        // Ambiguity case: PendingApproverSql#SINGLE_ACTIVE_CEO_NAME_SQL resolves to SQL NULL when
        // more than one active ceo-role employee exists -- stood in here by the (mocked) SQL layer
        // already reporting null.
        ResultSet rs = resultSet("SUBMITTED", false, null, null);

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("ceo");
        assertThat(repository.resolvePendingApproverName(rs)).isNull();
    }

    @Test
    void multipleDivisionManagerCandidates_omitsNameButKeepsRole() throws SQLException {
        ResultSet rs = resultSet("SUBMITTED", true, null, "ราม");

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("manager");
        assertThat(repository.resolvePendingApproverName(rs)).isNull();
    }

    @Test
    void approvedStatus_hasNoPendingApprover() throws SQLException {
        ResultSet rs = resultSet("APPROVED", true, "เอ็ม", "ราม");

        assertThat(repository.resolvePendingApproverRole(rs)).isNull();
        assertThat(repository.resolvePendingApproverName(rs)).isNull();
    }

    @Test
    void cancelledStatus_hasNoPendingApprover() throws SQLException {
        ResultSet rs = resultSet("CANCELLED", false, null, "ราม");

        assertThat(repository.resolvePendingApproverRole(rs)).isNull();
        assertThat(repository.resolvePendingApproverName(rs)).isNull();
    }
}
