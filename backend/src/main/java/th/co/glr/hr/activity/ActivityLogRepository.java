package th.co.glr.hr.activity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code hr.activity_log}. Mirrors V157. */
@Repository
public class ActivityLogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ActivityLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts one batch. Called only from {@link ActivityLogRecorder}'s single writer thread. */
    public void insertAll(List<ActivityLogEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = entries.stream()
            .map(entry -> new MapSqlParameterSource()
                .addValue("employeeId", entry.employeeId())
                .addValue("actorEmail", entry.actorEmail())
                .addValue("method", entry.method())
                .addValue("path", entry.path())
                .addValue("status", entry.status())
                .addValue("durationMs", entry.durationMs())
                .addValue("at", entry.at()))
            .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
            INSERT INTO hr.activity_log
                (employee_id, actor_email, method, path, status, duration_ms, at)
            VALUES
                (:employeeId, :actorEmail, :method, :path, :status, :durationMs, :at)
            """, batch);
    }

    /**
     * Newest-first activity across every employee, optionally narrowed to one of them.
     *
     * <p>{@code limit} is applied by this repository and is caller-supplied but clamped by
     * {@link ActivityLogService}; the ORDER BY is part of the contract, not a detail — the same
     * limit under a different sort truncates a different set of rows, which is the bug shape
     * CLAUDE.md's mock-contract section calls out. Mirror both if this is ever mocked.
     */
    public List<ActivityLogEntryDto> findRecent(OffsetDateTime from, OffsetDateTime to,
                                                Long employeeId, int limit) {
        Map<String, Object> params = Map.of(
            "from", from, "to", to,
            "employeeId", employeeId == null ? -1L : employeeId,
            "filterByEmployee", employeeId != null,
            "limit", limit);
        return jdbc.query("""
            SELECT a.id, a.employee_id, a.actor_email, a.method, a.path, a.status,
                   a.duration_ms, a.at,
                   e.employee_code,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS name_th
              FROM hr.activity_log a
              LEFT JOIN hr.employee e ON e.employee_id = a.employee_id
             WHERE a.at >= :from
               AND a.at <  :to
               AND (NOT :filterByEmployee OR a.employee_id = :employeeId)
             ORDER BY a.at DESC, a.id DESC
             LIMIT :limit
            """, params, (rs, rowNum) -> new ActivityLogEntryDto(
                rs.getLong("id"),
                rs.getObject("employee_id", Long.class),
                rs.getString("employee_code"),
                rs.getString("name_th"),
                rs.getString("actor_email"),
                rs.getString("method"),
                rs.getString("path"),
                rs.getInt("status"),
                rs.getObject("duration_ms", Integer.class),
                rs.getObject("at", OffsetDateTime.class)));
    }

    /** One row per employee active in the window, for the summary the page opens on. */
    public List<ActivityLogSummaryDto> summarize(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.query("""
            SELECT a.employee_id, e.employee_code,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS name_th,
                   MIN(a.actor_email)  AS actor_email,
                   COUNT(*)            AS request_count,
                   MIN(a.at)           AS first_seen,
                   MAX(a.at)           AS last_seen
              FROM hr.activity_log a
              LEFT JOIN hr.employee e ON e.employee_id = a.employee_id
             WHERE a.at >= :from
               AND a.at <  :to
               AND a.employee_id IS NOT NULL
             GROUP BY a.employee_id, e.employee_code, name_th
             ORDER BY MAX(a.at) DESC
            """, Map.of("from", from, "to", to), (rs, rowNum) -> new ActivityLogSummaryDto(
                rs.getLong("employee_id"),
                rs.getString("employee_code"),
                rs.getString("name_th"),
                rs.getString("actor_email"),
                rs.getLong("request_count"),
                rs.getObject("first_seen", OffsetDateTime.class),
                rs.getObject("last_seen", OffsetDateTime.class)));
    }

    /**
     * Semantic actions from {@code hr.audit_log} — who requested what, who approved what.
     *
     * <p>Joins twice on purpose: {@code actor} is who performed the action, and {@code subject} is
     * the employee the row is ABOUT when the entity is an employee-scoped one. Without the second
     * join an approval reads "ฟ้าใส approved request 7", which is not the question anyone asks.
     * {@code before_json}/{@code after_json} are deliberately NOT selected — they can hold salary
     * and other payroll detail, and this endpoint exists to show activity, not to become a second
     * way to read compensation.
     */
    public List<AuditEventDto> findAuditEvents(OffsetDateTime from, OffsetDateTime to,
                                               Long employeeId, int limit) {
        Map<String, Object> params = Map.of(
            "from", from, "to", to,
            "employeeId", employeeId == null ? -1L : employeeId,
            "filterByEmployee", employeeId != null,
            "limit", limit);
        return jdbc.query("""
            SELECT a.id, a.actor_user_id, a.actor_email, a.action, a.entity, a.entity_id, a.at,
                   actor.employee_code AS actor_code,
                   NULLIF(TRIM(CONCAT_WS(' ', actor.first_name_th, actor.last_name_th)), '') AS actor_name,
                   NULLIF(TRIM(CONCAT_WS(' ', subject.first_name_th, subject.last_name_th)), '') AS subject_name
              FROM hr.audit_log a
              LEFT JOIN hr.employee actor   ON actor.employee_id = a.actor_user_id
              LEFT JOIN hr.employee subject ON a.entity = 'employee' AND subject.employee_id = a.entity_id
             WHERE a.at >= :from
               AND a.at <  :to
               AND (NOT :filterByEmployee OR a.actor_user_id = :employeeId)
             ORDER BY a.at DESC, a.id DESC
             LIMIT :limit
            """, params, (rs, rowNum) -> new AuditEventDto(
                rs.getLong("id"),
                rs.getObject("actor_user_id", Long.class),
                rs.getString("actor_code"),
                rs.getString("actor_name"),
                rs.getString("actor_email"),
                rs.getString("action"),
                rs.getString("entity"),
                rs.getObject("entity_id", Long.class),
                rs.getString("subject_name"),
                rs.getObject("at", OffsetDateTime.class)));
    }

    /**
     * Inserts one batch of application events. Called only from {@link AppEventWriter}'s single
     * writer thread.
     */
    public void insertAppEvents(List<AppEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = events.stream()
            .map(event -> new MapSqlParameterSource()
                .addValue("at", event.at())
                .addValue("kind", event.kind())
                .addValue("level", event.level())
                .addValue("logger", event.logger())
                .addValue("message", event.message() == null ? "" : event.message())
                .addValue("exceptionType", event.exceptionType())
                .addValue("exceptionMessage", event.exceptionMessage())
                .addValue("firstFrame", event.firstFrame())
                .addValue("correlationId", event.correlationId())
                .addValue("thread", event.thread())
                .addValue("durationMs", event.durationMs()))
            .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
            INSERT INTO hr.app_event
                (at, kind, level, logger, message, exception_type, exception_message,
                 first_frame, correlation_id, thread, duration_ms)
            VALUES
                (:at, :kind, :level, :logger, :message, :exceptionType, :exceptionMessage,
                 :firstFrame, :correlationId, :thread, :durationMs)
            """, batch);
    }

    /**
     * WARN/ERROR events and job runs, newest first, optionally narrowed to one kind.
     *
     * <p>Selects {@code first_frame} but never a full trace, because no full trace is stored —
     * see V159. If a column ever appears here holding one, that is a data-exposure change.
     */
    public List<AppEventDto> findAppEvents(OffsetDateTime from, OffsetDateTime to,
                                           String kind, int limit) {
        Map<String, Object> params = Map.of(
            "from", from, "to", to,
            "kind", kind == null ? "" : kind,
            "filterByKind", kind != null && !kind.isBlank(),
            "limit", limit);
        return jdbc.query("""
            SELECT id, at, kind, level, logger, message, exception_type, exception_message,
                   first_frame, correlation_id, duration_ms
              FROM hr.app_event
             WHERE at >= :from
               AND at <  :to
               AND (NOT :filterByKind OR kind = :kind)
             ORDER BY at DESC, id DESC
             LIMIT :limit
            """, params, (rs, rowNum) -> new AppEventDto(
                rs.getLong("id"),
                rs.getObject("at", OffsetDateTime.class),
                rs.getString("kind"),
                rs.getString("level"),
                rs.getString("logger"),
                rs.getString("message"),
                rs.getString("exception_type"),
                rs.getString("exception_message"),
                rs.getString("first_frame"),
                rs.getString("correlation_id"),
                rs.getObject("duration_ms", Integer.class)));
    }

    /**
     * Live read of the admin capability.
     *
     * <p>Deliberately not cached on {@link th.co.glr.hr.auth.UserPrincipal}: the principal is
     * built once at login and lives in the session, so a cached copy would keep a revoked admin
     * privileged until they happened to log out. One extra query on admin-only endpoints is a
     * cheap price for revocation taking effect immediately.
     */
    public boolean isAdmin(long employeeId) {
        // EXISTS rather than SELECT is_admin ... WHERE employee_id = :id, because the latter
        // throws EmptyResultDataAccessException for a deleted or deactivated employee — turning
        // "not an admin" into a 500. EXISTS always returns exactly one row, so the deny is a
        // plain false.
        Boolean admin = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM hr.employee
                 WHERE employee_id = :id AND is_active AND is_admin
            )
            """, Map.of("id", employeeId), Boolean.class);
        return Boolean.TRUE.equals(admin);
    }
}
