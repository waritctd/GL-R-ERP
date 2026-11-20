package th.co.glr.hr.feedback;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.feedback.FeedbackDtos.FeedbackDto;
import th.co.glr.hr.feedback.FeedbackDtos.Requester;

@Repository
public class FeedbackRepository {
    /** Statuses a decided-and-accepted request moves between. */
    static final List<String> ACCEPTED_FAMILY = List.of("ACCEPTED", "IN_PROGRESS", "TESTING", "RELEASED");

    private static final String SELECT = """
        SELECT feedback_id, kind, area, details, extra, impact, page_path, user_agent, viewport,
               reporter_employee_id, reporter_name, reporter_department, reporter_role,
               status, eta, deny_reason, created_at, updated_at
          FROM hr.feedback_request
        """;

    private static final RowMapper<FeedbackDto> MAPPER = (rs, n) -> new FeedbackDto(
        rs.getLong("feedback_id"), rs.getString("kind"), rs.getString("area"), rs.getString("details"),
        rs.getString("extra"), rs.getString("impact"), rs.getString("page_path"), rs.getString("user_agent"),
        rs.getString("viewport"), rs.getLong("reporter_employee_id"), rs.getString("reporter_name"),
        rs.getString("reporter_department"), rs.getString("reporter_role"), rs.getString("status"),
        rs.getObject("eta", LocalDate.class), rs.getString("deny_reason"),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), List.of());

    private final NamedParameterJdbcTemplate jdbc;

    public FeedbackRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long reporterId, String reporterName, String department, String role,
                       FeedbackDtos.SubmitRequest r) {
        return jdbc.queryForObject("""
            INSERT INTO hr.feedback_request (kind, area, details, extra, impact, page_path, user_agent, viewport,
                                             reporter_employee_id, reporter_name, reporter_department, reporter_role)
            VALUES (:kind, :area, :details, :extra, :impact, :pagePath, :userAgent, :viewport,
                    :reporterId, :name, :department, :role)
            RETURNING feedback_id
            """, new MapSqlParameterSource()
            .addValue("kind", r.kind()).addValue("area", r.area()).addValue("details", r.details())
            .addValue("extra", r.extra()).addValue("impact", r.impact()).addValue("pagePath", r.pagePath())
            .addValue("userAgent", r.userAgent()).addValue("viewport", r.viewport())
            .addValue("reporterId", reporterId).addValue("name", reporterName)
            .addValue("department", department).addValue("role", role), Long.class);
    }

    public Optional<FeedbackDto> findById(long id) {
        return withRequesters(jdbc.query(SELECT + " WHERE feedback_id = :id", Map.of("id", id), MAPPER))
            .stream().findFirst();
    }

    /** Requests this person is a requester of (the reporter included). */
    public List<FeedbackDto> findByRequester(long employeeId) {
        return withRequesters(jdbc.query(SELECT + """
             WHERE EXISTS (SELECT 1 FROM hr.feedback_requester r
                            WHERE r.feedback_id = hr.feedback_request.feedback_id AND r.employee_id = :id)
             ORDER BY created_at DESC, feedback_id DESC
            """, Map.of("id", employeeId), MAPPER));
    }

    public List<FeedbackDto> findAll() {
        return withRequesters(jdbc.query(SELECT + " ORDER BY created_at DESC, feedback_id DESC", Map.of(), MAPPER));
    }

    /** Attaches every row's requesters (reporter first, then by added_at) with ONE query. */
    private List<FeedbackDto> withRequesters(List<FeedbackDto> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<Long, List<Requester>> byFeedback = new HashMap<>();
        jdbc.query("""
            SELECT r.feedback_id, r.employee_id, d.name_th AS department,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email) AS name
              FROM hr.feedback_requester r
              JOIN hr.feedback_request f ON f.feedback_id = r.feedback_id
              JOIN hr.employee e ON e.employee_id = r.employee_id
              LEFT JOIN hr.division d ON d.division_id = e.division_id
             WHERE r.feedback_id IN (:ids)
             ORDER BY r.feedback_id, (r.employee_id = f.reporter_employee_id) DESC, r.added_at, r.employee_id
            """, Map.of("ids", rows.stream().map(FeedbackDto::id).toList()), rs -> {
                byFeedback.computeIfAbsent(rs.getLong("feedback_id"), k -> new ArrayList<>()).add(
                    new Requester(rs.getLong("employee_id"), rs.getString("name"), rs.getString("department")));
            });
        return rows.stream().map(r -> r.withRequesters(byFeedback.getOrDefault(r.id(), List.of()))).toList();
    }

    public void addRequester(long feedbackId, long employeeId) {
        jdbc.update("""
            INSERT INTO hr.feedback_requester (feedback_id, employee_id) VALUES (:f, :e) ON CONFLICT DO NOTHING
            """, Map.of("f", feedbackId, "e", employeeId));
    }

    /** Replaces the set, keeping added_at of people already on it. */
    public void replaceRequesters(long feedbackId, List<Long> employeeIds) {
        jdbc.update("DELETE FROM hr.feedback_requester WHERE feedback_id = :f AND employee_id NOT IN (:ids)",
            Map.of("f", feedbackId, "ids", employeeIds));
        jdbc.update("""
            INSERT INTO hr.feedback_requester (feedback_id, employee_id)
            SELECT :f, e FROM unnest(CAST(:ids AS bigint[])) AS e ON CONFLICT DO NOTHING
            """, new MapSqlParameterSource().addValue("f", feedbackId)
            .addValue("ids", employeeIds.toArray(new Long[0])));
    }

    /** How many of these ids are ACTIVE employees. */
    public int countActive(List<Long> employeeIds) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM hr.employee WHERE is_active AND employee_id IN (:ids)",
            Map.of("ids", employeeIds), Integer.class);
    }

    /** Submissions by this reporter in the last hour (rate limit). */
    /** Stamps the first release; true only for the call that stamped it, so the done email goes out once. */
    public boolean markFirstRelease(long id) {
        return jdbc.update("""
            UPDATE hr.feedback_request SET released_at = now() WHERE feedback_id = :id AND released_at IS NULL
            """, Map.of("id", id)) == 1;
    }

    public int countLastHour(long employeeId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.feedback_request
             WHERE reporter_employee_id = :id AND created_at > now() - interval '60 minutes'
            """, Map.of("id", employeeId), Integer.class);
    }

    public String departmentOf(long employeeId) {
        return jdbc.query("""
            SELECT d.name_th FROM hr.employee e JOIN hr.division d ON d.division_id = e.division_id
             WHERE e.employee_id = :id
            """, Map.of("id", employeeId), (rs, n) -> rs.getString(1)).stream().findFirst().orElse(null);
    }

    public List<Long> activeAdminIds() {
        return jdbc.queryForList("SELECT employee_id FROM hr.employee WHERE is_active AND is_admin ORDER BY employee_id",
            Map.of(), Long.class);
    }

    /** Atomic: only a still-PENDING row is decided. Returns false when no row matched. */
    public boolean decide(long id, String status, LocalDate eta, String reason, long adminId) {
        return jdbc.update("""
            UPDATE hr.feedback_request
               SET status = :status, eta = :eta, deny_reason = :reason, decided_by = :admin,
                   decided_at = now(), updated_at = now()
             WHERE feedback_id = :id AND status = 'PENDING'
            """, new MapSqlParameterSource().addValue("id", id).addValue("status", status)
            .addValue("eta", eta).addValue("reason", reason).addValue("admin", adminId)) > 0;
    }

    /** Atomic: only an accepted-family row changes; null status / eta keep the stored value. */
    public boolean update(long id, String status, LocalDate eta) {
        return jdbc.update("""
            UPDATE hr.feedback_request
               SET status = COALESCE(:status, status), eta = COALESCE(:eta, eta), updated_at = now()
             WHERE feedback_id = :id AND status IN (:family)
            """, new MapSqlParameterSource().addValue("id", id).addValue("status", status)
            .addValue("eta", eta).addValue("family", ACCEPTED_FAMILY)) > 0;
    }
}
