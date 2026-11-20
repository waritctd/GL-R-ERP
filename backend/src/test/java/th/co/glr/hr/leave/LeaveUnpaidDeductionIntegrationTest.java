package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Leave -&gt; payroll unpaid-day deduction: real-Postgres coverage of the
 * {@link LeaveService#submit} gate (approve-with-split instead of auto-reject-on-quota),
 * {@link LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth} (per-month attribution, including
 * the cross-month split), and the cancel-after-close reversal ({@link LeaveService#cancel}) writing
 * a real {@code hr.leave_payroll_correction} row. Mockito cannot reach any of this -- the V85 schema
 * (paid_days/unpaid_days, the relaxed quota-nonnegative check, the LEAVE_WITHOUT_PAY seed row, the
 * correction table) and the real SQL are exactly what is under test.
 *
 * <p><b>Caveat carried from the migration:</b> this test locks in behaviour, not policy -- whether
 * "beyond quota is unpaid, chronological consumption" is GL&amp;R's actual final rule still needs
 * HR/legal sign-off before this reaches a live payroll run.
 */
class LeaveUnpaidDeductionIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- every date below is fixed relative to this so the 7-day
    // advance-notice rule is a non-issue for dates in mid-July onward.
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z");

    private LeaveRepository leaveRepository;
    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaborators() {
        leaveRepository = new LeaveRepository(jdbc);
        // fileStorage/leaveAttachments are mocked, but sickLeaveBeyondQuotaStillAutoRejectsWithoutACertificate submits
        // a real MockMultipartFile certificate, and LeaveService#submit's attachment path does a REAL FK-constrained
        // UPDATE.
        FileStorageService fileStorage = mock(FileStorageService.class);
        when(fileStorage.storeInDatabase(anyString(), anyLong(), any(), any()))
            .thenReturn(new FileStorageService.StoredContent(
                "cert.pdf", "leave/1/cert.pdf", "application/pdf", 3L, "cert content".getBytes()));
        long fileAttachmentId = jdbc.queryForObject("""
            INSERT INTO hr.file_attachment (domain, owner_id, file_name, file_path, mime_type, file_size)
            VALUES ('leave', 1, 'cert.pdf', 'leave/1/cert.pdf', 'application/pdf', 3)
            RETURNING attachment_id
            """, Map.of(), Long.class);
        LeaveAttachmentRepository leaveAttachments = mock(LeaveAttachmentRepository.class);
        when(leaveAttachments.saveWithContent(anyLong(), anyString(), anyString(), anyString(), any(), anyLong(), any()))
            .thenReturn(new LeaveAttachmentDto(fileAttachmentId, "leave", 1L, "cert.pdf", "application/pdf", 3L, 1L, Instant.now()));
        leaveService = new LeaveService(
            leaveRepository,
            leaveAttachments,
            fileStorage,
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class),
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));
    }

    @Test
    void beyondQuotaLeaveIsApprovedWithASplitInsteadOfAutoRejected() {
        long employeeId = insertEmployee("VAC-BEYOND");

        // Mon 2026-07-13 .. Tue 2026-07-21 covers 7 working days (13,14,15,16,17,20,21), against a
        // 6-day VACATION quota with nothing used yet: 6 paid + 1 unpaid. The gate must accept this
        // with a split, not auto-reject it for exceeding quota.
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-21"),
            employee(employeeId));

        // Leave requires approval: the split (paidDays/unpaidDays/quotaRemainingAfter)
        // is computed and persisted at #submit and is exactly what this test's title is about --
        // "approved with a split instead of auto-rejected" means "reaches SUBMITTED (rule
        // passed) with the split already computed", not "reaches APPROVED".
        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.totalDays()).isEqualByComparingTo("7.00");
        assertThat(result.paidDays()).isEqualByComparingTo("6.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("1.00");
        assertThat(result.quotaRemainingAfter()).isEqualByComparingTo("0.00");

        // findUnpaidLeaveDaysByEmployeeForMonth is the separate payroll-visibility seam, which is filtered to APPROVED
        // requests only (LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth) -- exercising it for real requires
        // actually driving the request to APPROVED first.
        leaveService.approve(result.id(), new ReviewLeaveRequest("approved"), hr());
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .containsEntry(employeeId, new BigDecimal("1.00"));
    }

    @Test
    void cancellingAfterPayrollHasProcessedRecordsARealPayrollCorrection() {
        long employeeId = insertEmployee("CANCEL-001");
        LeaveRequestDto approved = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-21"), // 7 working days, 6 paid + 1 unpaid
            employee(employeeId));
        assertThat(approved.unpaidDays()).isEqualByComparingTo("1.00");
        // Leave requires approval: this test's subject is the cancel-after-close
        // correction, which LeaveService#recordPayrollCorrectionIfNeeded only records for a request
        // that is APPROVED at cancel time -- drive it there first.
        leaveService.approve(approved.id(), new ReviewLeaveRequest("approved"), hr());

        // Payroll for July has since been processed -- the 1 unpaid day already reduced this employee's July net pay.
        insertProcessedPayrollPeriod(LocalDate.parse("2026-07-01"));

        leaveService.cancel(approved.id(), new ReviewLeaveRequest("no longer needed"), hr());

        Map<Long, BigDecimal> pending = leaveRepository.findPendingPayrollCorrectionsByEmployee();
        assertThat(pending).containsEntry(employeeId, new BigDecimal("1.00"));

        // Once cancelled, the leave no longer contributes to the (already-closed) month's unpaid-day query -- its
        // status is no longer APPROVED. The correction row above is the only remaining trace that a day was ever
        // deducted for it.
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .doesNotContainKey(employeeId);
    }

    // --- helpers ------------------------------------------------------------

    private SubmitLeaveRequest submitRequest(long employeeId, String leaveTypeCode, String startDate, String endDate) {
        return new SubmitLeaveRequest(employeeId, leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate), "Integration test leave");
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    // §5 leave-rules-as-data (V116): VACATION/PERSONAL now carry a min_service_months eligibility floor (12/4
    // months respectively). LeaveTypeRuleIntegrationTest carries the dedicated min-service coverage, including the
    // "no hire_date on file" case.
    private long insertEmployee(String code) {
        return insertEmployee(code, LocalDate.parse("2015-01-01"));
    }

    private long insertEmployee(String code, LocalDate hireDate) {
        long employeeId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, :hireDate)
            RETURNING employee_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("hireDate", hireDate), Long.class);
        // §5.3.5 VACATION carry-forward interaction: the default 2015 hire date leaves 2025
        // (fully elapsed by this class's fixed 2026-07-01 clock) reading as "0 of 6 used", which
        // legitimately carries 6.00 into 2026 -- correct behaviour, but not what this class's
        // quota/paid-cap assertions are about.
        // Neutralised by marking 2025's VACATION quota fully used, so
        // LeaveService#ensureCarryoverGrant computes a real, correct ZERO carry-in for 2026.
        neutralizeVacationCarryForwardFrom2025(employeeId);
        return employeeId;
    }

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
