package th.co.glr.hr.attendance.daily;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.attendance.AttendanceSql;

/** Reads punches for roll-up and writes the derived {@code hr.attendance_daily} rows. */
@Repository
public class AttendanceDailyRepository {

    /**
     * The one definition of badge resolution, shared with the punch list. Never inline a copy: a
     * divergence here would make a punch visible in one view and invisible in the other, silently.
     */
    private static final String RESOLVED_EMPLOYEE_JOIN = AttendanceSql.RESOLVED_EMPLOYEE_JOIN;

    private final NamedParameterJdbcTemplate jdbc;

    public AttendanceDailyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every (employee, date) pair with at least one punch in the range, for employees whose badge
     * resolves. Pairs whose badge does not resolve are skipped here — {@code employee_id} is NOT
     * NULL on the daily table, so they cannot be stored; they surface through
     * {@link #findUnmappedBadges} instead.
     */
    public List<EmployeeDay> findPairsWithPunches(LocalDate fromDate, LocalDate toDate) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("fromDate", fromDate)
            .addValue("toDate", toDate);

        return jdbc.query(
            "SELECT DISTINCT COALESCE(p.employee_id, e.employee_id) AS employee_id, p.work_date"
                + "  FROM hr.attendance_punch p"
                + RESOLVED_EMPLOYEE_JOIN
                + " WHERE p.work_date BETWEEN :fromDate AND :toDate"
                + "   AND COALESCE(p.employee_id, e.employee_id) IS NOT NULL"
                + " ORDER BY p.work_date, employee_id",
            params,
            (rs, rowNum) -> new EmployeeDay(rs.getLong("employee_id"), rs.getObject("work_date", LocalDate.class))
        );
    }

    /** That employee's punches on that date, oldest first. */
    public List<PunchRecord> findPunchesFor(long employeeId, LocalDate workDate) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("workDate", workDate);

        return jdbc.query(
            "SELECT p.punch_id, p.punch_time, p.site_code"
                + "  FROM hr.attendance_punch p"
                + RESOLVED_EMPLOYEE_JOIN
                + " WHERE p.work_date = :workDate"
                + "   AND COALESCE(p.employee_id, e.employee_id) = :employeeId"
                + " ORDER BY p.punch_time, p.punch_id",
            params,
            (rs, rowNum) -> new PunchRecord(
                rs.getLong("punch_id"),
                rs.getObject("punch_time", OffsetDateTime.class),
                rs.getString("site_code")
            )
        );
    }

    /**
     * Minutes of APPROVED overtime for that employee and date.
     *
     * <p>MANAGER_APPROVED is excluded deliberately: V34 introduced CEO approval as the second half
     * of a dual-approval gate, so counting the half-approved state would leak un-finalised overtime
     * into a pay-relevant figure and defeat the control that migration exists to impose.
     */
    public int findApprovedOvertimeMinutes(long employeeId, LocalDate workDate) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("workDate", workDate);

        Integer minutes = jdbc.queryForObject("""
            SELECT COALESCE(SUM(COALESCE(NULLIF(payable_minutes, 0), actual_minutes, 0)), 0)
              FROM hr.overtime_request
             WHERE employee_id = :employeeId
               AND work_date = :workDate
               AND status = 'APPROVED'
            """, params, Integer.class);
        return minutes == null ? 0 : minutes;
    }

    /**
     * Upserts a derived row, <strong>never overwriting an HR correction</strong>.
     *
     * <p>The {@code is_manual_override} guard lives in the {@code DO UPDATE ... WHERE} clause rather
     * than in a Java {@code if}. That makes clobbering structurally impossible from every write path
     * — live punch, .dat import, badge backfill, overtime decision, HR endpoint, nightly job and
     * backfill all go through this one statement — instead of depending on six callers each
     * remembering to check.
     *
     * @return true when a row was inserted or updated; false when an override blocked the write
     */
    public boolean upsert(AttendanceDailyRecord record) {
        return upsertAll(List.of(record)) > 0;
    }

    /** Batch form of {@link #upsert}, carrying the same override guard. */
    public int upsertAll(List<AttendanceDailyRecord> records) {
        if (records.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = records.stream()
            .map(AttendanceDailyRepository::upsertParams)
            .toArray(SqlParameterSource[]::new);

        int[] affected = jdbc.batchUpdate("""
            INSERT INTO hr.attendance_daily (
                employee_id, work_date, site_code,
                check_in_punch_id, check_out_punch_id, check_in, check_out,
                total_minutes, late_minutes, early_leave_minutes, overtime_minutes,
                punch_count, is_absent, calculated_at, updated_at
            )
            VALUES (
                :employeeId, :workDate, :siteCode,
                :checkInPunchId, :checkOutPunchId, :checkIn, :checkOut,
                :totalMinutes, :lateMinutes, :earlyLeaveMinutes, :overtimeMinutes,
                :punchCount, FALSE, now(), now()
            )
            ON CONFLICT (employee_id, work_date) DO UPDATE SET
                site_code           = EXCLUDED.site_code,
                check_in_punch_id   = EXCLUDED.check_in_punch_id,
                check_out_punch_id  = EXCLUDED.check_out_punch_id,
                check_in            = EXCLUDED.check_in,
                check_out           = EXCLUDED.check_out,
                total_minutes       = EXCLUDED.total_minutes,
                late_minutes        = EXCLUDED.late_minutes,
                early_leave_minutes = EXCLUDED.early_leave_minutes,
                overtime_minutes    = EXCLUDED.overtime_minutes,
                punch_count         = EXCLUDED.punch_count,
                is_absent           = EXCLUDED.is_absent,
                calculated_at       = now(),
                updated_at          = now()
            WHERE hr.attendance_daily.is_manual_override = FALSE
            """, batch);

        int written = 0;
        for (int rows : affected) {
            if (rows > 0) {
                written += rows;
            }
        }
        return written;
    }

    private static SqlParameterSource upsertParams(AttendanceDailyRecord record) {
        return new MapSqlParameterSource()
            .addValue("employeeId", record.employeeId())
            .addValue("workDate", record.workDate())
            .addValue("siteCode", record.siteCode())
            .addValue("checkInPunchId", record.checkInPunchId())
            .addValue("checkOutPunchId", record.checkOutPunchId())
            .addValue("checkIn", record.checkIn())
            .addValue("checkOut", record.checkOut())
            .addValue("totalMinutes", record.totalMinutes())
            .addValue("lateMinutes", record.lateMinutes())
            .addValue("earlyLeaveMinutes", record.earlyLeaveMinutes())
            .addValue("overtimeMinutes", record.overtimeMinutes())
            .addValue("punchCount", record.punchCount());
    }

    /**
     * One row per employee per day across the whole range, including days with no attendance at all
     * so the UI can render "-" rather than silently omitting them.
     *
     * <p>{@code generate_series} CROSS JOIN the scoped employees, bounded by hire date so rows never
     * predate employment. The caller must cap the range: this is employees × days.
     */
    public List<AttendanceDailyRow> findRange(AttendanceDailyFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("fromDate", filter.fromDate())
            .addValue("toDate", filter.toDate());

        StringBuilder employeeScope = new StringBuilder(" WHERE emp.is_active = TRUE");
        if (filter.employeeId() != null) {
            employeeScope.append(" AND emp.employee_id = :employeeId");
            params.addValue("employeeId", filter.employeeId());
        }
        if (filter.divisionId() != null) {
            employeeScope.append(" AND emp.division_id = :divisionId");
            params.addValue("divisionId", filter.divisionId());
        }

        String sql = """
            SELECT emp.employee_id,
                   emp.employee_code,
                   concat_ws(' ', emp.first_name_th, emp.last_name_th) AS employee_name,
                   emp.nickname AS nick_name,
                   pos.name_th AS position_th,
                   d.work_date,
                   ad.check_in,
                   ad.check_out,
                   ad.total_minutes,
                   COALESCE(ad.late_minutes, 0)        AS late_minutes,
                   COALESCE(ad.early_leave_minutes, 0) AS early_leave_minutes,
                   COALESCE(ad.overtime_minutes, 0)    AS overtime_minutes,
                   COALESCE(ad.punch_count, 0)         AS punch_count,
                   ad.site_code,
                   COALESCE(ad.is_manual_override, FALSE) AS is_manual_override,
                   ad.notes,
                   (ad.attendance_daily_id IS NOT NULL) AS has_record
              FROM (SELECT generate_series(:fromDate::date, :toDate::date, INTERVAL '1 day')::date
                           AS work_date) d
              CROSS JOIN (SELECT emp.employee_id, emp.employee_code, emp.first_name_th,
                                 emp.last_name_th, emp.nickname, emp.position_id, emp.hire_date
                            FROM hr.employee emp
            """
            + employeeScope
            + """
                         ) emp
              LEFT JOIN hr.position pos ON pos.position_id = emp.position_id
              LEFT JOIN hr.attendance_daily ad
                     ON ad.employee_id = emp.employee_id
                    AND ad.work_date = d.work_date
             WHERE emp.hire_date IS NULL OR d.work_date >= emp.hire_date
             ORDER BY d.work_date DESC, emp.employee_code
            """;

        return jdbc.query(sql, params, (rs, rowNum) -> new AttendanceDailyRow(
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getString("nick_name"),
            rs.getString("position_th"),
            rs.getObject("work_date", LocalDate.class),
            rs.getObject("check_in", OffsetDateTime.class),
            rs.getObject("check_out", OffsetDateTime.class),
            (Integer) rs.getObject("total_minutes"),
            rs.getInt("late_minutes"),
            rs.getInt("early_leave_minutes"),
            rs.getInt("overtime_minutes"),
            rs.getInt("punch_count"),
            rs.getString("site_code"),
            rs.getBoolean("is_manual_override"),
            rs.getString("notes"),
            rs.getBoolean("has_record")
        ));
    }

    /**
     * Badges that scanned in the range but resolve to no employee, grouped <em>by badge</em> rather
     * than per punch — a badge that scanned 200 times is one problem to fix, not 200 rows to read.
     */
    public List<UnmappedBadge> findUnmappedBadges(LocalDate fromDate, LocalDate toDate) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("fromDate", fromDate)
            .addValue("toDate", toDate);

        return jdbc.query(
            "SELECT p.badge_code,"
                + "       COUNT(*)          AS punch_count,"
                + "       MIN(p.punch_time) AS first_seen,"
                + "       MAX(p.punch_time) AS last_seen,"
                + "       MIN(p.site_code)  AS site_code"
                + "  FROM hr.attendance_punch p"
                + RESOLVED_EMPLOYEE_JOIN
                + " WHERE p.work_date BETWEEN :fromDate AND :toDate"
                + "   AND COALESCE(p.employee_id, e.employee_id) IS NULL"
                + " GROUP BY p.badge_code"
                + " ORDER BY COUNT(*) DESC, p.badge_code",
            params,
            (rs, rowNum) -> new UnmappedBadge(
                rs.getString("badge_code"),
                rs.getInt("punch_count"),
                rs.getObject("first_seen", OffsetDateTime.class),
                rs.getObject("last_seen", OffsetDateTime.class),
                rs.getString("site_code")
            )
        );
    }

    /** Employees the caller may pick from, mirroring the overtime picker's self/report/division scope. */
    public List<AttendanceEmployeeOption> findEmployeeOptions(
            Long actorEmployeeId, Long managerDivisionId, boolean includeAll) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("actorEmployeeId", actorEmployeeId)
            .addValue("managerDivisionId", managerDivisionId);

        StringBuilder sql = new StringBuilder("""
            SELECT e.employee_id,
                   e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
                   e.nickname AS nick_name,
                   dep.name_th AS department_name,
                   e.division_id,
                   div.name_th AS division_name
              FROM hr.employee e
              LEFT JOIN hr.department dep ON dep.department_id = e.department_id
              LEFT JOIN hr.division div ON div.division_id = e.division_id
             WHERE e.is_active = TRUE
            """);

        if (!includeAll) {
            sql.append(" AND (e.employee_id = :actorEmployeeId OR e.reports_to_employee_id = :actorEmployeeId");
            if (managerDivisionId != null) {
                sql.append(" OR e.division_id = :managerDivisionId");
            }
            sql.append(')');
        }
        sql.append(" ORDER BY e.employee_code");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new AttendanceEmployeeOption(
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getString("nick_name"),
            rs.getString("department_name"),
            nullableLong(rs.getObject("division_id")),
            rs.getString("division_name")
        ));
    }

    /** Distinct (employee, date) pairs touched by a set of punch ids — used after a badge backfill. */
    public List<EmployeeDay> findPairsForPunchIds(List<Long> punchIds) {
        if (punchIds == null || punchIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("punchIds", punchIds);
        return new ArrayList<>(jdbc.query(
            "SELECT DISTINCT COALESCE(p.employee_id, e.employee_id) AS employee_id, p.work_date"
                + "  FROM hr.attendance_punch p"
                + RESOLVED_EMPLOYEE_JOIN
                + " WHERE p.punch_id IN (:punchIds)"
                + "   AND COALESCE(p.employee_id, e.employee_id) IS NOT NULL",
            params,
            (rs, rowNum) -> new EmployeeDay(rs.getLong("employee_id"), rs.getObject("work_date", LocalDate.class))
        ));
    }

    /** Oldest business date with any punch, or null when the ledger is empty. */
    public LocalDate findEarliestPunchDate() {
        return jdbc.queryForObject(
            "SELECT MIN(work_date) FROM hr.attendance_punch",
            new MapSqlParameterSource(),
            LocalDate.class);
    }

    /** Newest business date with any punch, or null when the ledger is empty. */
    public LocalDate findLatestPunchDate() {
        return jdbc.queryForObject(
            "SELECT MAX(work_date) FROM hr.attendance_punch",
            new MapSqlParameterSource(),
            LocalDate.class);
    }

    /** The employee's division, needed to resolve their schedule. */
    public Long findDivisionId(long employeeId) {
        List<Long> found = jdbc.query(
            "SELECT division_id FROM hr.employee WHERE employee_id = :employeeId",
            new MapSqlParameterSource("employeeId", employeeId),
            (rs, rowNum) -> nullableLong(rs.getObject("division_id"))
        );
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * {@code division_id} is INTEGER in the schema, so a blind {@code (Long)} cast throws
     * ClassCastException at runtime. Widen through Number instead.
     */
    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
