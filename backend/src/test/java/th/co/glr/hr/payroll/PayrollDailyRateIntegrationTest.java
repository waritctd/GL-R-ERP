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

/**
 * Daily-rate (pay_type = 'D') payroll support (2026-07-30, owner-requested payroll-math fix).
 *
 * <p>Before this fix, {@code PayrollService} put {@code employee.current_salary} straight into
 * {@code PayrollComponent.SALARY} regardless of {@code hr.employee.pay_type}, so a daily-rate
 * employee's PER-DAY rate was paid as their MONTHLY salary. Verified in production: employee_id
 * 203 (พัฒวุฒิ สังฆวาส, code 10060, ฿450/day) would be paid ฿450 instead of the accountant's real
 * figures for each month, e.g. ฿11,250 for 25 days in May/Jun/Jul 2026.
 *
 * <p>Driven through the real {@link PayrollService} + real Postgres (not Mockito), exactly like
 * {@code PayrollCustomerReturnRoundTripIntegrationTest}: the point under test is that
 * {@code daysWorked} survives an actual INSERT/SELECT round trip through {@link PayrollRepository}
 * and that the SALARY/SSO/tax arithmetic is correct end to end, which a mocked repository would
 * "pass" regardless of what the SQL and calculator actually produce.
 */
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
            new th.co.glr.hr.config.AppProperties());
    }

    /**
     * The reference data from the accountant's workbooks (task spec): {@code rate * daysWorked}
     * must reproduce every one of these six real monthly figures exactly, and the SALARY component
     * gross-earnings figure is what actually gets taxed/SSO'd -- not the raw ฿450 rate.
     */
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

    /**
     * Design constraint 3: unpaid leave for a daily-rate employee must be deducted at the TRUE
     * ฿450/day rate, not grossPay/30 (which would be ฿11,250/30 = ฿375/day for a 25-day month --
     * a completely different, wrong figure that happens to depend on how many days were worked).
     */
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

    /**
     * Design constraint 4: SSO and tax must follow the CORRECTED gross base (rate * daysWorked),
     * not the raw ฿450 rate that would previously have been fed straight into SALARY.
     */
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
            .as("5% of the corrected ฿11,250 base = ฿562.50, not 5% of ฿450 = ฿22.50")
            .isEqualByComparingTo("562.50");
    }

    /**
     * Design constraint 1 (the primary regression risk): a monthly employee's computed pay must not
     * change by one satang. pay_type is NULL here (172 real rows have no pay_type at all), which
     * must be treated identically to 'M'.
     */
    @Test
    void monthlyEmployeeWithNullPayTypeIsUnaffectedByDailyRateSupport() {
        long employeeId = seedEmployee("DR-004", "ทดสอบ", "รายเดือน", new BigDecimal("30000.00"), null);
        // daysWorked is entered (as if by mistake) but must be a complete no-op for a monthly employee.
        PayrollEmployeeInputRequest input = dailyRateInput(employeeId, new BigDecimal("18"), BigDecimal.ZERO);

        payrollService.process(new ProcessPayrollRequest(LocalDate.of(2026, 5, 1), List.of(input)), hr());

        PayrollLineDto reloaded = reloadedLineFor(LocalDate.of(2026, 5, 1), employeeId);

        assertThat(reloaded.baseSalary())
            .as("a monthly employee's SALARY component is their current_salary, unconditionally -- "
                + "daysWorked must never multiply it")
            .isEqualByComparingTo("30000.00");
        assertThat(reloaded.dailyRate())
            .as("the /30 convention for a monthly employee is completely unchanged")
            .isEqualByComparingTo("1000.0000");
        assertThat(reloaded.ssoWageBase())
            .as("SSO base for a monthly employee is unaffected")
            .isEqualByComparingTo("17500.00"); // ceiling: 30000 clamped to the 17,500 max base
        assertThat(reloaded.socialSecurity())
            .as("5% of the ceiling base = ฿875, exactly as it always has been")
            .isEqualByComparingTo("875.00");
        assertThat(reloaded.payType()).isNull();
        assertThat(reloaded.daysWorked())
            .as("daysWorked was typed but must not even round-trip meaningfully for a monthly "
                + "employee -- it is persisted verbatim (harmless) but never read for arithmetic")
            .isEqualByComparingTo("18");
    }

    /**
     * Explicit pay_type = 'M' is the same as NULL -- both are monthly.
     */
    @Test
    void explicitMonthlyPayTypeBehavesIdenticallyToNull() {
        long employeeId = seedEmployee("DR-005", "ทดสอบ", "เอ็มชัดเจน", new BigDecimal("30000.00"), "M");
        PayrollEmployeeInputRequest input = dailyRateInput(employeeId, BigDecimal.ZERO, BigDecimal.ZERO);

        payrollService.process(new ProcessPayrollRequest(LocalDate.of(2026, 5, 1), List.of(input)), hr());

        PayrollLineDto reloaded = reloadedLineFor(LocalDate.of(2026, 5, 1), employeeId);

        assertThat(reloaded.baseSalary()).isEqualByComparingTo("30000.00");
        assertThat(reloaded.dailyRate()).isEqualByComparingTo("1000.0000");
        assertThat(reloaded.payType()).isEqualTo("M");
    }

    /**
     * Finding 3 fix (Opus review, 2026-07-30): {@code findLines} used to read {@code e.pay_type} LIVE
     * off the employee join, so editing the employee's {@code pay_type} AFTER processing retroactively
     * changed how an already-processed line reported itself -- the days-worked figure would vanish
     * from the reload (payType no longer 'D') while {@code days_worked} stayed on the row, and a
     * reprocess of the same month would silently recompute SALARY as the bare rate again, reintroducing
     * the exact bug this branch fixes. {@code pay_type} is now frozen on {@code payroll_line} at
     * processing time (mirroring {@code hr.overtime_request.salary_basis}), so a re-open must keep
     * reporting the pay_type AS OF THE RUN, not the employee's current value.
     */
    @Test
    void reopeningAProcessedLineReportsThePayTypeAsOfTheRunNotTheEmployeesCurrentValue() {
        long employeeId = seedEmployee("DR-006", "ทดสอบ", "ล็อคประเภท", DAY_RATE, "D");
        PayrollEmployeeInputRequest input = dailyRateInput(employeeId, new BigDecimal("25"), BigDecimal.ZERO);
        LocalDate month = LocalDate.of(2026, 5, 1);

        payrollService.process(new ProcessPayrollRequest(month, List.of(input)), hr());

        // Someone edits the employee's pay_type AFTER the month is already processed -- e.g. HR
        // corrects a miscoded employee, or (Finding 7) the cleanup migration flips a stale row.
        jdbc.update(
            "UPDATE hr.employee SET pay_type = 'M' WHERE employee_id = :employeeId",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("employeeId", employeeId));

        PayrollLineDto reopened = reloadedLineFor(month, employeeId);

        assertThat(reopened.payType())
            .as("pay_type is frozen on the line at processing time -- a later employee edit must not "
                + "retroactively change how this historical line reports itself")
            .isEqualTo("D");
        assertThat(reopened.daysWorked())
            .as("the days-worked figure must still be attached to a 'D' line, matching payType above")
            .isEqualByComparingTo("25");
        assertThat(reopened.baseSalary())
            .as("the persisted SALARY figure itself is obviously unaffected by a later employee edit "
                + "-- this just confirms the read-back is internally consistent")
            .isEqualByComparingTo("11250.00");
    }

    /**
     * Finding 4 fix (Opus review, 2026-07-30): 31 is the INCLUSIVE ceiling (see V103's {@code
     * chk_payroll_line_days_worked_range} and {@code PayrollService#MAX_DAYS_WORKED}) -- a real
     * 31-day month must still be payable in full, not off-by-one rejected.
     */
    @Test
    void exactlyThirtyOneDaysIsAcceptedAsTheInclusiveCeiling() {
        long employeeId = seedEmployee("DR-007", "สามสิบเอ็ด", "วัน", DAY_RATE, "D");
        PayrollEmployeeInputRequest input = dailyRateInput(employeeId, new BigDecimal("31"), BigDecimal.ZERO);

        payrollService.process(new ProcessPayrollRequest(LocalDate.of(2026, 7, 1), List.of(input)), hr());

        PayrollLineDto reloaded = reloadedLineFor(LocalDate.of(2026, 7, 1), employeeId);
        assertThat(reloaded.baseSalary())
            .as("31 days x ฿450 = ฿13,950 -- the ceiling itself must still be payable, not rejected")
            .isEqualByComparingTo("13950.00");
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
