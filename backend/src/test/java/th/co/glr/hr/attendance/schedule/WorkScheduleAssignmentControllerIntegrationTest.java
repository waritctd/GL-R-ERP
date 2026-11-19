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

/** Real-Postgres coverage of WorkScheduleAssignmentController / WorkScheduleAssignmentAdminService: the
 * brand-new write path over hr.work_schedule_assignment. */
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

    // --- overlap: predecessor normalised, successor rejected ------------------------------------

    /** Assigning a new schedule to a scope that already has an open-ended assignment auto-closes the old one the
     * day before the new one starts -- the routine "change the schedule going forward" case V115's own migration
     * comments call out as something a human forgets to do by hand. */
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

    // --- wrong-way-round authz ---------------------------------------------------------------

    @Test
    void aDivisionManagerCannotCreateAnAssignment() {
        long division = insertDivision("WSXD", "ฝ่ายทดสอบ authz manager");
        int scheduleId = scheduleIdByCode("OFFICE_5D");
        assertForbidden(() -> createAssignment(manager(),
            new WorkScheduleAssignmentCreateRequest(ScopeType.DIVISION, division, scheduleId, TODAY, null)));
    }

    // --- helpers -------------------------------------------------------------------------------

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
