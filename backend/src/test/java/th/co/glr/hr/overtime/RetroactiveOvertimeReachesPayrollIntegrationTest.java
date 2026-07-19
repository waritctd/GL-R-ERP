package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollCalculator;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.PayslipRenderer;
import th.co.glr.hr.payroll.ProcessPayrollRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Proves that overtime filed under the new retroactive rules actually reaches payroll.
 *
 * <p>Removing advance notice was only half the job: overtime is worth nothing until payroll pays
 * it. Payroll picks overtime up by {@code payroll_month} and only in status {@code APPROVED}
 * (`PayrollRepository#findApprovedOvertimePayByEmployee`), and both of those are set by
 * {@link OvertimeService} — so the seam between the two services is exactly where a backdated
 * request could be accepted and then quietly never paid.
 *
 * <p>This drives the real chain end to end against real Postgres: submit → manager approve → CEO
 * approve → payroll preview. Mockito cannot reach this; the join and the WHERE clause are the
 * thing under test.
 */
class RetroactiveOvertimeReachesPayrollIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final BigDecimal SALARY = new BigDecimal("30000.00");

    private OvertimeService overtimeService;
    private PayrollService payrollService;

    private long division;
    private long manager;
    private long staff;

    @BeforeEach
    void wireRealCollaborators() {
        overtimeService = new OvertimeService(
            new OvertimeRepository(jdbc),
            mock(AuditService.class),
            mock(NotificationService.class),
            new AppProperties(),
            mock(AttendanceDailyService.class));
        payrollService = new PayrollService(
            new PayrollRepository(jdbc),
            new PayrollCalculator(),
            new CommissionRepository(jdbc),
            new CommissionCalculator(),
            mock(AuditService.class),
            mock(PayslipRenderer.class));

        division = insertDivision("SLS", "ฝ่ายขาย");
        manager = insertEmployee("M001", null);
        staff = insertEmployee("S001", manager);
    }

    /**
     * The headline case. Two hours of workday overtime on a past date, self-filed, must show up in
     * the payroll preview for the month the work happened in.
     *
     * <p>Expected pay: 2 h x (30,000 / 30 / 8) x 1.5 = 375.00.
     */
    @Test
    void backdatedOvertimeIsPaidInThePayrollMonthOfTheWorkDate() {
        LocalDate workDate = backdatedWorkDateInCurrentMonth();

        long id = fileAndFullyApprove(workDate);

        assertThat(payrollMonthOf(id)).isEqualTo(workDate.withDayOfMonth(1));
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff))
            .isEqualByComparingTo(new BigDecimal("375.00"));
    }

    /**
     * Overtime only becomes payable at the second approval stage. A request stuck at
     * MANAGER_APPROVED must not be paid — payroll filters on status, and this proves the filter is
     * in the SQL rather than only in the service's head.
     */
    @Test
    void managerApprovedOvertimeIsNotYetPaid() {
        LocalDate workDate = backdatedWorkDateInCurrentMonth();
        insertPunchesCovering(workDate);
        long id = overtimeService.submit(backdated(workDate), employee(staff)).id();
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager());

        assertThat(statusOf(id)).isEqualTo("MANAGER_APPROVED");
        assertThat(payableMinutesOf(id)).isEqualTo(120);
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void rejectedBackdatedOvertimeIsNeverPaid() {
        LocalDate workDate = backdatedWorkDateInCurrentMonth();
        long id = overtimeService.submit(backdated(workDate), employee(staff)).id();
        overtimeService.reject(id, new ReviewOvertimeRequest("not approved"), directManager());

        assertThat(statusOf(id)).isEqualTo("REJECTED");
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * The gross figure on the payroll line has to move, not just the overtime column — otherwise the
     * money is visible in a report and absent from the payslip.
     */
    @Test
    void backdatedOvertimeRaisesGrossAndNetOnThePayrollLine() {
        LocalDate workDate = backdatedWorkDateInCurrentMonth();
        LocalDate payrollMonth = workDate.withDayOfMonth(1);

        PayrollLineDto before = previewLineFor(payrollMonth, staff);
        fileAndFullyApprove(workDate);
        PayrollLineDto after = previewLineFor(payrollMonth, staff);

        assertThat(before.overtimePay()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(after.overtimePay()).isEqualByComparingTo(new BigDecimal("375.00"));
        assertThat(after.grossEarnings()).isEqualByComparingTo(before.grossEarnings().add(new BigDecimal("375.00")));
        assertThat(after.netPay()).isGreaterThan(before.netPay());
    }

    /**
     * The guard that makes the whole thing safe. Without it a request could be filed into a month
     * payroll had already closed, be approved, and then never appear in any payroll run — because
     * {@code saveProcessedPeriod} writes a period once.
     */
    @Test
    void overtimeCannotBeFiledIntoAnAlreadyProcessedPayrollMonth() {
        LocalDate workDate = backdatedWorkDateInCurrentMonth();
        insertProcessedPeriod(workDate.withDayOfMonth(1));

        assertThatThrownBy(() -> overtimeService.submit(backdated(workDate), employee(staff)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * The same guard at the far end of the workflow: a request filed while the month was open can
     * still reach CEO approval after payroll has run. It must be refused there too, or it lands in
     * a closed month and is never paid.
     */
    @Test
    void ceoApprovalIsRefusedOnceThePayrollMonthClosesUnderneathTheRequest() {
        LocalDate workDate = backdatedWorkDateInCurrentMonth();
        insertPunchesCovering(workDate);
        long id = overtimeService.submit(backdated(workDate), employee(staff)).id();
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager());

        // Payroll runs for the month while the request waits on the CEO.
        insertProcessedPeriod(workDate.withDayOfMonth(1));

        assertThatThrownBy(() -> overtimeService.approve(id, new ReviewOvertimeRequest("ok"), ceo()))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(id)).isEqualTo("MANAGER_APPROVED");
    }

    /**
     * REGRESSION TEST for the fix, not a characterization of a defect anymore. Overtime is priced
     * from {@code hr.overtime_request.salary_basis}, frozen at manager approval time from the
     * salary effective on the work date ({@code OvertimeRepository#findSalaryBasisAsOf}) —
     * {@code findApprovedOvertimePayByEmployee} no longer re-reads {@code hr.employee.current_salary}
     * as the primary source, so a salary change after approval must not move an already-approved
     * figure.
     */
    @Test
    void overtimeIsNotRepricedByALaterSalaryChange() {
        LocalDate workDate = backdatedWorkDateInCurrentMonth();
        fileAndFullyApprove(workDate);
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff))
            .isEqualByComparingTo(new BigDecimal("375.00"));

        // A raise granted after the overtime was worked and approved.
        jdbc.update("UPDATE hr.employee SET current_salary = 60000 WHERE employee_id = :id",
            Map.of("id", staff));

        // Same work, same approval, same money — the rate was frozen at approval.
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff))
            .isEqualByComparingTo(new BigDecimal("375.00"));
    }

    /**
     * Overtime worked before a raise is paid at the old rate, even though by the time it is filed
     * and approved the employee's {@code current_salary} is already the new one. The raise is
     * recorded in {@code hr.salary_history} with an {@code effective_date} after the work date, so
     * {@code findSalaryBasisAsOf} must not pick it up.
     */
    @Test
    void overtimeWorkedBeforeARaiseIsPaidAtTheOldRate() {
        LocalDate workDate = backdatedWorkDateInCurrentMonth();
        insertSalaryHistory(staff, workDate.plusDays(1), new BigDecimal("60000.00"));
        jdbc.update("UPDATE hr.employee SET current_salary = 60000 WHERE employee_id = :id",
            Map.of("id", staff));

        fileAndFullyApprove(workDate);

        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff))
            .isEqualByComparingTo(new BigDecimal("375.00"));
    }

    /**
     * The counterpart case: when salary_history has an entry effective on or before the work date,
     * that historical amount is the basis, not whatever current_salary happens to hold.
     */
    @Test
    void overtimeUsesTheSalaryHistoryRowEffectiveOnOrBeforeTheWorkDate() {
        LocalDate workDate = backdatedWorkDateInCurrentMonth();
        insertSalaryHistory(staff, workDate, new BigDecimal("60000.00"));
        jdbc.update("UPDATE hr.employee SET current_salary = 90000 WHERE employee_id = :id",
            Map.of("id", staff));

        fileAndFullyApprove(workDate);

        // 2 h x (60,000 / 30 / 8) x 1.5 = 750.00
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff))
            .isEqualByComparingTo(new BigDecimal("750.00"));
    }

    private void insertSalaryHistory(long employeeId, LocalDate effectiveDate, BigDecimal newAmount) {
        Map<String, Object> params = new HashMap<>();
        params.put("employeeId", employeeId);
        params.put("effectiveDate", effectiveDate);
        params.put("oldAmount", SALARY);
        params.put("newAmount", newAmount);
        jdbc.update("""
            INSERT INTO hr.salary_history (employee_id, effective_date, recorded_date, old_amount, new_amount)
            VALUES (:employeeId, :effectiveDate, :effectiveDate, :oldAmount, :newAmount)
            """, params);
    }

    // --- helpers ------------------------------------------------------------

    private long fileAndFullyApprove(LocalDate workDate) {
        insertPunchesCovering(workDate);
        long id = overtimeService.submit(backdated(workDate), employee(staff)).id();
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager());
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), ceo());
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        return id;
    }

    /**
     * A past date guaranteed to sit in the current month, so the payroll month under test is the
     * one the payroll lock leaves open. On the 1st there is no earlier day in the month, so this
     * falls back to today — still exercising the same path.
     */
    private LocalDate backdatedWorkDateInCurrentMonth() {
        LocalDate today = LocalDate.now();
        return today.getDayOfMonth() > 1 ? today.withDayOfMonth(1) : today;
    }

    private SubmitOvertimeRequest backdated(LocalDate workDate) {
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(ZoneOffset.ofHours(7));
        return new SubmitOvertimeRequest(
            null, workDate, startAt, startAt.plusHours(2), "WORKDAY",
            "Urgent customer escalation, filed after the shift ended");
    }

    /**
     * Payable minutes are derived at manager approval from the overlap between the approved window
     * and the employee's first/last punch, so the punches are the real input — clocking in at 08:00
     * and out at 20:30 covers the whole 18:00-20:00 window and yields 120 payable minutes.
     */
    private void insertPunchesCovering(LocalDate workDate) {
        insertPunch(workDate.atTime(8, 0).atOffset(ZoneOffset.ofHours(7)), workDate);
        insertPunch(workDate.atTime(20, 30).atOffset(ZoneOffset.ofHours(7)), workDate);
    }

    private void insertPunch(OffsetDateTime at, LocalDate workDate) {
        jdbc.update("""
            INSERT INTO hr.attendance_punch (site_code, badge_code, punch_time, work_date, employee_id)
            VALUES ('SHOWROOM', :badge, :at, :workDate, :employeeId)
            """, Map.of("badge", "S001", "at", at, "workDate", workDate, "employeeId", staff));
    }

    private BigDecimal overtimePayFor(LocalDate payrollMonth, long employeeId) {
        return new PayrollRepository(jdbc)
            .findApprovedOvertimePayByEmployee(payrollMonth)
            .getOrDefault(employeeId, BigDecimal.ZERO);
    }

    private PayrollLineDto previewLineFor(LocalDate payrollMonth, long employeeId) {
        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(payrollMonth, List.of()), hr());
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private int payableMinutesOf(long id) {
        return jdbc.queryForObject(
            "SELECT payable_minutes FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", id), Integer.class);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject(
            "SELECT status FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", id), String.class);
    }

    private LocalDate payrollMonthOf(long id) {
        return jdbc.queryForObject(
            "SELECT payroll_month FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", id), LocalDate.class);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, "emp@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal directManager() {
        return new UserPrincipal(manager, "mgr@glr.co.th", "Manager", "employee",
            manager, true, LocalDate.now(), false, division, true);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(manager, "ceo@glr.co.th", "CEO", "ceo",
            manager, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(manager, "hr@glr.co.th", "HR", "hr",
            manager, true, LocalDate.now(), false, null, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long reportsTo) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", division);
        params.put("reportsTo", reportsTo);
        params.put("salary", SALARY);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, reports_to_employee_id, current_salary,
                                     hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :reportsTo, :salary, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    private void insertProcessedPeriod(LocalDate payrollMonth) {
        jdbc.update("""
            INSERT INTO hr.payroll_period (payroll_month, period_start, period_end, pay_date, status)
            VALUES (
                :payrollMonth, :payrollMonth,
                (:payrollMonth + INTERVAL '1 month - 1 day')::date,
                (:payrollMonth + INTERVAL '1 month - 1 day')::date,
                'PROCESSED'
            )
            """, Map.of("payrollMonth", payrollMonth));
    }
}
