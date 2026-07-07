package th.co.glr.hr.notification;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<NotificationDto> findByEmployeeId(long employeeId) {
        return jdbc.query("""
            SELECT n.notification_id, n.employee_id, n.ticket_id,
                   t.code AS ticket_code,
                   n.type, n.message, n.is_read, n.created_at
              FROM sales.notification n
              LEFT JOIN sales.ticket t ON t.ticket_id = n.ticket_id
             WHERE n.employee_id = :employeeId
             ORDER BY n.created_at DESC
             LIMIT 50
            """,
            Map.of("employeeId", employeeId),
            (rs, rowNum) -> {
                long rawTicketId = rs.getLong("ticket_id");
                Long ticketId = rs.wasNull() ? null : rawTicketId;
                return new NotificationDto(
                    rs.getLong("notification_id"),
                    rs.getLong("employee_id"),
                    ticketId,
                    rs.getString("ticket_code"),
                    rs.getString("type"),
                    rs.getString("message"),
                    rs.getBoolean("is_read"),
                    rs.getTimestamp("created_at").toInstant()
                );
            });
    }

    public int markRead(long notificationId, long employeeId) {
        return jdbc.update("""
            UPDATE sales.notification SET is_read = TRUE
             WHERE notification_id = :id AND employee_id = :employeeId
            """, Map.of("id", notificationId, "employeeId", employeeId));
    }

    public void notifyEmployee(long employeeId, long ticketId, String type, String message) {
        jdbc.update("""
            INSERT INTO sales.notification (employee_id, ticket_id, type, message)
            VALUES (:employeeId, :ticketId, :type, :message)
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("ticketId", ticketId)
                .addValue("type", type)
                .addValue("message", message));
    }

    /**
     * Notify all employees whose division maps to the given sales role.
     * Division mapping mirrors DivisionAccessPolicy — extended for sales module roles.
     */
    public void notifyByRole(String role, long ticketId, String type, String message) {
        String divisionFilter = switch (role) {
            case "import" -> "d.source_code ILIKE 'PCIM%'";
            case "ceo"    -> "d.source_code ILIKE 'MD%' OR d.source_code ILIKE 'MN%'";
            case "sales"  -> "d.source_code ILIKE 'SA%'";
            default -> null;
        };
        if (divisionFilter == null) return;

        jdbc.update("""
            INSERT INTO sales.notification (employee_id, ticket_id, type, message)
            SELECT e.employee_id, :ticketId, :type, :message
              FROM hr.employee e
              JOIN hr.division d ON d.division_id = e.division_id
             WHERE (%s) AND e.is_active = TRUE
            """.formatted(divisionFilter),
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("type", type)
                .addValue("message", message));
    }
}
