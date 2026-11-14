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
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentSsoInclusionUpsertRequest;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** REVIEWER-ADDED coverage for V95 (branch feat/payroll-classification-and-hr-declarations), written against a
 * real PostgreSQL database. The implementer's PayrollClassificationAndSsoInclusionIntegrationTest asserts the
 * SALARY lock only against a wrong non-null treatment (EXTRA_KNOWN_FREQUENCY). */
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
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
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
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new th.co.glr.hr.config.AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));
    }

    /** The SALARY lock must hold against NULL, not only against a wrong non-null treatment. */
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

    /** The same hole, reached through the ordinary update path rather than a fresh insert: an already-correct
     * SALARY classification must not be erasable back to unclassified. */
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

    /** Regression guard for the SSO-inclusion read path, which still uses Collectors.groupingBy -> toMap — the
     * collector that threw NPE on a null VALUE in the tax-treatment map. */
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

    /** Every PayrollComponent must exist as a row in hr.payroll_pay_component, because the two matrices carry a
     * foreign key to it. A value added to the Java enum without an appended INSERT in a migration would fail only
     * at write time, per component, in production. */
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

    /** Defect fix (Opus review, 2026-07-29) -- the January cliff, corrected:
     * PayrollRepository#findComponentTaxTreatmentsByEmployee and PayrollRepository #findComponentSsoInclusionByEmployee
     * used to resolve one effective tax year for the WHOLE table (SELECT MAX(tax_year) ... */
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

    /** D2 (fourth reachability audit, 2026-07-30): a partial save into a new tax year must not strand an employee's
     * OTHER, already-resolved components. */
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

        // 2027: HR opens the matrix (which resolves the rolled-forward 2026 values), edits ONE dropdown, and the
        // frontend's save() sends only that one changed cell.
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

    /** D2, end-to-end through the real service: the stranded component from the test above is not just a
     * map-reading nuisance -- it is exactly what PayrollCalculator#calculateClassified 409s a whole payroll run
     * over the moment it carries a non-zero amount. */
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

    /** companion coverage for PayrollPartialClassificationCliffReviewTest. That test proves an ABSENT component
     * still gets a synthesized default after the "partial classification cliff" fix. */
    @Test
    void aComponentExplicitlyLeftUnclassifiedStillBlocksAfterTheOtherComponentsGetSynthesizedDefaults() {
        long employee = seedEmployee("EMP-REV-010", "จี", "รีวิว");

        // HR opens the matrix, looks at SPECIAL_PAY_1, and explicitly leaves it "ยังไม่จัดประเภท" -- the frontend
        // submits this as taxTreatment: null, which the repository stores as a present row with a NULL value (see
        // ComponentTaxTreatmentUpsertRequest's own javadoc).
        repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SPECIAL_PAY_1, null)
        ), employee);

        assertThat(repository.findComponentTaxTreatmentsByEmployee(2026).get(employee))
            .as("the row must exist and be present-and-null, not absent")
            .containsKey(PayrollComponent.SPECIAL_PAY_1)
            .containsEntry(PayrollComponent.SPECIAL_PAY_1, null);

        PayrollEmployeeInputRequest input = specialPay1Of(employee, new BigDecimal("1500.00"));

        assertThatThrownBy(() -> payrollService.preview(
                new ProcessPayrollRequest(LocalDate.of(2026, 3, 1), List.of(input)), hr()))
            .as("a component HR explicitly left unclassified must still 409 -- the merge that fills "
                + "in defaults for components HR never touched must not also paper over one HR "
                + "deliberately declined to classify")
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT))
            .hasMessageContaining("SPECIAL_PAY_1")
            .hasMessageContaining("EMP-REV-010");
    }

    private PayrollEmployeeInputRequest specialPay1Of(long employeeId, BigDecimal specialPay1) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            specialPay1, zero, zero, zero, zero, zero, zero, zero, zero, // specialPay1-9
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

    /** @Valid on the controller's List<ComponentTaxTreatmentUpsertRequest> body does not cascade, so @NotNull
     * employeeId/component never fired. Now validated explicitly in PayrollService#upsertComponentTaxTreatments,
     * before the repository is ever reached. */
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

        // Wrong-way-round: a batch with ONE malformed item must write NOTHING, not silently apply the valid items and
        // skip the bad one.
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
