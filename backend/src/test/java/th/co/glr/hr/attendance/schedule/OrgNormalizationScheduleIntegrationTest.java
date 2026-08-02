package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres coverage of V121's Part B (แม่บ้าน department creation + moving employee 10051 into
 * it) and Part C (แม่บ้าน onto the six-day OPS_6D schedule, removing V115's now-redundant
 * EMPLOYEE-scope override for 10051, and raising the company-wide grace period from 5 to 15
 * minutes). Sales Support 1/2 were considered for OPS_6D during review and the owner REVERSED that
 * ruling -- they stay on OFFICE_5D -- so this class deliberately asserts they are UNCHANGED by the
 * migration rather than asserting they moved.
 *
 * <p>Same technique as {@code LeaveApproverOrgNormalizationIntegrationTest} and
 * {@code PayrollComponentTaxTreatmentBackfillIntegrationTest}: V121's own SQL text is read verbatim
 * from the classpath and replayed against fixture rows carrying the real production codes ('SALES',
 * 'SALES2', '10051', ...) that a freshly-migrated golden-template database does not have. This
 * proves the ACTUAL shipped migration statements, not a hand-typed re-implementation of them.
 */
class OrgNormalizationScheduleIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final LocalDate SATURDAY = LocalDate.of(2026, 8, 1);

    private String migrationSql;
    private WorkScheduleAssignmentRepository repository;
    private TieredWorkScheduleResolver resolver;

    @BeforeEach
    void wireRealCollaboratorsAndReadMigration() throws IOException {
        AppProperties properties = new AppProperties();
        repository = new WorkScheduleAssignmentRepository(jdbc, properties);
        resolver = new TieredWorkScheduleResolver(
            repository, new CompanyWideWorkScheduleResolver(properties), properties);
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V121__org_normalize_approvers_and_schedules.sql")) {
            assertThat(in).as("V121 migration file must be on the test classpath").isNotNull();
            migrationSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void jamnianIsMovedIntoTheNewMaebanDepartmentAndStillResolvesOps6dViaDepartmentAloneAfterTheRedundantOverrideIsRemoved() {
        long showroomDivision = insertDivision("SRX", "โชว์รูม (test division)");
        long showroomDept = insertDepartment("SRX", "โชว์รูม (test)", showroomDivision);
        long jamnian = insertEmployee("10051", showroomDivision, showroomDept);
        // ราม (10009) must exist so V121's Part A UPDATE for the new HK department -- and its
        // "nobody left without an approver" post-condition guard -- can actually assign her one;
        // without this row the department UPDATE would no-op (nothing to join against) and the
        // guard would (correctly) reject the whole replay. This test is about Part B/C's schedule
        // wiring, not Part A, so ราม is fixture setup, not the point under test.
        insertEmployee("10009", null, null);
        // V115's shape: an EMPLOYEE-scope OPS_6D override, same effective_from V121 targets for
        // removal. A fresh test DB never had this row (V115's own seed is a no-op here, same reason
        // documented in WorkScheduleAssignmentRepositoryIntegrationTest), so it is seeded by hand to
        // prove V121's DELETE genuinely removes a REAL row, not just no-ops against nothing.
        assignEmployee(jamnian, "OPS_6D", LocalDate.of(2024, 10, 1), null);

        executeStatements(migrationSql);

        Long maebanDeptId = jdbc.queryForObject(
            "SELECT department_id FROM hr.department WHERE source_code = 'HK'", Map.of(), Long.class);
        assertThat(maebanDeptId).as("แม่บ้าน department was created").isNotNull();

        Long jamnianDeptId = jdbc.queryForObject(
            "SELECT department_id FROM hr.employee WHERE employee_code = '10051'", Map.of(), Long.class);
        assertThat(jamnianDeptId).as("10051 moved into แม่บ้าน").isEqualTo(maebanDeptId);

        int remainingEmployeeScopeRows = jdbc.queryForObject("""
            SELECT count(*) FROM hr.work_schedule_assignment a
             WHERE a.scope_type = 'EMPLOYEE' AND a.scope_id = :employeeId
            """, Map.of("employeeId", jamnian), Integer.class);
        assertThat(remainingEmployeeScopeRows)
            .as("V115's redundant EMPLOYEE-scope OPS_6D override was removed")
            .isZero();

        WorkSchedule resolved = resolver.resolve(jamnian, showroomDivision, maebanDeptId, SATURDAY);
        assertThat(resolved.isWorkday(SATURDAY))
            .as("10051 still resolves OPS_6D on Saturday via the DEPARTMENT tier alone")
            .isTrue();
    }

    @Test
    void salesSupportStaysOnOfficeFiveDayAndAMaebanEmployeesSameSaturdayIsAWorkday() {
        // The owner REVERSED the ruling that would have moved Sales Support to OPS_6D -- this test
        // exists to pin that reversal, not the original plan. Both sides on ONE fixture (CLAUDE.md's
        // testing rule): a one-sided assertion ("Sales Support stays OFFICE_5D") would also pass if
        // V121 accidentally left EVERY department alone, including the new แม่บ้าน department that
        // genuinely must become OPS_6D. Checking both on the SAME Saturday rules that out.
        long salesSupport1 = insertDepartment("SALES", "ฝ่ายสนับสนุนการขาย 1", null);
        // จินตนา (10049) and ราม (10009) must exist so Part A's UPDATEs -- and the no-orphan-approver
        // guard -- can actually assign the employees below an approver; this test is about Part C's
        // schedule wiring, not Part A, so both are fixture setup, not the point under test.
        insertEmployee("10049", null, null);
        insertEmployee("10009", null, null);
        // V115's shape: DEPARTMENT-scope OFFICE_5D, effective_from 2024-10-01. A fresh test DB never
        // had this row (same reason as elsewhere in this class), so it is seeded by hand -- this also
        // proves V121 genuinely leaves a REAL pre-existing SALES row alone, not an absent one.
        assignDepartment(salesSupport1, "OFFICE_5D", LocalDate.of(2024, 10, 1), null);
        long salesSupportEmployee = insertEmployee("EMP-SALES-1", null, salesSupport1);
        long maebanEmployeeSeed = insertEmployee("10051", null, null);

        executeStatements(migrationSql);

        // Exactly the ONE row seeded above -- V121 must not delete it, and must not add a second row
        // for this scope either (which would also change what the resolver returns).
        int salesSupportScheduleRows = jdbc.queryForObject("""
            SELECT count(*) FROM hr.work_schedule_assignment a
             WHERE a.scope_type = 'DEPARTMENT' AND a.scope_id = :deptId
            """, Map.of("deptId", salesSupport1), Integer.class);
        assertThat(salesSupportScheduleRows)
            .as("Sales Support 1's OFFICE_5D row is untouched -- exactly one live row, unchanged")
            .isEqualTo(1);

        Long maebanDeptId = jdbc.queryForObject(
            "SELECT department_id FROM hr.employee WHERE employee_code = '10051'", Map.of(), Long.class);
        WorkSchedule salesSupportSchedule =
            resolver.resolve(salesSupportEmployee, null, salesSupport1, SATURDAY);
        WorkSchedule maebanSchedule =
            resolver.resolve(maebanEmployeeSeed, null, maebanDeptId, SATURDAY);

        assertThat(salesSupportSchedule.isWorkday(SATURDAY))
            .as("Sales Support 1 stays OFFICE_5D (owner-reversed) -- Saturday is still a non-workday")
            .isFalse();
        assertThat(maebanSchedule.isWorkday(SATURDAY))
            .as("แม่บ้าน is genuinely OPS_6D -- the SAME Saturday is a workday for her")
            .isTrue();
    }

    @Test
    void everySeededScheduleGetsTheFifteenMinuteGracePeriod() {
        // Owner ruling: the late-arrival grace period rises from 5 to 15 minutes, company-wide,
        // across every seeded schedule -- not just the ones this migration otherwise touches.
        executeStatements(migrationSql);

        Map<String, Object> graceByCode = new java.util.HashMap<>();
        for (String code : new String[] {"OFFICE_5D", "OPS_6D", "SALES_5D", "WEEKEND_DUTY"}) {
            Integer grace = jdbc.queryForObject(
                "SELECT grace_minutes FROM hr.work_schedule WHERE code = :code",
                Map.of("code", code), Integer.class);
            graceByCode.put(code, grace);
        }

        assertThat(graceByCode)
            .as("every seeded schedule's grace_minutes is 15 after V121")
            .containsEntry("OFFICE_5D", 15)
            .containsEntry("OPS_6D", 15)
            .containsEntry("SALES_5D", 15)
            .containsEntry("WEEKEND_DUTY", 15);
    }

    @Test
    void theNewMaebanDepartmentItselfResolvesOps6dForAnyEmployeeFiledUnderIt() {
        long maebanEmployeeSeed = insertEmployee("10051", null, null);
        // ราม (10009): see the identical note in the test above -- required for Part A's UPDATE and
        // the no-orphan-approver guard, not the point under test here.
        insertEmployee("10009", null, null);
        assignEmployee(maebanEmployeeSeed, "OPS_6D", LocalDate.of(2024, 10, 1), null);

        executeStatements(migrationSql);

        Long maebanDeptId = jdbc.queryForObject(
            "SELECT department_id FROM hr.department WHERE source_code = 'HK'", Map.of(), Long.class);
        assertThat(maebanDeptId).isNotNull();

        // A SECOND, unrelated employee filed under แม่บ้าน after the migration also resolves OPS_6D
        // -- proving the department itself carries the schedule, not something specific to 10051.
        long anotherHousekeepingEmployee = insertEmployee("HK-TEST-2", null, maebanDeptId);
        WorkSchedule resolved = resolver.resolve(anotherHousekeepingEmployee, null, maebanDeptId, SATURDAY);
        assertThat(resolved.isWorkday(SATURDAY))
            .as("any employee filed under the new แม่บ้าน department resolves OPS_6D")
            .isTrue();
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Strips {@code --} line comments first, then splits into statements on {@code ;} -- mirrors
     * PayrollComponentTaxTreatmentBackfillIntegrationTest#executeStatements, EXTENDED to be
     * dollar-quote aware: V121 contains a {@code DO $$ ... $$} block whose body contains its own
     * {@code ;} terminators that must NOT split the block apart. A semicolon is only a statement
     * boundary when it appears OUTSIDE a {@code $$ ... $$} region.
     */
    private void executeStatements(String sql) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (line.strip().startsWith("--")) {
                continue;
            }
            withoutComments.append(line).append('\n');
        }
        String body = withoutComments.toString();
        java.util.List<String> statements = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDollarQuote = false;
        for (int i = 0; i < body.length(); i++) {
            if (body.startsWith("$$", i)) {
                inDollarQuote = !inDollarQuote;
                current.append("$$");
                i++;
                continue;
            }
            char c = body.charAt(i);
            if (c == ';' && !inDollarQuote) {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        for (String statement : statements) {
            String trimmed = statement.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            jdbc.getJdbcOperations().execute(trimmed);
        }
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertDepartment(String code, String name, Long divisionId) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("code", code);
        params.put("name", name);
        params.put("divisionId", divisionId);
        return jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, division_id, is_active)
            VALUES (:code, :name, :divisionId, TRUE) RETURNING department_id
            """, params, Long.class);
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
