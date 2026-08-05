package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.attendance.schedule.HolidayCalendar;
import th.co.glr.hr.attendance.schedule.TieredWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.WorkScheduleAssignmentRepository;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * POST /api/leave/preview (Phase A0b): the single claim this phase's whole design rests on, proven
 * against the real repository -- {@link LeaveService#preview}'s blocking {@link LeaveRuleCode} (or
 * lack of one) agrees with the {@code system_note_code} {@link LeaveService#submit} actually persists
 * for the IDENTICAL request, across a fixture matrix spanning every bucket of {@code
 * LeaveService#autoRejectNote}'s gate chain (categorical eligibility, request-shape, document/timing,
 * and the one relational gate). If preview and submit could disagree, the feature would be worse than
 * useless -- it would tell an employee a request is fine and then reject it once they actually file.
 *
 * <p>Each test calls {@link LeaveService#preview} FIRST, then {@link LeaveService#submit} on the
 * SAME arguments -- preview writes nothing, so calling it first can never change what submit sees.
 *
 * <p>Also proves the one KNOWN, DOCUMENTED exception to "preview agrees with submit": QUICK-depth
 * preview deliberately skips {@link LeaveService#departmentCoverageRuleOutcome} and says so via
 * {@code coverageEvaluated == false} -- see {@code
 * quickDepthSkipsDepartmentCoverageAndSaysSoExplicitly} below. This is not a violation of the "same
 * code" claim above (that claim is about FULL depth, which is what every other test in this class
 * uses); it is the documented, opt-in cost of the cheap debounced read.
 */
class LeavePreviewIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- comfortably past VACATION's 3-day and PERSONAL's
    // 1-day seeded advance notice for every "should be approved" date used below, and the same fixed
    // instant LeaveRelationalRulesIntegrationTest uses (so the reused date fixtures below match it
    // exactly).
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z");

    private LeaveRepository leaveRepository;
    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaborators() {
        AppProperties appProperties = new AppProperties();
        WorkScheduleResolver scheduleResolver = new TieredWorkScheduleResolver(
            new WorkScheduleAssignmentRepository(jdbc, appProperties),
            new CompanyWideWorkScheduleResolver(appProperties),
            appProperties);
        HolidayCalendar holidayCalendar = new DbHolidayCalendar(jdbc);
        leaveRepository = new LeaveRepository(jdbc, scheduleResolver, holidayCalendar);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class),
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Baseline: no blocking gate on either side
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void approvedVacationRequestAgreesOnBothSides() {
        long employeeId = insertEmployee("PREV-OK-001", LocalDate.parse("2015-01-01"), null);
        assertPreviewAgreesWithSubmit(employeeId, "VACATION", "2026-07-13", "2026-07-13", null, false, null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Categorical eligibility (LeaveService#eligibilityRuleOutcome)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void oncePerEmploymentAgreesOnBothSides() {
        long employeeId = insertEmployee("PREV-ONCE-001", LocalDate.parse("2015-01-01"), null);
        // Pre-existing ORDINATION claim -- neither preview nor submit created this; both must see it.
        seedActiveRequest(employeeId, "ORDINATION", "2026-05-01", "2026-05-15", LeaveStatus.APPROVED);
        assertPreviewAgreesWithSubmit(
            employeeId, "ORDINATION", "2026-08-01", "2026-08-15", null, false, "ONCE_PER_EMPLOYMENT");
    }

    @Test
    void resignationGateAgreesOnBothSides() {
        long employeeId = insertEmployee("PREV-RES-001", LocalDate.parse("2015-01-01"), null);
        insertResignation(employeeId);
        assertPreviewAgreesWithSubmit(employeeId, "VACATION", "2026-07-13", "2026-07-13", null, false, "RESIGNATION_GATE");
    }

    @Test
    void probationNotPassedAgreesOnBothSides() {
        // Hired 5 days before "now" -- comfortably inside the 119-day default probation window (see
        // SpecialMoneyPolicyEvaluator#DEFAULT_PROBATION_DAYS), so PERSONAL's probation gate fires.
        long employeeId = insertEmployee("PREV-PROB-001", LocalDate.parse("2026-06-26"), null);
        assertPreviewAgreesWithSubmit(
            employeeId, "PERSONAL", "2026-07-13", "2026-07-13", null, false, "PROBATION_NOT_PASSED");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Request-shape gates
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void weddingMaxDaysAgreesOnBothSides() {
        long employeeId = insertEmployee("PREV-WED-001", LocalDate.parse("2015-01-01"), null);
        // 8-day span, over the 3-day wedding cap; well past PERSONAL's 1-day advance notice.
        assertPreviewAgreesWithSubmit(
            employeeId, "PERSONAL", "2026-07-13", "2026-07-20", "WEDDING", false, "WEDDING_MAX_DAYS");
    }

    @Test
    void contiguousLeavePairAgreesOnBothSides() {
        long employeeId = insertEmployee("PREV-CONTIG-001", LocalDate.parse("2015-01-01"), null);
        // Fri 2026-07-10 PERSONAL already taken; Mon 2026-07-13 VACATION is contiguous across the
        // weekend -- see LeaveService#isContiguous's Javadoc for why a plain weekend does not break it.
        seedActiveRequest(employeeId, "PERSONAL", "2026-07-10", "2026-07-10", LeaveStatus.APPROVED);
        assertPreviewAgreesWithSubmit(
            employeeId, "VACATION", "2026-07-13", "2026-07-13", null, false, "CONTIGUOUS_LEAVE_PAIR");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Document/timing gates
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void sickNoCertificateToleranceExhaustedAgreesOnBothSides() {
        long employeeId = insertEmployee("PREV-SICK-001", LocalDate.parse("2015-01-01"), null);
        // 3 certificate-less SICK occasions already used this month (the seeded tolerance) -- the 4th
        // must be refused on both sides.
        seedActiveRequest(employeeId, "SICK", "2026-07-02", "2026-07-02", LeaveStatus.APPROVED);
        seedActiveRequest(employeeId, "SICK", "2026-07-03", "2026-07-03", LeaveStatus.APPROVED);
        seedActiveRequest(employeeId, "SICK", "2026-07-06", "2026-07-06", LeaveStatus.APPROVED);
        assertPreviewAgreesWithSubmit(
            employeeId, "SICK", "2026-07-13", "2026-07-13", null, false, "SICK_NO_CERT_TOLERANCE_EXHAUSTED");
    }

    @Test
    void advanceNoticeAgreesOnBothSides() {
        long employeeId = insertEmployee("PREV-NOTICE-001", LocalDate.parse("2015-01-01"), null);
        // PERSONAL requires 1 day's notice; "today" (2026-07-01, the fixed clock) has none at all,
        // and the request is not declared emergency, so the plain notice gate fires.
        assertPreviewAgreesWithSubmit(
            employeeId, "PERSONAL", "2026-07-01", "2026-07-01", null, false, "ADVANCE_NOTICE");
    }

    @Test
    void emergencyToleranceExhaustedAgreesOnBothSides() {
        long employeeId = insertEmployee("PREV-EMERG-001", LocalDate.parse("2015-01-01"), null);
        // 3 prior PERSONAL requests this month already used the emergency exception (emergency_filing
        // = TRUE) -- the 4th same-day, no-notice, emergency-declared request must be refused on both
        // sides via the tolerance-exhausted message, not the plain notice one.
        markEmergencyFiling(seedActiveRequest(employeeId, "PERSONAL", "2026-07-02", "2026-07-02", LeaveStatus.APPROVED));
        markEmergencyFiling(seedActiveRequest(employeeId, "PERSONAL", "2026-07-03", "2026-07-03", LeaveStatus.APPROVED));
        markEmergencyFiling(seedActiveRequest(employeeId, "PERSONAL", "2026-07-06", "2026-07-06", LeaveStatus.APPROVED));
        assertPreviewAgreesWithSubmit(
            employeeId, "PERSONAL", "2026-07-01", "2026-07-01", null, true, "EMERGENCY_TOLERANCE_EXHAUSTED");
    }

    // ─────────────────────────────────────────────────────────────────────
    // The one relational gate (§5.3.2 department coverage)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void departmentCoverageAgreesOnBothSidesUnderFullDepth() {
        long departmentId = insertDepartment("PREV-DEPT-COVER-001");
        long requester = insertEmployee("PREV-COVER-EMP-001", LocalDate.parse("2015-01-01"), departmentId);
        long colleagueA = insertEmployee("PREV-COVER-EMP-002", LocalDate.parse("2015-01-01"), departmentId);
        long colleagueB = insertEmployee("PREV-COVER-EMP-003", LocalDate.parse("2015-01-01"), departmentId);
        seedActiveRequest(colleagueA, "VACATION", "2026-07-13", "2026-07-13", LeaveStatus.SUBMITTED);
        seedActiveRequest(colleagueB, "VACATION", "2026-07-13", "2026-07-13", LeaveStatus.APPROVED);

        assertPreviewAgreesWithSubmit(
            requester, "VACATION", "2026-07-13", "2026-07-13", null, false, "DEPARTMENT_COVERAGE");
    }

    /**
     * The documented exception, proven explicitly (not merely asserted-away): under QUICK depth,
     * preview does NOT run {@link LeaveService#departmentCoverageRuleOutcome} at all, so it reports
     * {@code blocking == null} / {@code coverageEvaluated == false} for the EXACT SAME request that
     * {@link #departmentCoverageAgreesOnBothSidesUnderFullDepth} above proves {@code submit} (and a
     * FULL-depth preview) refuses. This is {@code LeaveService#preview}'s stated contract, not a bug
     * -- a caller reading {@code coverageEvaluated == false} knows not to trust a clean QUICK verdict
     * for this specific gate.
     */
    @Test
    void quickDepthSkipsDepartmentCoverageAndSaysSoExplicitly() {
        long departmentId = insertDepartment("PREV-DEPT-COVER-QUICK-001");
        long requester = insertEmployee("PREV-COVER-QUICK-001", LocalDate.parse("2015-01-01"), departmentId);
        long colleagueA = insertEmployee("PREV-COVER-QUICK-002", LocalDate.parse("2015-01-01"), departmentId);
        long colleagueB = insertEmployee("PREV-COVER-QUICK-003", LocalDate.parse("2015-01-01"), departmentId);
        seedActiveRequest(colleagueA, "VACATION", "2026-07-13", "2026-07-13", LeaveStatus.SUBMITTED);
        seedActiveRequest(colleagueB, "VACATION", "2026-07-13", "2026-07-13", LeaveStatus.APPROVED);

        LeavePreviewDto quickPreview = leaveService.preview(new LeavePreviewRequest(
            "VACATION", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-13"), requester,
            null, false, false, LeavePreviewDepth.QUICK), employee(requester));

        assertThat(quickPreview.blocking()).as("QUICK depth must not surface the coverage rejection").isNull();
        assertThat(quickPreview.coverageEvaluated()).as("must explicitly say the gate did not run").isFalse();
        assertThat(quickPreview.datesEvaluated()).isTrue();

        // The SAME request, for real, IS refused -- confirming the QUICK read above was genuinely
        // optimistic, not merely "also happens to be approved".
        LeaveRequestDto submitted = leaveService.submit(
            new SubmitLeaveRequest(requester, "VACATION", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-13"), "Integration test leave"),
            employee(requester));
        assertThat(submitted.status()).isEqualTo("AUTO_REJECTED");
        assertThat(submitted.systemNoteCode()).isEqualTo("DEPARTMENT_COVERAGE");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Nullable dates: only the eligibility gates can run
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void previewWithNoDatesYetOnlyEvaluatesEligibilityGates() {
        long employeeId = insertEmployee("PREV-NODATES-001", LocalDate.parse("2026-06-26"), null);

        LeavePreviewDto preview = leaveService.preview(
            new LeavePreviewRequest("PERSONAL", null, null, employeeId, null, false, false, LeavePreviewDepth.FULL),
            employee(employeeId));

        assertThat(preview.datesEvaluated()).isFalse();
        assertThat(preview.coverageEvaluated()).isFalse();
        assertThat(preview.totalDays()).isNull();
        assertThat(preview.paidDays()).isNull();
        assertThat(preview.unpaidDays()).isNull();
        assertThat(preview.quotaYearSplits()).isEmpty();
        assertThat(preview.blocking()).isNotNull();
        assertThat(preview.blocking().code()).isEqualTo(LeaveRuleCode.PROBATION_NOT_PASSED);
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Calls {@link LeaveService#preview} (FULL depth) then {@link LeaveService#submit} on the
     * IDENTICAL request, and asserts preview's blocking code equals submit's persisted {@code
     * system_note_code} -- {@code null} on both sides when {@code expectedCode} is {@code null}.
     */
    private void assertPreviewAgreesWithSubmit(
            long employeeId, String leaveTypeCode, String startDate, String endDate,
            String purposeCode, boolean requestedAsEmergency, String expectedCode) {
        LeavePreviewDto preview = leaveService.preview(new LeavePreviewRequest(
            leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate), employeeId,
            purposeCode, requestedAsEmergency, false, LeavePreviewDepth.FULL), employee(employeeId));
        String previewCode = preview.blocking() == null ? null : preview.blocking().code().name();

        LeaveRequestDto submitted = leaveService.submit(new SubmitLeaveRequest(
            employeeId, leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate),
            "Integration test leave", null, null, null, null, null, null, null, purposeCode, requestedAsEmergency),
            employee(employeeId));

        assertThat(previewCode)
            .as("preview's blocking code must equal submit's system_note_code for the identical request")
            .isEqualTo(submitted.systemNoteCode());
        if (expectedCode == null) {
            assertThat(preview.blocking()).isNull();
            // Leave requires approval (2026-08-05): a rule-passing submit now lands SUBMITTED, not
            // APPROVED -- this helper's subject is preview/submit agreement on the BLOCKING code,
            // which is unaffected by this change (see LeaveService#submit's own comment).
            assertThat(submitted.status()).isEqualTo("SUBMITTED");
        } else {
            assertThat(previewCode).isEqualTo(expectedCode);
            assertThat(submitted.status()).isEqualTo("AUTO_REJECTED");
        }
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private long insertDepartment(String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.department (name_th, is_active) VALUES (:name, TRUE) RETURNING department_id
            """, Map.of("name", nameTh), Long.class);
    }

    private long insertEmployee(String code, LocalDate hireDate, Long departmentId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, is_active, hire_date, department_id)
            VALUES (:code, :code, 'ทดสอบ', TRUE, :hireDate, :departmentId)
            RETURNING employee_id
            """, new MapSqlParameterSource()
            .addValue("code", code)
            .addValue("hireDate", hireDate)
            .addValue("departmentId", departmentId),
            Long.class);
    }

    /** {@code hr.resignation} (V1): one row per employee -- see LeaveService#resignationRuleOutcome. */
    private void insertResignation(long employeeId) {
        jdbc.update("""
            INSERT INTO hr.resignation (employee_id, recorded_date, resign_date)
            VALUES (:employeeId, :recordedDate, :resignDate)
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("recordedDate", LocalDate.parse("2026-06-15"))
            .addValue("resignDate", LocalDate.parse("2026-08-15")));
    }

    /**
     * Seeds an "existing" leave request directly through {@link LeaveRepository#create} -- bypassing
     * every {@link LeaveService} gate, since the point is to construct pre-existing state for the
     * request UNDER TEST to react to, not to prove the seeded request's own submission was valid.
     * Returns the new id so callers that need to mark it as an emergency filing (see {@link
     * #markEmergencyFiling}) can do so.
     */
    private long seedActiveRequest(long employeeId, String leaveTypeCode, String startDate, String endDate, LeaveStatus status) {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        SubmitLeaveRequest seed = new SubmitLeaveRequest(employeeId, leaveTypeCode, start, end, "Seed fixture");
        return leaveRepository.create(employeeId, employeeId, seed, new BigDecimal("1.00"), new BigDecimal("1.00"),
            BigDecimal.ZERO, start.getYear(), status, new BigDecimal("5.00"), new BigDecimal("4.00"),
            null, null, null, null, null, null);
    }

    private void markEmergencyFiling(long leaveRequestId) {
        leaveRepository.markEmergencyFiling(leaveRequestId);
    }
}
