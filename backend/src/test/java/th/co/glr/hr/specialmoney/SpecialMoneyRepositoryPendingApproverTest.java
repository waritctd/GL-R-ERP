package th.co.glr.hr.specialmoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * feat/pending-approver-info: unit tests for
 * {@link SpecialMoneyRepository#resolvePendingApproverRole}/
 * {@link SpecialMoneyRepository#resolvePendingApproverName} -- plain Mockito-on-a-mocked-{@link
 * ResultSet} tests, not a real-DB integration test. Read-only informational resolver, not an
 * authorization decision (see {@code LeaveRepositoryPendingApproverTest}'s Javadoc in the
 * {@code leave} package for the full reasoning, which applies identically here). Welfare is
 * CEO-only, single-stage (see {@link SpecialMoneyService}'s class Javadoc) so there is no
 * manager-stage branch to test here, unlike leave/overtime.
 */
class SpecialMoneyRepositoryPendingApproverTest {

    private final SpecialMoneyRepository repository = new SpecialMoneyRepository(null, null);

    private ResultSet resultSet(String status, String ceoSingleName) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("status")).thenReturn(status);
        when(rs.getString("ceo_single_name")).thenReturn(ceoSingleName);
        return rs;
    }

    @Test
    void submitted_resolvesToCeoAndTheirName() throws SQLException {
        ResultSet rs = resultSet("SUBMITTED", "ราม");

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("ceo");
        assertThat(repository.resolvePendingApproverName(rs)).isEqualTo("ราม");
    }

    @Test
    void managerApprovedLegacyRow_stillResolvesToCeo() throws SQLException {
        // MANAGER_APPROVED survives only for rows written before the manager stage was removed
        // (SpecialMoneyService's class Javadoc) -- ceoApproveFrom handles it the same way as
        // SUBMITTED, so the pending-approver display must too.
        ResultSet rs = resultSet("MANAGER_APPROVED", "ราม");

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("ceo");
        assertThat(repository.resolvePendingApproverName(rs)).isEqualTo("ราม");
    }

    @Test
    void multipleCeoCandidates_omitsNameButKeepsRole() throws SQLException {
        // Ambiguity case: PendingApproverSql#SINGLE_ACTIVE_CEO_NAME_SQL resolves to SQL NULL when
        // more than one active ceo-role employee exists.
        ResultSet rs = resultSet("SUBMITTED", null);

        assertThat(repository.resolvePendingApproverRole(rs)).isEqualTo("ceo");
        assertThat(repository.resolvePendingApproverName(rs)).isNull();
    }

    @Test
    void approvedStatus_hasNoPendingApprover() throws SQLException {
        ResultSet rs = resultSet("APPROVED", "ราม");

        assertThat(repository.resolvePendingApproverRole(rs)).isNull();
        assertThat(repository.resolvePendingApproverName(rs)).isNull();
    }

    @Test
    void rejectedStatus_hasNoPendingApprover() throws SQLException {
        ResultSet rs = resultSet("REJECTED", null);

        assertThat(repository.resolvePendingApproverRole(rs)).isNull();
        assertThat(repository.resolvePendingApproverName(rs)).isNull();
    }
}
