package th.co.glr.hr.dashboard;

import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final NamedParameterJdbcTemplate jdbc;
    private final SessionContext sessions;

    public DashboardController(NamedParameterJdbcTemplate jdbc, SessionContext sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @GetMapping("/summary")
    DashboardSummaryDto summary(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        Long createdByFilter = "sales".equals(user.role()) ? user.id() : null;
        return jdbc.queryForObject("""
            SELECT
                COUNT(*) FILTER (WHERE status NOT IN ('closed','cancelled'))          AS total_open,
                COUNT(*) FILTER (WHERE status = 'submitted')                          AS submitted,
                COUNT(*) FILTER (WHERE status = 'in_review')                          AS in_review,
                COUNT(*) FILTER (WHERE status = 'price_proposed')                     AS price_proposed,
                COUNT(*) FILTER (WHERE status = 'approved')                           AS approved,
                COUNT(*) FILTER (WHERE status = 'quotation_issued')                   AS quotation_issued,
                COUNT(*) FILTER (WHERE status = 'closed'
                    AND closed_at >= DATE_TRUNC('month', now()))                       AS closed_this_month,
                COUNT(*) FILTER (WHERE status = 'cancelled'
                    AND updated_at >= DATE_TRUNC('month', now()))                      AS cancelled_this_month,
                COUNT(*) FILTER (WHERE status NOT IN ('closed','cancelled','draft')
                    AND created_at < now() - INTERVAL '3 days')                       AS overdue_over_3days
              FROM sales.ticket
             WHERE (:createdBy::bigint IS NULL OR created_by = :createdBy)
            """,
            new MapSqlParameterSource("createdBy", createdByFilter),
            (rs, rowNum) -> new DashboardSummaryDto(
                rs.getLong("total_open"),
                rs.getLong("submitted"),
                rs.getLong("in_review"),
                rs.getLong("price_proposed"),
                rs.getLong("approved"),
                rs.getLong("quotation_issued"),
                rs.getLong("closed_this_month"),
                rs.getLong("cancelled_this_month"),
                rs.getLong("overdue_over_3days")
            ));
    }
}
