package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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

    /**
     * Partial-day span (V166, 2026-09-10): {@link LeavePreviewRequest}'s startTime/endTime widening
     * -- previewed BEFORE submitting, the owner's own motivating multi-day case (half-day afternoon
     * + full next day = 1.50) must show the IDENTICAL {@code totalDays}/{@code paidDays} a real
     * submit would persist, the same "preview never lies" guarantee this whole class exists to
     * prove, now extended to a timed span rather than only a whole-day one.
     */
    @Test
    void timedMultiDaySpanPreviewAgreesWithSubmitOnTheOwnersOwnCase() {
        long employeeId = insertEmployee("PREV-SPAN-001", LocalDate.parse("2015-01-01"), null);

        LeavePreviewDto preview = leaveService.preview(new LeavePreviewRequest(
            "VACATION", LocalDate.parse("2026-08-11"), LocalDate.parse("2026-08-12"), employeeId,
            null, false, false, LeavePreviewDepth.FULL, null,
            LocalTime.of(13, 0), LocalTime.of(17, 30)),
            employee(employeeId));

        assertThat(preview.blocking()).isNull();
        assertThat(preview.totalDays()).isEqualByComparingTo("1.50");
        assertThat(preview.paidDays()).isEqualByComparingTo("1.50");
        assertThat(preview.unpaidDays()).isEqualByComparingTo("0.00");

        LeaveRequestDto submitted = leaveService.submit(new SubmitLeaveRequest(
            employeeId, "VACATION", LocalDate.parse("2026-08-11"), LocalDate.parse("2026-08-12"),
            "half day then full day", LocalTime.of(13, 0), LocalTime.of(17, 30),
            null, null, null, null, null, null, null),
            employee(employeeId));

        assertThat(submitted.totalDays())
            .as("previewed and submitted totalDays must never drift apart for the identical request")
            .isEqualByComparingTo(preview.totalDays());
        assertThat(submitted.paidDays()).isEqualByComparingTo(preview.paidDays());
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
    void probationNotPassedNeitherBlocksButSubmitStillWarns() {
        // Hired 5 days before "now" -- comfortably inside the 119-day default probation window (see
        // SpecialMoneyPolicyEvaluator#DEFAULT_PROBATION_DAYS), so PERSONAL's probation gate fires.
        // V164 (owner-approved change, 2026-09-09): PROBATION_NOT_PASSED is WARN_UNPAID_ALL, not
        // BLOCK -- renamed/updated from "...AgreesOnBothSides" (preview.blocking()/submit.
        // systemNoteCode() still agree, both null; the substance moved to submit's ruleWarnings).
        long employeeId = insertEmployee("PREV-PROB-001", LocalDate.parse("2026-06-26"), null);
        assertPreviewAgreesNeitherBlocksButSubmitWarns(
            employeeId, "PERSONAL", "2026-07-13", "2026-07-13", null, false, "PROBATION_NOT_PASSED");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Request-shape gates
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void weddingMaxDaysNeitherBlocksButSubmitStillWarns() {
        // V164 (owner-approved change, 2026-09-09): WEDDING_MAX_DAYS is WARN_UNPAID_EXCESS, not
        // BLOCK -- renamed/updated from "...AgreesOnBothSides".
        long employeeId = insertEmployee("PREV-WED-001", LocalDate.parse("2015-01-01"), null);
        // 8-day span, over the 3-day wedding cap; well past PERSONAL's 1-day advance notice.
        assertPreviewAgreesNeitherBlocksButSubmitWarns(
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
    void sickNoCertificateToleranceExhaustedNeitherBlocksButSubmitStillWarns() {
        // V164 (owner-approved change, 2026-09-09): SICK_NO_CERT_TOLERANCE_EXHAUSTED is
        // WARN_UNPAID_ALL, not BLOCK -- renamed/updated from "...AgreesOnBothSides".
        long employeeId = insertEmployee("PREV-SICK-001", LocalDate.parse("2015-01-01"), null);
        // 3 certificate-less SICK occasions already used this month (the seeded tolerance) -- the 4th
        // still fires the gate on both sides, just no longer as a rejection.
        seedActiveRequest(employeeId, "SICK", "2026-07-02", "2026-07-02", LeaveStatus.APPROVED);
        seedActiveRequest(employeeId, "SICK", "2026-07-03", "2026-07-03", LeaveStatus.APPROVED);
        seedActiveRequest(employeeId, "SICK", "2026-07-06", "2026-07-06", LeaveStatus.APPROVED);
        assertPreviewAgreesNeitherBlocksButSubmitWarns(
            employeeId, "SICK", "2026-07-13", "2026-07-13", null, false, "SICK_NO_CERT_TOLERANCE_EXHAUSTED");
    }

    @Test
    void advanceNoticeNeitherBlocksButSubmitStillWarns() {
        // V164 (owner-approved change, 2026-09-09): ADVANCE_NOTICE is WARN_UNPAID_ALL, not BLOCK --
        // renamed/updated from "...AgreesOnBothSides".
        long employeeId = insertEmployee("PREV-NOTICE-001", LocalDate.parse("2015-01-01"), null);
        // PERSONAL requires 1 day's notice; "today" (2026-07-01, the fixed clock) has none at all,
        // and the request is not declared emergency, so the plain notice gate fires.
        assertPreviewAgreesNeitherBlocksButSubmitWarns(
            employeeId, "PERSONAL", "2026-07-01", "2026-07-01", null, false, "ADVANCE_NOTICE");
    }

    @Test
    void emergencyToleranceExhaustedNeitherBlocksButSubmitStillWarns() {
        // V164 (owner-approved change, 2026-09-09): EMERGENCY_TOLERANCE_EXHAUSTED is WARN_UNPAID_ALL,
        // not BLOCK -- renamed/updated from "...AgreesOnBothSides".
        long employeeId = insertEmployee("PREV-EMERG-001", LocalDate.parse("2015-01-01"), null);
        // 3 prior PERSONAL requests this month already used the emergency exception (emergency_filing
        // = TRUE) -- the 4th same-day, no-notice, emergency-declared request still fires the
        // tolerance-exhausted gate on both sides, not the plain notice one.
        markEmergencyFiling(seedActiveRequest(employeeId, "PERSONAL", "2026-07-02", "2026-07-02", LeaveStatus.APPROVED));
        markEmergencyFiling(seedActiveRequest(employeeId, "PERSONAL", "2026-07-03", "2026-07-03", LeaveStatus.APPROVED));
        markEmergencyFiling(seedActiveRequest(employeeId, "PERSONAL", "2026-07-06", "2026-07-06", LeaveStatus.APPROVED));
        assertPreviewAgreesNeitherBlocksButSubmitWarns(
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
            null, false, false, LeavePreviewDepth.QUICK, null), employee(requester));

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

    /**
     * V164 preview follow-up (2026-09-09): combines an accumulating WARN gate (§5 advance notice,
     * PERSONAL's real seeded 1-day requirement, unmet by filing the SAME day) with the one relational
     * BLOCK gate (§5.3.2 department coverage) on the IDENTICAL request, to prove two things at once
     * that a narrower fixture could each only assert in isolation:
     *
     * <ol>
     *   <li><b>Wrong-way-round:</b> under FULL depth, the BLOCK wins outright and does NOT leak the
     *       ADVANCE_NOTICE warning that accumulated before it fired -- {@link
     *       LeaveService.AutoRejectResult}'s own dominance rule discards it (see
     *       {@link LeaveService#departmentCoverageRuleOutcome}'s BLOCK-return call site's comment).
     *   <li><b>QUICK depth does not corrupt WARN reporting:</b> the SAME request, previewed at QUICK
     *       depth, skips {@code #departmentCoverageRuleOutcome} entirely (the documented cost, proven
     *       in isolation by {@link #quickDepthSkipsDepartmentCoverageAndSaysSoExplicitly} above) --
     *       and with the BLOCK never reached, the ADVANCE_NOTICE warning that fired earlier in the
     *       SAME gate chain correctly reaches {@code ruleWarnings} instead. Department coverage is a
     *       BLOCK-only {@link LeaveRuleCode} (never contributes to {@code warnings}), so skipping it
     *       changes nothing about what warnings the earlier gates already found -- this is the
     *       concrete proof of that claim, not an assumption.
     * </ol>
     */
    @Test
    void aBlockingGateDiscardsAnAccumulatedWarningButQuickDepthStillSurfacesItWhenTheBlockIsSkipped() {
        long departmentId = insertDepartment("PREV-WCOV-DEPT-001");
        long requester = insertEmployee("PREV-WCOV-EMP-001", LocalDate.parse("2015-01-01"), departmentId);
        long colleagueA = insertEmployee("PREV-WCOV-EMP-002", LocalDate.parse("2015-01-01"), departmentId);
        long colleagueB = insertEmployee("PREV-WCOV-EMP-003", LocalDate.parse("2015-01-01"), departmentId);
        // Same-day PERSONAL leave for both colleagues -- the requester's own same-day PERSONAL request
        // below would leave nobody else in the department at work on 2026-07-01.
        seedActiveRequest(colleagueA, "PERSONAL", "2026-07-01", "2026-07-01", LeaveStatus.SUBMITTED);
        seedActiveRequest(colleagueB, "PERSONAL", "2026-07-01", "2026-07-01", LeaveStatus.APPROVED);

        // FULL depth: department coverage BLOCKs, and must not leak the ADVANCE_NOTICE warning that
        // accumulated before it (today, 2026-07-01, is inside PERSONAL's 1-day notice requirement).
        LeavePreviewDto fullPreview = leaveService.preview(new LeavePreviewRequest(
            "PERSONAL", LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-01"), requester,
            null, false, false, LeavePreviewDepth.FULL, null), employee(requester));

        assertThat(fullPreview.blocking()).isNotNull();
        assertThat(fullPreview.blocking().code().name()).isEqualTo("DEPARTMENT_COVERAGE");
        assertThat(fullPreview.coverageEvaluated()).isTrue();
        assertThat(fullPreview.ruleWarnings())
            .as("a BLOCK must not leak the ADVANCE_NOTICE warning that accumulated before it")
            .isEmpty();

        // QUICK depth, IDENTICAL request: coverage is skipped, so nothing blocks -- and the
        // ADVANCE_NOTICE warning (unaffected by the skip) correctly surfaces instead.
        LeavePreviewDto quickPreview = leaveService.preview(new LeavePreviewRequest(
            "PERSONAL", LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-01"), requester,
            null, false, false, LeavePreviewDepth.QUICK, null), employee(requester));

        assertThat(quickPreview.blocking()).as("QUICK depth must not surface the coverage rejection").isNull();
        assertThat(quickPreview.coverageEvaluated()).as("must explicitly say the gate did not run").isFalse();
        assertThat(quickPreview.ruleWarnings())
            .as("skipping department coverage must not suppress the WARN gate that fired above it")
            .extracting(LeaveRuleWarningDto::code)
            .containsExactly("ADVANCE_NOTICE");
        assertThat(quickPreview.unpaidByRuleDays())
            .as("the whole 1-day request is unpaid by rule (WARN_UNPAID_ALL)")
            .isEqualByComparingTo(BigDecimal.ONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Nullable dates: only the eligibility gates can run
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void previewWithNoDatesYetOnlyEvaluatesEligibilityGatesAndReportsNoBlockForAWarnOnlyEmployee() {
        // V164 (owner-approved change, 2026-09-09): this employee's ONLY eligibility-gate hit is
        // PROBATION_NOT_PASSED, which is WARN_UNPAID_ALL now, not BLOCK -- so this dateless preview's
        // `blocking()` is correctly NULL (nothing here blocks the eventual submission any more),
        // consistent with #submit's own post-V164 behaviour for the identical employee/type. See
        // LeaveService#eligibilityRuleOutcome's Javadoc for why this dateless path cannot surface the
        // WARN itself (no chosen dates yet to compute "how many days would be unpaid") -- this test
        // was renamed from "...EligibilityGates" to make that narrowing explicit, not silently drop
        // the original assertion.
        //
        // V164 preview follow-up (2026-09-09): now ALSO the decision-(a) proof for the "0 วัน" bug
        // this later branch closes -- ruleWarnings must be EMPTY (not a warning carrying a fabricated
        // "0 วัน" sentence) and unpaidByRuleDays must be null (unknown, not zero), even though
        // PROBATION_NOT_PASSED genuinely fired against LocalDate.now(clock) -- see LeavePreviewDto's/
        // LeaveService#preview's Javadoc for why.
        long employeeId = insertEmployee("PREV-NODATES-001", LocalDate.parse("2026-06-26"), null);

        LeavePreviewDto preview = leaveService.preview(
            new LeavePreviewRequest("PERSONAL", null, null, employeeId, null, false, false, LeavePreviewDepth.FULL, null),
            employee(employeeId));

        assertThat(preview.datesEvaluated()).isFalse();
        assertThat(preview.coverageEvaluated()).isFalse();
        assertThat(preview.totalDays()).isNull();
        assertThat(preview.paidDays()).isNull();
        assertThat(preview.unpaidDays()).isNull();
        assertThat(preview.quotaYearSplits()).isEmpty();
        assertThat(preview.blocking()).isNull();
        assertThat(preview.ruleWarnings()).isEmpty();
        assertThat(preview.unpaidByRuleDays()).isNull();
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Calls {@link LeaveService#preview} (FULL depth) then {@link LeaveService#submit} on the
     * IDENTICAL request, and asserts preview's blocking code equals submit's persisted {@code
     * system_note_code} -- {@code null} on both sides when {@code expectedCode} is {@code null}.
     *
     * <p>V164 follow-up (2026-09-09): also asserts {@code preview.ruleWarnings()} is EMPTY in every
     * case this helper covers -- when {@code expectedCode == null} because no gate fired at all (the
     * "clean request" case), and when {@code expectedCode != null} because every code this helper is
     * used for is a BLOCK code, and a BLOCK always reports an empty {@code ruleWarnings} regardless of
     * what accumulated before it fired (see {@link LeaveService.AutoRejectResult}'s dominance rule and
     * {@link LeavePreviewDto}'s Javadoc). This is the wrong-way-round proof for every BLOCK-code
     * fixture already in this class, not a special case built for just one of them.
     */
    private void assertPreviewAgreesWithSubmit(
            long employeeId, String leaveTypeCode, String startDate, String endDate,
            String purposeCode, boolean requestedAsEmergency, String expectedCode) {
        LeavePreviewDto preview = leaveService.preview(new LeavePreviewRequest(
            leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate), employeeId,
            purposeCode, requestedAsEmergency, false, LeavePreviewDepth.FULL, null), employee(employeeId));
        String previewCode = preview.blocking() == null ? null : preview.blocking().code().name();

        LeaveRequestDto submitted = leaveService.submit(new SubmitLeaveRequest(
            employeeId, leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate),
            "Integration test leave", null, null, null, null, null, null, null, purposeCode, requestedAsEmergency),
            employee(employeeId));

        assertThat(previewCode)
            .as("preview's blocking code must equal submit's system_note_code for the identical request")
            .isEqualTo(submitted.systemNoteCode());
        assertThat(preview.ruleWarnings())
            .as("a BLOCK code (or no gate at all) must never leak a ruleWarnings entry")
            .isEmpty();
        if (expectedCode == null) {
            assertThat(preview.blocking()).isNull();
            assertThat(preview.unpaidByRuleDays())
                .as("a clean request must report ZERO unpaid-by-rule days, not null -- dates ARE known here")
                .isEqualByComparingTo(BigDecimal.ZERO);
            // Leave requires approval (2026-08-05): a rule-passing submit now lands SUBMITTED, not
            // APPROVED -- this helper's subject is preview/submit agreement on the BLOCKING code,
            // which is unaffected by this change (see LeaveService#submit's own comment).
            assertThat(submitted.status()).isEqualTo("SUBMITTED");
        } else {
            assertThat(previewCode).isEqualTo(expectedCode);
            assertThat(submitted.status()).isEqualTo("AUTO_REJECTED");
        }
    }

    /**
     * V164 (owner-approved change, 2026-09-09) companion to {@link #assertPreviewAgreesWithSubmit}:
     * for a code whose {@link LeaveRuleCode#enforcement()} is WARN_UNPAID_ALL/WARN_UNPAID_EXCESS,
     * BOTH sides now report "not blocking" ({@code preview.blocking() == null} and {@code
     * submit.systemNoteCode() == null}) -- the original helper's core "agrees" claim still holds
     * (asserted here identically), it is just no longer meaningful to compare it against {@code
     * expectedCode}. What moved is where the gate's actual effect surfaces: {@link
     * LeaveService#submit} still lands SUBMITTED, but with {@code expectedCode} present among {@code
     * ruleWarnings} -- that is what this helper additionally proves, restoring the same "the fixture
     * genuinely exercised the gate this test claims" coverage the old {@code expectedCode} equality
     * used to provide.
     *
     * <p>V164 preview follow-up (2026-09-09): now ALSO asserts {@code preview.ruleWarnings()} carries
     * {@code expectedWarnCode} and {@code preview.unpaidByRuleDays()} equals {@code
     * submitted.unpaidByRuleDays()} EXACTLY -- the whole point of this branch is that a caller can
     * trust preview's warning/day-count BEFORE submitting, so this helper proves preview and submit
     * agree on both, not merely that submit alone carries the warning.
     */
    private void assertPreviewAgreesNeitherBlocksButSubmitWarns(
            long employeeId, String leaveTypeCode, String startDate, String endDate,
            String purposeCode, boolean requestedAsEmergency, String expectedWarnCode) {
        LeavePreviewDto preview = leaveService.preview(new LeavePreviewRequest(
            leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate), employeeId,
            purposeCode, requestedAsEmergency, false, LeavePreviewDepth.FULL, null), employee(employeeId));

        LeaveRequestDto submitted = leaveService.submit(new SubmitLeaveRequest(
            employeeId, leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate),
            "Integration test leave", null, null, null, null, null, null, null, purposeCode, requestedAsEmergency),
            employee(employeeId));

        assertThat(preview.blocking())
            .as("a WARN_UNPAID_* code must not block preview either")
            .isNull();
        assertThat(submitted.systemNoteCode())
            .as("preview's blocking code (null) must equal submit's system_note_code for the identical request")
            .isNull();
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.ruleWarnings()).extracting(LeaveRuleWarningDto::code).contains(expectedWarnCode);
        assertThat(preview.ruleWarnings())
            .as("preview must show the SAME warning submit will persist, before the employee commits")
            .extracting(LeaveRuleWarningDto::code)
            .contains(expectedWarnCode);
        assertThat(preview.unpaidByRuleDays())
            .as("preview's unpaid-by-rule day count must agree with what submit actually persists")
            .isEqualByComparingTo(submitted.unpaidByRuleDays());
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
