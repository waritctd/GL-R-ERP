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
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres coverage of V160 — ฝ่ายขาย moving to a six-day week (Saturday becomes a scheduled
 * working day) effective 2026-09-01.
 *
 * <p>Same technique as {@link OrgNormalizationScheduleIntegrationTest}: V160's own SQL text is read
 * verbatim from the classpath and replayed against fixture rows carrying the real production
 * source_codes ('SA', 'SATM', 'SALES', ...) that a freshly-migrated golden-template database does
 * not have — so every INSERT ... SELECT in the shipped migration no-ops at migrate time and this
 * class exercises the ACTUAL shipped statements rather than a hand-typed re-implementation.
 *
 * <p>Assertions are deliberately written wrong-way-round wherever the change could pass for the
 * wrong reason: every "Saturday is now a workday" claim is paired, on the SAME date and in the SAME
 * fixture, with something that must NOT have moved — a Saturday before the effective date, a
 * Sunday, or a non-sales division. A one-sided test here would stay green if V160 made every day a
 * workday for everybody.
 */
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
        // V117's shape: DIVISION 'SA' and DEPARTMENT 'SATM' both on SALES_5D. Seeded by hand because
        // V115/V117's own INSERTs no-op on a golden-template DB that has no 'SA' division.
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

    @Test
    void salesSupportGetsSaturdayButStillHasToScanOut_whileANonSalesDivisionIsUntouched() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย (test)");
        long salesSupport1 = insertDepartment("SALES", "ฝ่ายสนับสนุนการขาย 1 (test)", salesDivision);
        long supportEmployee = insertEmployee("SALES-1", salesDivision, salesSupport1);
        assignDivision(salesDivision, "SALES_5D", ANNOUNCEMENT_2567, null);
        assignDepartment(salesSupport1, "OFFICE_5D", ANNOUNCEMENT_2567, null);

        // A five-day division V160 must not touch, resolved on the SAME Saturday. Without this the
        // test would still pass if V160 dropped the source_code filter and moved every scope.
        long hrDivision = insertDivision("HR", "HR-ฝ่ายบุคคล (test)");
        long hrEmployee = insertEmployee("HR-1", hrDivision, null);
        assignDivision(hrDivision, "OFFICE_5D", ANNOUNCEMENT_2567, null);

        executeStatements(migrationSql);
        resolver.invalidate();

        WorkSchedule support = resolver.resolve(supportEmployee, salesDivision, salesSupport1, SATURDAY_AFTER);
        assertThat(support.isWorkday(SATURDAY_AFTER))
            .as("Sales Support 1's DEPARTMENT row moved to OPS_6D — Saturday is a working day")
            .isTrue();
        assertThat(support.requiresCheckOut())
            .as("Sales Support still has to scan out — V160 changes which DAYS are worked, nothing else")
            .isTrue();

        assertThat(resolver.resolve(hrEmployee, hrDivision, null, SATURDAY_AFTER).isWorkday(SATURDAY_AFTER))
            .as("ฝ่ายบุคคล's same Saturday is still a non-working day — the change is sales-only")
            .isFalse();
    }

    @Test
    void aSalesDepartmentWithNoDepartmentRowInheritsSaturdayFromTheDivisionTier() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย (test)");
        // ออกแบบ has no DEPARTMENT-scope assignment in prod, so it resolves through DIVISION 'SA'.
        // This is the case the division-tier row in Part C exists for.
        long designDept = insertDepartment("SADS", "ออกแบบ (test)", salesDivision);
        long designer = insertEmployee("SADS-1", salesDivision, designDept);
        assignDivision(salesDivision, "SALES_5D", ANNOUNCEMENT_2567, null);

        executeStatements(migrationSql);
        resolver.invalidate();

        WorkSchedule resolved = resolver.resolve(designer, salesDivision, designDept, SATURDAY_AFTER);
        assertThat(resolved.isWorkday(SATURDAY_AFTER))
            .as("a ฝ่ายขาย department with no row of its own inherits SALES_6D from the DIVISION tier")
            .isTrue();
        assertThat(resolved.requiresCheckOut())
            .as("the division-tier catch-all is SALES_6D (scan-in-only), matching the SALES_5D it replaces")
            .isFalse();
    }

    @Test
    void theBlockedLeaveDateNowCountsAsAWorkingDayThroughLeaveRepositorysOwnPredicate() {
        // The user-facing outcome, asserted through the exact collaborator LeaveService#submit uses:
        // LeaveService rejects a range whose working-day count is zero
        // ("ช่วงวันลาต้องมีวันทำงานอย่างน้อย 1 วัน"), and a lone Saturday counted zero under SALES_5D.
        long salesDivision = insertDivision("SA", "ฝ่ายขาย (test)");
        long salesTeam = insertDepartment("SATM", "ทีมขาย (test)", salesDivision);
        long employee = insertEmployee("SATM-LEAVE", salesDivision, salesTeam);
        assignDivision(salesDivision, "SALES_5D", ANNOUNCEMENT_2567, null);
        assignDepartment(salesTeam, "SALES_5D", ANNOUNCEMENT_2567, null);

        LeaveRepository leaveRepository = new LeaveRepository(jdbc, resolver, new DbHolidayCalendar(jdbc));

        Predicate<LocalDate> before =
            leaveRepository.workingDayPredicate(employee, SATURDAY_AFTER, SATURDAY_AFTER);
        assertThat(before.test(SATURDAY_AFTER))
            .as("precondition: 2026-09-05 counts zero working days, which is what LeaveService rejects")
            .isFalse();

        executeStatements(migrationSql);
        resolver.invalidate();

        Predicate<LocalDate> after =
            leaveRepository.workingDayPredicate(employee, SATURDAY_BEFORE, SUNDAY_AFTER);
        assertThat(after.test(SATURDAY_AFTER))
            .as("2026-09-05 now counts as a working day, so a leave request for it is accepted")
            .isTrue();
        assertThat(after.test(SATURDAY_BEFORE))
            .as("the identical predicate still says 2026-08-29 is not a working day")
            .isFalse();
        assertThat(after.test(SUNDAY_AFTER))
            .as("the identical predicate still says Sunday is not a working day")
            .isFalse();
    }

    @Test
    void replayingTheMigrationIsANoOp_becauseProductionGetsTheseRowsByHandFirst() {
        // V160's rows were applied to prod by hand on 2026-09-01 (the Render backend does not
        // auto-deploy), so Flyway will replay this file over a database that already has them. Every
        // statement is written to be a no-op in that case; this test is what proves it, because a
        // duplicate assignment row would not error — it would quietly create an overlapping
        // last-write-wins pair that TieredWorkScheduleResolver resolves by SQL ORDER BY.
        long salesDivision = insertDivision("SA", "ฝ่ายขาย (test)");
        long salesTeam = insertDepartment("SATM", "ทีมขาย (test)", salesDivision);
        long salesSupport1 = insertDepartment("SALES", "ฝ่ายสนับสนุนการขาย 1 (test)", salesDivision);
        assignDivision(salesDivision, "SALES_5D", ANNOUNCEMENT_2567, null);
        assignDepartment(salesTeam, "SALES_5D", ANNOUNCEMENT_2567, null);
        assignDepartment(salesSupport1, "OFFICE_5D", ANNOUNCEMENT_2567, null);

        executeStatements(migrationSql);
        Map<String, Object> afterFirstRun = snapshot();

        executeStatements(migrationSql);

        assertThat(snapshot())
            .as("a second replay of V160 changes no schedule, day or assignment row")
            .isEqualTo(afterFirstRun);
    }

    /** Row counts plus the full assignment ledger — anything a duplicate replay could disturb. */
    private Map<String, Object> snapshot() {
        Map<String, Object> state = new HashMap<>();
        state.put("schedules", jdbc.queryForObject(
            "SELECT count(*) FROM hr.work_schedule", Map.of(), Integer.class));
        state.put("scheduleDays", jdbc.queryForObject(
            "SELECT count(*) FROM hr.work_schedule_day", Map.of(), Integer.class));
        state.put("assignments", jdbc.queryForList("""
            SELECT a.scope_type, a.scope_id, s.code, a.effective_from, a.effective_to
              FROM hr.work_schedule_assignment a
              JOIN hr.work_schedule s ON s.work_schedule_id = a.work_schedule_id
             ORDER BY a.scope_type, a.scope_id, a.effective_from, s.code
            """, Map.of()));
        return state;
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Strips {@code --} line comments, then splits on {@code ;}. V160 contains no {@code $$} block,
     * so the dollar-quote handling {@link OrgNormalizationScheduleIntegrationTest} needs for V121 is
     * not repeated here.
     */
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
