package th.co.glr.hr.attendance.daily;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Exercises the daily roll-up SQL against a real PostgreSQL database. These cases exist because Mockito cannot
 * reach them. The override guard lives in an ON CONFLICT ... DO UPDATE ... This repository has been bitten by
 * exactly that before. */
class AttendanceDailyRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 15);

    private static final WorkSchedule SCHEDULE = new WorkSchedule(
        BANGKOK, LocalTime.of(8, 30), LocalTime.of(17, 30), 5,
        Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
               DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    );

    private AttendanceDailyRepository repository;
    private AttendanceDailyCalculator calculator;

    @BeforeEach
    void wire() {
        repository = new AttendanceDailyRepository(jdbc);
        calculator = new AttendanceDailyCalculator();
    }

    private long insertEmployee(String code, String badge, Long divisionId, LocalDate hireDate) {
        // HashMap, not Map.of: badge and divisionId are legitimately null in several cases here and Map.of rejects
        // nulls.
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("code", code);
        params.put("badge", badge);
        params.put("divisionId", divisionId);
        params.put("hireDate", hireDate);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active)
            VALUES (:code, :badge, 'ทดสอบ', :code, :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private void insertPunch(String badge, OffsetDateTime at) {
        jdbc.update("""
            INSERT INTO hr.attendance_punch (site_code, badge_code, punch_time, work_date)
            VALUES ('SHOWROOM', :badge, :at, :workDate)
            """,
            Map.of("badge", badge, "at", at,
                   "workDate", at.atZoneSameInstant(BANGKOK).toLocalDate()));
    }

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(BANGKOK).toOffsetDateTime();
    }

    private int rollUp(long employeeId, LocalDate date) {
        List<PunchRecord> punches = repository.findPunchesFor(employeeId, date);
        if (punches.isEmpty()) {
            return 0;
        }
        return repository.upsertAll(List.of(calculator.calculate(
            employeeId, date, punches, SCHEDULE,
            repository.findApprovedOvertimeMinutes(employeeId, date), false)));
    }

    /** The guard that matters most. It lives in SQL precisely so no caller can forget it — which also means only a
     * real database can prove it works. */
    @Test
    void upsertRefusesToOverwriteAManuallyOverriddenRow() {
        long employeeId = insertEmployee("E100", "E100", null, LocalDate.of(2020, 1, 1));
        insertPunch("E100", at(WEDNESDAY, 8, 40));
        insertPunch("E100", at(WEDNESDAY, 17, 35));
        assertThat(rollUp(employeeId, WEDNESDAY)).isEqualTo(1);
        assertThat(lateMinutes(employeeId)).isEqualTo(10);

        // HR corrects the row by hand and marks it overridden.
        jdbc.update("""
            UPDATE hr.attendance_daily
               SET late_minutes = 0, is_manual_override = TRUE, notes = 'approved late arrival'
             WHERE employee_id = :employeeId
            """, Map.of("employeeId", employeeId));

        // A later recalculation must leave that correction alone, and report that it wrote nothing.
        assertThat(rollUp(employeeId, WEDNESDAY)).isZero();
        assertThat(lateMinutes(employeeId)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT notes FROM hr.attendance_daily WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), String.class))
            .isEqualTo("approved late arrival");
    }

    /** A manager asking for someone in another division gets nothing rather than that person's data: both
     * predicates AND, so the out-of-division id simply matches no rows. */
    @Test
    void divisionScopeAndEmployeeFilterCombineSoAManagerCannotReachOutside() {
        long sales = insertDivision("SL2", "ฝ่ายขาย 2");
        long factory = insertDivision("FC2", "ฝ่ายโรงงาน 2");
        insertEmployee("E110", "E110", sales, LocalDate.of(2020, 1, 1));
        long outside = insertEmployee("E111", "E111", factory, LocalDate.of(2020, 1, 1));

        List<AttendanceDailyRow> rows = repository.findRange(new AttendanceDailyFilter(
            outside, sales, WEDNESDAY, WEDNESDAY));

        assertThat(rows).isEmpty();
    }

    private int lateMinutes(long employeeId) {
        return jdbc.queryForObject(
            "SELECT late_minutes FROM hr.attendance_daily WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
    }

}
