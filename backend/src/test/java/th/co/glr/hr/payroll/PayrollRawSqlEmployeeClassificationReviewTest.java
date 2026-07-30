package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * REVIEWER-ADDED (fourth Opus review, 2026-07-30). The narrower half of the P0 that task 5's
 * three-layer fix does NOT cover.
 *
 * <p>{@link PayrollClassificationReachabilityIntegrationTest
 * #payrollRunsForAnEmployeeConfiguredOnlyThroughThePathsAProductionDeploymentActuallyHas} calls
 * {@code payrollRepository.seedSsoInclusionDefaults(...)} explicitly on its employee before
 * previewing. That single line is load-bearing: {@code PayrollService#hasBeenOnboardedForPayroll}
 * decides whether layer 3's read-time default fires by looking for at least one NON-SALARY row in
 * {@code hr.payroll_component_sso_inclusion}. With that call, layer 3 fires and the run previews.
 *
 * <p>Take the call away and the employee has zero rows in BOTH matrices -- which is exactly the
 * shape of an employee inserted by raw SQL after the backfills have already run:
 *
 * <ul>
 *   <li>{@code db/migration-uat/V900__uat_reference_and_employees.sql} inserts 93 employees with
 *       raw {@code INSERT INTO hr.employee}. Flyway orders merged locations by version, so on a
 *       FRESH uat database V96 (SSO backfill), V97 and V100 (tax-treatment backfill) all run
 *       BEFORE V900 and reach zero of those 93 rows.
 *   <li>{@code db/migration-uat/V902} then seeds fully-approved overtime that feeds payroll -- so
 *       {@code OVERTIME_PAY} is non-zero for those employees on the payroll page's own initial GET,
 *       with no HR input typed at all.
 * </ul>
 *
 * <p>This test asserts the behaviour that fix's own stated goal requires: an employee nothing has
 * ever seeded must still be payroll-runnable, because production has insert paths that seed
 * nothing. It is deliberately written wrong-way-round against the CURRENT implementation.
 */
class PayrollRawSqlEmployeeClassificationReviewTest extends AbstractPostgresIntegrationTest {

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
            mock(th.co.glr.hr.notification.NotificationService.class),
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
            new th.co.glr.hr.config.AppProperties());
    }

    /**
     * The uat-V900 / bulk-import population: inserted by raw SQL, seeded through NOTHING. Same
     * ฿407 พิเศษ 1 the reachability test uses (handoff section 9d, employee 10012's real June
     * figure).
     */
    @Test
    void payrollRunsForAnEmployeeInsertedByRawSqlAfterTheBackfillsAlreadyRan() {
        long employeeId = seedEmployeeByRawSql("RAWSQL-001", "อดิเทพ", "ดิบ", new BigDecimal("30000.00"));
        // Deliberately NOTHING seeded: no seedSsoInclusionDefaults, no seedComponentTaxTreatmentDefaults,
        // no seedRegularTaxTreatment. This is the uat V900 / demo bulk-import shape.

        PayrollEmployeeInputRequest input = specialPayOneOf(employeeId, new BigDecimal("407.00"));

        assertThatCode(() -> payrollService.preview(
                new ProcessPayrollRequest(LocalDate.of(2026, 3, 1), List.of(input)), hr()))
            .withFailMessage("""
                An employee inserted by raw SQL AFTER V96/V100 ran has zero rows in BOTH
                hr.payroll_component_sso_inclusion and hr.payroll_component_tax_treatment.
                PayrollService#hasBeenOnboardedForPayroll therefore returns false, treatmentsFor
                returns Map.of(), and calculateClassified 409s the whole run -- the exact P0 task 5
                set out to close, still open for the one population that has no seeding path at all.
                On a freshly rebuilt uat, V900's 93 employees plus V902's approved overtime put every
                one of them in this state on the payroll page's own initial GET.
                """)
            .doesNotThrowAnyException();
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

    private long seedEmployeeByRawSql(String code, String firstNameTh, String lastNameTh, BigDecimal salary) {
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
