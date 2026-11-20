package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Exercises WorkScheduleAssignmentRepository and TieredWorkScheduleResolver against a real PostgreSQL
 * database. Only the assignment rows are inserted per test, since V115's seeded assignments key off production
 * source_codes (HR, WH, ...) that a fresh test database does not have. */
class WorkScheduleAssignmentRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final LocalDate WITHIN_WINDOW = LocalDate.of(2026, 8, 1); // a Saturday

    private WorkScheduleAssignmentRepository repository;
    private TieredWorkScheduleResolver resolver;

    private void wire() {
        AppProperties properties = new AppProperties();
        repository = new WorkScheduleAssignmentRepository(jdbc, properties);
        resolver = new TieredWorkScheduleResolver(
            repository, new CompanyWideWorkScheduleResolver(properties), properties);
    }

    /** The case CLAUDE.md's testing rules insist on: assert BOTH sides on ONE fixture. */
    @Test
    void aWarehouseEmployeesSaturdayIsAWorkdayAndAnHrEmployeesSameSaturdayIsNot() {
        wire();
        long warehouseDivision = insertDivision("WHX", "ฝ่ายคลังสินค้า (test)");
        long hrDivision = insertDivision("HRX", "ฝ่ายบุคคล (test)");
        assignDivision(warehouseDivision, "OPS_6D", LocalDate.of(2024, 10, 1), null);
        assignDivision(hrDivision, "OFFICE_5D", LocalDate.of(2024, 10, 1), null);
        long warehouseEmployee = insertEmployee("WHX-1", warehouseDivision, null);
        long hrEmployee = insertEmployee("HRX-1", hrDivision, null);

        WorkSchedule warehouseSchedule =
            resolver.resolve(warehouseEmployee, warehouseDivision, null, WITHIN_WINDOW);
        WorkSchedule hrSchedule = resolver.resolve(hrEmployee, hrDivision, null, WITHIN_WINDOW);

        assertThat(warehouseSchedule.isWorkday(WITHIN_WINDOW))
            .as("OPS_6D (Mon-Sat) treats Saturday as a workday")
            .isTrue();
        assertThat(hrSchedule.isWorkday(WITHIN_WINDOW))
            .as("OFFICE_5D (Mon-Fri) treats the same Saturday as a non-workday")
            .isFalse();
    }

    // --- helpers --------------------------------------------------------------------------------

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

    private long scheduleIdByCode(String code) {
        return jdbc.queryForObject(
            "SELECT work_schedule_id FROM hr.work_schedule WHERE code = :code",
            Map.of("code", code), Long.class);
    }

    private void assignDivision(long divisionId, String scheduleCode, LocalDate from, LocalDate to) {
        assign("DIVISION", divisionId, scheduleCode, from, to);
    }

    private void assign(String scopeType, long scopeId, String scheduleCode, LocalDate from, LocalDate to) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("scopeType", scopeType);
        params.put("scopeId", scopeId);
        params.put("workScheduleId", scheduleIdByCode(scheduleCode));
        params.put("from", from);
        params.put("to", to);
        jdbc.update("""
            INSERT INTO hr.work_schedule_assignment
                (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
            VALUES (:scopeType, :scopeId, :workScheduleId, :from, :to)
            """, params);
    }
}
