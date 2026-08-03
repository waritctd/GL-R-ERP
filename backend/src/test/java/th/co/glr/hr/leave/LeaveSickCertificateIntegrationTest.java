package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * §5.1 SICK certificate filing-window + no-certificate monthly tolerance (V124): real-Postgres
 * coverage of {@code hr.leave_type.certificate_filing_window_days} /
 * {@code no_certificate_monthly_tolerance}, {@link LeaveRepository#countNoCertificateRequestsInMonth}'s
 * real SQL (the {@code attachment_id IS NULL} predicate and the {@code start_date BETWEEN} month
 * range), and {@link LeaveService#sickCertificateRuleOutcome}'s combined decision through the real V124
 * schema. {@code LeaveServiceTest} covers the same decision table against a Mockito-faked
 * repository; this class proves the real SQL -- a mocked repository would happily "pass" while the
 * SQL counted the wrong month, the wrong status set, or ignored {@code attachment_id} entirely.
 *
 * <p>Unlike {@link LeaveTypeRuleIntegrationTest} (which mocks {@link LeaveAttachmentRepository}/
 * {@link FileStorageService} since it never submits WITH a real attachment), this class wires a REAL
 * {@link LeaveAttachmentRepository} against {@code jdbc} -- {@code hr.leave_request.attachment_id}
 * must be genuinely set (or genuinely NULL) in the database for
 * {@code countNoCertificateRequestsInMonth}'s {@code attachment_id IS NULL} predicate to mean
 * anything real. {@link FileStorageService} itself stays mocked (no real disk I/O needed to prove
 * the SQL/decision logic under test here).
 *
 * <p><b>Money note:</b> every test in this class that asserts APPROVED on a certificate-less request
 * is, directly, a claim about SICK leave that today's production code auto-rejects outright (no
 * leave, no pay) now being granted and paid instead. See the branch's PR body for the plain
 * statement of this effect.
 */
class LeaveSickCertificateIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- matches every other leave integration test's fixed
    // clock. SICK's advance_notice_days is 0 (unaffected by this migration), so no date below needs
    // to clear an advance-notice window; every date is chosen only to land on a specific side of the
    // 3-working-day filing window or a specific calendar month.
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z");
    private static final Set<String> LEAVE_ATTACHMENT_MIME_TYPES = Set.of(
        "application/pdf", "image/jpeg", "image/png");

    private LeaveRepository leaveRepository;
    private LeaveAttachmentRepository leaveAttachments;
    private FileStorageService fileStorage;
    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaborators() {
        leaveRepository = new LeaveRepository(jdbc);
        // REAL attachment repository (unlike LeaveTypeRuleIntegrationTest): attachment_id must be
        // genuinely persisted for countNoCertificateRequestsInMonth's SQL to be under real test.
        leaveAttachments = new LeaveAttachmentRepository(jdbc);
        fileStorage = mock(FileStorageService.class);
        leaveService = new LeaveService(
            leaveRepository,
            leaveAttachments,
            fileStorage,
            mock(AuditService.class),
            mock(NotificationService.class),
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));
    }

    // ─────────────────────────────────────────────────────────────────────
    // No-certificate monthly tolerance: real SQL proof that occasions 1-3 are ALLOWED and the 4th is
    // REJECTED -- and that the count resets at a calendar-month boundary. Each request here is a
    // REAL leave_request row (not a stubbed count), so LeaveRepository#countNoCertificateRequestsInMonth's
    // actual SQL (attachment_id IS NULL, start_date BETWEEN, status IN) is what decides the outcome.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void firstThreeCertificatelessSickRequestsInACalendarMonthAreApprovedButTheFourthIsAutoRejected() {
        long employeeId = insertEmployee("SICK-TOL-001");

        // Mon 2026-07-06, Wed 2026-07-08, Fri 2026-07-10, Mon 2026-07-13 -- four distinct working
        // days, all in July 2026, all certificate-less. The boundary is asserted from BOTH sides in
        // one flow: the 3rd is still allowed, the 4th (same employee, same month, same shape) is not.
        LeaveRequestDto first = leaveService.submit(sickRequest(employeeId, "2026-07-06"), employee(employeeId));
        LeaveRequestDto second = leaveService.submit(sickRequest(employeeId, "2026-07-08"), employee(employeeId));
        LeaveRequestDto third = leaveService.submit(sickRequest(employeeId, "2026-07-10"), employee(employeeId));
        LeaveRequestDto fourth = leaveService.submit(sickRequest(employeeId, "2026-07-13"), employee(employeeId));

        assertThat(first.status()).isEqualTo("APPROVED");
        assertThat(second.status()).isEqualTo("APPROVED");
        assertThat(third.status()).isEqualTo("APPROVED");
        assertThat(fourth.status()).isEqualTo("AUTO_REJECTED");
        assertThat(fourth.systemNoteCode()).isEqualTo("SICK_NO_CERT_TOLERANCE_EXHAUSTED");
        assertThat(fourth.systemNote()).isNotBlank();
        // Money-effect pin: the first three each granted a paid day (paid_days_cap is NULL for SICK,
        // well within the 30-day quota) -- today's pre-V124 code would have auto-rejected ALL FOUR.
        assertThat(first.paidDays()).isEqualByComparingTo("1.00");
        assertThat(second.paidDays()).isEqualByComparingTo("1.00");
        assertThat(third.paidDays()).isEqualByComparingTo("1.00");
        assertThat(fourth.paidDays()).isEqualByComparingTo("0.00");
    }

    @Test
    void theNoCertificateOccasionCountResetsAtACalendarMonthBoundary() {
        long employeeId = insertEmployee("SICK-TOL-RESET-001");

        // Three certificate-less occasions in JUNE (Mon 22nd, Tue 23rd, Wed 24th 2026) exhaust June's
        // tolerance...
        LeaveRequestDto juneFirst = leaveService.submit(sickRequest(employeeId, "2026-06-22"), employee(employeeId));
        LeaveRequestDto juneSecond = leaveService.submit(sickRequest(employeeId, "2026-06-23"), employee(employeeId));
        LeaveRequestDto juneThird = leaveService.submit(sickRequest(employeeId, "2026-06-24"), employee(employeeId));
        assertThat(juneFirst.status()).isEqualTo("APPROVED");
        assertThat(juneSecond.status()).isEqualTo("APPROVED");
        assertThat(juneThird.status()).isEqualTo("APPROVED");

        // A fourth certificate-less occasion, still in June, would be rejected (same boundary as the
        // test above) -- proving June's own count is genuinely exhausted, not that this employee has
        // some unlimited allowance.
        LeaveRequestDto juneFourth = leaveService.submit(sickRequest(employeeId, "2026-06-25"), employee(employeeId));
        assertThat(juneFourth.status()).isEqualTo("AUTO_REJECTED");

        // ...but a request starting in JULY is a NEW calendar month: the counter must have reset, not
        // carried the exhausted June count forward. Wed 2026-07-01 is FIXED_NOW itself, a working day,
        // SICK requires zero advance notice.
        LeaveRequestDto julyFirst = leaveService.submit(sickRequest(employeeId, "2026-07-01"), employee(employeeId));
        assertThat(julyFirst.status()).isEqualTo("APPROVED");
    }

    @Test
    void certifiedSickRequestsDoNotCountAgainstTheNoCertificateMonthlyTolerance() {
        // MUTATION-CHECK NOTE: this is the ONE test in this class that only the real SQL can prove --
        // every other tolerance test in this class happens to submit only certificate-less requests,
        // so a query that (wrongly) dropped the "attachment_id IS NULL" predicate from
        // LeaveRepository#countNoCertificateRequestsInMonth would still pass them all (a
        // Mockito-mocked repository can never expose this: it would just return whatever count a test
        // stubs, regardless of what the real WHERE clause says). Three CERTIFIED SICK requests this
        // month, then ONE certificate-less request in the SAME month: if the predicate is real, the
        // certificate-less request is occasion #1 of the no-certificate count (APPROVED) -- the three
        // certified ones must NOT be counted against it, even though they are also SICK requests in
        // the same month for the same employee.
        long employeeId = insertEmployee("SICK-NOCT-001");
        stubFileStorage();

        LeaveRequestDto certifiedFirst = leaveService.submit(
            sickRequest(employeeId, "2026-07-01"), certificate(), employee(employeeId));
        LeaveRequestDto certifiedSecond = leaveService.submit(
            sickRequest(employeeId, "2026-07-02"), certificate(), employee(employeeId));
        LeaveRequestDto certifiedThird = leaveService.submit(
            sickRequest(employeeId, "2026-07-03"), certificate(), employee(employeeId));
        assertThat(certifiedFirst.status()).isEqualTo("APPROVED");
        assertThat(certifiedSecond.status()).isEqualTo("APPROVED");
        assertThat(certifiedThird.status()).isEqualTo("APPROVED");

        LeaveRequestDto uncertifiedFourth = leaveService.submit(
            sickRequest(employeeId, "2026-07-06"), employee(employeeId));

        assertThat(uncertifiedFourth.status()).isEqualTo("APPROVED");
        assertThat(uncertifiedFourth.paidDays()).isEqualByComparingTo("1.00");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Certificate filing window: real submit() calls WITH a real MultipartFile, so
    // hr.leave_request.attachment_id is genuinely populated (or, for the rejected case, still
    // populated -- see LeaveService#submit's unconditional post-create attach block) through the
    // real LeaveAttachmentRepository/hr.file_attachment schema.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void aSickRequestWithACertificateFiledWithinTheWorkingDayWindowIsApproved() {
        // FIXED_NOW = Wed 2026-07-01. Start Mon 2026-06-29: addWorkingDays(Mon, 3) = Thu 2026-07-02
        // -- "today" (2026-07-01) is on/before that deadline -> ON TIME.
        long employeeId = insertEmployee("SICK-WIN-OK-001");
        stubFileStorage();

        LeaveRequestDto result = leaveService.submit(
            sickRequest(employeeId, "2026-06-29"), certificate(), employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.paidDays()).isEqualByComparingTo("1.00");
        assertRealAttachmentPersisted(result.id());
    }

    @Test
    void aSickRequestWithACertificateFiledOutsideTheWorkingDayWindowIsAutoRejected() {
        // Start Mon 2026-06-01 (a full month before FIXED_NOW): addWorkingDays(Mon, 3) =
        // Thu 2026-06-04 -- "today" (2026-07-01) is long past that deadline -> LATE, even though a
        // real certificate is attached.
        long employeeId = insertEmployee("SICK-WIN-LATE-001");
        stubFileStorage();

        LeaveRequestDto result = leaveService.submit(
            sickRequest(employeeId, "2026-06-01"), certificate(), employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNoteCode()).isEqualTo("SICK_CERTIFICATE_WINDOW");
        assertThat(result.paidDays()).isEqualByComparingTo("0.00");
        // The late certificate is still recorded (LeaveService#submit's attach block is unconditional
        // on hasAttachment, not on the outcome) -- it just did not buy approval.
        assertRealAttachmentPersisted(result.id());
    }

    // ─────────────────────────────────────────────────────────────────────
    // THE combined case the task calls out explicitly: no certificate AND beyond the 3-working-day
    // window, with tolerance still available -> ALLOWED. The filing-window clause governs the
    // certificate path only; it must never gate the no-certificate tolerance path. Proven with the
    // EXACT SAME start date as the "filed late" test above (which IS rejected when a certificate is
    // attached), through the real service/SQL this time.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void aCertificatelessSickRequestFiledLongAfterItsStartDateIsStillApprovedWhenToleranceRemains() {
        long employeeId = insertEmployee("SICK-COMBO-001");

        LeaveRequestDto result = leaveService.submit(sickRequest(employeeId, "2026-06-01"), employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.paidDays()).isEqualByComparingTo("1.00");
    }

    // --- helpers --------------------------------------------------------

    private long insertEmployee(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, '2015-01-01')
            RETURNING employee_id
            """, new MapSqlParameterSource().addValue("code", code), Long.class);
    }

    private SubmitLeaveRequest sickRequest(long employeeId, String date) {
        return new SubmitLeaveRequest(employeeId, "SICK", LocalDate.parse(date), LocalDate.parse(date), "Fever");
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private MultipartFile certificate() {
        return new MockMultipartFile("attachment", "cert.pdf", "application/pdf", "cert content".getBytes());
    }

    /** Stubs FileStorageService.store to return a fixed StoredFile for ANY owner id / file. */
    private void stubFileStorage() {
        when(fileStorage.store(eq("leave"), org.mockito.ArgumentMatchers.anyLong(),
            any(), eq(LEAVE_ATTACHMENT_MIME_TYPES)))
            .thenReturn(new FileStorageService.StoredFile("cert.pdf", "/uploads/leave/x/cert.pdf", "application/pdf", 4L));
    }

    private void assertRealAttachmentPersisted(long leaveRequestId) {
        Long attachmentId = jdbc.queryForObject("""
            SELECT attachment_id FROM hr.leave_request WHERE leave_request_id = :id
            """, new MapSqlParameterSource().addValue("id", leaveRequestId), Long.class);
        assertThat(attachmentId).isNotNull();
        Integer fileRowCount = jdbc.queryForObject("""
            SELECT count(*)::int FROM hr.file_attachment WHERE attachment_id = :attachmentId AND domain = 'leave'
            """, new MapSqlParameterSource().addValue("attachmentId", attachmentId), Integer.class);
        assertThat(fileRowCount).isEqualTo(1);
    }
}
