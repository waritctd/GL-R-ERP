package th.co.glr.hr.feedback;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.feedback.FeedbackDtos.FeedbackDto;

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
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

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
        return jdbc.query(SELECT + " WHERE feedback_id = :id", Map.of("id", id), MAPPER).stream().findFirst();
    }

    public List<FeedbackDto> findByReporter(long employeeId) {
        return jdbc.query(SELECT + " WHERE reporter_employee_id = :id ORDER BY created_at DESC, feedback_id DESC",
            Map.of("id", employeeId), MAPPER);
    }

    public List<FeedbackDto> findAll() {
        return jdbc.query(SELECT + " ORDER BY created_at DESC, feedback_id DESC", Map.of(), MAPPER);
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
