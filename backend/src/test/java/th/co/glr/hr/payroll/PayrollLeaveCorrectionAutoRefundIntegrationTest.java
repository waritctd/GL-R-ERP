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
 * Cancel-after-close reversal, AUTO-REFUND (2026-07-23; owner decision, same day as V85 --
 * the original record-and-surface-only design was not enough, this must auto-net). Full real-DB
 * cycle: beyond-quota leave deducts in month M -&gt; leave is cancelled AFTER M is PROCESSED,
 * recording a {@code hr.leave_payroll_correction} row -&gt; month M+1's PREVIEW shows the refund
 * (before anything is persisted) -&gt; month M+1's PROCESS applies the refund as a real pre-tax
 * credit (tax/SSO recompute, not a flat net-pay bump) and resolves the correction -&gt; RE-processing
 * month M+1 does not double-refund.
 *
 * <p>Deliberately a NEW test class (not {@code PayrollServiceTest}, {@code
 * PayrollRepositoryIntegrationTest}, or {@code RetroactiveOvertimeReachesPayrollIntegrationTest} --
 * a concurrent branch touches those). Mockito cannot reach any of this: the whole point under test
 * is that a real transaction sees a consistent view across the leave and payroll repositories'
 * independently-written queries, and that the resolve-on-process UPDATE actually lands in Postgres.
 *
 * <p>Uses {@link LeaveService}'s public constructor (real system clock), like {@code
 * PayrollLeaveUnpaidDeductionSeamIntegrationTest} -- dates are computed relative to "now" ({@link
 * #firstMondayOfMonth}) rather than hardcoded literals.
 */
class PayrollLeaveCorrectionAutoRefundIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final BigDecimal SALARY = new BigDecimal("30000.00");
    // dailyRate = 30000 / 30 = 1000.00 exactly -- chosen so every figure below is a round number.
    private static final BigDecimal DAILY_RATE = new BigDecimal("1000.0000");

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
    void fullCycleDeductThenCancelAfterCloseThenAutoRefundNextMonthThenNoDoubleRefundOnReprocess() {
        long employeeId = insertEmployee("REFUND-001");
        LocalDate monday = firstMondayOfMonth(2);
        LocalDate monthM = monday.withDayOfMonth(1);
        LocalDate monthM1 = monthM.plusMonths(1);

        // Step 1: beyond-quota leave in month M. 7 working days (Mon..next Tue) against a 6-day
        // VACATION quota, nothing used yet -> 6 paid + 1 unpaid.
        LeaveRequestDto leave = leaveService.submit(
            new SubmitLeaveRequest(employeeId, "VACATION", monday, monday.plusDays(8), "Trip"),
            employee(employeeId));
        assertThat(leave.unpaidDays()).isEqualByComparingTo("1.00");

        // Step 2: month M is PROCESSED for real, with HR typing in the 1 unpaid day (exactly how the
        // frontend pre-fills from suggestedInputs and submits today) -- deducts 1,000.00 pre-tax.
        PayrollPeriodDto periodM = payrollService.process(
            new ProcessPayrollRequest(monthM, List.of(inputWithUnpaidLeaveDays(employeeId, leave.unpaidDays()))),
            hr());
        PayrollLineDto lineM = lineFor(periodM, employeeId);
        assertThat(lineM.unpaidLeaveDeduction()).isEqualByComparingTo("1000.00");
        assertThat(lineM.leaveRefundDays()).isEqualByComparingTo("0.00");
        assertThat(lineM.leaveDeductionRefund()).isEqualByComparingTo("0.00");

        // Step 3: the leave is cancelled AFTER month M has already processed -- LeaveService#cancel
        // records a correction (1 unpaid day owed back) since month M is now closed.
        leaveService.cancel(leave.id(), new ReviewLeaveRequest("cancelled after close"), hr());
        assertThat(leaveRepository.findPendingPayrollCorrectionsByEmployee())
            .containsEntry(employeeId, new BigDecimal("1.00"));

        // Control employee: same salary, never took leave, no correction -- inserted before the
        // month M+1 run so it's picked up in the SAME preview/process calls below (findActiveEmployees
        // scans all active employees), letting the refunded employee's net pay be compared against a
        // same-salary/same-month baseline instead of a hand-derived tax figure (which would depend on
        // "today"'s relative month value and duplicate the progressive-tax engine already covered by
        // PayrollCalculatorTest).
        long controlEmployeeId = insertEmployee("REFUND-001-CONTROL");

        // Step 4: month M+1's PREVIEW (nothing persisted yet) already shows the refund -- HR sees it
        // before committing to Process.
        PayrollPeriodDto previewM1 = payrollService.preview(new ProcessPayrollRequest(monthM1, List.of()), hr());
        PayrollLineDto previewLine = lineFor(previewM1, employeeId);
        assertThat(previewLine.leaveRefundDays()).isEqualByComparingTo("1.00");
        assertThat(previewLine.leaveDeductionRefund()).isEqualByComparingTo(DAILY_RATE.multiply(BigDecimal.ONE).setScale(2));
        assertThat(previewLine.unpaidLeaveDays()).isEqualByComparingTo("0.00");
        assertThat(previewLine.unpaidLeaveDeduction()).isEqualByComparingTo("0.00");
        // Pre-tax credit: no unpaid leave of its own this month, so grossTaxableIncome is base +
        // refund, and net pay is HIGHER than a plain unaffected month (base salary, no deductions at
        // all, would net exactly base salary here since there's no SSO/tax at this low income level
        // in isolation -- but the refund itself is taxed/SSO'd, see the process() assertions below
        // for the concrete recomputed figures).
        assertThat(previewLine.grossTaxableIncome())
            .isEqualByComparingTo(previewLine.grossEarnings().add(previewLine.leaveDeductionRefund()));
        // Nothing resolved yet -- preview() must never mutate hr.leave_payroll_correction.
        assertThat(leaveRepository.findPendingPayrollCorrectionsByEmployee())
            .containsEntry(employeeId, new BigDecimal("1.00"));

        // Step 5: month M+1 is PROCESSED -- the refund is auto-applied (matches preview exactly) AND
        // the correction is resolved.
        PayrollPeriodDto processedM1 = payrollService.process(new ProcessPayrollRequest(monthM1, List.of()), hr());
        PayrollLineDto processedLine = lineFor(processedM1, employeeId);
        assertThat(processedLine.leaveRefundDays()).isEqualByComparingTo(previewLine.leaveRefundDays());
        assertThat(processedLine.leaveDeductionRefund()).isEqualByComparingTo(previewLine.leaveDeductionRefund());
        assertThat(processedLine.grossTaxableIncome()).isEqualByComparingTo(previewLine.grossTaxableIncome());
        assertThat(processedLine.netPay()).isEqualByComparingTo(previewLine.netPay());
        // Net pay is strictly higher than the same-salary/same-month control employee's -- the credit
        // genuinely reached the employee's pocket net of whatever extra SSO/tax it incurred, not just
        // an internal pre-tax figure. (SSO's own wage base is already at the 17,500 ceiling either way
        // at this salary, so the entire difference here is the refund minus its marginal tax cost --
        // guaranteed positive since the top marginal rate is 35%, never 100%.)
        PayrollLineDto controlLine = lineFor(processedM1, controlEmployeeId);
        assertThat(processedLine.netPay()).isGreaterThan(controlLine.netPay());
        assertThat(controlLine.leaveRefundDays()).isEqualByComparingTo("0.00");

        assertThat(leaveRepository.findPendingPayrollCorrectionsByEmployee()).doesNotContainKey(employeeId);
        OffsetDateTimeAndPeriod resolved = fetchResolution(leave.id());
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.resolvedPeriodId()).isEqualTo(processedM1.id());

        // Step 6: RE-processing month M+1 (e.g. HR re-hits Process after noticing nothing else
        // needed changing) must NOT double-refund -- same period_id (saveProcessedPeriod upserts by
        // payroll_month), same refund figures, same single resolved correction row.
        PayrollPeriodDto reprocessedM1 = payrollService.process(new ProcessPayrollRequest(monthM1, List.of()), hr());
        assertThat(reprocessedM1.id()).isEqualTo(processedM1.id());
        PayrollLineDto reprocessedLine = lineFor(reprocessedM1, employeeId);
        assertThat(reprocessedLine.leaveRefundDays()).isEqualByComparingTo("1.00");
        assertThat(reprocessedLine.leaveDeductionRefund()).isEqualByComparingTo(processedLine.leaveDeductionRefund());
        assertThat(reprocessedLine.netPay()).isEqualByComparingTo(processedLine.netPay());
        assertThat(countCorrectionRows(leave.id())).isEqualTo(1);
        OffsetDateTimeAndPeriod reResolved = fetchResolution(leave.id());
        assertThat(reResolved.resolvedPeriodId()).isEqualTo(processedM1.id());

        // A THIRD month (M+2) must see nothing outstanding -- the correction was fully consumed by
        // M+1, not left dangling or double-counted forward.
        PayrollPeriodDto previewM2 = payrollService.preview(new ProcessPayrollRequest(monthM1.plusMonths(1), List.of()), hr());
        PayrollLineDto previewLineM2 = lineFor(previewM2, employeeId);
        assertThat(previewLineM2.leaveRefundDays()).isEqualByComparingTo("0.00");
        assertThat(previewLineM2.leaveDeductionRefund()).isEqualByComparingTo("0.00");
    }

    @Test
    void pendingCorrectionWithNoOtherPayrollActivityStillRefunds() {
        long employeeId = insertEmployee("REFUND-002");
        LocalDate month = firstMondayOfMonth(3).withDayOfMonth(1);
        // Correction inserted directly, bypassing the leave workflow entirely, to isolate the
        // payroll-side refund logic from leave-side plumbing: this employee has NEVER submitted any
        // leave request and has no other payroll inputs this month at all -- the refund must still
        // apply on an otherwise completely vanilla run.
        insertPendingCorrection(employeeId, month.minusMonths(1), new BigDecimal("2.50"));

        PayrollPeriodDto preview = payrollService.preview(new ProcessPayrollRequest(month, List.of()), hr());
        PayrollLineDto line = lineFor(preview, employeeId);
        assertThat(line.leaveRefundDays()).isEqualByComparingTo("2.50");
        assertThat(line.leaveDeductionRefund()).isEqualByComparingTo(DAILY_RATE.multiply(new BigDecimal("2.50")).setScale(2, java.math.RoundingMode.HALF_UP));

        PayrollPeriodDto processed = payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        PayrollLineDto processedLine = lineFor(processed, employeeId);
        assertThat(processedLine.leaveRefundDays()).isEqualByComparingTo("2.50");
        assertThat(leaveRepository.findPendingPayrollCorrectionsByEmployee()).doesNotContainKey(employeeId);
    }

    // --- helpers ------------------------------------------------------------

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
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,
            unpaidLeaveDays,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null // withholdingTaxOverride (none)
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

    private void insertPendingCorrection(long employeeId, LocalDate payrollMonth, BigDecimal unpaidDays) {
        // A correction always references a real leave_request row (FK), so create a trivial
        // already-cancelled one to satisfy it -- its own fields are irrelevant to this test, only
        // the correction row's employee_id/payroll_month/unpaid_days_to_refund matter.
        Long leaveRequestId = jdbc.queryForObject("""
            INSERT INTO hr.leave_request (
                employee_id, leave_type_code, start_date, end_date, total_days, paid_days, unpaid_days,
                quota_year, reason, status, quota_remaining_before, quota_remaining_after, requested_by_id
            )
            VALUES (
                :employeeId, 'VACATION', :payrollMonth, :payrollMonth, 1, 0, 1,
                EXTRACT(YEAR FROM :payrollMonth)::smallint, 'fixture', 'CANCELLED', 6, 6, :employeeId
            )
            RETURNING leave_request_id
            """, new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("payrollMonth", payrollMonth), Long.class);
        leaveRepository.recordPayrollCorrection(leaveRequestId, employeeId, payrollMonth, unpaidDays);
    }

    private int countCorrectionRows(long leaveRequestId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)::int FROM hr.leave_payroll_correction WHERE leave_request_id = :leaveRequestId
            """, new MapSqlParameterSource("leaveRequestId", leaveRequestId), Integer.class);
        return count == null ? 0 : count;
    }

    private OffsetDateTimeAndPeriod fetchResolution(long leaveRequestId) {
        return jdbc.queryForObject("""
            SELECT resolved_at, resolved_payroll_period_id
              FROM hr.leave_payroll_correction
             WHERE leave_request_id = :leaveRequestId
            """,
            new MapSqlParameterSource("leaveRequestId", leaveRequestId),
            (rs, rowNum) -> new OffsetDateTimeAndPeriod(
                rs.getObject("resolved_at", java.time.OffsetDateTime.class),
                rs.getObject("resolved_payroll_period_id", Long.class)));
    }

    private record OffsetDateTimeAndPeriod(java.time.OffsetDateTime resolvedAt, Long resolvedPeriodId) {}
}
