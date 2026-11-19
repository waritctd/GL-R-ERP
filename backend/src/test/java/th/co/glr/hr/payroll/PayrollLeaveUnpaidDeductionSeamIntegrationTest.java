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
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.leave.LeaveAttachmentRepository;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.leave.LeaveRequestDto;
import th.co.glr.hr.leave.LeaveService;
import th.co.glr.hr.leave.ReviewLeaveRequest;
import th.co.glr.hr.leave.SubmitLeaveRequest;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Leave -> payroll unpaid-day deduction: the cross-package seam between the real LeaveService and the real
 * PayrollService. Deliberately a NEW test class (not PayrollServiceTest, PayrollRepositoryIntegrationTest, or
 * RetroactiveOvertimeReachesPayrollIntegrationTest -- a concurrent branch touches those). */
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
            mock(EmployeeRepository.class));

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
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));
    }

    @Test
    void approvedBeyondQuotaLeaveSurfacesInSuggestionsAndDeductsBaseOverThirtyPerUnpaidDayInPreview() {
        long employeeId = insertEmployee("SEAM-001");
        LocalDate monday = firstMondayOfMonth(2);
        LocalDate month = monday.withDayOfMonth(1);

        // 7 working days (Mon,Tue,Wed,Thu,Fri, next Mon,Tue) against a 6-day VACATION quota, nothing used yet -> 6
        // paid + 1 unpaid.
        LeaveRequestDto leave = leaveService.submit(
            new SubmitLeaveRequest(employeeId, "VACATION", monday, monday.plusDays(8), "Trip"),
            employee(employeeId));
        assertThat(leave.unpaidDays()).isEqualByComparingTo("1.00");
        // Leave requires approval: #submit now lands a rule-passing request at SUBMITTED, not APPROVED --
        // findUnpaidLeaveDaysByEmployeeForMonth filters `WHERE status = 'APPROVED'`, so this test's subject requires
        // driving the request all the way to APPROVED before it can surface.
        leaveService.approve(leave.id(), new ReviewLeaveRequest("approved"), hr());

        PayrollCarryForwardDtos.SuggestedInputsResponse suggestions = payrollService.suggestedInputs(month, hr());
        PayrollCarryForwardDtos.SuggestedInputRow row = suggestions.suggestions().stream()
            .filter(r -> employeeId == r.employeeId())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no suggestion row for employee " + employeeId));
        assertThat(row.unpaidLeaveDays()).isEqualByComparingTo("1.00");
        assertThat(row.pendingUnpaidLeaveCorrectionDays()).isEqualByComparingTo("0.00");

        // The frontend pre-fills the unpaidLeaveDays form field from `row.unpaidLeaveDays()` and HR submits it as an
        // explicit input -- preview()/process() never read leave data directly.
        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(month, List.of(inputWithUnpaidLeaveDays(employeeId, row.unpaidLeaveDays()))),
            hr());
        PayrollLineDto line = lineFor(period, employeeId);

        // dailyRate = 30000/30 = 1000.00; unpaidLeaveDeduction = 1000.00 x 1 unpaid day = 1000.00, subtracted PRE-TAX
        // (grossTaxableIncome = grossEarnings - unpaidLeaveDeduction).
        assertThat(line.unpaidLeaveDays()).isEqualByComparingTo("1.00");
        assertThat(line.unpaidLeaveDeduction()).isEqualByComparingTo("1000.00");
        assertThat(line.grossEarnings()).isEqualByComparingTo("30000.00");
        assertThat(line.grossTaxableIncome()).isEqualByComparingTo("29000.00");
    }

    // --- helpers ------------------------------------------------------------

    /** The first Monday of the month monthsFromNow months from today, in Asia/Bangkok -- always well clear of the
     * 7-day advance-notice window, and always within that same month (the first Monday of a month is always on day
     * 1-7). */
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
            BigDecimal.ZERO, // specialPay9
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
            BigDecimal.ZERO, // otherPretaxDeduction
            null             // withholdingTaxOverride (none)
        );
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    // §5 leave-rules-as-data (V116): VACATION now carries a 12-month min_service_months floor. This class is about
    // the leave->payroll seam (deduction/refund plumbing), not eligibility, so hire_date is set far enough in the
    // past (2015) that the floor is always a no-op here.
    private long insertEmployee(String code) {
        long employeeId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', :salary, TRUE, DATE '2015-01-01')
            RETURNING employee_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("salary", SALARY), Long.class);
        neutralizeVacationCarryForwardFrom2025(employeeId);
        return employeeId;
    }

    /** §5.3.5 VACATION carry-forward interaction: the default 2015 hire date leaves 2025 reading as "0 of 6 used",
     * which now legitimately carries 6.00 into 2026 -- correct new behaviour, but not what this class's
     * pre-existing leave->payroll-seam assertions are about. */
    private void neutralizeVacationCarryForwardFrom2025(long employeeId) {
        Long leaveRequestId = jdbc.queryForObject("""
            INSERT INTO hr.leave_request (
                employee_id, leave_type_code, start_date, end_date, total_days, paid_days, unpaid_days,
                quota_year, reason, status, quota_remaining_before, quota_remaining_after, requested_by_id
            )
            VALUES (
                :employeeId, 'VACATION', '2025-01-06', '2025-01-13', 6.00, 6.00, 0.00,
                2025, 'V127 fixture neutraliser', 'APPROVED', 6.00, 0.00, :employeeId
            )
            RETURNING leave_request_id
            """, new MapSqlParameterSource("employeeId", employeeId), Long.class);
        jdbc.update("""
            INSERT INTO hr.leave_request_quota_year (
                leave_request_id, quota_year, total_days, paid_days, unpaid_days,
                quota_remaining_before, quota_remaining_after,
                -- §5.3.5 pool split (V161): chk_lrqy_pool_matches_consumed requires these two to sum
                -- to quota_remaining_before - quota_remaining_after (6.00 here). Charging the whole
                -- 6.00 to OWN quota is not just constraint-satisfying, it is what makes this helper
                -- still NEUTRALIZE the carry-forward: ensureCarryoverGrant now computes
                -- carryOut = ownQuota - ownUsed, so own_quota_days = 6.00 gives 6 - 6 = 0 carried
                -- out. Splitting it the other way would grant a full 6.00 and silently undo the
                -- very thing this method exists to prevent.
                carried_in_days, own_quota_days
            )
            VALUES (:leaveRequestId, 2025, 6.00, 6.00, 0.00, 6.00, 0.00, 0.00, 6.00)
            """, new MapSqlParameterSource("leaveRequestId", leaveRequestId));
    }

}
