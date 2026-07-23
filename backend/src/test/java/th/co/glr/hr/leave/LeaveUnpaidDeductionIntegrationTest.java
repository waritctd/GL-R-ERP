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
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Leave -&gt; payroll unpaid-day deduction (2026-07-23): real-Postgres coverage of the redesigned
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
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- every date below is fixed relative to this so the
    // 7-day advance-notice rule is a non-issue for dates in mid-July onward.
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z");

    private LeaveRepository leaveRepository;
    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaborators() {
        leaveRepository = new LeaveRepository(jdbc);
        // fileStorage/leaveAttachments are mocked (attachment storage is not under test here), but
        // sickLeaveBeyondQuotaStillAutoRejectsWithoutACertificate submits a real MockMultipartFile
        // certificate, and LeaveService#submit's attachment path does a REAL FK-constrained UPDATE
        // (hr.leave_request.attachment_id -> hr.file_attachment.attachment_id) -- so the stubbed
        // LeaveAttachmentDto must point at a real row, not a fabricated id.
        FileStorageService fileStorage = mock(FileStorageService.class);
        when(fileStorage.store(anyString(), anyLong(), any(), any()))
            .thenReturn(new FileStorageService.StoredFile("cert.pdf", "/tmp/cert.pdf", "application/pdf", 3L));
        long fileAttachmentId = jdbc.queryForObject("""
            INSERT INTO hr.file_attachment (domain, owner_id, file_name, file_path, mime_type, file_size)
            VALUES ('leave', 1, 'cert.pdf', '/tmp/cert.pdf', 'application/pdf', 3)
            RETURNING attachment_id
            """, Map.of(), Long.class);
        LeaveAttachmentRepository leaveAttachments = mock(LeaveAttachmentRepository.class);
        when(leaveAttachments.save(anyLong(), anyString(), anyString(), anyString(), any(), anyLong()))
            .thenReturn(new LeaveAttachmentDto(fileAttachmentId, "leave", 1L, "cert.pdf", "application/pdf", 3L, 1L, Instant.now()));
        leaveService = new LeaveService(
            leaveRepository,
            leaveAttachments,
            fileStorage,
            mock(AuditService.class),
            mock(NotificationService.class),
            new AppProperties(),
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));
    }

    @Test
    void withinQuotaLeaveIsFullyPaidAndContributesNoUnpaidDays() {
        long employeeId = insertEmployee("VAC-WITHIN");

        // Mon 2026-07-13 .. Tue 2026-07-14: 2 working days, well within the 6-day VACATION quota.
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-14"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("2.00");
        assertThat(result.paidDays()).isEqualByComparingTo("2.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("0.00");
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .doesNotContainKey(employeeId);
    }

    @Test
    void beyondQuotaLeaveIsApprovedWithASplitInsteadOfAutoRejected() {
        long employeeId = insertEmployee("VAC-BEYOND");

        // Mon 2026-07-13 .. Tue 2026-07-21 covers 7 working days (13,14,15,16,17,20,21), against a
        // 6-day VACATION quota with nothing used yet: 6 paid + 1 unpaid. The gate must APPROVE this,
        // not auto-reject it the way the pre-redesign quota gate would have.
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-21"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("7.00");
        assertThat(result.paidDays()).isEqualByComparingTo("6.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("1.00");
        assertThat(result.quotaRemainingAfter()).isEqualByComparingTo("0.00");

        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .containsEntry(employeeId, new BigDecimal("1.00"));
    }

    @Test
    void leaveWithoutPayIsAlwaysFullyUnpaidFromDayOne() {
        long employeeId = insertEmployee("LWOP-001");

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "LEAVE_WITHOUT_PAY", "2026-07-13", "2026-07-14"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.paidDays()).isEqualByComparingTo("0.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("2.00");
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .containsEntry(employeeId, new BigDecimal("2.00"));
    }

    @Test
    void aLeaveSpanningTwoCalendarMonthsSplitsItsUnpaidDaysCorrectly() {
        long employeeId = insertEmployee("SPLIT-001");

        // Thu 2026-07-30 .. Wed 2026-08-05: working days (chronological) are 7/30, 7/31, 8/3, 8/4,
        // 8/5 -- 5 total. PERSONAL quota is 3 (nothing used yet), so the first 3 (7/30, 7/31, 8/3) are
        // paid and the last 2 (8/4, 8/5) are unpaid -- both landing in August despite the request
        // starting in July.
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-30", "2026-08-05"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("5.00");
        assertThat(result.paidDays()).isEqualByComparingTo("3.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("2.00");

        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .doesNotContainKey(employeeId);
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-08-01")))
            .containsEntry(employeeId, new BigDecimal("2.00"));
    }

    @Test
    void sickLeaveBeyondQuotaStillAutoRejectsWithoutACertificate() {
        long employeeId = insertEmployee("SICK-30PLUS");
        // Exhaust the 30-day SICK quota first, WITH a certificate each time so the fill-up itself is
        // approved.
        MockMultipartFile certificate = new MockMultipartFile("attachment", "cert.pdf", "application/pdf", "pdf".getBytes());
        LeaveRequestDto fillUp = leaveService.submit(
            submitRequest(employeeId, "SICK", "2026-07-13", "2026-08-21"), // 30 working weekdays
            certificate,
            employee(employeeId));
        assertThat(fillUp.status()).isEqualTo("APPROVED");
        assertThat(fillUp.paidDays()).isEqualByComparingTo("30.00");

        // A further SICK request with quota fully exhausted (remaining 0) and NO certificate: the
        // certificate rule must still fire and auto-reject -- it does not get silently waived just
        // because the request would have been entirely unpaid anyway.
        LeaveRequestDto beyondQuotaNoAttachment = leaveService.submit(
            submitRequest(employeeId, "SICK", "2026-08-24", "2026-08-24"),
            employee(employeeId));

        assertThat(beyondQuotaNoAttachment.status()).isEqualTo("AUTO_REJECTED");
        assertThat(beyondQuotaNoAttachment.systemNote()).contains("medical certificate");
        assertThat(beyondQuotaNoAttachment.paidDays()).isEqualByComparingTo("0.00");
        assertThat(beyondQuotaNoAttachment.unpaidDays()).isEqualByComparingTo("0.00");
    }

    @Test
    void cancellingAfterPayrollHasProcessedRecordsARealPayrollCorrection() {
        long employeeId = insertEmployee("CANCEL-001");
        LeaveRequestDto approved = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-21"), // 7 working days, 6 paid + 1 unpaid
            employee(employeeId));
        assertThat(approved.unpaidDays()).isEqualByComparingTo("1.00");

        // Payroll for July has since been processed -- the 1 unpaid day already reduced this
        // employee's July net pay.
        insertProcessedPayrollPeriod(LocalDate.parse("2026-07-01"));

        leaveService.cancel(approved.id(), new ReviewLeaveRequest("no longer needed"), hr());

        Map<Long, BigDecimal> pending = leaveRepository.findPendingPayrollCorrectionsByEmployee();
        assertThat(pending).containsEntry(employeeId, new BigDecimal("1.00"));

        // Once cancelled, the leave no longer contributes to the (already-closed) month's unpaid-day
        // query -- its status is no longer APPROVED. The correction row above is the only remaining
        // trace that a day was ever deducted for it.
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .doesNotContainKey(employeeId);
    }

    @Test
    void cancellingBeforePayrollProcessesRecordsNoCorrection() {
        long employeeId = insertEmployee("CANCEL-002");
        LeaveRequestDto approved = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-21"),
            employee(employeeId));
        assertThat(approved.unpaidDays()).isEqualByComparingTo("1.00");

        // No payroll_period has been PROCESSED for July -- nothing was ever actually deducted, so
        // cancelling owes no credit back.
        leaveService.cancel(approved.id(), new ReviewLeaveRequest("changed my mind"), hr());

        assertThat(leaveRepository.findPendingPayrollCorrectionsByEmployee()).doesNotContainKey(employeeId);
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

    private long insertEmployee(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE)
            RETURNING employee_id
            """, Map.of("code", code), Long.class);
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
