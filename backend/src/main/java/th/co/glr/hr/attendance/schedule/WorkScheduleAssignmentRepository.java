package th.co.glr.hr.attendance.schedule;

import java.sql.Array;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.config.AppProperties;

/** Reads hr.work_schedule / hr.work_schedule_day / hr.work_schedule_assignment (V115). #findAllAssignments()
 * loads the entire assignment table in one query rather than resolving per employee-day. */
@Repository
public class WorkScheduleAssignmentRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ZoneId zone;

    public WorkScheduleAssignmentRepository(NamedParameterJdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.zone = parseZone(properties.getAttendance().getSchedule().getZone());
    }

    /** Every assignment row, joined to its schedule and that schedule's workdays, ordered most recent
     * effective_from first, ties broken by assignment_id descending — this is part of this method's contract, not
     * an incidental detail. */
    public List<ScheduleAssignment> findAllAssignments() {
        List<ScheduleAssignment> assignments = new ArrayList<>();
        jdbc.query("""
            SELECT a.scope_type, a.scope_id, a.effective_from, a.effective_to,
                   s.work_start, s.work_end, s.grace_minutes, s.requires_check_out,
                   array_agg(d.day_of_week ORDER BY d.day_of_week) AS days
              FROM hr.work_schedule_assignment a
              JOIN hr.work_schedule s ON s.work_schedule_id = a.work_schedule_id
              LEFT JOIN hr.work_schedule_day d ON d.work_schedule_id = s.work_schedule_id
             GROUP BY a.assignment_id, a.scope_type, a.scope_id, a.effective_from, a.effective_to,
                      s.work_start, s.work_end, s.grace_minutes, s.requires_check_out
             ORDER BY a.effective_from DESC, a.assignment_id DESC
            """, new MapSqlParameterSource(), rs -> {
            WorkSchedule schedule = new WorkSchedule(
                zone,
                rs.getObject("work_start", LocalTime.class),
                rs.getObject("work_end", LocalTime.class),
                rs.getInt("grace_minutes"),
                workdaysOf(rs.getArray("days")),
                rs.getBoolean("requires_check_out")
            );
            assignments.add(new ScheduleAssignment(
                ScopeType.valueOf(rs.getString("scope_type")),
                rs.getLong("scope_id"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class),
                schedule
            ));
        });
        return assignments;
    }

    // Admin CRUD: catalogue read + assignment list/create/end. See WorkScheduleAssignmentAdminService for the
    // validation/authorization/audit/cache-invalidation this layer deliberately does not do.

    /** Every hr.work_schedule row, for a future admin UI's picker. Creating new schedules is out of scope for this
     * branch — this is read-only. */
    public List<WorkScheduleSummaryDto> listSchedules() {
        List<WorkScheduleSummaryDto> result = new ArrayList<>();
        jdbc.query("""
            SELECT s.work_schedule_id, s.code, s.name_th, s.work_start, s.work_end, s.grace_minutes,
                   s.requires_check_out,
                   array_agg(d.day_of_week ORDER BY d.day_of_week) AS days
              FROM hr.work_schedule s
              LEFT JOIN hr.work_schedule_day d ON d.work_schedule_id = s.work_schedule_id
             GROUP BY s.work_schedule_id, s.code, s.name_th, s.work_start, s.work_end,
                      s.grace_minutes, s.requires_check_out
             ORDER BY s.code
            """, new MapSqlParameterSource(), rs -> {
            result.add(new WorkScheduleSummaryDto(
                rs.getInt("work_schedule_id"),
                rs.getString("code"),
                rs.getString("name_th"),
                rs.getObject("work_start", LocalTime.class),
                rs.getObject("work_end", LocalTime.class),
                rs.getInt("grace_minutes"),
                rs.getBoolean("requires_check_out"),
                workdaysAsIsoInts(rs.getArray("days"))
            ));
        });
        return result;
    }

    /** Every hr.work_schedule_assignment row, joined to its schedule's code, for the admin list view — unlike
     * #findAllAssignments() this exposes assignment_id (so a caller can end a specific row) and is ordered for
     * human reading, not resolver precedence. */
    public List<WorkScheduleAssignmentDto> listAssignments() {
        List<WorkScheduleAssignmentDto> result = new ArrayList<>();
        jdbc.query("""
            SELECT a.assignment_id, a.scope_type, a.scope_id, a.work_schedule_id, s.code,
                   a.effective_from, a.effective_to
              FROM hr.work_schedule_assignment a
              JOIN hr.work_schedule s ON s.work_schedule_id = a.work_schedule_id
             ORDER BY a.scope_type, a.scope_id, a.effective_from DESC, a.assignment_id DESC
            """, new MapSqlParameterSource(), rs -> { result.add(toAssignmentDto(rs)); });
        return result;
    }

    public Optional<WorkScheduleAssignmentDto> findAssignmentById(int assignmentId) {
        List<WorkScheduleAssignmentDto> rows = jdbc.query("""
            SELECT a.assignment_id, a.scope_type, a.scope_id, a.work_schedule_id, s.code,
                   a.effective_from, a.effective_to
              FROM hr.work_schedule_assignment a
              JOIN hr.work_schedule s ON s.work_schedule_id = a.work_schedule_id
             WHERE a.assignment_id = :id
            """, new MapSqlParameterSource("id", assignmentId), (rs, rowNum) -> toAssignmentDto(rs));
        return rows.stream().findFirst();
    }

    /** Bare row (no schedule join), for WorkScheduleAssignmentAdminService#end to validate the target exists and
     * read its current effective_from/effective_to. */
    public Optional<AssignmentWindow> findWindowById(int assignmentId) {
        List<AssignmentWindow> rows = jdbc.query("""
            SELECT assignment_id, scope_type, scope_id, effective_from, effective_to
              FROM hr.work_schedule_assignment
             WHERE assignment_id = :id
            """,
            new MapSqlParameterSource("id", assignmentId),
            (rs, rowNum) -> new AssignmentWindow(
                rs.getInt("assignment_id"),
                ScopeType.valueOf(rs.getString("scope_type")),
                rs.getLong("scope_id"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class)));
        return rows.stream().findFirst();
    }

    public boolean scheduleExists(int workScheduleId) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM hr.work_schedule WHERE work_schedule_id = :id)",
            new MapSqlParameterSource("id", workScheduleId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** scopeType comes from a closed Java enum (never string-concatenated), so branching the table/column name per
     * scope is not an injection risk. */
    public boolean scopeExists(ScopeType scopeType, long scopeId) {
        String sql = switch (scopeType) {
            case EMPLOYEE -> "SELECT EXISTS(SELECT 1 FROM hr.employee WHERE employee_id = :id)";
            case DEPARTMENT -> "SELECT EXISTS(SELECT 1 FROM hr.department WHERE department_id = :id)";
            case DIVISION -> "SELECT EXISTS(SELECT 1 FROM hr.division WHERE division_id = :id)";
        };
        Boolean exists = jdbc.queryForObject(sql, new MapSqlParameterSource("id", scopeId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** True when an assignment already exists for this scope starting on or after newEffectiveFrom that would still
     * overlap the new window [newEffectiveFrom, newEffectiveTo] — i.e. a genuinely conflicting, separately planned
     * future change. */
    public boolean hasConflictingSuccessor(
            ScopeType scopeType, long scopeId, LocalDate newEffectiveFrom, LocalDate newEffectiveTo) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM hr.work_schedule_assignment
                 WHERE scope_type = :scopeType
                   AND scope_id = :scopeId
                   AND effective_from >= :newFrom
                   AND (CAST(:newTo AS DATE) IS NULL OR effective_from <= CAST(:newTo AS DATE))
            )
            """,
            new MapSqlParameterSource()
                .addValue("scopeType", scopeType.name())
                .addValue("scopeId", scopeId)
                .addValue("newFrom", newEffectiveFrom)
                .addValue("newTo", newEffectiveTo),
            Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** Closes (sets effective_to = newEffectiveFrom - 1) every predecessor assignment for this scope that is still
     * live at newEffectiveFrom — i.e. effective_from < newEffectiveFrom and (effective_to IS NULL or effective_to
     * >= newEffectiveFrom). */
    public List<Integer> closeOverlappingPredecessors(ScopeType scopeType, long scopeId, LocalDate newEffectiveFrom) {
        return jdbc.query("""
            UPDATE hr.work_schedule_assignment
               SET effective_to = :newFrom - 1
             WHERE scope_type = :scopeType
               AND scope_id = :scopeId
               AND effective_from < :newFrom
               AND (effective_to IS NULL OR effective_to >= :newFrom)
            RETURNING assignment_id
            """,
            new MapSqlParameterSource()
                .addValue("scopeType", scopeType.name())
                .addValue("scopeId", scopeId)
                .addValue("newFrom", newEffectiveFrom),
            (rs, rowNum) -> rs.getInt("assignment_id"));
    }

    public int insertAssignment(
            ScopeType scopeType, long scopeId, int workScheduleId, LocalDate effectiveFrom, LocalDate effectiveTo) {
        return jdbc.queryForObject("""
            INSERT INTO hr.work_schedule_assignment
                (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
            VALUES (:scopeType, :scopeId, :workScheduleId, :effectiveFrom, :effectiveTo)
            RETURNING assignment_id
            """,
            new MapSqlParameterSource()
                .addValue("scopeType", scopeType.name())
                .addValue("scopeId", scopeId)
                .addValue("workScheduleId", workScheduleId)
                .addValue("effectiveFrom", effectiveFrom)
                .addValue("effectiveTo", effectiveTo),
            Integer.class);
    }

    /** Returns {@code false} if no row exists for {@code assignmentId} (caller turns that into 404). */
    public boolean endAssignment(int assignmentId, LocalDate effectiveTo) {
        int updated = jdbc.update("""
            UPDATE hr.work_schedule_assignment
               SET effective_to = :effectiveTo
             WHERE assignment_id = :id
            """,
            new MapSqlParameterSource().addValue("effectiveTo", effectiveTo).addValue("id", assignmentId));
        return updated > 0;
    }

    private static WorkScheduleAssignmentDto toAssignmentDto(java.sql.ResultSet rs) throws SQLException {
        return new WorkScheduleAssignmentDto(
            rs.getInt("assignment_id"),
            ScopeType.valueOf(rs.getString("scope_type")),
            rs.getLong("scope_id"),
            rs.getInt("work_schedule_id"),
            rs.getString("code"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class));
    }

    /** Bare {@code hr.work_schedule_assignment} row, no schedule join — see {@link #findWindowById}. */
    public record AssignmentWindow(
        int assignmentId, ScopeType scopeType, long scopeId, LocalDate effectiveFrom, LocalDate effectiveTo
    ) {}

    /** Same element-by-Number defensiveness as #workdaysOf, but returning the raw ISO day-of-week ints the
     * catalogue DTO exposes rather than DayOfWeek — a future admin UI reads these directly, no java.time
     * translation needed on either side. */
    private static List<Integer> workdaysAsIsoInts(Array sqlArray) {
        List<Integer> days = new ArrayList<>();
        if (sqlArray == null) {
            return days;
        }
        try {
            Object[] elements = (Object[]) sqlArray.getArray();
            for (Object element : elements) {
                if (element instanceof Number isoDay) {
                    days.add(isoDay.intValue());
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read hr.work_schedule_day array", ex);
        }
        return days;
    }

    /** day_of_week is SMALLINT, so pgjdbc materialises array_agg(...) as Short[] — but read every element through
     * Number rather than casting the array itself, so a driver returning Integer[] (as some do for other integer
     * array widths) does not throw a ClassCastException here. */
    private static Set<DayOfWeek> workdaysOf(Array sqlArray) {
        Set<DayOfWeek> days = new LinkedHashSet<>();
        if (sqlArray == null) {
            return days;
        }
        try {
            Object[] elements = (Object[]) sqlArray.getArray();
            for (Object element : elements) {
                if (element instanceof Number isoDay) {
                    days.add(DayOfWeek.of(isoDay.intValue()));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read hr.work_schedule_day array", ex);
        }
        return days;
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value.trim());
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "app.attendance.schedule.zone is not a valid zone id: " + value, ex);
        }
    }
}
