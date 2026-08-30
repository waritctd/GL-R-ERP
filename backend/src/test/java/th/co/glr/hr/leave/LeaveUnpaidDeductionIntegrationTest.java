package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
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
        // V134 storage-durability fix: LeaveService#submit stores attachments to the database now,
        // via FileStorageService#storeInDatabase + LeaveAttachmentRepository#saveWithContent.
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
    void withinQuotaLeaveIsFullyPaidAndContributesNoUnpaidDays() {
        long employeeId = insertEmployee("VAC-WITHIN");

        // Mon 2026-07-13 .. Tue 2026-07-14: 2 working days, well within the 6-day VACATION quota.
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-14"),
            employee(employeeId));

        // Leave requires approval (2026-08-05): this test's subject is the SUBMISSION-time split
        // (fully paid, zero unpaid days) -- a rule-passing request now lands SUBMITTED, not
        // APPROVED. No #approve() needed for the doesNotContainKey assertion below: it holds
        // trivially either way (zero unpaid days), so it is not evidence either way about the
        // approval seam -- that seam has its own dedicated coverage below.
        assertThat(result.status()).isEqualTo("SUBMITTED");
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

        // Leave requires approval (2026-08-05): the split (paidDays/unpaidDays/quotaRemainingAfter)
        // is computed and persisted at #submit and is exactly what this test's title is about --
        // "approved with a split instead of auto-rejected" now means "reaches SUBMITTED (rule
        // passed) with the split already computed", not "reaches APPROVED".
        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.totalDays()).isEqualByComparingTo("7.00");
        assertThat(result.paidDays()).isEqualByComparingTo("6.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("1.00");
        assertThat(result.quotaRemainingAfter()).isEqualByComparingTo("0.00");

        // findUnpaidLeaveDaysByEmployeeForMonth is the separate payroll-visibility seam, which is
        // filtered to APPROVED requests only (LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth)
        // -- exercising it for real requires actually driving the request to APPROVED first.
        leaveService.approve(result.id(), new ReviewLeaveRequest("approved"), hr());
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .containsEntry(employeeId, new BigDecimal("1.00"));
    }

    /**
     * Leave requires approval (2026-08-05): THE payroll-critical property this whole branch rests
     * on, pinned on ONE fixture with BOTH sides asserted -- not two separate tests that could each
     * pass for the wrong reason. Before this branch, a rule-passing request reached APPROVED (and
     * therefore payroll-visibility, via {@link LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth}'s
     * {@code WHERE status = 'APPROVED'}) instantly, at submit time -- SUBMITTED was unreachable from
     * {@link LeaveService#submit} at all. Now a rule-passing request sits SUBMITTED until a human
     * reviews it, and payroll must not see it until then.
     *
     * <p>MUTATION-CHECK: widening {@code findUnpaidLeaveDaysByEmployeeForMonth}'s status filter to
     * include SUBMITTED (e.g. {@code WHERE lr.status IN ('APPROVED', 'SUBMITTED')}) must turn the
     * FIRST assertion below red (the SUBMITTED-only side) and nothing else in this class.
     */
    @Test
    void submittedLeaveContributesNothingToPayrollUntilApprovedThenContributesItsUnpaidDays() {
        long employeeId = insertEmployee("SUBMIT-GATE-001");

        // Mon 2026-07-13 .. Tue 2026-07-21: 7 working days against a 6-day VACATION quota, nothing
        // used yet -> 6 paid + 1 unpaid, computed and persisted at submit time regardless of status.
        LeaveRequestDto leave = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-21"),
            employee(employeeId));
        assertThat(leave.status()).isEqualTo("SUBMITTED");
        assertThat(leave.unpaidDays()).isEqualByComparingTo("1.00");

        // Side 1: SUBMITTED (pending approval) contributes ZERO to payroll's unpaid-day view --
        // the whole point of this branch. Before this branch this request could not even exist in
        // this state (submit() went straight to APPROVED), so this side was previously untestable.
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .as("a SUBMITTED request must not yet reduce anyone's pay")
            .doesNotContainKey(employeeId);

        leaveService.approve(leave.id(), new ReviewLeaveRequest("approved"), hr());

        // Side 2: the SAME request, now APPROVED, contributes its unpaid days -- proving Side 1 was
        // a genuine "not yet", not an accidental "never" (e.g. a broken employee_id join).
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .as("the identical request, now APPROVED, must reduce pay")
            .containsEntry(employeeId, new BigDecimal("1.00"));
    }

    @Test
    void leaveWithoutPayIsAlwaysFullyUnpaidFromDayOne() {
        long employeeId = insertEmployee("LWOP-001");

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "LEAVE_WITHOUT_PAY", "2026-07-13", "2026-07-14"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.paidDays()).isEqualByComparingTo("0.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("2.00");
        // Leave requires approval (2026-08-05): findUnpaidLeaveDaysByEmployeeForMonth is filtered
        // to APPROVED requests -- drive the request there before checking payroll's view of it.
        leaveService.approve(result.id(), new ReviewLeaveRequest("approved"), hr());
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .containsEntry(employeeId, new BigDecimal("2.00"));
    }

    @Test
    void aLeaveSpanningTwoCalendarMonthsSplitsItsUnpaidDaysCorrectly() {
        long employeeId = insertEmployee("SPLIT-001");

        // §5 leave-rules-as-data (V116): this scenario used to submit PERSONAL, but PERSONAL now
        // carries a 3-consecutive-calendar-day cap (§5.2) that a 13-calendar-day span like this one
        // would violate -- see LeaveTypeRuleIntegrationTest for that gate's dedicated coverage. VACATION
        // has no consecutive-day cap, so it is used here instead; the point of this test is the
        // cross-month unpaid-day split, not which type triggers it.
        //
        // Thu 2026-07-23 .. Tue 2026-08-04: working days (chronological) are 7/23, 7/24, 7/27, 7/28,
        // 7/29, 7/30, 7/31 (7 in July) then 8/3, 8/4 (2 in August) -- 9 total... but the 6-day VACATION
        // quota (nothing used yet) only covers the first 6 (7/23 through 7/30, all July): paid=6,
        // unpaid=3, split 1 (7/31) in July and 2 (8/3, 8/4) in August -- a genuinely cross-month UNPAID
        // split, which the original all-August-unpaid PERSONAL scenario did not actually exercise.
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-23", "2026-08-04"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.totalDays()).isEqualByComparingTo("9.00");
        assertThat(result.paidDays()).isEqualByComparingTo("6.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("3.00");

        // Leave requires approval (2026-08-05): findUnpaidLeaveDaysByEmployeeForMonth is filtered
        // to APPROVED requests -- drive the request there before checking payroll's per-month view.
        leaveService.approve(result.id(), new ReviewLeaveRequest("approved"), hr());
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01")))
            .containsEntry(employeeId, new BigDecimal("1.00"));
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-08-01")))
            .containsEntry(employeeId, new BigDecimal("2.00"));
    }

    @Test
    void sickLeaveBeyondQuotaWithNoCertificateIsApprovedButFullyUnpaidUntilTheMonthlyToleranceRunsOut() {
        // V124 (§5.1): a certificate-less SICK request is no longer an unconditional auto-reject --
        // it is tolerated up to 3 occasions per calendar month, independent of quota state. This test
        // used to pin the OLD "certificate rule fires regardless of quota" behaviour
        // (sickLeaveBeyondQuotaStillAutoRejectsWithoutACertificate, pre-V124: quota-exhausted +
        // no-certificate => outright AUTO_REJECTED, 0 days). That premise is gone -- quota exhaustion
        // and the certificate rule are now genuinely independent: quota exhaustion still drives an
        // APPROVED-but-fully-UNPAID split (the ordinary quota machinery, untouched by this migration),
        // while the certificate rule now separately gates on the MONTHLY OCCASION COUNT, not on
        // quota availability at all.
        long employeeId = insertEmployee("SICK-30PLUS");
        // Exhaust the 30-day SICK quota first, WITH a certificate each time so the fill-up itself is
        // approved.
        MockMultipartFile certificate = new MockMultipartFile("attachment", "cert.pdf", "application/pdf", "pdf".getBytes());
        LeaveRequestDto fillUp = leaveService.submit(
            submitRequest(employeeId, "SICK", "2026-07-13", "2026-08-21"), // 30 working weekdays
            certificate,
            employee(employeeId));
        assertThat(fillUp.status()).isEqualTo("SUBMITTED");
        assertThat(fillUp.paidDays()).isEqualByComparingTo("30.00");

        // Occasions 1-3 this month (August), quota fully exhausted (remaining 0): each is APPROVED
        // (money-moving: the OLD code auto-rejected every one of these, 0 pay) but entirely UNPAID
        // (the ordinary quota-exceeded split, unrelated to the certificate rule).
        LeaveRequestDto firstOccasion = leaveService.submit(
            submitRequest(employeeId, "SICK", "2026-08-24", "2026-08-24"), employee(employeeId)); // Mon
        LeaveRequestDto secondOccasion = leaveService.submit(
            submitRequest(employeeId, "SICK", "2026-08-25", "2026-08-25"), employee(employeeId)); // Tue
        LeaveRequestDto thirdOccasion = leaveService.submit(
            submitRequest(employeeId, "SICK", "2026-08-26", "2026-08-26"), employee(employeeId)); // Wed
        for (LeaveRequestDto occasion : List.of(firstOccasion, secondOccasion, thirdOccasion)) {
            assertThat(occasion.status()).isEqualTo("SUBMITTED");
            assertThat(occasion.paidDays()).isEqualByComparingTo("0.00");
            assertThat(occasion.unpaidDays()).isEqualByComparingTo("1.00");
        }

        // The 4th certificate-less occasion this SAME month: the monthly tolerance (not quota) is
        // what now binds -- AUTO_REJECTED, exactly the old outright-rejection shape (0/0), but for
        // the tolerance reason, not merely "quota is 0".
        LeaveRequestDto fourthOccasion = leaveService.submit(
            submitRequest(employeeId, "SICK", "2026-08-27", "2026-08-27"), employee(employeeId)); // Thu

        assertThat(fourthOccasion.status()).isEqualTo("AUTO_REJECTED");
        assertThat(fourthOccasion.systemNoteCode()).isEqualTo("SICK_NO_CERT_TOLERANCE_EXHAUSTED");
        assertThat(fourthOccasion.systemNote()).isNotBlank();
        assertThat(fourthOccasion.paidDays()).isEqualByComparingTo("0.00");
        assertThat(fourthOccasion.unpaidDays()).isEqualByComparingTo("0.00");
    }

    @Test
    void cancellingAfterPayrollHasProcessedRecordsARealPayrollCorrection() {
        long employeeId = insertEmployee("CANCEL-001");
        LeaveRequestDto approved = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-21"), // 7 working days, 6 paid + 1 unpaid
            employee(employeeId));
        assertThat(approved.unpaidDays()).isEqualByComparingTo("1.00");
        // Leave requires approval (2026-08-05): this test's subject is the cancel-after-close
        // correction, which LeaveService#recordPayrollCorrectionIfNeeded only records for a request
        // that is APPROVED at cancel time -- drive it there first.
        leaveService.approve(approved.id(), new ReviewLeaveRequest("approved"), hr());

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

    /**
     * Paper-form contact-during-leave autofill (2026-07-25 review fix), authz -- required by
     * CLAUDE.md's "Permission changes must ship evidence" rule: {@code GET
     * /api/leave/contact-defaults} exposes another employee's home address + phone, so it needs the
     * same real-DB, wrong-way-round proof as any other scope/filter change. Two employees with no
     * manager relationship (plain {@link #insertEmployee} sets no {@code reports_to_employee_id}): a
     * plain employee asking for a peer's contact defaults -- someone who is neither themself, their
     * manager, nor a direct report -- must be refused. This asserts the caller CANNOT reach what they
     * shouldn't (not merely that they can reach their own), matching {@link LeaveService#balances}'s
     * predicate that {@link LeaveService#contactDefaults} deliberately reuses.
     */
    @Test
    void employeeCannotReadANonReportPeersContactDefaults() {
        long actorId = insertEmployee("PEER-ACTOR");
        long peerId = insertEmployee("PEER-OTHER");

        assertThatThrownBy(() -> leaveService.contactDefaults(employee(actorId), peerId))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void cancellingBeforePayrollProcessesRecordsNoCorrection() {
        long employeeId = insertEmployee("CANCEL-002");
        LeaveRequestDto approved = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-21"),
            employee(employeeId));
        assertThat(approved.unpaidDays()).isEqualByComparingTo("1.00");
        // Leave requires approval (2026-08-05): approve first so this test's "no processed period ->
        // no correction" assertion is genuinely exercising #recordPayrollCorrectionIfNeeded's
        // processed-period check, not trivially passing because the request was never APPROVED at
        // all (that would make this test vacuous -- see CLAUDE.md's fixture-supplies-what-
        // production-lacks trap).
        leaveService.approve(approved.id(), new ReviewLeaveRequest("approved"), hr());

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

    // §5 leave-rules-as-data (V116): VACATION/PERSONAL now carry a min_service_months eligibility
    // floor (12/4 months respectively). None of the scenarios in this class are ABOUT that gate --
    // they predate it and exercise the quota/paid-cap/cross-month-split machinery -- so the default
    // hire date here is far enough in the past (2015, well over both floors) that it is a no-op for
    // every existing test. LeaveTypeRuleIntegrationTest carries the dedicated min-service coverage,
    // including the "no hire_date on file" case.
    private long insertEmployee(String code) {
        return insertEmployee(code, LocalDate.parse("2015-01-01"));
    }

    private long insertEmployee(String code, LocalDate hireDate) {
        long employeeId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, :hireDate)
            RETURNING employee_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("hireDate", hireDate), Long.class);
        // §5.3.5 VACATION carry-forward (V127) interaction: the default 2015 hire date leaves 2025
        // (fully elapsed by this class's fixed 2026-07-01 clock) reading as "0 of 6 used", which now
        // legitimately carries 6.00 into 2026 -- correct new behaviour, but not what this class's
        // pre-existing quota/paid-cap assertions (written before carry-forward existed) are about.
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
                quota_remaining_before, quota_remaining_after
            )
            VALUES (:leaveRequestId, 2025, 6.00, 6.00, 0.00, 6.00, 0.00)
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
