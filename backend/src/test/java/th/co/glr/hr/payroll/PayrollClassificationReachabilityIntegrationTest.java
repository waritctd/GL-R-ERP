package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * REVIEWER-ADDED (Opus review, 2026-07-30). Every other payroll integration test on this branch
 * seeds {@code hr.payroll_component_tax_treatment} rows through
 * {@link AbstractPostgresIntegrationTest#seedRegularTaxTreatment} before driving
 * {@link PayrollService#preview}/{@code #process}. That helper exists purely for the tests -- it
 * calls {@link PayrollRepository#upsertComponentTaxTreatment} directly. So the whole suite is green
 * over a question none of it asks: <b>how does a row ever get into that table on a real deployment?</b>
 *
 * <p>The answer, as at {@code b5733607}, is that it never does:
 * <ul>
 *   <li>V95 creates the table and seeds only the {@code hr.payroll_pay_component} lookup -- no
 *       classification rows for any employee.
 *   <li>V96 backfills {@code hr.payroll_component_sso_inclusion} for every pre-existing employee
 *       (task 1's known risk 3) but has no counterpart for tax treatment.
 *   <li>{@link PayrollController} exposes no mapping that reaches
 *       {@code upsertComponentTaxTreatment}; {@code EmployeeService} calls only
 *       {@code seedSsoInclusionDefaults}.
 *   <li>The frontend has no matrix screen and {@code hrApi.js} has no method for it.
 * </ul>
 *
 * <p>Meanwhile {@link PayrollCalculator#calculateClassified} -- the only engine
 * {@code PayrollService#calculateLine} calls since task 2 -- rejects the whole run with a 409 for
 * any non-zero component that has no stored treatment. SALARY is exempt (hardcoded to
 * REGULAR_REPROJECT), but every other component is not: a special pay, overtime, a commission feed,
 * or a director fee is enough. Production carries all of those today (handoff section 9d records
 * employee 10012 at {@code special_pay_1 = 407}), and the uat seed's V902 deliberately seeds
 * fully-approved overtime that "feeds payroll".
 *
 * <p>The two tests below are the reachability pair the rest of the suite skips.
 */
class PayrollClassificationReachabilityIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final int TAX_YEAR = 2026;

    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class));
        payrollService = new PayrollService(
            payrollRepository,
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
                mock(AuditService.class)));
    }

    /**
     * The employee is configured through EXACTLY the paths a real deployment has: V96's SSO-inclusion
     * backfill (reproduced here by its own seeding method, which is what {@code EmployeeService} calls
     * on the create path) and nothing else. No {@code seedRegularTaxTreatment} -- deliberately, because
     * nothing in {@code src/main} or in any migration can produce that row.
     *
     * <p>HR then does the most ordinary thing possible: types ฿407 of พิเศษ 1 (ค่าครองชีพ), the exact
     * figure handoff section 9d transcribes for employee 10012 out of the accountant's June sheet.
     *
     * <p>Expected: the run previews. Actual at {@code b5733607}: 409, "has a non-zero amount of 407.00
     * but no withholding-tax classification. HR must classify this component before payroll can run" --
     * with no screen, endpoint, or migration anywhere that lets HR do that. Payroll is unrunnable for
     * every employee who receives anything beyond bare salary.
     */
    @Test
    void payrollRunsForAnEmployeeConfiguredOnlyThroughThePathsAProductionDeploymentActuallyHas() {
        long employeeId = seedEmployee("REACH-001", "มณฑ์ชญา", "รีช", new BigDecimal("30000.00"));
        // The one and only classification-adjacent seeding a real deployment performs.
        payrollRepository.seedSsoInclusionDefaults(employeeId, TAX_YEAR, null);

        PayrollEmployeeInputRequest input = specialPayOneOf(employeeId, new BigDecimal("407.00"));

        assertThatCode(() -> payrollService.preview(
                new ProcessPayrollRequest(LocalDate.of(2026, 3, 1), List.of(input)), hr()))
            .withFailMessage("""
                Payroll must be runnable for an employee configured through the paths production has.
                calculateClassified rejects any non-zero component with no stored tax treatment, but
                hr.payroll_component_tax_treatment is only ever written by
                PayrollRepository#upsertComponentTaxTreatment, which has no caller in src/main, no
                controller mapping, no frontend, and no migration backfill. Every green test on this
                branch seeds the row itself via AbstractPostgresIntegrationTest#seedRegularTaxTreatment.
                """)
            .doesNotThrowAnyException();
    }

    /**
     * The structural half, and the reason the test above is not merely a fixture omission: even given
     * a willing HR user and a REST client, there is no request that can create the row the engine
     * demands. Asserted against {@link PayrollController} because that is where every other payroll
     * declaration surface lives ({@code PUT /api/payroll/tax-allowances}, {@code PUT
     * /api/payroll/ytd-seed}) -- if the matrix ever gets an endpoint it belongs beside them.
     */
    @Test
    void hrHasSomeHttpWayToClassifyAComponentBeforeTheEngineDemandsIt() {
        List<String> mappingsAcceptingATreatmentUpsert = Arrays.stream(PayrollController.class.getDeclaredMethods())
            .filter(PayrollClassificationReachabilityIntegrationTest::acceptsTreatmentUpsert)
            .map(Method::getName)
            .toList();

        assertThat(mappingsAcceptingATreatmentUpsert)
            .withFailMessage("""
                PayrollController exposes no mapping that accepts a ComponentTaxTreatmentUpsertRequest,
                so hr.payroll_component_tax_treatment can never be populated on a real deployment --
                yet PayrollCalculator#calculateClassified 409s the entire payroll run without it.
                Either give the matrix an HTTP surface (and a screen), or give the engine a documented
                default/backfill, before this branch reaches main.
                """)
            .isNotEmpty();
    }

    private static boolean acceptsTreatmentUpsert(Method method) {
        return Arrays.stream(method.getGenericParameterTypes())
            .map(java.lang.reflect.Type::getTypeName)
            .anyMatch(name -> name.contains(ComponentTaxTreatmentUpsertRequest.class.getSimpleName()));
    }

    // ------------------------------------------------------------------

    private PayrollEmployeeInputRequest specialPayOneOf(long employeeId, BigDecimal specialPay1) {
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

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, BigDecimal salary) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, :salary, TRUE)
            RETURNING employee_id
            """,
            java.util.Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
    }
}
