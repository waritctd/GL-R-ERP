package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** Verifies stored allowances, director remuneration, and request-body non-taxable income reach payroll preview
 * through the database. */
class PayrollAllowanceDirectorNonTaxableIntegrationTest extends AbstractPostgresIntegrationTest {
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
        // Leave -> payroll unpaid-day deduction (2026-07-23): mechanical constructor-arity fix -- PayrollService
        // gained a LeaveRepository dependency for #suggestedInputs, unrelated to what this test exercises.
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

    // ---- P8: a stored tax-allowance declaration changes the bracket/amount through preview -----

    /** Salary 40,000, January (monthsRemaining = 12), no YTD. Baseline (no stored allowance): taxableAnnualIncome
     * 309,500.00 sits in the 10% bracket, annualTax 8,450.00, withholdingTax 704.00 (whole baht since 2026-08-28;
     * see PayrollPersistedPayslipIntegrationTest for the full trace -- identical inputs). */
    @Test
    void storedTaxAllowanceChangesTheBracketAndAmountThroughPreview() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("ALW-001", "มีค่าลดหย่อน", "ทดสอบ", new BigDecimal("40000.00"));

        PayrollLineDto beforeAllowance = previewLineFor(month, employeeId);
        assertThat(beforeAllowance.taxableAnnualIncome()).isEqualByComparingTo("309500.00");
        assertThat(beforeAllowance.annualTax()).isEqualByComparingTo("8450.00");
        assertThat(beforeAllowance.withholdingTax()).isEqualByComparingTo("704.00");

        payrollRepository.upsertTaxAllowances(2026, List.of(
            new EmployeeTaxAllowanceUpsertRequest(
                employeeId,
                BigDecimal.ZERO,                  // spouseAllowance
                new BigDecimal("100000.00"),      // childAllowance
                BigDecimal.ZERO,                  // parentCareAllowance
                BigDecimal.ZERO,                  // disabledCareAllowance
                BigDecimal.ZERO,                  // maternityAllowance
                BigDecimal.ZERO,                  // lifeInsuranceAllowance
                BigDecimal.ZERO,                  // healthInsuranceAllowance
                BigDecimal.ZERO,                  // parentHealthInsuranceAllowance
                BigDecimal.ZERO,                  // rmfAllowance
                BigDecimal.ZERO,                  // ssfAllowance
                BigDecimal.ZERO,                  // pensionInsuranceAllowance
                BigDecimal.ZERO,                  // thaiEsgAllowance
                BigDecimal.ZERO,                  // homeLoanInterestAllowance
                BigDecimal.ZERO,                  // educationDonation
                BigDecimal.ZERO,                  // generalDonation
                BigDecimal.ZERO)                  // politicalDonation
        ), employeeId);

        PayrollLineDto afterAllowance = previewLineFor(month, employeeId);

        assertThat(afterAllowance.taxAllowanceTotal()).isEqualByComparingTo("170500.00");
        assertThat(afterAllowance.taxableAnnualIncome()).isEqualByComparingTo("209500.00");
        assertThat(afterAllowance.annualTax()).isEqualByComparingTo("2975.00");
        assertThat(afterAllowance.withholdingTax()).isEqualByComparingTo("248.00");
        assertThat(afterAllowance.withholdingTax()).isLessThan(beforeAllowance.withholdingTax());
    }

    // --- helpers ------------------------------------------------------------

    private PayrollLineDto previewLineFor(LocalDate payrollMonth, long employeeId) {
        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(payrollMonth, List.of()), hr());
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, BigDecimal salary) {
        long employeeId = jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, :salary, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
        // SALARY needs no tax-treatment seed (locked to REGULAR_REPROJECT regardless), but SSO inclusion has no
        // calculator-layer default -- seed it so socialSecurity comes out non-zero, matching every assertion in this
        // file that expects the pre-task-2 salary-only SSO base.
        seedSsoIncluded(employeeId, 2026, PayrollComponent.SALARY);
        return employeeId;
    }

}
