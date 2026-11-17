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
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** REVIEWER-ADDED (Opus review, 2026-07-30). The answer, as at b5733607, is that it never does: V95 creates the
 * table and seeds only the hr.payroll_pay_component lookup -- no classification rows for any employee. */
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
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
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
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));
    }

    /** The employee is configured through EXACTLY the paths a real deployment has: V96's SSO-inclusion backfill
     * (reproduced here by its own seeding method, which is what EmployeeService calls on the create path) and
     * nothing else. */
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

    /** The structural half, and the reason the test above is not merely a fixture omission: even given a willing HR
     * user and a REST client, there is no request that can create the row the engine demands. */
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
