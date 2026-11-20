package th.co.glr.hr.specialmoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** feat/pending-approver-info: unit tests for SpecialMoneyRepository#resolvePendingApproverRole/
 * SpecialMoneyRepository#resolvePendingApproverName -- plain Mockito-on-a-mocked-ResultSet tests, not a
 * real-DB integration test. */
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

}
