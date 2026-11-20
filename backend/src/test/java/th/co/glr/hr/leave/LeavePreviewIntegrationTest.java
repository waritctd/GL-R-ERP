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

/** POST /api/leave/preview: the single claim this phase's whole design rests on, proven against the real
 * repository. Each test calls LeaveService#preview FIRST, then LeaveService#submit on the SAME arguments --
 * preview writes nothing, so calling it first can never change what submit sees. */
class LeavePreviewIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- comfortably past VACATION's 3-day and PERSONAL's 1-day seeded
    // advance notice for every "should be approved" date used below.
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

    // Baseline: no blocking gate on either side

    // Categorical eligibility (LeaveService#eligibilityRuleOutcome)

    // Request-shape gates

    // Document/timing gates

    @Test
    void advanceNoticeNeitherBlocksButSubmitStillWarns() {
        // ADVANCE_NOTICE is WARN_UNPAID_ALL, not BLOCK.
        long employeeId = insertEmployee("PREV-NOTICE-001", LocalDate.parse("2015-01-01"), null);
        // PERSONAL requires 1 day's notice; "today" (2026-07-01, the fixed clock) has none at all, and the request is
        // not declared emergency, so the plain notice gate fires.
        assertPreviewAgreesNeitherBlocksButSubmitWarns(
            employeeId, "PERSONAL", "2026-07-01", "2026-07-01", null, false, "ADVANCE_NOTICE");
    }

    // The one relational gate (§5.3.2 department coverage)

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

    // Nullable dates: only the eligibility gates can run

    // --- helpers ------------------------------------------------------------

    /**
     * Calls {@link LeaveService#preview} (FULL depth) then {@link LeaveService#submit} on the
     * IDENTICAL request, and asserts preview's blocking code equals submit's persisted {@code
     * system_note_code} -- {@code null} on both sides when {@code expectedCode} is {@code null}.
     *
     * <p>Also asserts {@code preview.ruleWarnings()} is EMPTY in every
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
            // Leave requires approval: a rule-passing submit lands SUBMITTED, not
            // APPROVED -- this helper's subject is preview/submit agreement on the BLOCKING code,
            // which is unaffected (see LeaveService#submit's own comment).
            assertThat(submitted.status()).isEqualTo("SUBMITTED");
        } else {
            assertThat(previewCode).isEqualTo(expectedCode);
            assertThat(submitted.status()).isEqualTo("AUTO_REJECTED");
        }
    }

    /**
     * Companion to {@link #assertPreviewAgreesWithSubmit}:
     * for a code whose {@link LeaveRuleCode#enforcement()} is WARN_UNPAID_ALL/WARN_UNPAID_EXCESS,
     * BOTH sides report "not blocking" ({@code preview.blocking() == null} and {@code
     * submit.systemNoteCode() == null}) -- so comparing against {@code expectedCode} is not
     * meaningful. The gate's actual effect surfaces elsewhere: {@link LeaveService#submit} lands
     * SUBMITTED, but with {@code expectedCode} present among {@code ruleWarnings} -- that is what
     * this helper additionally proves, so the fixture is shown to have genuinely exercised the
     * gate the test claims.
     *
     * <p>Also asserts {@code preview.ruleWarnings()} carries
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

    /** Seeds an "existing" leave request directly through LeaveRepository#create -- bypassing every LeaveService
     * gate, since the point is to construct pre-existing state for the request UNDER TEST to react to, not to
     * prove the seeded request's own submission was valid. */
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
