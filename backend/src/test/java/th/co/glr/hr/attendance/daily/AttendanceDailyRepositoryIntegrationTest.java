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

/**
 * Exercises the daily roll-up SQL against a real PostgreSQL database.
 *
 * <p>These cases exist because Mockito cannot reach them. The override guard lives in an
 * {@code ON CONFLICT ... DO UPDATE ... WHERE} clause, absent days come from {@code generate_series},
 * and badge resolution is a correlated subquery — a mocked repository would happily "pass" all three
 * while the real statements did something else. This repository has been bitten by exactly that
 * before.
 */
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
        // HashMap, not Map.of: badge and divisionId are legitimately null in several cases here and
        // Map.of rejects nulls.
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
            repository.findApprovedOvertimeMinutes(employeeId, date))));
    }

    /**
     * The guard that matters most. It lives in SQL precisely so no caller can forget it — which
     * also means only a real database can prove it works.
     */
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

    @Test
    void upsertIsIdempotentSoBackfillCanBeRerun() {
        long employeeId = insertEmployee("E101", "E101", null, LocalDate.of(2020, 1, 1));
        insertPunch("E101", at(WEDNESDAY, 8, 20));
        insertPunch("E101", at(WEDNESDAY, 17, 40));

        rollUp(employeeId, WEDNESDAY);
        Map<String, Object> first = snapshot(employeeId);
        rollUp(employeeId, WEDNESDAY);
        Map<String, Object> second = snapshot(employeeId);

        assertThat(second).containsAllEntriesOf(first);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hr.attendance_daily WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Long.class)).isEqualTo(1L);
    }

    /** Card readers report the card number, fingerprint punches the employee code — both must resolve. */
    @Test
    void punchesResolveByCardNumberOrEmployeeCode() {
        long byCard = insertEmployee("E102", "CARD-102", null, LocalDate.of(2020, 1, 1));
        long byCode = insertEmployee("E103", null, null, LocalDate.of(2020, 1, 1));

        insertPunch("CARD-102", at(WEDNESDAY, 8, 0));
        insertPunch("E103", at(WEDNESDAY, 8, 5));

        assertThat(repository.findPunchesFor(byCard, WEDNESDAY)).hasSize(1);
        assertThat(repository.findPunchesFor(byCode, WEDNESDAY)).hasSize(1);

        List<EmployeeDay> pairs = repository.findPairsWithPunches(WEDNESDAY, WEDNESDAY);
        assertThat(pairs).extracting(EmployeeDay::employeeId).containsExactlyInAnyOrder(byCard, byCode);
    }

    @Test
    void unresolvableBadgesAreReportedPerBadgeNotPerPunch() {
        insertEmployee("E104", "E104", null, LocalDate.of(2020, 1, 1));
        insertPunch("E104", at(WEDNESDAY, 8, 0));
        insertPunch("GHOST", at(WEDNESDAY, 8, 1));
        insertPunch("GHOST", at(WEDNESDAY, 12, 1));
        insertPunch("GHOST", at(WEDNESDAY, 17, 1));

        List<UnmappedBadge> unmapped = repository.findUnmappedBadges(WEDNESDAY, WEDNESDAY);

        assertThat(unmapped).hasSize(1);
        assertThat(unmapped.get(0).badgeCode()).isEqualTo("GHOST");
        assertThat(unmapped.get(0).punchCount()).isEqualTo(3);
        assertThat(unmapped.get(0).firstSeen()).isBefore(unmapped.get(0).lastSeen());
        // And it never becomes a daily row, because employee_id is NOT NULL.
        assertThat(repository.findPairsWithPunches(WEDNESDAY, WEDNESDAY)).hasSize(1);
    }

    /** MANAGER_APPROVED is only half of the dual-approval gate and must contribute nothing. */
    @Test
    void onlyFullyApprovedOvertimeCounts() {
        long employeeId = insertEmployee("E105", "E105", null, LocalDate.of(2020, 1, 1));
        insertOvertime(employeeId, WEDNESDAY, "MANAGER_APPROVED", 120);
        assertThat(repository.findApprovedOvertimeMinutes(employeeId, WEDNESDAY)).isZero();

        insertOvertime(employeeId, WEDNESDAY, "APPROVED", 90);
        assertThat(repository.findApprovedOvertimeMinutes(employeeId, WEDNESDAY)).isEqualTo(90);

        insertOvertime(employeeId, WEDNESDAY, "REJECTED", 60);
        assertThat(repository.findApprovedOvertimeMinutes(employeeId, WEDNESDAY)).isEqualTo(90);
    }

    @Test
    void rangeReadReturnsEveryDayIncludingThoseWithNoAttendance() {
        long employeeId = insertEmployee("E106", "E106", null, LocalDate.of(2020, 1, 1));
        insertPunch("E106", at(WEDNESDAY, 8, 20));
        insertPunch("E106", at(WEDNESDAY, 17, 40));
        rollUp(employeeId, WEDNESDAY);

        List<AttendanceDailyRow> rows = repository.findRange(new AttendanceDailyFilter(
            employeeId, null, WEDNESDAY.minusDays(2), WEDNESDAY));

        assertThat(rows).hasSize(3);
        assertThat(rows).filteredOn(AttendanceDailyRow::hasRecord).hasSize(1);
        assertThat(rows).filteredOn(row -> !row.hasRecord()).hasSize(2)
            .allSatisfy(row -> {
                assertThat(row.checkIn()).isNull();
                assertThat(row.punchCount()).isZero();
            });
    }

    /** Rows must not appear before someone joined the company. */
    @Test
    void rangeReadDoesNotReturnDaysBeforeHireDate() {
        long employeeId = insertEmployee("E107", "E107", null, WEDNESDAY);

        List<AttendanceDailyRow> rows = repository.findRange(new AttendanceDailyFilter(
            employeeId, null, WEDNESDAY.minusDays(3), WEDNESDAY));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).workDate()).isEqualTo(WEDNESDAY);
    }

    @Test
    void divisionScopeExcludesEmployeesOutsideIt() {
        long sales = insertDivision("SL", "ฝ่ายขาย");
        long factory = insertDivision("FC", "ฝ่ายโรงงาน");
        long inside = insertEmployee("E108", "E108", sales, LocalDate.of(2020, 1, 1));
        long outside = insertEmployee("E109", "E109", factory, LocalDate.of(2020, 1, 1));

        List<AttendanceDailyRow> rows = repository.findRange(new AttendanceDailyFilter(
            null, sales, WEDNESDAY, WEDNESDAY));

        assertThat(rows).extracting(AttendanceDailyRow::employeeId).contains(inside);
        assertThat(rows).extracting(AttendanceDailyRow::employeeId).doesNotContain(outside);
    }

    /**
     * A manager asking for someone in another division gets nothing rather than that person's data:
     * both predicates AND, so the out-of-division id simply matches no rows.
     */
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

    @Test
    void employeeOptionsAreScopedToSelfReportsAndDivision() {
        long sales = insertDivision("SL3", "ฝ่ายขาย 3");
        long factory = insertDivision("FC3", "ฝ่ายโรงงาน 3");
        long manager = insertEmployee("E112", "E112", sales, LocalDate.of(2020, 1, 1));
        long teammate = insertEmployee("E113", "E113", sales, LocalDate.of(2020, 1, 1));
        long stranger = insertEmployee("E114", "E114", factory, LocalDate.of(2020, 1, 1));

        List<AttendanceEmployeeOption> scoped =
            repository.findEmployeeOptions(manager, sales, false);
        assertThat(scoped).extracting(AttendanceEmployeeOption::employeeId)
            .contains(manager, teammate)
            .doesNotContain(stranger);

        List<AttendanceEmployeeOption> all = repository.findEmployeeOptions(manager, sales, true);
        assertThat(all).extracting(AttendanceEmployeeOption::employeeId).contains(stranger);
    }

    /**
     * Reads division_id, which is INTEGER in the schema — a blind (Long) cast throws at runtime.
     * This runs on every recalculation, so it has to be exercised against a real result set.
     */
    @Test
    void divisionLookupWidensTheIntegerColumnWithoutCasting() {
        long division = insertDivision("SL4", "ฝ่ายขาย 4");
        long withDivision = insertEmployee("E115", "E115", division, LocalDate.of(2020, 1, 1));
        long withoutDivision = insertEmployee("E116", "E116", null, LocalDate.of(2020, 1, 1));

        assertThat(repository.findDivisionId(withDivision)).isEqualTo(division);
        assertThat(repository.findDivisionId(withoutDivision)).isNull();
    }

    /**
     * The production failure this bulk path exists for: a real backfill spans thousands of
     * employee-days. The per-day form issued three queries each — ~14k round trips in one
     * transaction — which timed out against the hosted database and wrote nothing at all.
     *
     * <p>Also exercises the chunked upsert, since the row count here exceeds UPSERT_CHUNK.
     */
    @Test
    void bulkRangeLoadReturnsEveryEmployeeDayAndSurvivesChunkedUpsert() {
        LocalDate start = LocalDate.of(2026, 3, 2);
        int employees = 12;
        int days = 60;

        List<Long> ids = new java.util.ArrayList<>();
        for (int e = 0; e < employees; e++) {
            ids.add(insertEmployee("BULK" + e, "BULK" + e, null, LocalDate.of(2020, 1, 1)));
        }
        for (int d = 0; d < days; d++) {
            LocalDate date = start.plusDays(d);
            for (int e = 0; e < employees; e++) {
                insertPunch("BULK" + e, at(date, 8, 20));
                insertPunch("BULK" + e, at(date, 17, 40));
            }
        }

        LocalDate end = start.plusDays(days - 1);
        Map<EmployeeDay, List<PunchRecord>> byDay = repository.findPunchesInRange(start, end, null);
        assertThat(byDay).hasSize(employees * days);
        assertThat(byDay.values()).allSatisfy(punches -> assertThat(punches).hasSize(2));

        Map<Long, Long> divisions = repository.findDivisionIdsByEmployee();
        assertThat(divisions.keySet()).containsAll(ids);

        List<AttendanceDailyRecord> records = byDay.entrySet().stream()
            .map(entry -> calculator.calculate(
                entry.getKey().employeeId(), entry.getKey().workDate(),
                entry.getValue(), SCHEDULE, 0))
            .toList();

        assertThat(repository.upsertAll(records)).isEqualTo(employees * days);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM hr.attendance_daily", Map.of(), Long.class))
            .isEqualTo((long) employees * days);
    }

    /** The bulk overtime lookup must keep the APPROVED-only rule of its per-day counterpart. */
    @Test
    void bulkOvertimeLookupCountsOnlyApprovedRequests() {
        long employeeId = insertEmployee("E120", "E120", null, LocalDate.of(2020, 1, 1));
        insertOvertime(employeeId, WEDNESDAY, "APPROVED", 90);
        insertOvertime(employeeId, WEDNESDAY.plusDays(1), "MANAGER_APPROVED", 120);

        Map<EmployeeDay, Integer> byDay =
            repository.findApprovedOvertimeMinutesInRange(WEDNESDAY, WEDNESDAY.plusDays(1));

        assertThat(byDay.get(new EmployeeDay(employeeId, WEDNESDAY))).isEqualTo(90);
        assertThat(byDay).doesNotContainKey(new EmployeeDay(employeeId, WEDNESDAY.plusDays(1)));
    }

    private void insertOvertime(long employeeId, LocalDate workDate, String status, int payable) {
        jdbc.update("""
            INSERT INTO hr.overtime_request (
                employee_id, work_date, planned_start_at, planned_end_at, planned_minutes,
                reason, status, payable_minutes, payroll_month
            ) VALUES (
                :employeeId, :workDate, :start, :end, :planned,
                'test', :status, :payable, :payrollMonth
            )
            """,
            Map.of(
                "employeeId", employeeId,
                "workDate", workDate,
                "start", at(workDate, 17, 30),
                "end", at(workDate, 19, 30),
                "planned", 120,
                "status", status,
                "payable", payable,
                "payrollMonth", workDate.withDayOfMonth(1)
            ));
    }

    private int lateMinutes(long employeeId) {
        return jdbc.queryForObject(
            "SELECT late_minutes FROM hr.attendance_daily WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
    }

    private Map<String, Object> snapshot(long employeeId) {
        return jdbc.queryForMap("""
            SELECT check_in, check_out, total_minutes, late_minutes,
                   early_leave_minutes, overtime_minutes, punch_count
              FROM hr.attendance_daily WHERE employee_id = :employeeId
            """, Map.of("employeeId", employeeId));
    }
}
