package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentSsoInclusionUpsertRequest;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * REVIEWER-ADDED coverage for V95 (branch feat/payroll-classification-and-hr-declarations), written
 * against a real PostgreSQL database.
 *
 * <p>The implementer's {@link PayrollClassificationAndSsoInclusionIntegrationTest} asserts the
 * SALARY lock only against a <em>wrong non-null</em> treatment
 * ({@code EXTRA_KNOWN_FREQUENCY}). It never tries the one value a SQL {@code CHECK} cannot
 * catch: {@code NULL}. A CHECK constraint is violated only when it evaluates to FALSE — an
 * expression evaluating to NULL passes — so
 * {@code CHECK (component <> 'SALARY' OR tax_treatment = 'REGULAR_REPROJECT')} does NOT block
 * {@code ('SALARY', NULL)}, contradicting V95's own comment ("a SALARY row can never be stored
 * with any other treatment (including NULL)") and the owner's "salary is LOCKED to
 * REGULAR_REPROJECT" decision.
 *
 * <p>{@link #salaryIsRejectedWhenStoredUnclassified()} asserts the intended invariant and is
 * therefore expected to FAIL on the branch as it stands. It should go green once the constraint
 * becomes {@code CHECK (component <> 'SALARY' OR (tax_treatment IS NOT NULL AND tax_treatment =
 * 'REGULAR_REPROJECT'))} (forward-only, in a new migration).
 */
class PayrollClassificationReviewIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new PayrollRepository(jdbc);
    }

    /**
     * The SALARY lock must hold against NULL, not only against a wrong non-null treatment.
     *
     * <p>Failure scenario: HR classifies เงินเดือน as REGULAR_REPROJECT, then later clears the cell
     * (the documented "null explicitly resets the component back to not yet classified" affordance
     * on {@code ComponentTaxTreatmentUpsertRequest}). The ON CONFLICT DO UPDATE writes NULL, the
     * CHECK evaluates to NULL and passes, and salary is now stored as unclassified — the exact
     * state the next task's blocker is meant to reject and the exact state the owner said cannot
     * exist. Every subsequent payroll run for that employee is then blocked on a component that is
     * supposed to be un-blockable, or (worse, if the blocker only inspects present-and-non-null
     * keys) salary falls through with no ป.96 limb at all.
     */
    @Test
    void salaryIsRejectedWhenStoredUnclassified() {
        long employee = seedEmployee("EMP-REV-001", "สมชาย", "รีวิว");

        // Direct insert of an unclassified SALARY row must be refused.
        assertThatThrownBy(() -> repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SALARY, null)
        ), employee))
            .as("chk_pctt_salary_locked_to_regular_reproject must reject an unclassified SALARY row")
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.findComponentTaxTreatmentsByEmployee(2026))
            .as("no SALARY row should have been stored at all")
            .doesNotContainKey(employee);
    }

    /**
     * The same hole, reached through the ordinary update path rather than a fresh insert: an
     * already-correct SALARY classification must not be erasable back to unclassified.
     */
    @Test
    void anExistingSalaryClassificationCannotBeErasedBackToUnclassified() {
        long employee = seedEmployee("EMP-REV-002", "สมหญิง", "รีวิว");

        repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SALARY, PayrollTaxTreatment.REGULAR_REPROJECT)
        ), employee);

        assertThatThrownBy(() -> repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SALARY, null)
        ), employee))
            .as("clearing เงินเดือน's treatment must be refused, not silently stored as NULL")
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.findComponentTaxTreatmentsByEmployee(2026).get(employee).get(PayrollComponent.SALARY))
            .as("the stored SALARY treatment must survive the rejected clear")
            .isEqualTo(PayrollTaxTreatment.REGULAR_REPROJECT);
    }

    /**
     * Regression guard for the SSO-inclusion read path, which still uses {@code
     * Collectors.groupingBy -> toMap} — the collector that threw NPE on a null VALUE in the
     * tax-treatment map. This is safe today only because {@code is_included} is {@code BOOLEAN NOT
     * NULL DEFAULT TRUE}; this test pins that dependency so a future migration relaxing the column
     * to nullable fails here rather than in production. It also confirms that a partially-ticked
     * employee (rows for some components, none for others) round-trips with the untouched
     * components genuinely ABSENT rather than defaulted to any value on read.
     */
    @Test
    void ssoInclusionReadPathSurvivesPartialRowsAndNeverSynthesizesADefault() {
        long employee = seedEmployee("EMP-REV-003", "สมปอง", "รีวิว");

        repository.upsertComponentSsoInclusion(2026, List.of(
            new ComponentSsoInclusionUpsertRequest(employee, PayrollComponent.SPECIAL_PAY_9, false),
            new ComponentSsoInclusionUpsertRequest(employee, PayrollComponent.OVERTIME_PAY, true)
        ), employee);

        Map<PayrollComponent, Boolean> inclusion =
            repository.findComponentSsoInclusionByEmployee(2026).get(employee);

        assertThat(inclusion).hasSize(2);
        assertThat(inclusion.get(PayrollComponent.SPECIAL_PAY_9)).isFalse();
        assertThat(inclusion.get(PayrollComponent.OVERTIME_PAY)).isTrue();
        // Never seeded, never ticked -> absent. The read must not invent the seed default.
        assertThat(inclusion).doesNotContainKey(PayrollComponent.SALARY);
        assertThat(inclusion).doesNotContainKey(PayrollComponent.DIRECTOR_REMUNERATION);
    }

    /**
     * Every {@link PayrollComponent} must exist as a row in {@code hr.payroll_pay_component},
     * because the two matrices carry a foreign key to it. A value added to the Java enum without
     * an appended INSERT in a migration would fail only at write time, per component, in
     * production. Guards the append-only contract in both directions.
     */
    @Test
    void everyJavaComponentIsStorableAndTheLookupTableHasNoExtraValues() {
        long employee = seedEmployee("EMP-REV-004", "สมศรี", "รีวิว");

        for (PayrollComponent component : PayrollComponent.values()) {
            repository.upsertComponentSsoInclusion(2026, List.of(
                new ComponentSsoInclusionUpsertRequest(employee, component, true)
            ), employee);
        }
        assertThat(repository.findComponentSsoInclusionByEmployee(2026).get(employee))
            .hasSize(PayrollComponent.values().length);

        List<String> stored = jdbc.queryForList(
            "SELECT component FROM hr.payroll_pay_component ORDER BY component",
            Map.of(), String.class);
        assertThat(stored)
            .as("hr.payroll_pay_component and PayrollComponent must not drift")
            .containsExactlyInAnyOrderElementsOf(
                java.util.Arrays.stream(PayrollComponent.values()).map(Enum::name).toList());
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
