package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** The full history of hr.employee_tax_allowance.provident_fund_allowance, and the CHECK constraints that both
 * migrations touching it could have silently destroyed. V93 added it, V99 removed it, V137 put it back. */
class PayrollProvidentFundAllowanceLifecycleIntegrationTest extends AbstractPostgresIntegrationTest {

    /** And the new term itself must be enforced, not merely declared. */
    @Test
    void theRebuiltNonNegativeCheckAlsoCoversProvidentFund() {
        long employeeId = seedEmployee("PVD-LIFECYCLE-003");

        assertThatThrownBy(() -> jdbc.update(
            """
            INSERT INTO hr.employee_tax_allowance (employee_id, tax_year, provident_fund_allowance)
            VALUES (:employeeId, 2026, -1)
            """,
            Map.of("employeeId", employeeId)))
            .as("the provident_fund_allowance >= 0 term V137 added must actually bite")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long seedEmployee(String code) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, 'ทดสอบ', 'V137', 30000, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code), Long.class);
    }
}
