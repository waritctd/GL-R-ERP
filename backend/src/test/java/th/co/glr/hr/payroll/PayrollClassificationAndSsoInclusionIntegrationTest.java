package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Exercises the V95 payroll withholding classification + HR declaration schema against a real PostgreSQL
 * database (docs/agent-handoffs/119_feat-payroll-classification-and-hr-declarations.md). Schema + model +
 * repository only -- PayrollCalculator does not consult any of this yet (next task). */
class PayrollClassificationAndSsoInclusionIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new PayrollRepository(jdbc);
    }

    // ---- SSO inclusion defaults ------------------------------------------------------------

    @Test
    void seedsSsoInclusionDefaultsTrueEverywhereExceptDirectorRemunerationAndNonTaxableIncome() {
        long alice = seedEmployee("EMP-CLS-001", "อลิสา", "คลาส");

        repository.seedSsoInclusionDefaults(alice, 2026, alice);

        Map<Long, Map<PayrollComponent, Boolean>> byEmployee = repository.findComponentSsoInclusionByEmployee(2026);
        Map<PayrollComponent, Boolean> aliceInclusion = byEmployee.get(alice);

        assertThat(aliceInclusion).hasSize(PayrollComponent.values().length);
        for (PayrollComponent component : PayrollComponent.values()) {
            boolean expected = component != PayrollComponent.DIRECTOR_REMUNERATION
                && component != PayrollComponent.NON_TAXABLE_INCOME;
            assertThat(aliceInclusion.get(component))
                .as("default SSO inclusion for %s", component)
                .isEqualTo(expected);
        }
        assertThat(aliceInclusion.get(PayrollComponent.DIRECTOR_REMUNERATION)).isFalse();
        assertThat(aliceInclusion.get(PayrollComponent.NON_TAXABLE_INCOME)).isFalse();
        assertThat(aliceInclusion.get(PayrollComponent.SALARY)).isTrue();
        assertThat(aliceInclusion.get(PayrollComponent.COMMISSION_PAY)).isTrue();
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    // ---- Declaration verification -----------------------------------------------------------

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
