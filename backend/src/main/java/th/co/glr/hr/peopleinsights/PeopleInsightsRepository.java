package th.co.glr.hr.peopleinsights;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.AttendanceRow;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.LeaveRow;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.LeaveType;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.Person;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.QuotaUsage;

@Repository
public class PeopleInsightsRepository {
    private static final String BKK = "'Asia/Bangkok'";

    public record TeamRow(long id, String label) {}

    private final NamedParameterJdbcTemplate jdbc;

    public PeopleInsightsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TeamRow> teams() {
        return jdbc.query("SELECT team_id, name_th FROM hr.insight_team ORDER BY sort_order, team_id", Map.of(),
            (rs, i) -> new TeamRow(rs.getLong("team_id"), rs.getString("name_th")));
    }

    public List<Long> teamIdsHeadedBy(long employeeId) {
        return jdbc.queryForList("SELECT team_id FROM hr.insight_team_head WHERE employee_id = :id", Map.of("id", employeeId), Long.class);
    }

    public boolean isTeamHead(long employeeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM hr.insight_team_head WHERE employee_id = :id)", Map.of("id", employeeId), Boolean.class));
    }

    /** Head nicknames per team (first name when there is no nickname). */
    public Map<Long, List<String>> headNicknames() {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        jdbc.query("""
            SELECT h.team_id, COALESCE(NULLIF(e.nickname, ''), e.first_name_th) AS nick
              FROM hr.insight_team_head h JOIN hr.employee e ON e.employee_id = h.employee_id
             ORDER BY h.team_id, e.employee_code
            """, Map.of(), rs -> {
                out.computeIfAbsent(rs.getLong("team_id"), k -> new ArrayList<>()).add(rs.getString("nick"));
            });
        return out;
    }

    /** In a year's figures: everyone active, plus people who left but have approved leave or attendance in that year. */
    private static final String IN_YEAR_SCOPE = """
        (e.is_active
         OR EXISTS (SELECT 1 FROM hr.leave_request r WHERE r.employee_id = e.employee_id AND r.status = 'APPROVED'
                       AND r.start_date < :to AND r.end_date >= :from)
         OR EXISTS (SELECT 1 FROM hr.attendance_daily a WHERE a.employee_id = e.employee_id AND a.work_date >= :from AND a.work_date < :to))
        """;

    public List<Long> companyEmployeeIds(int year) {
        return jdbc.queryForList("SELECT e.employee_id FROM hr.employee e WHERE " + IN_YEAR_SCOPE + " ORDER BY e.employee_code",
            range(List.of(), year), Long.class);
    }

    /** Everyone below any head of each team, at any depth, keyed by team; heads of that team and detached lines (with everyone under them) left out. */
    public Map<Long, List<Long>> teamMemberIds(int year) {
        Map<Long, List<Long>> out = new LinkedHashMap<>();
        jdbc.query("""
            WITH RECURSIVE heads AS (
                SELECT team_id, employee_id FROM hr.insight_team_head
            ), line AS (
                SELECT h.team_id, e.employee_id FROM heads h
                  JOIN hr.employee e ON e.reports_to_employee_id = h.employee_id
                  LEFT JOIN hr.insight_employee_setting s ON s.employee_id = e.employee_id
                 WHERE NOT COALESCE(s.detached_line, FALSE)
                UNION
                SELECT l.team_id, e.employee_id FROM hr.employee e
                  JOIN line l ON e.reports_to_employee_id = l.employee_id
                  LEFT JOIN hr.insight_employee_setting s ON s.employee_id = e.employee_id
                 WHERE NOT COALESCE(s.detached_line, FALSE)
            )
            SELECT line.team_id, e.employee_id FROM line JOIN hr.employee e ON e.employee_id = line.employee_id
             WHERE %s AND NOT EXISTS (SELECT 1 FROM heads h WHERE h.team_id = line.team_id AND h.employee_id = e.employee_id)
             ORDER BY line.team_id, e.employee_code
            """.formatted(IN_YEAR_SCOPE), range(List.of(), year), rs -> {
                out.computeIfAbsent(rs.getLong("team_id"), k -> new ArrayList<>()).add(rs.getLong("employee_id"));
            });
        return out;
    }

    /** First and last year with attendance or APPROVED leave; null when there is none. Only the bounds are read, never every year. */
    public record YearBounds(int first, int last) {}

    public YearBounds dataYearBounds() {
        return jdbc.queryForObject("""
            SELECT EXTRACT(YEAR FROM LEAST(a.lo, l.lo))::int AS first, EXTRACT(YEAR FROM GREATEST(a.hi, l.hi))::int AS last
              FROM (SELECT MIN(work_date) AS lo, MAX(work_date) AS hi FROM hr.attendance_daily) a,
                   (SELECT MIN(start_date) AS lo, MAX(end_date) AS hi FROM hr.leave_request WHERE status = 'APPROVED') l
            """, Map.of(), (rs, i) -> rs.getObject("first") == null ? null : new YearBounds(rs.getInt("first"), rs.getInt("last")));
    }

    public List<Person> people(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("""
            SELECT e.employee_id, e.employee_code, COALESCE(NULLIF(e.nickname, ''), e.first_name_th) AS nick,
                   COALESCE(s.time_exempt, FALSE) AS time_exempt, e.is_active
              FROM hr.employee e LEFT JOIN hr.insight_employee_setting s ON s.employee_id = e.employee_id
             WHERE e.employee_id IN (:ids) ORDER BY e.employee_code
            """, new MapSqlParameterSource("ids", ids),
            (rs, i) -> new Person(rs.getLong("employee_id"), rs.getString("employee_code"), rs.getString("nick"), rs.getBoolean("time_exempt"), rs.getBoolean("is_active")));
    }

    /**
     * APPROVED requests overlapping the year. Days (total and unpaid) are split over the calendar months a request covers by
     * the share of its calendar days, clipped to the year; the request count, late filing and sick-edge days stay on the
     * start month, and only when that month is in the year.
     */
    public List<LeaveRow> leave(Collection<Long> ids, int year) {
        if (ids.isEmpty()) return List.of();
        record Req(long employeeId, String type, LocalDate start, LocalDate end, BigDecimal total, BigDecimal unpaid, boolean sickEdge, boolean lateFiled) {}
        List<Req> reqs = jdbc.query("""
            SELECT employee_id, leave_type_code, start_date, end_date, total_days, unpaid_days,
                   leave_type_code = 'SICK' AND EXTRACT(ISODOW FROM start_date) IN (1, 5, 6) AS sick_edge,
                   -- system_note IS NULL: import/backfill rows (filed by HR after the fact) carry a note even on APPROVED rows.
                   -- Sick leave is normally filed after the day, so it is never "late".
                   leave_type_code <> 'SICK' AND requested_by_id = employee_id AND system_note IS NULL
                       AND (requested_at AT TIME ZONE %s)::date > start_date AS late_filed
              FROM hr.leave_request
             WHERE status = 'APPROVED' AND employee_id IN (:ids) AND start_date < :to AND end_date >= :from
            """.formatted(BKK), range(ids, year),
            (rs, i) -> new Req(rs.getLong("employee_id"), rs.getString("leave_type_code"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getBigDecimal("total_days"), rs.getBigDecimal("unpaid_days"),
                rs.getBoolean("sick_edge"), rs.getBoolean("late_filed")));

        record Key(long employeeId, String month, String type) {}
        class Acc {
            BigDecimal days = BigDecimal.ZERO, edge = BigDecimal.ZERO, unpaid = BigDecimal.ZERO;
            int requests, lateFiled;
        }
        Map<Key, Acc> acc = new LinkedHashMap<>();
        for (Req r : reqs) {
            Map<YearMonth, BigDecimal> days = apportion(r.start(), r.end(), r.total(), year);
            Map<YearMonth, BigDecimal> unpaid = apportion(r.start(), r.end(), r.unpaid(), year);
            days.forEach((m, d) -> {
                Acc a = acc.computeIfAbsent(new Key(r.employeeId(), m.toString(), r.type()), k -> new Acc());
                a.days = a.days.add(d);
                a.unpaid = a.unpaid.add(unpaid.get(m));
            });
            if (r.start().getYear() == year) {
                Acc a = acc.get(new Key(r.employeeId(), YearMonth.from(r.start()).toString(), r.type()));
                a.requests++;
                if (r.lateFiled()) a.lateFiled++;
                if (r.sickEdge()) a.edge = a.edge.add(r.total());
            }
        }
        return acc.entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<Key, Acc> e) -> e.getKey().employeeId()).thenComparing(e -> e.getKey().month()).thenComparing(e -> e.getKey().type()))
            .map(e -> new LeaveRow(e.getKey().employeeId(), e.getKey().month(), e.getKey().type(), e.getValue().days, e.getValue().requests,
                e.getValue().edge, e.getValue().lateFiled, e.getValue().unpaid))
            .toList();
    }

    /**
     * Splits amount ONCE over every month [start, end] touches, in any year, by calendar-day share, then keeps only the
     * year's months. Rounding is cumulative (each month takes round2(amount * daysSoFar / span) minus what earlier months
     * took), so parts are never negative, always sum to the amount, and do not depend on which year was asked for.
     */
    static Map<YearMonth, BigDecimal> apportion(LocalDate start, LocalDate end, BigDecimal amount, int year) {
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        Map<YearMonth, BigDecimal> out = new LinkedHashMap<>();
        BigDecimal taken = BigDecimal.ZERO;
        long days = 0;
        for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
            LocalDate a = m.atDay(1).isBefore(start) ? start : m.atDay(1);
            LocalDate b = m.atEndOfMonth().isAfter(end) ? end : m.atEndOfMonth();
            days += ChronoUnit.DAYS.between(a, b) + 1;
            BigDecimal cumulative = amount.multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(span), 2, RoundingMode.HALF_UP);
            if (m.getYear() == year) out.put(m, cumulative.subtract(taken));
            taken = cumulative;
        }
        return out;
    }

    public List<QuotaUsage> quotaUsage(Collection<Long> ids, int year) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("""
            SELECT r.employee_id, r.leave_type_code, SUM(q.total_days) AS days
              FROM hr.leave_request_quota_year q JOIN hr.leave_request r ON r.leave_request_id = q.leave_request_id
             WHERE r.status = 'APPROVED' AND r.employee_id IN (:ids) AND q.quota_year = :year
             GROUP BY r.employee_id, r.leave_type_code
             ORDER BY r.employee_id, r.leave_type_code
            """, new MapSqlParameterSource("ids", ids).addValue("year", year),
            (rs, i) -> new QuotaUsage(rs.getLong("employee_id"), rs.getString("leave_type_code"), rs.getBigDecimal("days")));
    }

    public List<AttendanceRow> attendance(Collection<Long> ids, int year) {
        if (ids.isEmpty()) return List.of();
        // Late and early-leave figures skip every day an approved leave covers: PeopleInsightsService adds those days back through LeaveDayRule (leaveDays).
        return jdbc.query("""
            SELECT employee_id, to_char(work_date, 'YYYY-MM') AS month,
                   COUNT(*) FILTER (WHERE check_in IS NOT NULL) AS scan_days,
                   COUNT(*) FILTER (WHERE late_minutes > 0 AND NOT on_leave) AS late_days,
                   COALESCE(SUM(late_minutes) FILTER (WHERE NOT on_leave), 0) AS late_minutes,
                   COUNT(*) FILTER (WHERE check_in IS NOT NULL AND check_out IS NULL) AS no_check_out,
                   COUNT(*) FILTER (WHERE early_leave_minutes > 0 AND NOT on_leave) AS early_leave,
                   COUNT(*) FILTER (WHERE check_in IS NOT NULL AND (check_in AT TIME ZONE %1$s)::time < TIME '12:00') AS morning_scan_days,
                   AVG(EXTRACT(HOUR FROM check_in AT TIME ZONE %1$s) * 60 + EXTRACT(MINUTE FROM check_in AT TIME ZONE %1$s))
                       FILTER (WHERE check_in IS NOT NULL AND (check_in AT TIME ZONE %1$s)::time < TIME '12:00') AS avg_in
              FROM (SELECT d.*, EXISTS (SELECT 1 FROM hr.leave_request r WHERE r.employee_id = d.employee_id AND r.status = 'APPROVED'
                                          AND d.work_date BETWEEN r.start_date AND r.end_date) AS on_leave
                      FROM hr.attendance_daily d
                     WHERE d.employee_id IN (:ids) AND d.work_date >= :from AND d.work_date < :to) d
             GROUP BY employee_id, month ORDER BY employee_id, month
            """.formatted(BKK), range(ids, year),
            (rs, i) -> new AttendanceRow(rs.getLong("employee_id"), rs.getString("month"), rs.getInt("scan_days"),
                rs.getInt("late_days"), rs.getInt("late_minutes"), rs.getInt("no_check_out"), rs.getInt("early_leave"), rs.getInt("morning_scan_days"),
                rs.getObject("avg_in") == null ? null : rs.getDouble("avg_in")));
    }

    /** One row per (attendance day, APPROVED leave covering it) where the day has stored late or early-leave minutes; the day rule turns these into the real figures. */
    public record LeaveDayRow(long employeeId, LocalDate workDate, OffsetDateTime checkIn, OffsetDateTime checkOut, int lateMinutes,
                              int earlyLeaveMinutes, Long divisionId, Long departmentId, LocalDate leaveStart, LocalDate leaveEnd,
                              LocalTime startTime, LocalTime endTime) {}

    public List<LeaveDayRow> leaveDays(Collection<Long> ids, int year) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("""
            SELECT d.employee_id, d.work_date, d.check_in, d.check_out, d.late_minutes, d.early_leave_minutes,
                   e.division_id, e.department_id, r.start_date, r.end_date, r.start_time, r.end_time
              FROM hr.attendance_daily d
              JOIN hr.employee e ON e.employee_id = d.employee_id
              JOIN hr.leave_request r ON r.employee_id = d.employee_id AND r.status = 'APPROVED'
                                     AND d.work_date BETWEEN r.start_date AND r.end_date
             WHERE d.employee_id IN (:ids) AND d.work_date >= :from AND d.work_date < :to
               AND (d.late_minutes > 0 OR d.early_leave_minutes > 0)
             ORDER BY d.employee_id, d.work_date
            """, range(ids, year),
            (rs, i) -> new LeaveDayRow(rs.getLong("employee_id"), rs.getObject("work_date", LocalDate.class),
                rs.getObject("check_in", OffsetDateTime.class), rs.getObject("check_out", OffsetDateTime.class),
                rs.getInt("late_minutes"), rs.getInt("early_leave_minutes"),
                nullableId(rs.getObject("division_id")), nullableId(rs.getObject("department_id")),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class)));
    }

    public List<LeaveType> leaveTypes(Collection<String> codes) {
        if (codes.isEmpty()) return List.of();
        return jdbc.query("SELECT leave_type_code, name_th, annual_quota_days FROM hr.leave_type WHERE leave_type_code IN (:codes) ORDER BY leave_type_code",
            new MapSqlParameterSource("codes", codes),
            (rs, i) -> new LeaveType(rs.getString("leave_type_code"), rs.getString("name_th"), rs.getBigDecimal("annual_quota_days")));
    }

    /** The latest day any attendance was calculated, or null when there is none. */
    public LocalDate latestAttendanceDate() {
        return jdbc.queryForObject("SELECT MAX(work_date) FROM hr.attendance_daily", Map.of(), LocalDate.class);
    }

    private static Long nullableId(Object id) {
        return id == null ? null : ((Number) id).longValue();
    }

    private static MapSqlParameterSource range(Collection<Long> ids, int year) {
        return new MapSqlParameterSource("ids", ids)
            .addValue("from", LocalDate.of(year, 1, 1)).addValue("to", LocalDate.of(year + 1, 1, 1));
    }
}
