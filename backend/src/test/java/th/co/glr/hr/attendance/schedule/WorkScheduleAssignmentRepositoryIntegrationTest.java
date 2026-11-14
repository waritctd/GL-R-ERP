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
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 5);

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

    @Test
    void employeeScopeOverridesTheDepartmentItIsFiledUnder() {
        wire();
        long division = insertDivision("SVX", "โชว์รูม (test)");
        long department = insertDepartment("SHOWROOMX", "โชว์รูม (test dept)", division);
        assignDepartment(department, "OFFICE_5D", LocalDate.of(2024, 10, 1), null);
        long cleaner = insertEmployee("10051X", division, department);
        assignEmployee(cleaner, "OPS_6D", LocalDate.of(2024, 10, 1), null);

        WorkSchedule resolved = resolver.resolve(cleaner, division, department, WITHIN_WINDOW);

        assertThat(resolved.isWorkday(WITHIN_WINDOW))
            .as("the employee-scope OPS_6D override wins over the department's OFFICE_5D")
            .isTrue();
    }

    @Test
    void anAssignmentThatHasNotStartedYetDoesNotApply() {
        wire();
        long division = insertDivision("FUTX", "ฝ่ายทดสอบอนาคต");
        assignDivision(division, "OPS_6D", LocalDate.of(2030, 1, 1), null);
        long employee = insertEmployee("FUTX-1", division, null);

        WorkSchedule resolved = resolver.resolve(employee, division, null, WEDNESDAY);

        // Falls back to the company-wide default (Mon-Fri), not the not-yet-effective OPS_6D row.
        assertThat(resolved.isWorkday(WITHIN_WINDOW)).isFalse();
    }

    @Test
    void anAssignmentThatHasAlreadyEndedDoesNotApply() {
        wire();
        long division = insertDivision("PASTX", "ฝ่ายทดสอบอดีต");
        assignDivision(division, "OPS_6D", LocalDate.of(2020, 1, 1), LocalDate.of(2022, 12, 31));
        long employee = insertEmployee("PASTX-1", division, null);

        WorkSchedule resolved = resolver.resolve(employee, division, null, WEDNESDAY);

        assertThat(resolved.isWorkday(WITHIN_WINDOW)).isFalse();
    }

    /** Overlapping rows for the SAME scope, both open-ended, both already effective: the schema does not forbid
     * this, and it is exactly the state a hand-written "corrected schedule" INSERT leaves behind if the previous
     * row's effective_to is not also closed out. The newer effective_from must win. */
    @Test
    void ofTwoOverlappingAssignmentsForTheSameScopeTheNewerEffectiveFromWinsRegardlessOfInsertOrder() {
        wire();
        long division = insertDivision("OVLX", "ฝ่ายทดสอบซ้อนทับ");
        long employee = insertEmployee("OVLX-1", division, null);

        // Older row (the wrong answer) inserted FIRST -> gets the smaller assignment_id.
        assignDivision(division, "OFFICE_5D", LocalDate.of(2024, 10, 1), null);
        // Newer row (the correct answer) inserted SECOND -> gets the larger assignment_id.
        assignDivision(division, "OPS_6D", LocalDate.of(2025, 6, 1), null);

        WorkSchedule resolved = resolver.resolve(employee, division, null, WITHIN_WINDOW);

        assertThat(resolved.isWorkday(WITHIN_WINDOW))
            .as("OPS_6D (the newer effective_from, inserted last) must win over the older OFFICE_5D row")
            .isTrue();
    }

    /** V117: requires_check_out must survive the join/array_agg round trip this repository does, and the
     * DEPARTMENT-beats-DIVISION precedence already proven above must correctly separate ทีมขาย from Sales Support
     * even though both departments sit under the same ฝ่ายขาย division. */
    @Test
    void teamSalesDepartmentResolvesSalesScheduleWhileSalesSupportDepartmentStaysOnOffice() {
        wire();
        long salesDivision = insertDivision("SAX", "ฝ่ายขาย (test)");
        long salesTeamDept = insertDepartment("SATMX", "ทีมขาย (test)", salesDivision);
        long salesSupportDept = insertDepartment("SALESX", "ฝ่ายสนับสนุนการขาย (test)", salesDivision);

        // Mirrors V117's shape: DIVISION -> SALES_5D, ทีมขาย DEPARTMENT overridden to SALES_5D, Sales Support
        // DEPARTMENT deliberately left on OFFICE_5D.
        assignDivision(salesDivision, "SALES_5D", LocalDate.of(2024, 10, 1), null);
        assignDepartment(salesTeamDept, "SALES_5D", LocalDate.of(2024, 10, 1), null);
        assignDepartment(salesSupportDept, "OFFICE_5D", LocalDate.of(2024, 10, 1), null);

        long teamEmployee = insertEmployee("SATMX-1", salesDivision, salesTeamDept);
        long supportEmployee = insertEmployee("SALESX-1", salesDivision, salesSupportDept);

        WorkSchedule teamSchedule = resolver.resolve(teamEmployee, salesDivision, salesTeamDept, WEDNESDAY);
        WorkSchedule supportSchedule =
            resolver.resolve(supportEmployee, salesDivision, salesSupportDept, WEDNESDAY);

        assertThat(teamSchedule.requiresCheckOut())
            .as("SALES_5D's requires_check_out = FALSE persisted and survived the join/array_agg round trip")
            .isFalse();
        assertThat(supportSchedule.requiresCheckOut())
            .as("Sales Support's DEPARTMENT row still outranks the DIVISION SALES_5D row -> requires_check_out stays TRUE")
            .isTrue();
    }

    // --- helpers --------------------------------------------------------------------------------

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertDepartment(String code, String name, long divisionId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, division_id, is_active)
            VALUES (:code, :name, :divisionId, TRUE) RETURNING department_id
            """, Map.of("code", code, "name", name, "divisionId", divisionId), Long.class);
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

    private void assignDepartment(long departmentId, String scheduleCode, LocalDate from, LocalDate to) {
        assign("DEPARTMENT", departmentId, scheduleCode, from, to);
    }

    private void assignEmployee(long employeeId, String scheduleCode, LocalDate from, LocalDate to) {
        assign("EMPLOYEE", employeeId, scheduleCode, from, to);
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
