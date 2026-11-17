package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** The full history of hr.employee_tax_allowance.provident_fund_allowance, and the CHECK constraints that both
 * migrations touching it could have silently destroyed. V93 added it, V99 removed it, V137 put it back. */
class PayrollProvidentFundAllowanceLifecycleIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void providentFundAllowanceColumnExistsAgainAfterV137() {
        Map<String, Object> column = jdbc.queryForMap(
            """
            SELECT is_nullable, column_default, numeric_precision, numeric_scale
              FROM information_schema.columns
             WHERE table_schema = 'hr' AND table_name = 'employee_tax_allowance'
               AND column_name = 'provident_fund_allowance'
            """,
            Map.of());

        assertThat(column.get("is_nullable"))
            .as("V137 restores provident_fund_allowance as NOT NULL, matching every sibling amount")
            .isEqualTo("NO");
        assertThat(String.valueOf(column.get("column_default")))
            .as("existing rows must read 0, not null — the column is promoted into by apply()")
            .startsWith("0");
        assertThat(column.get("numeric_precision")).isEqualTo(12);
        assertThat(column.get("numeric_scale")).isEqualTo(2);
    }

    /** The declaration table must carry the same column, or apply() cannot promote ข้อ 9. */
    @Test
    void theDeclarationTableCarriesTheSameColumn() {
        Integer count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = 'hr' AND table_name = 'tax_allowance_declaration'
               AND column_name = 'provident_fund_allowance'
            """,
            Map.of(), Integer.class);

        assertThat(count).isEqualTo(1);
    }

    /** V99's rebuild survived. Unchanged by V137, and kept because it is the invariant whose silent loss would be
     * most expensive. */
    @Test
    void theCountsCheckStillRejectsChildCountDoubleExceedingChildCount() {
        long employeeId = seedEmployee("PVD-LIFECYCLE-001");

        assertThatThrownBy(() -> jdbc.update(
            """
            INSERT INTO hr.employee_tax_allowance (employee_id, tax_year, child_count, child_count_double)
            VALUES (:employeeId, 2026, 1, 2)
            """,
            Map.of("employeeId", employeeId)))
            .as("chk_eta_counts_non_negative must still reject child_count_double > child_count")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** V137's own rebuild. It dropped chk_eta_non_negative (sixteen >= 0 terms) to add a seventeenth. Had it
     * forgotten to re-add the constraint, or re-added a shorter version, every negative-amount guard on the table
     * would be gone and nothing else would notice. */
    @Test
    void theRebuiltNonNegativeCheckStillRejectsANegativeAmount() {
        long employeeId = seedEmployee("PVD-LIFECYCLE-002");

        assertThatThrownBy(() -> jdbc.update(
            """
            INSERT INTO hr.employee_tax_allowance (employee_id, tax_year, life_insurance_allowance)
            VALUES (:employeeId, 2026, -1)
            """,
            Map.of("employeeId", employeeId)))
            .as("V137 must re-add chk_eta_non_negative, not just drop it")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

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
