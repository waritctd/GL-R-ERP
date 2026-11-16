package th.co.glr.hr.peopleinsights;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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
        return !teamIdsHeadedBy(employeeId).isEmpty();
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

    public List<Long> activeEmployeeIds() {
        return jdbc.queryForList("SELECT employee_id FROM hr.employee WHERE is_active ORDER BY employee_code", Map.of(), Long.class);
    }

    /** Active people below any head of the team, at any depth; heads and detached lines (with everyone under them) left out. */
    public List<Long> teamMemberIds(long teamId) {
        return jdbc.queryForList("""
            WITH RECURSIVE heads AS (
                SELECT employee_id FROM hr.insight_team_head WHERE team_id = :team
            ), line AS (
                SELECT e.employee_id FROM hr.employee e
                  LEFT JOIN hr.insight_employee_setting s ON s.employee_id = e.employee_id
                 WHERE e.reports_to_employee_id IN (SELECT employee_id FROM heads) AND NOT COALESCE(s.detached_line, FALSE)
                UNION
                SELECT e.employee_id FROM hr.employee e
                  JOIN line l ON e.reports_to_employee_id = l.employee_id
                  LEFT JOIN hr.insight_employee_setting s ON s.employee_id = e.employee_id
                 WHERE NOT COALESCE(s.detached_line, FALSE)
            )
            SELECT e.employee_id FROM line JOIN hr.employee e ON e.employee_id = line.employee_id
             WHERE e.is_active AND e.employee_id NOT IN (SELECT employee_id FROM heads)
             ORDER BY e.employee_code
            """, Map.of("team", teamId), Long.class);
    }

    public List<Person> people(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("""
            SELECT e.employee_id, e.employee_code, COALESCE(NULLIF(e.nickname, ''), e.first_name_th) AS nick,
                   COALESCE(s.time_exempt, FALSE) AS time_exempt
              FROM hr.employee e LEFT JOIN hr.insight_employee_setting s ON s.employee_id = e.employee_id
             WHERE e.employee_id IN (:ids) ORDER BY e.employee_code
            """, new MapSqlParameterSource("ids", ids),
            (rs, i) -> new Person(rs.getLong("employee_id"), rs.getString("employee_code"), rs.getString("nick"), rs.getBoolean("time_exempt")));
    }

    public List<LeaveRow> leave(Collection<Long> ids, int year) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("""
            SELECT employee_id, to_char(start_date, 'YYYY-MM') AS month, leave_type_code,
                   SUM(total_days) AS days, COUNT(*) AS requests,
                   COALESCE(SUM(total_days) FILTER (WHERE leave_type_code = 'SICK' AND EXTRACT(ISODOW FROM start_date) IN (1, 5, 6)), 0) AS sick_edge,
                   -- system_note IS NULL: import/backfill rows (filed by HR after the fact) carry a note even on APPROVED rows.
                   -- Sick leave is normally filed after the day, so it is never "late".
                   COUNT(*) FILTER (WHERE leave_type_code <> 'SICK' AND requested_by_id = employee_id AND system_note IS NULL
                                      AND (requested_at AT TIME ZONE %s)::date > start_date) AS late_filed,
                   SUM(unpaid_days) AS unpaid
              FROM hr.leave_request
             WHERE status = 'APPROVED' AND employee_id IN (:ids) AND start_date >= :from AND start_date < :to
             GROUP BY employee_id, month, leave_type_code
             ORDER BY employee_id, month, leave_type_code
            """.formatted(BKK), range(ids, year),
            (rs, i) -> new LeaveRow(rs.getLong("employee_id"), rs.getString("month"), rs.getString("leave_type_code"),
                rs.getBigDecimal("days"), rs.getInt("requests"), rs.getBigDecimal("sick_edge"), rs.getInt("late_filed"),
                rs.getBigDecimal("unpaid")));
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
        return jdbc.query("""
            SELECT employee_id, to_char(work_date, 'YYYY-MM') AS month,
                   COUNT(*) FILTER (WHERE check_in IS NOT NULL) AS scan_days,
                   COUNT(*) FILTER (WHERE late_minutes > 0) AS late_days,
                   COALESCE(SUM(late_minutes), 0) AS late_minutes,
                   COUNT(*) FILTER (WHERE check_in IS NOT NULL AND check_out IS NULL) AS no_check_out,
                   COUNT(*) FILTER (WHERE early_leave_minutes > 0) AS early_leave,
                   COUNT(*) FILTER (WHERE check_in IS NOT NULL AND (check_in AT TIME ZONE %1$s)::time < TIME '12:00') AS morning_scan_days,
                   AVG(EXTRACT(HOUR FROM check_in AT TIME ZONE %1$s) * 60 + EXTRACT(MINUTE FROM check_in AT TIME ZONE %1$s))
                       FILTER (WHERE check_in IS NOT NULL AND (check_in AT TIME ZONE %1$s)::time < TIME '12:00') AS avg_in
              FROM hr.attendance_daily
             WHERE employee_id IN (:ids) AND work_date >= :from AND work_date < :to
             GROUP BY employee_id, month ORDER BY employee_id, month
            """.formatted(BKK), range(ids, year),
            (rs, i) -> new AttendanceRow(rs.getLong("employee_id"), rs.getString("month"), rs.getInt("scan_days"),
                rs.getInt("late_days"), rs.getInt("late_minutes"), rs.getInt("no_check_out"), rs.getInt("early_leave"), rs.getInt("morning_scan_days"),
                rs.getObject("avg_in") == null ? null : rs.getDouble("avg_in")));
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

    private static MapSqlParameterSource range(Collection<Long> ids, int year) {
        return new MapSqlParameterSource("ids", ids)
            .addValue("from", LocalDate.of(year, 1, 1)).addValue("to", LocalDate.of(year + 1, 1, 1));
    }
}
