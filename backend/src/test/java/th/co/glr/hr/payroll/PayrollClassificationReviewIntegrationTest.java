package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentSsoInclusionUpsertRequest;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

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
    private PayrollService payrollService;

    @BeforeEach
    void wireRepository() {
        repository = new PayrollRepository(jdbc);
        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(th.co.glr.hr.notification.NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class));
        payrollService = new PayrollService(
            repository,
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.config.AppProperties());
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

    /**
     * Defect fix (Opus review, 2026-07-29) -- the January cliff, corrected: {@link
     * PayrollRepository#findComponentTaxTreatmentsByEmployee} and {@link PayrollRepository
     * #findComponentSsoInclusionByEmployee} used to resolve one effective tax year for the WHOLE
     * table ({@code SELECT MAX(tax_year) ... WHERE tax_year <= :taxYear}, no employee scoping). The
     * first employee hired in January of a new tax year (seeded at their hire year by {@code
     * EmployeeService#create}) flips that single table-wide MAX forward for EVERY employee, so on
     * the very next payroll run every pre-existing employee's classification map reads back
     * completely empty: any employee with a non-zero non-salary component gets the run rejected, and
     * salary-only employees (which pass the classification gate because SALARY is always auto-
     * REGULAR_REPROJECT) silently get an SSO wage base of zero.
     *
     * <p>Reproduces that exact shape: employee A gets a 2027 row (the new hire), employee B has only
     * a 2026 row (the pre-existing employee) and no 2027 row at all. Reading at {@code taxYear=2027}
     * must resolve A to 2027 and B to 2026 INDEPENDENTLY -- not collapse to one shared year for both.
     */
    @Test
    void resolvesTheEffectiveTaxYearPerEmployeeNotForTheWholeTable() {
        long employeeA = seedEmployee("EMP-REV-005", "เอ", "รีวิว");
        long employeeB = seedEmployee("EMP-REV-006", "บี", "รีวิว");

        // Employee A: hired/classified in 2027 only (the new-hire scenario that used to flip MAX).
        repository.upsertComponentTaxTreatment(2027, List.of(
            new ComponentTaxTreatmentUpsertRequest(employeeA, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY)
        ), employeeA);
        repository.upsertComponentSsoInclusion(2027, List.of(
            new ComponentSsoInclusionUpsertRequest(employeeA, PayrollComponent.SALARY, true)
        ), employeeA);

        // Employee B: classified in 2026 only -- the pre-existing employee, never touched for 2027.
        repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(employeeB, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.REGULAR_REPROJECT)
        ), employeeB);
        repository.upsertComponentSsoInclusion(2026, List.of(
            new ComponentSsoInclusionUpsertRequest(employeeB, PayrollComponent.SALARY, true)
        ), employeeB);

        Map<Long, Map<PayrollComponent, PayrollTaxTreatment>> treatmentsAtRequestedYear2027 =
            repository.findComponentTaxTreatmentsByEmployee(2027);
        Map<Long, Map<PayrollComponent, Boolean>> ssoInclusionAtRequestedYear2027 =
            repository.findComponentSsoInclusionByEmployee(2027);

        assertThat(treatmentsAtRequestedYear2027.get(employeeA).get(PayrollComponent.SPECIAL_PAY_1))
            .as("employee A must resolve to their own 2027 row")
            .isEqualTo(PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY);
        assertThat(treatmentsAtRequestedYear2027)
            .as("employee B must still resolve, rolling forward from their own 2026 row -- "
                + "NOT read as empty just because employee A has a 2027 row")
            .containsKey(employeeB);
        assertThat(treatmentsAtRequestedYear2027.get(employeeB).get(PayrollComponent.SPECIAL_PAY_1))
            .isEqualTo(PayrollTaxTreatment.REGULAR_REPROJECT);

        assertThat(ssoInclusionAtRequestedYear2027.get(employeeA).get(PayrollComponent.SALARY)).isTrue();
        assertThat(ssoInclusionAtRequestedYear2027)
            .as("employee B's SSO inclusion must not silently read as empty (which would zero their wage base)")
            .containsKey(employeeB);
        assertThat(ssoInclusionAtRequestedYear2027.get(employeeB).get(PayrollComponent.SALARY)).isTrue();
    }

    /**
     * D2 (fourth reachability audit, 2026-07-30): a partial save into a new tax year must not strand
     * an employee's OTHER, already-resolved components. Reproduces exactly the shape {@code
     * TaxTreatmentMatrixSection.save()} (`PayrollPage.jsx`) produces: it saves only the edited cell
     * ({@code changes}, the diff), never the full resolved matrix. Before the fix, the single 2027
     * row this test writes for {@code SPECIAL_PAY_1} flipped {@code findComponentTaxTreatmentsByEmployee
     * }'s per-EMPLOYEE roll-forward to 2027 for the whole employee, and the JOIN then excluded
     * {@code SPECIAL_PAY_2}/{@code BONUS_PAY}'s still-valid 2026 rows entirely -- they read back as
     * "not yet classified" in the very API response the edit's own save renders.
     */
    @Test
    void aPartialSaveIntoANewTaxYearDoesNotStrandOtherComponentsAlreadyRolledForward() {
        long employee = seedEmployee("EMP-REV-007", "ดี", "รีวิว");

        // 2026: full classification, as V100's backfill (or an earlier HR save) would leave it.
        repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.REGULAR_REPROJECT),
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SPECIAL_PAY_2, PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL),
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.BONUS_PAY, PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY)
        ), employee);
        repository.upsertComponentSsoInclusion(2026, List.of(
            new ComponentSsoInclusionUpsertRequest(employee, PayrollComponent.SALARY, true)
        ), employee);

        // 2027: HR opens the matrix (which resolves the rolled-forward 2026 values), edits ONE
        // dropdown, and the frontend's save() sends only that one changed cell.
        repository.upsertComponentTaxTreatment(2027, List.of(
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY)
        ), employee);

        Map<PayrollComponent, PayrollTaxTreatment> resolved =
            repository.findComponentTaxTreatmentsByEmployee(2027).get(employee);

        assertThat(resolved.get(PayrollComponent.SPECIAL_PAY_1))
            .as("the edited component resolves to its own new 2027 row")
            .isEqualTo(PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY);
        assertThat(resolved.get(PayrollComponent.SPECIAL_PAY_2))
            .as("an untouched component must still roll forward from 2026 -- not read as stranded "
                + "just because a sibling component gained a 2027 row")
            .isEqualTo(PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);
        assertThat(resolved.get(PayrollComponent.BONUS_PAY))
            .as("a second untouched component must also still roll forward from 2026")
            .isEqualTo(PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY);
    }

    /**
     * D2, end-to-end through the real service: the stranded component from the test above is not
     * just a map-reading nuisance -- it is exactly what {@link PayrollCalculator#calculateClassified}
     * 409s a whole payroll run over the moment it carries a non-zero amount. Proves the fix all the
     * way up: a partial matrix save into a new tax year must not 409 the next run for the untouched
     * components.
     */
    @Test
    void payrollRunsForTheFollowingTaxYearAfterOnlyOneComponentWasSavedIntoIt() {
        long employee = seedEmployee("EMP-REV-008", "อี", "รีวิว");

        repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.REGULAR_REPROJECT),
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SPECIAL_PAY_2, PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL)
        ), employee);
        repository.upsertComponentSsoInclusion(2026, List.of(
            new ComponentSsoInclusionUpsertRequest(employee, PayrollComponent.SALARY, true)
        ), employee);

        // Only SPECIAL_PAY_1 gets a 2027 row -- SPECIAL_PAY_2 is never re-saved.
        repository.upsertComponentTaxTreatment(2027, List.of(
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY)
        ), employee);

        PayrollEmployeeInputRequest input = specialPay2Of(employee, new BigDecimal("1500.00"));

        assertThatCode(() -> payrollService.preview(
                new ProcessPayrollRequest(LocalDate.of(2027, 1, 1), List.of(input)), hr()))
            .as("SPECIAL_PAY_2 must still resolve its rolled-forward 2026 classification for the "
                + "2027 run, not 409 just because SPECIAL_PAY_1 was saved into 2027 first")
            .doesNotThrowAnyException();
    }

    private PayrollEmployeeInputRequest specialPay2Of(long employeeId, BigDecimal specialPay2) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            zero, specialPay2, zero, zero, zero, zero, zero, zero, zero, // specialPay1-9
            zero, // nonTaxableIncome
            zero, // unpaidLeaveDays
            zero, // studentLoanDeduction
            zero, // legalExecutionDeduction
            zero, // otherPostTaxDeductions
            zero, zero, zero, zero, zero, // spouse..maternity
            zero, zero, zero, zero, zero, // life..ssf
            zero, zero, zero, zero, zero, zero, // pension..political
            zero, // warningLetterDeduction
            zero, // customerReturnDeduction
            zero, // otherPretaxDeduction
            null, // withholdingTaxOverride
            zero, // mealAllowance
            zero, // perDiemExempt
            zero, // perDiemTaxable
            null, // perDiemBasis
            zero, // bonusPay
            zero, // otherOneOffPay
            false, // customerReturnAlreadyEarned
            null, // garnishmentType
            null // parentCareCount
        );
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    /**
     * Minor fix (fourth reachability audit, 2026-07-30): {@code @Valid} on the controller's
     * {@code List<ComponentTaxTreatmentUpsertRequest>} body does not cascade, so {@code @NotNull
     * employeeId}/{@code component} never fired. Now validated explicitly in {@link
     * PayrollService#upsertComponentTaxTreatments}, before the repository is ever reached.
     */
    @Test
    void upsertRejectsAMalformedItemWithANullEmployeeIdBeforeWritingAnything() {
        long employee = seedEmployee("EMP-REV-009", "เอฟ", "รีวิว");

        assertThatThrownBy(() -> payrollService.upsertComponentTaxTreatments(2026, List.of(
                new ComponentTaxTreatmentUpsertRequest(null, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.REGULAR_REPROJECT)
            ), hr()))
            .as("a null employeeId must be rejected with a clean 400, not reach the repository")
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> payrollService.upsertComponentTaxTreatments(2026, List.of(
                new ComponentTaxTreatmentUpsertRequest(employee, null, PayrollTaxTreatment.REGULAR_REPROJECT)
            ), hr()))
            .as("a null component must also be rejected with a clean 400")
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        // Wrong-way-round: a batch with ONE malformed item must write NOTHING, not silently apply
        // the valid items and skip the bad one.
        assertThatThrownBy(() -> payrollService.upsertComponentTaxTreatments(2026, List.of(
                new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.REGULAR_REPROJECT),
                new ComponentTaxTreatmentUpsertRequest(null, PayrollComponent.SPECIAL_PAY_2, PayrollTaxTreatment.REGULAR_REPROJECT)
            ), hr()))
            .isInstanceOf(ApiException.class);
        assertThat(repository.findComponentTaxTreatmentsByEmployee(2026))
            .as("the whole batch must be rejected before any write, including the otherwise-valid item")
            .doesNotContainKey(employee);
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
