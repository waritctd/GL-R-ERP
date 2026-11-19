package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Daily-rate (pay_type = 'D') payroll support (2026-07-30, owner-requested payroll-math fix). Before this fix,
 * PayrollService put employee.current_salary straight into PayrollComponent.SALARY regardless of
 * hr.employee.pay_type, so a daily-rate employee's PER-DAY rate was paid as their MONTHLY salary. */
class PayrollDailyRateIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final BigDecimal DAY_RATE = new BigDecimal("450.00");

    private PayrollRepository payrollRepository;
    private PayrollService payrollService;
    private CommissionService commissionService;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        commissionService = mock(CommissionService.class);
        when(commissionService.payrollCommissionTotalsByEmployee(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Map.of());
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

    /** The reference data from the accountant's workbooks (task spec): rate * daysWorked must reproduce every one
     * of these six real monthly figures exactly, and the SALARY component gross-earnings figure is what actually
     * gets taxed/SSO'd -- not the raw ฿450 rate. */
    @Test
    void ratesTimesDaysWorkedReproducesEverySixMonthsOfAccountantFigures() {
        long employeeId = seedEmployee("DR-001", "พัฒวุฒิ", "สังฆวาส", DAY_RATE, "D");

        assertMonthlyGrossPay(employeeId, LocalDate.of(2026, 2, 1), 21, "9450.00");
        assertMonthlyGrossPay(employeeId, LocalDate.of(2026, 3, 1), 24, "10800.00");
        assertMonthlyGrossPay(employeeId, LocalDate.of(2026, 4, 1), 27, "12150.00");
        assertMonthlyGrossPay(employeeId, LocalDate.of(2026, 5, 1), 25, "11250.00");
        assertMonthlyGrossPay(employeeId, LocalDate.of(2026, 6, 1), 25, "11250.00");
        assertMonthlyGrossPay(employeeId, LocalDate.of(2026, 7, 1), 25, "11250.00");
    }

    private void assertMonthlyGrossPay(long employeeId, LocalDate month, int daysWorked, String expectedGrossPay) {
        PayrollEmployeeInputRequest input = dailyRateInput(employeeId, new BigDecimal(daysWorked), BigDecimal.ZERO);
        payrollService.process(new ProcessPayrollRequest(month, List.of(input)), hr());

        PayrollLineDto reloaded = reloadedLineFor(month, employeeId);

        assertThat(reloaded.baseSalary())
            .as("SALARY component for %s (%d days @ ฿450) must equal the accountant's real monthly pay",
                month, daysWorked)
            .isEqualByComparingTo(expectedGrossPay);
        assertThat(reloaded.grossEarnings())
            .as("no other component is paid this run, so gross earnings equals the SALARY component")
            .isEqualByComparingTo(expectedGrossPay);
        assertThat(reloaded.dailyRate())
            .as("the reported daily rate must always be the employee's TRUE per-day rate (฿450), "
                + "never grossPay/30 -- which would drift with every month's different day count")
            .isEqualByComparingTo(DAY_RATE);
        // Survives a reload: daysWorked and payType must round-trip verbatim.
        assertThat(reloaded.daysWorked())
            .as("daysWorked must round-trip through the DB exactly as HR typed it")
            .isEqualByComparingTo(new BigDecimal(daysWorked));
        assertThat(reloaded.payType()).isEqualTo("D");
    }

    /** Design constraint 3: unpaid leave for a daily-rate employee must be deducted at the TRUE ฿450/day rate, not
     * grossPay/30 (which would be ฿11,250/30 = ฿375/day for a 25-day month -- a completely different, wrong figure
     * that happens to depend on how many days were worked). */
    @Test
    void unpaidLeaveIsDeductedAtTheTrueDailyRateNotGrossOverThirty() {
        long employeeId = seedEmployee("DR-002", "ทดสอบ", "วันลา", DAY_RATE, "D");
        PayrollEmployeeInputRequest input = dailyRateInput(employeeId, new BigDecimal("25"), new BigDecimal("2"));

        payrollService.process(new ProcessPayrollRequest(LocalDate.of(2026, 5, 1), List.of(input)), hr());

        PayrollLineDto reloaded = reloadedLineFor(LocalDate.of(2026, 5, 1), employeeId);

        assertThat(reloaded.unpaidLeaveDeduction())
            .as("2 unpaid days at the TRUE ฿450/day rate = ฿900 -- NOT (11250/30)*2 = ฿750")
            .isEqualByComparingTo("900.00");
        assertThat(reloaded.grossEarnings())
            .as("gross earnings themselves are the full 25-day pay; the leave deduction is separate")
            .isEqualByComparingTo("11250.00");
    }

    /** Design constraint 4: SSO and tax must follow the CORRECTED gross base (rate * daysWorked), not the raw ฿450
     * rate that would previously have been fed straight into SALARY. */
    @Test
    void ssoIsComputedOnTheCorrectedGrossBaseNotTheRawDayRate() {
        long employeeId = seedEmployee("DR-003", "ทดสอบ", "ประกันสังคม", DAY_RATE, "D");
        PayrollEmployeeInputRequest input = dailyRateInput(employeeId, new BigDecimal("25"), BigDecimal.ZERO);

        payrollService.process(new ProcessPayrollRequest(LocalDate.of(2026, 5, 1), List.of(input)), hr());

        PayrollLineDto reloaded = reloadedLineFor(LocalDate.of(2026, 5, 1), employeeId);

        assertThat(reloaded.ssoWageBase())
            .as("SSO wage base must be the ฿11,250 gross (within the ฿1,650-17,500 band), not ฿450")
            .isEqualByComparingTo("11250.00");
        assertThat(reloaded.socialSecurity())
            .as("SSO on the corrected ฿11,250 base is ฿562.50, rounded to ฿563 whole baht "
                + "(มาตรา 46 วรรคท้าย) -- not the ฿22.50/฿23 the raw ฿450 day rate would give")
            .isEqualByComparingTo("563.00");
    }

    // ------------------------------------------------------------------

    private PayrollLineDto reloadedLineFor(LocalDate month, long employeeId) {
        long periodId = payrollRepository.findPeriodByMonth(month)
            .map(PayrollPeriodDto::id)
            .orElseThrow(() -> new AssertionError("no period persisted for " + month));
        return payrollRepository.findLines(periodId).stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no persisted line for employee " + employeeId + " / " + month));
    }

    private PayrollEmployeeInputRequest dailyRateInput(long employeeId, BigDecimal daysWorked, BigDecimal unpaidLeaveDays) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            zero, zero, zero, zero, zero, zero, zero, zero, zero, // specialPay1-9
            zero, // nonTaxableIncome
            unpaidLeaveDays,
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
            null, // parentCareCount
            daysWorked
        );
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, BigDecimal salary, String payType) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, pay_type, is_active)
            VALUES (:code, :first, :last, :salary, :payType, TRUE)
            RETURNING employee_id
            """,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("code", code)
                .addValue("first", firstNameTh)
                .addValue("last", lastNameTh)
                .addValue("salary", salary)
                .addValue("payType", payType),
            Long.class);
    }
}
