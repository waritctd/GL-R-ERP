package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Real-Postgres coverage of V160 — ฝ่ายขาย moving to a six-day week (Saturday becomes a scheduled working day)
 * effective 2026-09-01. SELECT in the shipped migration no-ops at migrate time and this class exercises the
 * ACTUAL shipped statements rather than a hand-typed re-implementation. */
class SalesDivisionSaturdayWorkdayIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The date the owner request was actually about: a ทีมขาย employee could not file leave for it. */
    private static final LocalDate SATURDAY_AFTER = LocalDate.of(2026, 9, 5);
    /** The last Saturday BEFORE V160 takes effect — history must keep resolving the five-day schedule. */
    private static final LocalDate SATURDAY_BEFORE = LocalDate.of(2026, 8, 29);
    /** V160 adds Saturday only. Sunday stays off for everyone. */
    private static final LocalDate SUNDAY_AFTER = LocalDate.of(2026, 9, 6);
    /** V115/V117 seed every assignment from the announcement's own effective date. */
    private static final LocalDate ANNOUNCEMENT_2567 = LocalDate.of(2024, 10, 1);

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
                .getResourceAsStream("db/migration/V160__sales_division_saturday_workday.sql")) {
            assertThat(in).as("V160 migration file must be on the test classpath").isNotNull();
            migrationSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void salesTeamGetsSaturdayFromTheEffectiveDateOnly_andKeepsItsScanInOnlyExemption() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย (test)");
        long salesTeam = insertDepartment("SATM", "ทีมขาย (test)", salesDivision);
        long employee = insertEmployee("SATM-1", salesDivision, salesTeam);
        // V117's shape: DIVISION 'SA' and DEPARTMENT 'SATM' both on SALES_5D. Seeded by hand because V115/V117's own
        // INSERTs no-op on a golden-template DB that has no 'SA' division.
        assignDivision(salesDivision, "SALES_5D", ANNOUNCEMENT_2567, null);
        assignDepartment(salesTeam, "SALES_5D", ANNOUNCEMENT_2567, null);

        assertThat(resolver.resolve(employee, salesDivision, salesTeam, SATURDAY_AFTER).isWorkday(SATURDAY_AFTER))
            .as("precondition: before V160 runs, 2026-09-05 is NOT a working day — this is the bug")
            .isFalse();

        executeStatements(migrationSql);
        resolver.invalidate();

        WorkSchedule after = resolver.resolve(employee, salesDivision, salesTeam, SATURDAY_AFTER);
        assertThat(after.isWorkday(SATURDAY_AFTER))
            .as("2026-09-05 (the blocked Saturday) is now a working day for ทีมขาย")
            .isTrue();
        assertThat(after.requiresCheckOut())
            .as("ทีมขาย keeps V117's §4 scan-in-only exemption — SALES_6D, not OPS_6D")
            .isFalse();

        // The two halves that stop this passing for the wrong reason.
        assertThat(resolver.resolve(employee, salesDivision, salesTeam, SATURDAY_BEFORE).isWorkday(SATURDAY_BEFORE))
            .as("2026-08-29 still resolves the CLOSED five-day row — V160 is not retroactive")
            .isFalse();
        assertThat(resolver.resolve(employee, salesDivision, salesTeam, SUNDAY_AFTER).isWorkday(SUNDAY_AFTER))
            .as("Sunday 2026-09-06 is still a non-working day — V160 adds Saturday, not the weekend")
            .isFalse();
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Strips -- line comments, then splits on ;. V160 contains no $$ block, so no dollar-quote handling is needed. */
    private void executeStatements(String sql) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (line.strip().startsWith("--")) {
                continue;
            }
            withoutComments.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String statement : withoutComments.toString().split(";")) {
            String trimmed = statement.strip();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        assertThat(statements)
            .as("V160 must still be the 7 statements this test replays — an 8th would go unexercised")
            .hasSize(7);
        for (String statement : statements) {
            jdbc.getJdbcOperations().execute(statement);
        }
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertDepartment(String code, String name, Long divisionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("name", name);
        params.put("divisionId", divisionId);
        return jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, division_id, is_active)
            VALUES (:code, :name, :divisionId, TRUE) RETURNING department_id
            """, params, Long.class);
    }

    private long insertEmployee(String code, Long divisionId, Long departmentId) {
        Map<String, Object> params = new HashMap<>();
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

    private void assignDivision(long divisionId, String scheduleCode, LocalDate from, LocalDate to) {
        assign("DIVISION", divisionId, scheduleCode, from, to);
    }

    private void assignDepartment(long departmentId, String scheduleCode, LocalDate from, LocalDate to) {
        assign("DEPARTMENT", departmentId, scheduleCode, from, to);
    }

    private void assign(String scopeType, long scopeId, String scheduleCode, LocalDate from, LocalDate to) {
        Map<String, Object> params = new HashMap<>();
        params.put("scopeType", scopeType);
        params.put("scopeId", scopeId);
        params.put("workScheduleId", jdbc.queryForObject(
            "SELECT work_schedule_id FROM hr.work_schedule WHERE code = :code",
            Map.of("code", scheduleCode), Long.class));
        params.put("from", from);
        params.put("to", to);
        jdbc.update("""
            INSERT INTO hr.work_schedule_assignment
                (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
            VALUES (:scopeType, :scopeId, :workScheduleId, :from, :to)
            """, params);
    }
}
