package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import th.co.glr.hr.audit.AuditLogRepository;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres coverage of {@link WorkScheduleAssignmentController} /
 * {@link WorkScheduleAssignmentAdminService}: the brand-new write path over {@code
 * hr.work_schedule_assignment}. CLAUDE.md "Permission changes must ship evidence" — every handler
 * gets a wrong-way-round authz case, plus the back-dating / overlap / cache-invalidation behaviours
 * called out in the branch's task description.
 *
 * <p>"Today" is pinned via a fixed {@link Clock} (2026-08-10, a Monday) so back-dating assertions
 * do not race the real wall clock — same seam shape as {@code BotHolidayFetchServiceTest}.
 */
class WorkScheduleAssignmentControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);
    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"), ZoneId.of("Asia/Bangkok"));

    private WorkScheduleAssignmentController controller;
    private WorkScheduleController scheduleController;
    private WorkScheduleAssignmentRepository repository;
    private TieredWorkScheduleResolver resolver;

    @BeforeEach
    void wireRealCollaborators() {
        AppProperties properties = new AppProperties();
        repository = new WorkScheduleAssignmentRepository(jdbc, properties);
        resolver = new TieredWorkScheduleResolver(
            repository, new CompanyWideWorkScheduleResolver(properties), properties);
        AuditService auditService = new AuditService(
            new AuditLogRepository(jdbc), new ObjectMapper().registerModule(new JavaTimeModule()));
        WorkScheduleAssignmentAdminService service =
            new WorkScheduleAssignmentAdminService(repository, resolver, auditService, FIXED_CLOCK);
        controller = new WorkScheduleAssignmentController(service, new SessionContext());
        scheduleController = new WorkScheduleController(service, new SessionContext());
    }

    // --- happy path --------------------------------------------------------------------------

    @Test
    void hrCanListTheScheduleCatalogue() {
        List<WorkScheduleSummaryDto> schedules = listSchedules(hr());

        assertThat(schedules).extracting(WorkScheduleSummaryDto::code)
            .contains("OFFICE_5D", "OPS_6D", "WEEKEND_DUTY");
    }

    @Test
    void hrCanAssignAScheduleAndSeeItInTheList() {
        long division = insertDivision("WSX1", "ฝ่ายทดสอบ 1");
        int scheduleId = scheduleIdByCode("OPS_6D");

        WorkScheduleAssignmentDto created = createAssignment(
            hr(), new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, null));

        assertThat(created.scopeType()).isEqualTo(ScopeType.DIVISION);
        assertThat(created.scopeId()).isEqualTo(division);
        assertThat(created.scheduleCode()).isEqualTo("OPS_6D");
        assertThat(created.effectiveTo()).isNull();

        List<WorkScheduleAssignmentDto> assignments = listAssignments(ceo());
        assertThat(assignments).extracting(WorkScheduleAssignmentDto::assignmentId).contains(created.assignmentId());
    }

    @Test
    void ceoCanEndAnAssignment() {
        long division = insertDivision("WSX2", "ฝ่ายทดสอบ 2");
        int scheduleId = scheduleIdByCode("OFFICE_5D");
        WorkScheduleAssignmentDto created = createAssignment(
            ceo(), new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, null));

        WorkScheduleAssignmentDto ended =
            endAssignment(ceo(), created.assignmentId(), new WorkScheduleAssignmentEndRequest(TODAY.plusDays(5)));

        assertThat(ended.effectiveTo()).isEqualTo(TODAY.plusDays(5));
    }

    // --- invalidate() wiring -------------------------------------------------------------------

    /**
     * Proves {@code TieredWorkScheduleResolver.invalidate()} is actually called, not just present:
     * warms the resolver's cache with an unrelated read FIRST (so a real cached copy exists), then
     * creates an assignment through the admin write path, then resolves again in the SAME test
     * without touching the clock or TTL. If invalidate() were not wired, this would still see the
     * pre-write (stale) result for up to the 5-minute TTL.
     */
    @Test
    void anAssignmentWriteIsVisibleToTheNextResolveWithoutWaitingForTheTtl() {
        long division = insertDivision("WSX3", "ฝ่ายทดสอบแคช");
        long employee = insertEmployee("WSX3-1", division, null);

        // Warms/creates the resolver's internal cache with "no assignment for this division yet".
        WorkSchedule before = resolver.resolve(employee, division, null, TODAY);

        LocalDate saturday = LocalDate.of(2026, 8, 15);
        assertThat(before.isWorkday(saturday))
            .as("before assigning OPS_6D, the company-wide default (Mon-Fri) treats Saturday as a non-workday")
            .isFalse();

        int scheduleId = scheduleIdByCode("OPS_6D");
        createAssignment(hr(), new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, null));

        WorkSchedule after = resolver.resolve(employee, division, null, TODAY);
        assertThat(after.isWorkday(saturday))
            .as("the write must be visible on the very next resolve() call -- proves invalidate() is wired, "
                + "not just present (see TieredWorkScheduleResolver#invalidate javadoc)")
            .isTrue();
    }

    // --- back-dating ---------------------------------------------------------------------------

    @Test
    void creatingAnAssignmentWithAPastEffectiveFromIsRejected() {
        long division = insertDivision("WSX4", "ฝ่ายทดสอบย้อนหลัง");
        int scheduleId = scheduleIdByCode("OPS_6D");

        assertThatThrownBy(() -> createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY.minusDays(1), null)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void creatingAnAssignmentEffectiveTodayIsAllowed() {
        long division = insertDivision("WSX5", "ฝ่ายทดสอบวันนี้");
        int scheduleId = scheduleIdByCode("OPS_6D");

        WorkScheduleAssignmentDto created = createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, null));

        assertThat(created.effectiveFrom()).isEqualTo(TODAY);
    }

    @Test
    void endingAnAssignmentWithAPastEffectiveToIsRejected() {
        long division = insertDivision("WSX6", "ฝ่ายทดสอบสิ้นสุดย้อนหลัง");
        int scheduleId = scheduleIdByCode("OPS_6D");
        WorkScheduleAssignmentDto created = createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, null));

        assertThatThrownBy(() -> endAssignment(
            hr(), created.assignmentId(), new WorkScheduleAssignmentEndRequest(TODAY.minusDays(1))))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- overlap: predecessor normalised, successor rejected ------------------------------------

    /**
     * Assigning a new schedule to a scope that already has an open-ended assignment auto-closes the
     * old one the day before the new one starts -- the routine "change the schedule going forward"
     * case V115's own migration comments call out as something a human forgets to do by hand.
     */
    @Test
    void assigningANewScheduleAutoClosesTheOpenEndedPredecessorForTheSameScope() {
        long division = insertDivision("WSX7", "ฝ่ายทดสอบปกติซ้อนทับ");
        int office = scheduleIdByCode("OFFICE_5D");
        int ops6d = scheduleIdByCode("OPS_6D");
        WorkScheduleAssignmentDto predecessor = createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, office, TODAY, null));

        LocalDate newFrom = TODAY.plusDays(10);
        createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, ops6d, newFrom, null));

        WorkScheduleAssignmentDto closedPredecessor = listAssignments(hr()).stream()
            .filter(a -> a.assignmentId() == predecessor.assignmentId())
            .findFirst().orElseThrow();
        assertThat(closedPredecessor.effectiveTo())
            .as("the predecessor must be auto-closed the day before the new assignment starts")
            .isEqualTo(newFrom.minusDays(1));
    }

    /**
     * A genuinely conflicting FUTURE assignment (starts on/after the new one, still overlapping) is
     * NOT silently truncated -- that would surprise whoever planned it. The create is rejected
     * instead, asked-wrong-way-round: can a second, conflicting future assignment be created at all.
     */
    @Test
    void creatingAnAssignmentThatConflictsWithAnAlreadyPlannedFutureAssignmentIsRejected() {
        long division = insertDivision("WSX8", "ฝ่ายทดสอบอนาคตซ้อนทับ");
        int office = scheduleIdByCode("OFFICE_5D");
        int ops6d = scheduleIdByCode("OPS_6D");
        LocalDate futureFrom = TODAY.plusDays(30);
        createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, ops6d, futureFrom, null));

        // A second create, starting before the already-planned future one but still open-ended,
        // would overlap it -- must be rejected, not silently truncate the existing future plan.
        assertThatThrownBy(() -> createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, office, TODAY, null)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    // --- input validation ------------------------------------------------------------------------

    @Test
    void createRejectsAScopeIdThatDoesNotExist() {
        int scheduleId = scheduleIdByCode("OFFICE_5D");
        assertThatThrownBy(() -> createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, 9_999_999L, scheduleId, TODAY, null)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createRejectsAWorkScheduleIdThatDoesNotExist() {
        long division = insertDivision("WSX9", "ฝ่ายทดสอบไม่มีตาราง");
        assertThatThrownBy(() -> createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, 999_999, TODAY, null)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createRejectsAnEffectiveToBeforeEffectiveFrom() {
        long division = insertDivision("WSXA", "ฝ่ายทดสอบช่วงเวลาผิด");
        int scheduleId = scheduleIdByCode("OFFICE_5D");
        assertThatThrownBy(() -> createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, TODAY.minusDays(1))))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void endOnAMissingAssignmentIs404() {
        assertThatThrownBy(() -> endAssignment(hr(), 9_999_999, new WorkScheduleAssignmentEndRequest(TODAY)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- wrong-way-round authz ---------------------------------------------------------------

    @Test
    void anEmployeeCannotListTheScheduleCatalogue() {
        assertForbidden(() -> listSchedules(employee()));
    }

    @Test
    void aDivisionManagerCannotListTheScheduleCatalogue() {
        assertForbidden(() -> listSchedules(manager()));
    }

    @Test
    void anEmployeeCannotListAssignments() {
        assertForbidden(() -> listAssignments(employee()));
    }

    @Test
    void aSalesCallerCannotListAssignments() {
        assertForbidden(() -> listAssignments(sales()));
    }

    @Test
    void aDivisionManagerCannotListAssignments() {
        assertForbidden(() -> listAssignments(manager()));
    }

    @Test
    void anEmployeeCannotCreateAnAssignment() {
        long division = insertDivision("WSXB", "ฝ่ายทดสอบ authz สร้าง");
        int scheduleId = scheduleIdByCode("OFFICE_5D");
        assertForbidden(() -> createAssignment(employee(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, null)));
    }

    @Test
    void aSalesCallerCannotCreateAnAssignment() {
        long division = insertDivision("WSXC", "ฝ่ายทดสอบ authz sales");
        int scheduleId = scheduleIdByCode("OFFICE_5D");
        assertForbidden(() -> createAssignment(sales(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, null)));
    }

    @Test
    void aDivisionManagerCannotCreateAnAssignment() {
        long division = insertDivision("WSXD", "ฝ่ายทดสอบ authz manager");
        int scheduleId = scheduleIdByCode("OFFICE_5D");
        assertForbidden(() -> createAssignment(manager(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, null)));
    }

    @Test
    void anEmployeeCannotEndAnAssignment() {
        long division = insertDivision("WSXE", "ฝ่ายทดสอบ authz จบ");
        int scheduleId = scheduleIdByCode("OFFICE_5D");
        WorkScheduleAssignmentDto created = createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, null));
        assertForbidden(() -> endAssignment(employee(), created.assignmentId(), null));
    }

    @Test
    void aDivisionManagerCannotEndAnAssignment() {
        long division = insertDivision("WSXF", "ฝ่ายทดสอบ authz จบ manager");
        int scheduleId = scheduleIdByCode("OFFICE_5D");
        WorkScheduleAssignmentDto created = createAssignment(hr(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, null));
        assertForbidden(() -> endAssignment(manager(), created.assignmentId(), null));
    }

    // --- helpers -------------------------------------------------------------------------------

    private List<WorkScheduleSummaryDto> listSchedules(UserPrincipal user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return scheduleController.list(session).schedules();
    }

    private List<WorkScheduleAssignmentDto> listAssignments(UserPrincipal user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.list(session).assignments();
    }

    private WorkScheduleAssignmentDto createAssignment(UserPrincipal user, WorkScheduleAssignmentCreateRequest request) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.create(request, session);
    }

    private WorkScheduleAssignmentDto endAssignment(UserPrincipal user, int assignmentId, WorkScheduleAssignmentEndRequest request) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.end(assignmentId, request, session);
    }

    private static void assertForbidden(ThrowingCallable callable) {
        assertThatThrownBy(callable::call)
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private interface ThrowingCallable {
        void call();
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId, Long departmentId) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("departmentId", departmentId);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th,
                                     division_id, department_id, hire_date, is_active)
            VALUES (:code, 'ทดสอบ', :code, :divisionId, :departmentId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    private int scheduleIdByCode(String code) {
        return jdbc.queryForObject(
            "SELECT work_schedule_id FROM hr.work_schedule WHERE code = :code",
            Map.of("code", code), Integer.class);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(1L, "ceo@glr.co.th", "ceo", "ceo", null, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(2L, "hr@glr.co.th", "hr", "hr", null, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal employee() {
        return new UserPrincipal(3L, "emp@glr.co.th", "emp", "employee", null, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal sales() {
        return new UserPrincipal(4L, "sales@glr.co.th", "sales", "sales", null, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal manager() {
        return new UserPrincipal(5L, "mgr@glr.co.th", "mgr", "employee", null, true, LocalDate.now(), false, null, true);
    }
}
