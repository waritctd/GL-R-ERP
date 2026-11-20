package th.co.glr.hr.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import th.co.glr.hr.attendance.daily.AttendanceDailyCalculator;
import th.co.glr.hr.attendance.daily.AttendanceDailyDto;
import th.co.glr.hr.attendance.daily.AttendanceDailyFilter;
import th.co.glr.hr.attendance.daily.AttendanceDailyRepository;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.daily.AttendanceDayStatus;
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Confirms the CEO/HR "mark present" (WFH / stand-up) write path against the real service, the real SQL and
 * the real controller role gate — CLAUDE.md "Permission changes must ship evidence", because this endpoint is
 * both a new write path and a new authorization rule. */
class AttendanceMarkPresentIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 15);

    private AttendanceController controller;
    private AttendanceDailyService dailyService;

    private long division;
    private long alice;
    private long bob;

    @BeforeEach
    void wireRealCollaborators() {
        AppProperties properties = new AppProperties();
        AttendanceDailyRepository dailyRepository = new AttendanceDailyRepository(jdbc);
        dailyService = new AttendanceDailyService(
            dailyRepository,
            new AttendanceDailyCalculator(),
            new CompanyWideWorkScheduleResolver(properties),
            new DbHolidayCalendar(jdbc));
        AttendanceService service = new AttendanceService(
            new AttendanceRepository(jdbc, new ObjectMapper()),
            new AttendanceDatParser(),
            properties,
            dailyService);
        // Real AttendanceMonthlySummaryService -- unrelated to markPresent, but this file's own philosophy extends
        // naturally to the controller's other collaborator now that it has one; both LeaveRepository and
        // AttendanceMonthlySummaryExporter are cheap to construct for real.
        AttendanceMonthlySummaryService monthlySummaryService = new AttendanceMonthlySummaryService(
            service, dailyService, new LeaveRepository(jdbc), new AttendanceMonthlySummaryExporter());
        controller = new AttendanceController(service, monthlySummaryService, new SessionContext());

        division = insertDivision("SLS", "ฝ่ายขาย");
        alice = insertEmployee("A001", division);
        bob = insertEmployee("B001", division);
    }

    // --- (a) CEO and HR can mark present ------------------------------------

    @Test
    void hrCanMarkPresentWithNoPunches() {
        AttendanceMarkPresentResponse response = markPresent(hr(), WEDNESDAY, List.of(bob), null);

        assertThat(response.markedCount()).isEqualTo(1);
        AttendanceDailyDto day = dayFor(bob, WEDNESDAY);
        assertThat(day.status()).isEqualTo(AttendanceDayStatus.WFH);
        assertThat(day.manualOverride()).isTrue();
        assertThat(day.checkIn()).isNull();
        assertThat(day.checkOut()).isNull();
    }

    // --- (b) everyone else is forbidden --------------------------------------

    @Test
    void anEmployeeCannotMarkAnyoneEvenThemselvesPresent() {
        assertForbidden(() -> markPresent(employee(alice), WEDNESDAY, List.of(alice), null));
    }

    // --- helpers --------------------------------------------------------------

    private AttendanceMarkPresentResponse markPresent(
            UserPrincipal user, LocalDate date, List<Long> employeeIds, String notes) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.markPresent(new AttendanceMarkPresentRequest(date, employeeIds, notes), session);
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

    private AttendanceDailyDto dayFor(long employeeId, LocalDate date) {
        return dailyService.list(new AttendanceDailyFilter(employeeId, null, date, date)).stream()
            .filter(day -> day.employeeId() == employeeId)
            .findFirst()
            .orElseThrow();
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(1L, "ceo@glr.co.th", "ceo", "ceo", alice, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(2L, "hr@glr.co.th", "hr", "hr", alice, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(3L, "emp@glr.co.th", "emp", "employee", employeeId, true,
            LocalDate.now(), false, division, false);
    }

    private UserPrincipal sales(long employeeId) {
        return new UserPrincipal(4L, "sales@glr.co.th", "sales", "sales", employeeId, true,
            LocalDate.now(), false, division, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

}
