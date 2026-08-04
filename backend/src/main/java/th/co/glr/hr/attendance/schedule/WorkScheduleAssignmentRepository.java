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

/**
 * Reads {@code hr.work_schedule} / {@code hr.work_schedule_day} / {@code hr.work_schedule_assignment}
 * (V115).
 *
 * <p>{@link #findAllAssignments()} loads the <strong>entire</strong> assignment table in one query
 * rather than resolving per employee-day. That table is a small, HR-edited configuration table
 * (one row per scope — a handful of divisions/departments plus occasional employee overrides), not
 * one that scales with attendance volume, so a full read is cheap and — critically — bounded,
 * unlike a query issued once per employee-day would be. {@link TieredWorkScheduleResolver} caches
 * the result for a bounded TTL rather than the lifetime of the bean — today's assignments are edited
 * only via a forward-only migration or direct SQL (there is deliberately no admin CRUD UI in this
 * branch), so a restart is not guaranteed after a change, and the TTL is what makes such a change
 * take effect on its own. See {@link TieredWorkScheduleResolver}'s class javadoc for the full design
 * (including {@link TieredWorkScheduleResolver#invalidate()}, the seam a future admin UI should use
 * for immediate effect).
 */
@Repository
public class WorkScheduleAssignmentRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ZoneId zone;

    public WorkScheduleAssignmentRepository(NamedParameterJdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.zone = parseZone(properties.getAttendance().getSchedule().getZone());
    }

    /**
     * Every assignment row, joined to its schedule and that schedule's workdays, ordered
     * <strong>most recent {@code effective_from} first, ties broken by {@code assignment_id}
     * descending</strong> — this is part of this method's contract, not an incidental detail.
     *
     * <p>Nothing in the V115 schema stops two rows existing for the same
     * {@code (scope_type, scope_id)} with overlapping effective ranges — there is deliberately no
     * admin UI in this branch, so a corrected schedule is added by hand-written SQL, and closing
     * out the previous row is a step a human can forget. {@link TieredWorkScheduleResolver}'s
     * per-tier loop returns the <em>first</em> row that matches scope and covers the date, so
     * without an explicit order here, an overlap's winner would depend on Postgres's physical row
     * order — which can change after a {@code VACUUM} or a plan change, with no error and no signal
     * that anything was wrong. The ordering below makes the newest {@code effective_from} win, and
     * {@code assignment_id DESC} makes the most recently inserted row win a same-day tie — i.e.
     * last-write-wins, deterministically, regardless of physical storage order.
     */
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

    // ---------------------------------------------------------------------------------------
    // Admin CRUD: catalogue read + assignment list/create/end. See WorkScheduleAssignmentAdminService
    // for the validation/authorization/audit/cache-invalidation this layer deliberately does not do.
    // ---------------------------------------------------------------------------------------

    /** Every {@code hr.work_schedule} row, for a future admin UI's picker. Creating new schedules is
     * out of scope for this branch — this is read-only. */
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

    /** Every {@code hr.work_schedule_assignment} row, joined to its schedule's {@code code}, for the
     * admin list view — unlike {@link #findAllAssignments()} this exposes {@code assignment_id} (so
     * a caller can end a specific row) and is ordered for human reading, not resolver precedence. */
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

    /** Bare row (no schedule join), for {@link WorkScheduleAssignmentAdminService#end} to validate
     * the target exists and read its current {@code effective_from}/{@code effective_to}. */
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

    /** {@code scopeType} comes from a closed Java enum (never string-concatenated), so branching the
     * table/column name per scope is not an injection risk. */
    public boolean scopeExists(ScopeType scopeType, long scopeId) {
        String sql = switch (scopeType) {
            case EMPLOYEE -> "SELECT EXISTS(SELECT 1 FROM hr.employee WHERE employee_id = :id)";
            case DEPARTMENT -> "SELECT EXISTS(SELECT 1 FROM hr.department WHERE department_id = :id)";
            case DIVISION -> "SELECT EXISTS(SELECT 1 FROM hr.division WHERE division_id = :id)";
        };
        Boolean exists = jdbc.queryForObject(sql, new MapSqlParameterSource("id", scopeId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * True when an assignment already exists for this scope starting on or after
     * {@code newEffectiveFrom} that would still overlap the new window
     * [{@code newEffectiveFrom}, {@code newEffectiveTo}] — i.e. a genuinely conflicting, separately
     * planned future change. {@link WorkScheduleAssignmentAdminService#create} rejects the create
     * when this is true rather than silently truncating someone else's already-planned assignment;
     * see that method's javadoc for the full overlap policy (predecessors ARE auto-closed, successors
     * are NOT).
     */
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

    /**
     * Closes (sets {@code effective_to = newEffectiveFrom - 1}) every predecessor assignment for
     * this scope that is still live at {@code newEffectiveFrom} — i.e. {@code effective_from <
     * newEffectiveFrom} and ({@code effective_to IS NULL} or {@code effective_to >= newEffectiveFrom}).
     * This is the "normalise" half of the overlap policy: a routine schedule change (assign a new
     * schedule, the old one closes automatically) is exactly the step V115's own migration comments
     * call out as one a human can forget to do by hand. Returns the closed {@code assignment_id}s for
     * the audit trail.
     */
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

    /** Same element-by-{@link Number} defensiveness as {@link #workdaysOf}, but returning the raw ISO
     * day-of-week ints the catalogue DTO exposes rather than {@link DayOfWeek} — a future admin UI
     * reads these directly, no {@code java.time} translation needed on either side. */
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

    /**
     * {@code day_of_week} is SMALLINT, so pgjdbc materialises {@code array_agg(...)} as
     * {@code Short[]} — but read every element through {@link Number} rather than casting the array
     * itself, so a driver returning {@code Integer[]} (as some do for other integer array widths)
     * does not throw a {@link ClassCastException} here.
     */
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
