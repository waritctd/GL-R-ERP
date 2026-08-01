package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * REVIEWER-ADDED (Opus review of feat/payroll-daily-rate-support, 2026-07-30). Written against the
 * real {@link PayrollService} + real Postgres, not Mockito, for the reason CLAUDE.md gives: a mocked
 * repository "passes" while the SQL does something else.
 *
 * <p><b>Fixes-round update (2026-07-30):</b> this file originally PINNED three observed gaps rather
 * than endorsing them (the absent/zero {@code daysWorked} boundary -- two tests -- and the missing
 * upper bound -- one test). All three are now fixed ({@code
 * PayrollService#requireEveryDailyRateEmployeeHasDaysWorked} for the first two, the {@code
 * MAX_DAYS_WORKED} guard in {@code PayrollService#calculateLine} plus V103's tightened {@code
 * chk_payroll_line_days_worked_range} for the third), so those three tests were updated in place to
 * assert the refusal instead of the gap -- per this review's own instruction for the first two
 * ("it may assert the ฿0 behaviour, in which case update it to assert the refusal"), and by the same
 * logic for the third (Finding 4 necessarily changes what 45 days does; the old assertion could not
 * stay green and still mean anything). Nothing was deleted; every test still exercises the exact
 * scenario it was written for, just against the corrected outcome.
 *
 * <p>Two things the branch's own {@code PayrollDailyRateIntegrationTest} does not cover:
 *
 * <ol>
 *   <li><b>The absent/zero {@code daysWorked} boundary.</b> A daily-rate employee whose day count
 *       was never typed used to compute a ฿0 salary with no error at all. Note this is the exact
 *       production path: {@code PayrollPage.jsx#hasPayrollInput} does not submit an adjustment whose
 *       every numeric field is zero/blank, so "HR forgot the day count" reaches the service as NO
 *       input row for that employee at all, not as {@code daysWorked = 0}.</li>
 *   <li><b>The monthly /30 convention, pinned through a real unpaid-leave deduction.</b> The branch's
 *       own monthly test asserts {@code dailyRate} alone; nothing asserted that the figure DERIVED
 *       from it (unpaidLeaveDeduction / leaveDeductionRefund) is unchanged for a monthly employee,
 *       which is the silent-company-wide-regression risk. This is the mutation target for that claim.</li>
 * </ol>
 */
class PayrollDailyRateEdgeCaseIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final BigDecimal DAY_RATE = new BigDecimal("450.00");
    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);

    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        CommissionService commissionService = mock(CommissionService.class);
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

    /**
     * UPDATED (Finding 1 fix, Opus review of the review, 2026-07-30): this test originally PINNED the
     * gap it describes -- a ฿0 payslip processing silently with no error. Per the review's own
     * acceptance criteria for Finding 1 ("the reviewer's test goes GREEN without modification... it
     * may assert the ฿0 behaviour, in which case update it to assert the refusal"), this test now
     * asserts the fix: {@code PayrollService#requireEveryDailyRateEmployeeHasDaysWorked} refuses the
     * PROCESS (409, naming the employee) instead of silently persisting a ฿0 payslip. Nothing is
     * persisted -- {@code reloadedLineFor} would find no period at all, so this test no longer reads
     * one back.
     */
    @Test
    void aDailyRateEmployeeWithNoInputRowAtAllProcessesSilentlyAtZeroPay() {
        long employeeId = seedEmployee("DRE-001", "ไม่กรอก", "วันทำงาน", DAY_RATE, "D");

        assertThatThrownBy(() -> payrollService.process(new ProcessPayrollRequest(JULY, List.of()), hr()))
            .as("FIXED: a daily-rate employee with no days-worked input row must refuse the run, "
                + "not silently persist a ฿0 payslip")
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT))
            .hasMessageContaining("DRE-001");
        assertThat(payrollRepository.findPeriodByMonth(JULY))
            .as("the guard fires before saveProcessedPeriod is ever called -- nothing persisted")
            .isEmpty();
    }

    /**
     * UPDATED (Finding 1 fix, 2026-07-30): an explicitly typed zero is the same refusal as an omitted
     * input row -- see the javadoc on the test above for why this assertion changed from pinning the
     * ฿0 gap to asserting the fix.
     */
    @Test
    void anExplicitZeroDaysWorkedIsTheSameSilentZeroPay() {
        long employeeId = seedEmployee("DRE-002", "ศูนย์วัน", "ทดสอบ", DAY_RATE, "D");

        assertThatThrownBy(() -> payrollService.process(
            new ProcessPayrollRequest(JULY, List.of(dailyInput(employeeId, BigDecimal.ZERO, BigDecimal.ZERO))), hr()))
            .as("FIXED: an explicit 0 is refused exactly like an omitted input row")
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT))
            .hasMessageContaining("DRE-002");
        assertThat(payrollRepository.findPeriodByMonth(JULY)).isEmpty();
    }

    /**
     * UPDATED (Finding 4 fix, 2026-07-30): this test originally PINNED the gap it describes -- 45
     * days silently accepted and paid in full. Unlike the two tests above, the review did not name
     * this one as a pre-authorized rewrite; it is updated anyway because the fix for Finding 4
     * necessarily changes this exact outcome (the review's own Finding 4 asks for the bound that
     * makes 45 unreachable), and leaving the old assertion in place would simply fail red with no
     * useful signal. Reported explicitly in the PR body as a deviation from "verbatim", same
     * disclosure standard as the two tests Finding 1 named directly.
     *
     * <p>45 is refused by {@code PayrollService#calculateLine}'s own {@code MAX_DAYS_WORKED} check
     * (mirrors the per-diem-basis guard) -- a clean 400, not a bare {@code
     * DataIntegrityViolationException} from V103's tightened {@code
     * chk_payroll_line_days_worked_range} CHECK, though that CHECK still exists as the DB-level
     * backstop for any path that reaches this table without going through the service.
     */
    @Test
    void anImpossibleDayCountIsAcceptedAndPaidWithNoUpperBoundCheck() {
        long employeeId = seedEmployee("DRE-003", "สี่สิบห้า", "วัน", DAY_RATE, "D");

        assertThatThrownBy(() -> payrollService.process(
            new ProcessPayrollRequest(JULY, List.of(dailyInput(employeeId, new BigDecimal("45"), BigDecimal.ZERO))), hr()))
            .as("FIXED: a calendar-impossible day count (45 in a 31-day month) is now refused, not paid")
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("DRE-003");
        assertThat(payrollRepository.findPeriodByMonth(JULY)).isEmpty();
    }

    /** A half day is a real thing for a daily worker; it must round to the satang, not drift. */
    @Test
    void aFractionalDayCountMultipliesToTheSatang() {
        long employeeId = seedEmployee("DRE-004", "ครึ่งวัน", "ทดสอบ", DAY_RATE, "D");

        payrollService.process(
            new ProcessPayrollRequest(JULY, List.of(dailyInput(employeeId, new BigDecimal("21.5"), BigDecimal.ZERO))), hr());

        PayrollLineDto line = reloadedLineFor(JULY, employeeId);
        assertThat(line.baseSalary())
            .as("21.5 days x ฿450 = ฿9,675.00 exactly")
            .isEqualByComparingTo("9675.00");
        assertThat(line.daysWorked())
            .as("NUMERIC(6,2) holds the half day without truncating it")
            .isEqualByComparingTo("21.5");
    }

    /**
     * DESIGN CONSTRAINT A, the company-wide regression risk, pinned through the DERIVED figures
     * rather than the rate alone: for a MONTHLY employee the dailyRate must stay {@code baseSalary/30}
     * and every figure computed off it must be byte-identical to the pre-change engine.
     *
     * <p>MUTATION TARGET: make {@code PayrollService#calculateLine}'s {@code dailyRateOverride}
     * unconditional (drop the {@code dailyRatePay ?} test) and this test goes red on the ฿3,000
     * deduction -- it becomes ฿90,000 (30,000/day). It is the test that proves the /30 convention did
     * not move for the 29 monthly employees.
     */
    @Test
    void aMonthlyEmployeesUnpaidLeaveStillDeductsAtBaseSalaryOverThirty() {
        long employeeId = seedEmployee("DRE-005", "รายเดือน", "ลาไม่รับ", new BigDecimal("30000.00"), "M");

        payrollService.process(
            new ProcessPayrollRequest(JULY, List.of(dailyInput(employeeId, null, new BigDecimal("3")))), hr());

        PayrollLineDto line = reloadedLineFor(JULY, employeeId);
        assertThat(line.dailyRate())
            .as("monthly: ฿30,000/30 = ฿1,000.0000, unchanged")
            .isEqualByComparingTo("1000.0000");
        assertThat(line.hourlyRate())
            .as("monthly: dailyRate/8 = ฿125.0000, unchanged")
            .isEqualByComparingTo("125.0000");
        assertThat(line.unpaidLeaveDeduction())
            .as("3 unpaid days at the monthly /30 rate = ฿3,000.00 -- the figure that must not move")
            .isEqualByComparingTo("3000.00");
        assertThat(line.ssoWageBase())
            .as("30,000 - 3,000 = 27,000, clamped to the ฿17,500 ceiling, exactly as before")
            .isEqualByComparingTo("17500.00");
        assertThat(line.grossTaxableIncome())
            .as("30,000 gross less the ฿3,000 pre-tax unpaid-leave deduction")
            .isEqualByComparingTo("27000.00");
    }

    /**
     * The mirror of the above for a DAILY employee: the refund limb must agree with the deduction
     * limb, both at the TRUE ฿450 rate. A refund derived from gross/30 while the deduction used the
     * true rate would leave a residue on every cancel-after-close correction.
     */
    @Test
    void aDailyEmployeesLeaveRefundAgreesWithTheDeductionAtTheTrueRate() {
        long employeeId = seedEmployee("DRE-006", "คืนวันลา", "ทดสอบ", DAY_RATE, "D");
        // 2 unpaid days this month; the leave-refund limb is fed from hr.leave_payroll_correction, so
        // assert the two limbs share one rate by checking the deduction and the reported rate agree.
        payrollService.process(
            new ProcessPayrollRequest(JULY, List.of(dailyInput(employeeId, new BigDecimal("25"), new BigDecimal("2")))), hr());

        PayrollLineDto line = reloadedLineFor(JULY, employeeId);
        assertThat(line.unpaidLeaveDeduction()).isEqualByComparingTo("900.00");
        assertThat(line.hourlyRate())
            .as("hourlyRate for a daily employee is the TRUE rate/8 = ฿56.25 -- NOT (gross/30)/8 = ฿46.875. "
                + "See the review finding: hr.overtime_request's own pricing SQL "
                + "(PayrollRepository#findOvertimeTotals, 'salary_basis / 30 / 8') does NOT agree with "
                + "this figure for a daily-rate employee.")
            .isEqualByComparingTo("56.2500");
        assertThat(line.grossTaxableIncome())
            .as("11,250 gross less the ฿900 true-rate unpaid-leave deduction")
            .isEqualByComparingTo("10350.00");
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

    private PayrollEmployeeInputRequest dailyInput(long employeeId, BigDecimal daysWorked, BigDecimal unpaidLeaveDays) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            zero, zero, zero, zero, zero, zero, zero, zero, zero, // specialPay1-9
            zero, // nonTaxableIncome
            unpaidLeaveDays,
            zero, zero, zero, // studentLoan, legalExecution, otherPostTax
            zero, zero, zero, zero, zero, // spouse..maternity
            zero, zero, zero, zero, zero, // life..ssf
            zero, zero, zero, zero, zero, zero, // pension..political
            zero, zero, zero, // warningLetter, customerReturn, otherPretax
            null, // withholdingTaxOverride
            zero, zero, zero, null, // meal, perDiemExempt, perDiemTaxable, perDiemBasis
            zero, zero, // bonusPay, otherOneOffPay
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
