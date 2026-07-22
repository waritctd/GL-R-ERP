package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveAttachmentRepository;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.leave.LeaveRequestDto;
import th.co.glr.hr.leave.LeaveService;
import th.co.glr.hr.leave.ReviewLeaveRequest;
import th.co.glr.hr.leave.SubmitLeaveRequest;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Leave -&gt; payroll unpaid-day deduction (2026-07-23): the cross-package seam between the real
 * {@link LeaveService} (writing {@code hr.leave_request.paid_days}/{@code unpaid_days} and, on
 * cancel-after-close, {@code hr.leave_payroll_correction}) and the real {@link PayrollService}
 * (reading both back through {@link PayrollService#suggestedInputs} and folding an explicit
 * {@code unpaidLeaveDays} figure into {@link PayrollService#preview} exactly as it always has).
 *
 * <p>Deliberately a NEW test class (not {@code PayrollServiceTest}, {@code
 * PayrollRepositoryIntegrationTest}, or {@code RetroactiveOvertimeReachesPayrollIntegrationTest} --
 * a concurrent branch touches those). Mockito cannot reach this: the whole point under test is that
 * two independently-written repository queries (leave's per-month attribution, payroll's suggestion
 * merge) agree with each other and with the real {@code hr.leave_request}/{@code
 * hr.payroll_period} rows.
 *
 * <p>Does not touch attendance/lateness (Labour Protection Act §76) at all -- {@code
 * PayrollCalculator} and {@code preview()}/{@code process()} math are unchanged by this feature, so
 * the §76 no-penalty guarantee ({@code LatenessNeverAffectsPayrollTest}) is structurally unaffected.
 *
 * <p>Uses {@link LeaveService}'s public constructor (real system clock) rather than the
 * package-private test-clock constructor {@code LeaveServiceTest}/{@code
 * LeaveUnpaidDeductionIntegrationTest} use, since this class lives in {@code th.co.glr.hr.payroll}.
 * Dates are therefore computed relative to "now" ({@link #firstMondayOfMonth}) rather than hardcoded
 * literals, comfortably clear of the 7-day advance-notice window either way.
 */
class PayrollLeaveUnpaidDeductionSeamIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final BigDecimal SALARY = new BigDecimal("30000.00");

    private LeaveRepository leaveRepository;
    private LeaveService leaveService;
    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

    @BeforeEach
    void wireRealCollaborators() {
        leaveRepository = new LeaveRepository(jdbc);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            new AppProperties());

        payrollRepository = new PayrollRepository(jdbc);
        payrollService = new PayrollService(
            payrollRepository,
            new PayrollCalculator(),
            mock(CommissionService.class),
            mock(AuditService.class),
            mock(PayslipRenderer.class),
            leaveRepository,
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.config.AppProperties());
    }

    @Test
    void approvedBeyondQuotaLeaveSurfacesInSuggestionsAndDeductsBaseOverThirtyPerUnpaidDayInPreview() {
        long employeeId = insertEmployee("SEAM-001");
        LocalDate monday = firstMondayOfMonth(2);
        LocalDate month = monday.withDayOfMonth(1);

        // 7 working days (Mon,Tue,Wed,Thu,Fri, next Mon,Tue) against a 6-day VACATION quota, nothing
        // used yet -> 6 paid + 1 unpaid.
        LeaveRequestDto leave = leaveService.submit(
            new SubmitLeaveRequest(employeeId, "VACATION", monday, monday.plusDays(8), "Trip"),
            employee(employeeId));
        assertThat(leave.unpaidDays()).isEqualByComparingTo("1.00");

        PayrollCarryForwardDtos.SuggestedInputsResponse suggestions = payrollService.suggestedInputs(month, hr());
        PayrollCarryForwardDtos.SuggestedInputRow row = suggestions.suggestions().stream()
            .filter(r -> employeeId == r.employeeId())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no suggestion row for employee " + employeeId));
        assertThat(row.unpaidLeaveDays()).isEqualByComparingTo("1.00");
        assertThat(row.pendingUnpaidLeaveCorrectionDays()).isEqualByComparingTo("0.00");

        // The frontend pre-fills the unpaidLeaveDays form field from `row.unpaidLeaveDays()` and HR
        // submits it as an explicit input -- preview()/process() never read leave data directly.
        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(month, List.of(inputWithUnpaidLeaveDays(employeeId, row.unpaidLeaveDays()))),
            hr());
        PayrollLineDto line = lineFor(period, employeeId);

        // dailyRate = 30000/30 = 1000.00; unpaidLeaveDeduction = 1000.00 x 1 unpaid day = 1000.00,
        // subtracted PRE-TAX (grossTaxableIncome = grossEarnings - unpaidLeaveDeduction).
        assertThat(line.unpaidLeaveDays()).isEqualByComparingTo("1.00");
        assertThat(line.unpaidLeaveDeduction()).isEqualByComparingTo("1000.00");
        assertThat(line.grossEarnings()).isEqualByComparingTo("30000.00");
        assertThat(line.grossTaxableIncome()).isEqualByComparingTo("29000.00");
    }

    @Test
    void paidWithinQuotaLeaveContributesNoUnpaidDaysAndNoDeduction() {
        long employeeId = insertEmployee("SEAM-002");
        LocalDate monday = firstMondayOfMonth(2);
        LocalDate month = monday.withDayOfMonth(1);

        LeaveRequestDto leave = leaveService.submit(
            new SubmitLeaveRequest(employeeId, "VACATION", monday, monday.plusDays(1), "Short trip"),
            employee(employeeId));
        assertThat(leave.unpaidDays()).isEqualByComparingTo("0.00");

        PayrollCarryForwardDtos.SuggestedInputsResponse suggestions = payrollService.suggestedInputs(month, hr());
        boolean hasNonZeroUnpaidRow = suggestions.suggestions().stream()
            .anyMatch(r -> employeeId == r.employeeId() && r.unpaidLeaveDays().signum() > 0);
        assertThat(hasNonZeroUnpaidRow).isFalse();

        PayrollPeriodDto period = payrollService.preview(new ProcessPayrollRequest(month, List.of()), hr());
        PayrollLineDto line = lineFor(period, employeeId);

        assertThat(line.unpaidLeaveDays()).isEqualByComparingTo("0.00");
        assertThat(line.unpaidLeaveDeduction()).isEqualByComparingTo("0.00");
        assertThat(line.grossTaxableIncome()).isEqualByComparingTo(line.grossEarnings());
    }

    @Test
    void cancelAfterCloseCorrectionSurfacesInSuggestionsWithoutTouchingPreview() {
        long employeeId = insertEmployee("SEAM-003");
        LocalDate monday = firstMondayOfMonth(2);
        LocalDate month = monday.withDayOfMonth(1);
        LocalDate nextMonth = month.plusMonths(1);

        LeaveRequestDto leave = leaveService.submit(
            new SubmitLeaveRequest(employeeId, "VACATION", monday, monday.plusDays(8), "Trip"),
            employee(employeeId));
        assertThat(leave.unpaidDays()).isEqualByComparingTo("1.00");

        insertProcessedPayrollPeriod(month);
        leaveService.cancel(leave.id(), new ReviewLeaveRequest("cancelled after close"), hr());

        PayrollCarryForwardDtos.SuggestedInputsResponse suggestions = payrollService.suggestedInputs(nextMonth, hr());
        PayrollCarryForwardDtos.SuggestedInputRow row = suggestions.suggestions().stream()
            .filter(r -> employeeId == r.employeeId())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no suggestion row for employee " + employeeId));

        // The cancelled leave no longer contributes unpaid days going forward (next month has none),
        // but the pending correction credit (1 unpaid day already deducted in the now-closed month) is
        // surfaced so HR can see and manually net it -- this PR does NOT auto-apply it into
        // unpaidLeaveDays or into preview()/process(); see LeaveService#cancel.
        assertThat(row.unpaidLeaveDays()).isEqualByComparingTo("0.00");
        assertThat(row.pendingUnpaidLeaveCorrectionDays()).isEqualByComparingTo("1.00");
    }

    // --- helpers ------------------------------------------------------------

    /** The first Monday of the month {@code monthsFromNow} months from today, in Asia/Bangkok --
     *  always well clear of the 7-day advance-notice window, and always within that same month
     *  (the first Monday of a month is always on day 1-7). */
    private LocalDate firstMondayOfMonth(int monthsFromNow) {
        return LocalDate.now(BUSINESS_ZONE).plusMonths(monthsFromNow)
            .withDayOfMonth(1)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    private PayrollLineDto lineFor(PayrollPeriodDto period, long employeeId) {
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private PayrollEmployeeInputRequest inputWithUnpaidLeaveDays(long employeeId, BigDecimal unpaidLeaveDays) {
        return new PayrollEmployeeInputRequest(
            employeeId,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, // specialPay1-4
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, // specialPay5-8
            BigDecimal.ZERO, // nonTaxableIncome
            unpaidLeaveDays,
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
            BigDecimal.ZERO  // otherPretaxDeduction
        );
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :code, 'ทดสอบ', :salary, TRUE)
            RETURNING employee_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("salary", SALARY), Long.class);
    }

    private void insertProcessedPayrollPeriod(LocalDate payrollMonth) {
        jdbc.update("""
            INSERT INTO hr.payroll_period (payroll_month, period_start, period_end, pay_date, status)
            VALUES (
                :payrollMonth, :payrollMonth,
                (:payrollMonth + INTERVAL '1 month - 1 day')::date,
                (:payrollMonth + INTERVAL '1 month - 1 day')::date,
                'PROCESSED'
            )
            """, new MapSqlParameterSource().addValue("payrollMonth", payrollMonth));
    }
}
