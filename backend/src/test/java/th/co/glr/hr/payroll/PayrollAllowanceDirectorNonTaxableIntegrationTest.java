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
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * P8 + P10 + P12: three independent inputs to {@link PayrollService#preview} that all feed
 * {@link PayrollCalculator}, each proven through the real DB seam that only {@link
 * PayrollService#preview} exercises: stored {@code hr.employee_tax_allowance} declarations (C1),
 * director remuneration on {@code hr.employee} (C3), and per-run non-taxable income from the
 * request body.
 */
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
            mock(AttachmentRepository.class));
        // Leave -> payroll unpaid-day deduction (2026-07-23): mechanical constructor-arity fix --
        // PayrollService gained a LeaveRepository dependency for #suggestedInputs, unrelated to what
        // this test exercises.
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

    // ---- P8: a stored tax-allowance declaration changes the bracket/amount through preview -----

    /**
     * Salary 40,000, January (monthsRemaining = 12), no YTD. Baseline (no stored allowance):
     * taxableAnnualIncome 309,500.00 sits in the 10% bracket, annualTax 8,450.00, withholdingTax
     * 704.17 (see {@link PayrollPersistedPayslipIntegrationTest} for the full trace -- identical
     * inputs).
     *
     * <p>With a stored {@code childAllowance} of 100,000.00 for the same employee/tax-year: family
     * allowance = 100,000.00, taxAllowanceTotal = 60,000 (personal) + 10,500 (SSO) + 100,000 (family)
     * = 170,500.00; taxableAnnualIncome = 480,000 - 100,000 - 170,500 = 209,500.00 -- now entirely
     * inside the 5% bracket; annualTax = (209,500 - 150,000) x 5% = 2,975.00; withholdingTax =
     * 2,975 / 12 = 247.9166... -> HALF_UP 247.92. The allowance doesn't just shave the total, it
     * moves the employee out of the 10% bracket entirely.
     */
    @Test
    void storedTaxAllowanceChangesTheBracketAndAmountThroughPreview() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("ALW-001", "มีค่าลดหย่อน", "ทดสอบ", new BigDecimal("40000.00"));

        PayrollLineDto beforeAllowance = previewLineFor(month, employeeId);
        assertThat(beforeAllowance.taxableAnnualIncome()).isEqualByComparingTo("309500.00");
        assertThat(beforeAllowance.annualTax()).isEqualByComparingTo("8450.00");
        assertThat(beforeAllowance.withholdingTax()).isEqualByComparingTo("704.17");

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
        assertThat(afterAllowance.withholdingTax()).isEqualByComparingTo("247.92");
        assertThat(afterAllowance.withholdingTax()).isLessThan(beforeAllowance.withholdingTax());
    }

    // ---- P10: director remuneration through preview: gross + tax rise, SSO stays zero -----------

    /**
     * A director has base salary 0 and only {@code director_remuneration} (150,000.00/month,
     * January, no YTD). Trace: grossEarnings = grossTaxableIncome = 150,000.00; {@code ssoWageBase}
     * is derived from base salary alone (0 - 0 = 0), so it floors at zero rather than the 1,650
     * minimum -- socialSecurity = 0.00; projectedAnnualIncome = 1,800,000.00; taxExpenseDeduction
     * caps at 100,000.00; SSO allowance = 0 (no contribution to allow for); taxAllowanceTotal =
     * 60,000.00; taxableAnnualIncome = 1,640,000.00; annualTax = 150k@5+200k@10+250k@15+250k@20+
     * 640k@25 = 7,500+20,000+37,500+50,000+160,000 = 275,000.00; withholdingTax = 275,000/12 =
     * 22,916.6666... -> HALF_UP 22,916.67.
     *
     * <p>A plain salaried employee previewed in the same run keeps paying ordinary SSO (875.00),
     * proving the zero-SSO result is specific to the director's remuneration type, not some
     * preview-wide default.
     */
    @Test
    void directorRemunerationRaisesGrossAndTaxButSocialSecurityStaysZeroThroughPreview() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long director = seedDirector("DIR-P10", "กรรมการ", "ทดสอบ", new BigDecimal("150000.00"));
        long plainEmployee = seedEmployee("EMP-P10", "พนักงาน", "ทั่วไป", new BigDecimal("30000.00"));

        PayrollLineDto directorLine = previewLineFor(month, director);
        PayrollLineDto plainLine = previewLineFor(month, plainEmployee);

        assertThat(directorLine.baseSalary()).isEqualByComparingTo("0.00");
        assertThat(directorLine.directorRemuneration()).isEqualByComparingTo("150000.00");
        assertThat(directorLine.grossEarnings()).isEqualByComparingTo("150000.00");
        assertThat(directorLine.grossTaxableIncome()).isEqualByComparingTo("150000.00");
        assertThat(directorLine.ssoWageBase()).isEqualByComparingTo("0.00");
        assertThat(directorLine.socialSecurity()).isEqualByComparingTo("0.00");
        assertThat(directorLine.withholdingTax()).isEqualByComparingTo("22916.67");
        assertThat(directorLine.netPay()).isEqualByComparingTo("127083.33");

        // The director's zero SSO is specific to them, not a preview-wide effect.
        assertThat(plainLine.socialSecurity()).isEqualByComparingTo("875.00");
    }

    // ---- P12: non-taxable income through preview: excluded from tax/SSO, added back to net ------

    /**
     * Salary 30,000, January, nonTaxableIncome 5,000 supplied in the request body. Reuses the exact
     * scenario and pinned figures from {@code PayrollCalculatorTest#nonTaxableIncomeIsExcludedFromTaxAndSsoButAddedBackToNet}
     * (grossEarnings 30,000.00 -- non-taxable income never joins gross earnings; grossTaxableIncome
     * 30,000.00; socialSecurity 875.00; withholdingTax 164.58; netPay = 30,000 - 1,039.58 + 5,000 =
     * 33,960.42) but drives it through {@link PayrollService#preview} against real Postgres via a
     * {@link PayrollEmployeeInputRequest}, rather than constructing {@link PayrollCalculationInput}
     * directly.
     */
    @Test
    void nonTaxableIncomeThroughPreviewIsExcludedFromTaxAndSsoButAddedBackToNet() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("NTX-001", "ไม่คิดภาษี", "ทดสอบ", new BigDecimal("30000.00"));

        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(month, List.of(inputWithNonTaxableIncome(employeeId, new BigDecimal("5000.00")))),
            hr());
        PayrollLineDto line = period.lines().stream()
            .filter(item -> item.employeeId() == employeeId)
            .findFirst()
            .orElseThrow();

        assertThat(line.nonTaxableIncome()).isEqualByComparingTo("5000.00");
        assertThat(line.grossEarnings()).isEqualByComparingTo("30000.00");
        assertThat(line.grossTaxableIncome()).isEqualByComparingTo("30000.00");
        assertThat(line.socialSecurity()).isEqualByComparingTo("875.00");
        assertThat(line.withholdingTax()).isEqualByComparingTo("164.58");
        assertThat(line.netPay()).isEqualByComparingTo("33960.42");
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

    private PayrollEmployeeInputRequest inputWithNonTaxableIncome(long employeeId, BigDecimal nonTaxableIncome) {
        return new PayrollEmployeeInputRequest(
            employeeId,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, // specialPay1-4
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, // specialPay5-8
            nonTaxableIncome,
            BigDecimal.ZERO, // unpaidLeaveDays
            BigDecimal.ZERO, // studentLoanDeduction
            BigDecimal.ZERO, // legalExecutionDeduction
            BigDecimal.ZERO, // otherPostTaxDeductions
            BigDecimal.ZERO, // spouseAllowance
            BigDecimal.ZERO, // childAllowance
            BigDecimal.ZERO, // parentCareAllowance
            BigDecimal.ZERO, // disabledCareAllowance
            BigDecimal.ZERO, // maternityAllowance
            BigDecimal.ZERO, // lifeInsuranceAllowance
            BigDecimal.ZERO, // healthInsuranceAllowance
            BigDecimal.ZERO, // parentHealthInsuranceAllowance
            BigDecimal.ZERO, // rmfAllowance
            BigDecimal.ZERO, // ssfAllowance
            BigDecimal.ZERO, // pensionInsuranceAllowance
            BigDecimal.ZERO, // thaiEsgAllowance
            BigDecimal.ZERO, // homeLoanInterestAllowance
            BigDecimal.ZERO, // educationDonation
            BigDecimal.ZERO, // generalDonation
            BigDecimal.ZERO, // politicalDonation
            BigDecimal.ZERO, // warningLetterDeduction
            BigDecimal.ZERO, // customerReturnDeduction
            BigDecimal.ZERO, // otherPretaxDeduction
            null); // withholdingTaxOverride (none)
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
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
    }

    /** A director: no salary at all, only director_remuneration -- must still be payroll-eligible. */
    private long seedDirector(String code, String firstNameTh, String lastNameTh, BigDecimal directorRemuneration) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, director_remuneration, is_active)
            VALUES (:code, :first, :last, 0, :directorRemuneration, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "directorRemuneration", directorRemuneration),
            Long.class);
    }
}
