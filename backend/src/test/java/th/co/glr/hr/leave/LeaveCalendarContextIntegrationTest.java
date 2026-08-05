package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpSession;
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.attendance.schedule.HolidayCalendar;
import th.co.glr.hr.attendance.schedule.HolidayRepository;
import th.co.glr.hr.attendance.schedule.TieredWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.WorkScheduleAssignmentRepository;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres coverage of {@code GET /api/leave/calendar-context} (2026-08-03) -- a brand-new
 * authorization surface: it widens who may read holiday/schedule-derived data from hr/ceo-only
 * ({@code HolidayController}/{@code WorkScheduleController}) to every authenticated employee, for
 * their OWN data. See CLAUDE.md "Permission changes must ship evidence" -- Mockito cannot prove
 * this; the whole point under test is whether the SQL joins (employee -> division/department,
 * {@code hr.work_schedule_assignment}'s EMPLOYEE&gt;DEPARTMENT&gt;DIVISION precedence,
 * {@code hr.holiday}) resolve to the CALLING session's own employee, never another one.
 *
 * <p>Every scoping test here is deliberately wrong-way-round (CLAUDE.md's phrase): the endpoint
 * takes no {@code employeeId} parameter at all, so there is no "wrong id" a caller could pass --
 * the only way to prove correct scoping is to seed two employees with genuinely DIFFERENT
 * schedule assignments and show that calling the identical endpoint under two different sessions,
 * in the identical date range, returns each employee's OWN schedule and never the other's. See
 * {@link #employeeANeverReceivesEmployeeBsScheduleOrViceVersa}.
 */
class LeaveCalendarContextIntegrationTest extends AbstractPostgresIntegrationTest {

    // Mon 2026-08-03 .. Sun 2026-08-09: one full week including both a Saturday (workday under
    // OPS_6D, not under OFFICE_5D) and a Sunday (non-workday under either).
    private static final LocalDate RANGE_FROM = LocalDate.of(2026, 8, 3);
    private static final LocalDate RANGE_TO = LocalDate.of(2026, 8, 9);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 8, 8);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 8, 9);
    private static final LocalDate HOLIDAY_DATE = LocalDate.of(2026, 8, 5); // Wednesday, inside the range

    private LeaveController controller;
    private long plainDepartmentId;

    @BeforeEach
    void wireRealCollaborators() {
        AppProperties appProperties = new AppProperties();
        WorkScheduleResolver scheduleResolver = new TieredWorkScheduleResolver(
            new WorkScheduleAssignmentRepository(jdbc, appProperties),
            new CompanyWideWorkScheduleResolver(appProperties),
            appProperties);
        HolidayCalendar holidayCalendar = new DbHolidayCalendar(jdbc);
        LeaveRepository leaveRepository = new LeaveRepository(jdbc, scheduleResolver, holidayCalendar);
        HolidayRepository holidayRepository = new HolidayRepository(jdbc);
        LeaveCalendarContextService service = new LeaveCalendarContextService(leaveRepository, holidayRepository);
        controller = new LeaveController(
            /* leaveService */ null, new SessionContext(), /* leavePolicyDocuments */ null, service,
            /* attachmentBlobs */ null);

        plainDepartmentId = insertDepartment("CAL-PLAIN");
    }

    // --- Own data: holidays + resolved schedule + non-working dates -------------------------

    @Test
    void employeeSeesOwnHolidaysAndScheduleDerivedNonWorkingDates() {
        long employeeId = insertEmployee("CAL-A", plainDepartmentId);
        assignEmployeeSchedule(employeeId, "OPS_6D");
        insertHoliday(HOLIDAY_DATE, "วันหยุดทดสอบ");

        LeaveCalendarContextDto context = calendarContext(employee(employeeId), RANGE_FROM, RANGE_TO);

        assertThat(context.from()).isEqualTo(RANGE_FROM);
        assertThat(context.to()).isEqualTo(RANGE_TO);
        assertThat(context.holidays())
            .as("the seeded holiday inside the range must appear, same data HolidayController exposes")
            .extracting(LeaveCalendarHolidayDto::holidayDate)
            .containsExactly(HOLIDAY_DATE);
        assertThat(context.holidays().get(0).nameTh()).isEqualTo("วันหยุดทดสอบ");

        // OPS_6D: Mon-Sat (ISO 1-6) — see V115's seed.
        assertThat(context.schedule().workdays()).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(context.schedule().requiresCheckOut()).isTrue();

        // nonWorkingDates = LeaveRepository#workingDayPredicate's own answer, not a reimplementation:
        // the holiday counts even though it falls on an OPS_6D workday (Wednesday), Sunday counts
        // (no schedule works Sundays), and Saturday must NOT count for a six-day schedule.
        assertThat(context.nonWorkingDates()).contains(HOLIDAY_DATE, SUNDAY);
        assertThat(context.nonWorkingDates())
            .as("OPS_6D employee: Saturday is a working day, must not be flagged non-working")
            .doesNotContain(SATURDAY);
    }

    // --- The critical wrong-way-round case: A can never receive B's schedule, or vice versa ---

    @Test
    void employeeANeverReceivesEmployeeBsScheduleOrViceVersa() {
        long employeeA = insertEmployee("CAL-A-SCOPE", plainDepartmentId);
        long employeeB = insertEmployee("CAL-B-SCOPE", plainDepartmentId);
        assignEmployeeSchedule(employeeA, "OPS_6D");    // A works Saturdays
        assignEmployeeSchedule(employeeB, "OFFICE_5D"); // B does not

        // Same endpoint, same identical date range, session is the ONLY thing that differs.
        LeaveCalendarContextDto asA = calendarContext(employee(employeeA), RANGE_FROM, RANGE_TO);
        LeaveCalendarContextDto asB = calendarContext(employee(employeeB), RANGE_FROM, RANGE_TO);

        assertThat(asA.schedule().workdays())
            .as("A's own OPS_6D assignment")
            .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(asB.schedule().workdays())
            .as("B's own OFFICE_5D assignment — must differ from A's, proving no cross-talk")
            .containsExactly(1, 2, 3, 4, 5);

        assertThat(asA.nonWorkingDates())
            .as("A (six-day) works Saturday")
            .doesNotContain(SATURDAY);
        assertThat(asB.nonWorkingDates())
            .as("B (five-day) does not work Saturday — the opposite of A's answer for the same date")
            .contains(SATURDAY);

        // Belt-and-suspenders: A's whole response must not equal B's, and neither is some third,
        // fixed default either — each is genuinely derived from the calling session's own employee id.
        assertThat(asA).isNotEqualTo(asB);
    }

    // --- HR is not privileged here: HR gets its OWN data, nothing wider ----------------------

    @Test
    void hrCallerSeesOnlyItsOwnScheduleNotAnElevatedOrDifferentView() {
        long hrEmployeeId = insertEmployee("CAL-HR", plainDepartmentId);
        assignEmployeeSchedule(hrEmployeeId, "OFFICE_5D");

        UserPrincipal hrUser = new UserPrincipal(
            hrEmployeeId, "hr@glr.co.th", "HR ทดสอบ", "hr", hrEmployeeId, true, LocalDate.now(), false, null, false);
        LeaveCalendarContextDto context = calendarContext(hrUser, RANGE_FROM, RANGE_TO);

        assertThat(context.schedule().workdays())
            .as("HR's own OFFICE_5D assignment, exactly the same code path an ordinary employee gets")
            .containsExactly(1, 2, 3, 4, 5);
        assertThat(context.nonWorkingDates()).contains(SATURDAY, SUNDAY);
    }

    // --- Validation mirrors HolidayController's admin GET (same bad-input shape, same behavior) --

    @Test
    void toBeforeFromIsRejectedWith400SameAsHolidayControllersAdminGet() {
        long employeeId = insertEmployee("CAL-VALID", plainDepartmentId);

        assertThatThrownBy(() -> calendarContext(employee(employeeId), RANGE_TO, RANGE_FROM))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException apiException = (ApiException) exception;
                assertThat(apiException.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(apiException.getMessage()).isEqualTo("วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มต้น");
            });
    }

    @Test
    void anAccountNotLinkedToAnEmployeeIsRejectedBeforeAnyResolution() {
        UserPrincipal unlinked = new UserPrincipal(
            999L, "nolink@glr.co.th", "ไม่ผูกพนักงาน", "hr", null, true, LocalDate.now(), false, null, false);

        assertThatThrownBy(() -> calendarContext(unlinked, RANGE_FROM, RANGE_TO))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException apiException = (ApiException) exception;
                assertThat(apiException.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(apiException.getMessage()).isEqualTo("บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล");
            });
    }

    @Test
    void anAnonymousCallerIsRejectedWith401() {
        MockHttpSession anonymousSession = new MockHttpSession();
        assertThatThrownBy(() -> controller.calendarContext(RANGE_FROM, RANGE_TO, anonymousSession))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers --------------------------------------------------------------------------

    private LeaveCalendarContextDto calendarContext(UserPrincipal user, LocalDate from, LocalDate to) {
        return controller.calendarContext(from, to, sessionFor(user)).calendarContext();
    }

    private MockHttpSession sessionFor(UserPrincipal user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return session;
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "พนักงานทดสอบ", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private long insertDepartment(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, is_active)
            VALUES (:code, :code, TRUE)
            RETURNING department_id
            """, new MapSqlParameterSource("code", code), Long.class);
    }

    private long insertEmployee(String code, long departmentId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, department_id, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', :departmentId, TRUE, DATE '2015-01-01')
            RETURNING employee_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("departmentId", departmentId),
            Long.class);
    }

    private void assignEmployeeSchedule(long employeeId, String scheduleCode) {
        long workScheduleId = jdbc.queryForObject(
            "SELECT work_schedule_id FROM hr.work_schedule WHERE code = :code",
            new MapSqlParameterSource("code", scheduleCode), Long.class);
        jdbc.update("""
            INSERT INTO hr.work_schedule_assignment
                (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
            VALUES ('EMPLOYEE', :employeeId, :workScheduleId, DATE '2024-10-01', NULL)
            """, new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("workScheduleId", workScheduleId));
    }

    private void insertHoliday(LocalDate date, String nameTh) {
        jdbc.update("""
            INSERT INTO hr.holiday (holiday_date, name_th, source)
            VALUES (:date, :nameTh, 'COMPANY')
            """, new MapSqlParameterSource().addValue("date", date).addValue("nameTh", nameTh));
    }
}
