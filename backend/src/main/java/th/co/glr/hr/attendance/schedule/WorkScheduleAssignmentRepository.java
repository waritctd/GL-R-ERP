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
 * the result for the lifetime of the bean, the same way {@link CompanyWideWorkScheduleResolver}
 * parses its configured schedule once at construction: today's assignments are edited only via a
 * forward-only migration or direct SQL (there is deliberately no admin CRUD UI in this branch), so
 * picking up a change requires a redeploy either way. A future admin UI must add cache invalidation
 * alongside it — see {@link TieredWorkScheduleResolver}'s class javadoc.
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
                   s.work_start, s.work_end, s.grace_minutes,
                   array_agg(d.day_of_week ORDER BY d.day_of_week) AS days
              FROM hr.work_schedule_assignment a
              JOIN hr.work_schedule s ON s.work_schedule_id = a.work_schedule_id
              LEFT JOIN hr.work_schedule_day d ON d.work_schedule_id = s.work_schedule_id
             GROUP BY a.assignment_id, a.scope_type, a.scope_id, a.effective_from, a.effective_to,
                      s.work_start, s.work_end, s.grace_minutes
             ORDER BY a.effective_from DESC, a.assignment_id DESC
            """, new MapSqlParameterSource(), rs -> {
            WorkSchedule schedule = new WorkSchedule(
                zone,
                rs.getObject("work_start", LocalTime.class),
                rs.getObject("work_end", LocalTime.class),
                rs.getInt("grace_minutes"),
                workdaysOf(rs.getArray("days"))
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
