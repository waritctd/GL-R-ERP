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

/** Verifies schedule-aware attendance recalculation and resolver cache expiry against PostgreSQL. */
class AttendanceDailyScheduleAwareRecalculationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    // 2026-08-05 is a Wednesday (a SALES_5D/OFFICE_5D/OPS_6D workday for all three).
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 5);

    private AttendanceDailyRepository repository;
    private AttendanceDailyCalculator calculator;

    private void wire() {
        repository = new AttendanceDailyRepository(jdbc);
        calculator = new AttendanceDailyCalculator();
    }

    /** The regression: before the fix, this assertion failed because toDto flagged MISSING_CHECK_OUT
     * unconditionally whenever checkOut was null, ignoring schedule.requiresCheckOut(). The write path already
     * knew better (V117); the read path did not agree with it. */
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

}
