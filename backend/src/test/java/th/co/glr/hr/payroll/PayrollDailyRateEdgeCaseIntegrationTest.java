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

/** REVIEWER-ADDED (Opus review of feat/payroll-daily-rate-support, 2026-07-30). A daily-rate employee whose day
 * count was never typed used to compute a ฿0 salary with no error at all. Two things the branch's own
 * PayrollDailyRateIntegrationTest does not cover: The absent/zero daysWorked boundary. */
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

    /** UPDATED (Finding 1 fix, Opus review of the review, 2026-07-30): this test originally PINNED the gap it
     * describes -- a ฿0 payslip processing silently with no error. Per the review's own acceptance criteria for
     * Finding 1 ("the reviewer's test goes GREEN without modification... */
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

    /** UPDATED (Finding 1 fix, 2026-07-30): an explicitly typed zero is the same refusal as an omitted input row --
     * see the javadoc on the test above for why this assertion changed from pinning the ฿0 gap to asserting the
     * fix. */
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

    /** UPDATED (Finding 4 fix, 2026-07-30): this test originally PINNED the gap it describes -- 45 days silently
     * accepted and paid in full. Reported explicitly in the PR body as a deviation from "verbatim", same
     * disclosure standard as the two tests Finding 1 named directly. */
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

    /** DESIGN CONSTRAINT A, the company-wide regression risk, pinned through the DERIVED figures rather than the
     * rate alone: for a MONTHLY employee the dailyRate must stay baseSalary/30 and every figure computed off it
     * must be byte-identical to the pre-change engine. */
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

    /** The mirror of the above for a DAILY employee: the refund limb must agree with the deduction limb, both at
     * the TRUE ฿450 rate. A refund derived from gross/30 while the deduction used the true rate would leave a
     * residue on every cancel-after-close correction. */
    @Test
    void aDailyEmployeesLeaveRefundAgreesWithTheDeductionAtTheTrueRate() {
        long employeeId = seedEmployee("DRE-006", "คืนวันลา", "ทดสอบ", DAY_RATE, "D");
        // 2 unpaid days this month; the leave-refund limb is fed from hr.leave_payroll_correction, so assert the two
        // limbs share one rate by checking the deduction and the reported rate agree.
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
