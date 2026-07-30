package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * V99 (owner decision, 2026-07-29, handoff section 4): GL&R operates no provident fund --
 * verified against production, zero employees hold a {@code provident_fund_no}. {@code
 * provident_fund_allowance} is removed from {@code hr.employee_tax_allowance}.
 *
 * <p>The column sat inside the MULTI-column {@code chk_eta_counts_non_negative} alongside {@code
 * child_count}/{@code child_count_double}/{@code disabled_care_count} (including the invariant
 * {@code child_count_double <= child_count}), and Postgres drops a CHECK constraint IN ITS
 * ENTIRETY when any column it references is dropped -- so a bare {@code DROP COLUMN} would have
 * silently taken all four guards with it and nothing would have failed. V99 instead drops the
 * constraint, drops the column, then re-adds the constraint minus the PVD term. This test pins
 * both halves: the column is actually gone, AND the surviving guard still rejects exactly what it
 * always rejected -- the concrete consequence being that without this invariant, HR could declare
 * more second-or-later children than the stored {@code child_count} and {@code
 * PayrollCalculator#childAllowanceCap}-equivalent logic would hand back an unlawfully large
 * ค่าลดหย่อนบุตร.
 *
 * <p><b>NOT TOUCHED, and not this test's concern:</b> {@code
 * hr_restricted.employee_pii.provident_fund_no} (V1), a genuine pre-existing employee PII field
 * predating this work, unrelated to this tax-allowance column beyond sharing a name.
 */
class PayrollProvidentFundRemovalIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void providentFundAllowanceColumnNoLongerExists() {
        Integer count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = 'hr' AND table_name = 'employee_tax_allowance'
               AND column_name = 'provident_fund_allowance'
            """,
            Map.of(),
            Integer.class);

        assertThat(count)
            .as("V99 must drop provident_fund_allowance from hr.employee_tax_allowance")
            .isZero();
    }

    /**
     * Wrong-way-round per CLAUDE.md: assert the INVALID row is REJECTED, not that a valid one is
     * accepted. This is the regression V99's drop-rebuild shape exists to prevent -- a bare {@code
     * DROP COLUMN provident_fund_allowance} would have taken {@code chk_eta_counts_non_negative}
     * with it entirely, and a {@code child_count_double > child_count} row would then insert
     * cleanly with no guard left to stop it.
     */
    @Test
    void theSurvivingCheckStillRejectsChildCountDoubleExceedingChildCount() {
        long employeeId = seedEmployee("PVD-REMOVE-001", "ทดสอบ", "V99");

        assertThatThrownBy(() -> jdbc.update(
            """
            INSERT INTO hr.employee_tax_allowance (employee_id, tax_year, child_count, child_count_double)
            VALUES (:employeeId, 2026, 1, 2)
            """,
            Map.of("employeeId", employeeId)))
            .as("chk_eta_counts_non_negative must still reject child_count_double > child_count "
                + "after V99 rebuilt the constraint minus the provident-fund term")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, 30000, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh),
            Long.class);
    }
}
