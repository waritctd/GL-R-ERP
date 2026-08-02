package th.co.glr.hr.attendance.daily;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.attendance.schedule.TieredWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.WorkScheduleAssignmentRepository;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres coverage of two things this branch is specifically about, wired through the real
 * {@code AttendanceDailyService}/{@code AttendanceDailyRepository}/{@code TieredWorkScheduleResolver}
 * chain rather than mocks:
 *
 * <ol>
 *   <li>recalculation genuinely picking up a schedule change (the point of
 *       {@code recalculateAllHistory}/{@code recalculateRange} existing at all — see this branch's
 *       task description), and that the cache added alongside it is what makes that possible without
 *       a restart;
 *   <li>a regression this investigation found: {@code AttendanceDailyService#toDto} (the read path)
 *       did not consult {@code schedule.requiresCheckOut()} the way {@code
 *       AttendanceDailyCalculator#statusOf} (the write path) already did, so a V117 sales employee's
 *       compliant lone check-in re-flagged as MISSING_CHECK_OUT on every read even though the stored
 *       row was computed correctly.
 * </ol>
 */
class AttendanceDailyScheduleAwareRecalculationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    // 2026-08-05 is a Wednesday (a SALES_5D/OFFICE_5D/OPS_6D workday for all three).
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 5);
    // 2026-08-08 is a Saturday: a non-workday under the company-wide Mon-Fri default, but a
    // workday under OPS_6D.
    private static final LocalDate SATURDAY = LocalDate.of(2026, 8, 8);

    private AttendanceDailyRepository repository;
    private AttendanceDailyCalculator calculator;

    private void wire() {
        repository = new AttendanceDailyRepository(jdbc);
        calculator = new AttendanceDailyCalculator();
    }

    /**
     * The regression: before the fix, this assertion failed because {@code toDto} flagged
     * MISSING_CHECK_OUT unconditionally whenever {@code checkOut} was null, ignoring
     * {@code schedule.requiresCheckOut()}. The write path already knew better (V117); the read path
     * did not agree with it. See {@code AttendanceDailyService#toDto}'s javadoc for the fix.
     */
    @Test
    void aSalesEmployeesLoneCheckInAgreesBetweenTheWriteAndReadPaths() {
        wire();
        long salesDept = insertDepartment("SALES-RW", "ฝ่ายขาย (test read/write agreement)");
        assignDepartment(salesDept, "SALES_5D", LocalDate.of(2024, 10, 1), null);
        long employeeId = insertEmployee("RW1", "RW1", salesDept, LocalDate.of(2020, 1, 1));
        insertPunch("RW1", at(WEDNESDAY, 8, 20)); // on-time check-in, no check-out at all

        AttendanceDailyService service = serviceWith(new AppProperties());

        int written = service.recalculateRange(WEDNESDAY, WEDNESDAY, null);
        assertThat(written).isEqualTo(1);

        List<AttendanceDailyDto> rows = service.list(
            new AttendanceDailyFilter(employeeId, null, WEDNESDAY, WEDNESDAY));
        assertThat(rows).hasSize(1);
        AttendanceDailyDto dto = rows.get(0);

        assertThat(dto.status())
            .as("a SALES_5D employee's lone check-in is complete and compliant (V117) — not MISSING_CHECK_OUT")
            .isEqualTo(AttendanceDayStatus.PRESENT);
        assertThat(dto.flags())
            .as("the read path must agree with what the write path already knew about requiresCheckOut")
            .doesNotContain(AttendanceDayFlag.MISSING_CHECK_OUT);
    }

    /**
     * A sales employee who DOES scan out is unaffected by the fix — the ordinary two-punch path
     * applies unchanged on both read and write. Asserted on the SAME schedule as the test above so a
     * resolver bug that always returned {@code requiresCheckOut = false} (rather than reading it from
     * the assignment) could not make both tests pass for the wrong reason.
     */
    @Test
    void aSalesEmployeeWhoDoesScanOutIsUnaffected() {
        wire();
        long salesDept = insertDepartment("SALES-RW2", "ฝ่ายขาย (test read/write agreement 2)");
        assignDepartment(salesDept, "SALES_5D", LocalDate.of(2024, 10, 1), null);
        long employeeId = insertEmployee("RW2", "RW2", salesDept, LocalDate.of(2020, 1, 1));
        insertPunch("RW2", at(WEDNESDAY, 8, 20));
        insertPunch("RW2", at(WEDNESDAY, 17, 25));

        AttendanceDailyService service = serviceWith(new AppProperties());
        service.recalculateRange(WEDNESDAY, WEDNESDAY, null);

        AttendanceDailyDto dto = service.list(
            new AttendanceDailyFilter(employeeId, null, WEDNESDAY, WEDNESDAY)).get(0);

        assertThat(dto.status()).isEqualTo(AttendanceDayStatus.PRESENT);
        assertThat(dto.checkOut()).isNotNull();
    }

    /**
     * Recalculation correctness at the numeric-output level: the SAME employee-day's stored
     * {@code late_minutes} genuinely differs before and after a real schedule assignment change, and
     * the TTL cache added to {@code TieredWorkScheduleResolver} is what lets the second
     * {@code recalculateRange} call see that change without recreating the resolver bean (i.e.
     * without an application restart) — exactly the scenario Problem 1 and Problem 2 in this branch's
     * task both describe.
     */
    @Test
    void recalculationReflectsARealScheduleChangeOnceTheCacheHasExpired() throws InterruptedException {
        wire();
        long dept = insertDepartment("OPS-RECALC", "ฝ่ายทดสอบ (test recalc)");
        long employeeId = insertEmployee("RECALC1", "RECALC1", dept, LocalDate.of(2020, 1, 1));
        insertPunch("RECALC1", at(SATURDAY, 8, 50));
        insertPunch("RECALC1", at(SATURDAY, 17, 20));

        AppProperties properties = new AppProperties();
        properties.getAttendance().getSchedule().setAssignmentCacheTtlMs(20);
        AttendanceDailyService service = serviceWith(properties);

        int firstRun = service.recalculateRange(SATURDAY, SATURDAY, null);
        assertThat(firstRun).isEqualTo(1);
        assertThat(lateMinutes(employeeId))
            .as("before OPS_6D applies, Saturday falls back to the Mon-Fri default — not a workday, "
                + "so late/early are left at their zero default rather than measured")
            .isZero();

        // The department is moved onto OPS_6D (Mon-Sat) — a real assignment change, the same shape
        // V121 made for real departments.
        assignDepartment(dept, "OPS_6D", LocalDate.of(2024, 10, 1), null);
        Thread.sleep(200); // comfortably outlive the 20ms TTL

        int secondRun = service.recalculateRange(SATURDAY, SATURDAY, null);
        assertThat(secondRun).isEqualTo(1);
        assertThat(lateMinutes(employeeId))
            .as("after OPS_6D applies (post-V121 grace = 15), Saturday is a workday and the 08:50 "
                + "check-in is 20 minutes late — genuinely different stored output, not merely a "
                + "different read-time label")
            .isEqualTo(20);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private AttendanceDailyService serviceWith(AppProperties properties) {
        WorkScheduleAssignmentRepository scheduleRepository =
            new WorkScheduleAssignmentRepository(jdbc, properties);
        TieredWorkScheduleResolver resolver = new TieredWorkScheduleResolver(
            scheduleRepository, new CompanyWideWorkScheduleResolver(properties), properties);
        DbHolidayCalendar holidayCalendar = new DbHolidayCalendar(jdbc);
        return new AttendanceDailyService(repository, calculator, resolver, holidayCalendar);
    }

    private long insertDepartment(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING department_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, String badge, Long departmentId, LocalDate hireDate) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("code", code);
        params.put("badge", badge);
        params.put("departmentId", departmentId);
        params.put("hireDate", hireDate);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     department_id, hire_date, is_active)
            VALUES (:code, :badge, 'ทดสอบ', :code, :departmentId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    private void insertPunch(String badge, OffsetDateTime punchAt) {
        jdbc.update("""
            INSERT INTO hr.attendance_punch (site_code, badge_code, punch_time, work_date)
            VALUES ('SHOWROOM', :badge, :at, :workDate)
            """,
            Map.of("badge", badge, "at", punchAt,
                   "workDate", punchAt.atZoneSameInstant(BANGKOK).toLocalDate()));
    }

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(BANGKOK).toOffsetDateTime();
    }

    private long scheduleIdByCode(String code) {
        return jdbc.queryForObject(
            "SELECT work_schedule_id FROM hr.work_schedule WHERE code = :code",
            Map.of("code", code), Long.class);
    }

    private void assignDepartment(long departmentId, String scheduleCode, LocalDate from, LocalDate to) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("scopeId", departmentId);
        params.put("workScheduleId", scheduleIdByCode(scheduleCode));
        params.put("from", from);
        params.put("to", to);
        jdbc.update("""
            INSERT INTO hr.work_schedule_assignment
                (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
            VALUES ('DEPARTMENT', :scopeId, :workScheduleId, :from, :to)
            """, params);
    }

    private int lateMinutes(long employeeId) {
        return jdbc.queryForObject(
            "SELECT late_minutes FROM hr.attendance_daily WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
    }
}
