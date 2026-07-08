package th.co.glr.hr.notification;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
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
            SELECT n.notification_id, n.employee_id,
                   n.type, n.title, n.message, n.link, n.is_read, n.created_at
              FROM hr.notification n
             WHERE n.employee_id = :employeeId
             ORDER BY n.created_at DESC
             LIMIT 50
            """,
            Map.of("employeeId", employeeId),
            (rs, rowNum) -> mapHrNotification(rs));
    }

    public Optional<NotificationDto> findById(long id) {
        try {
            NotificationDto notification = jdbc.queryForObject("""
                SELECT n.notification_id, n.employee_id,
                       n.type, n.title, n.message, n.link, n.is_read, n.created_at
                  FROM hr.notification n
                 WHERE n.notification_id = :id
                """,
                Map.of("id", id),
                (rs, rowNum) -> mapHrNotification(rs));
            return Optional.ofNullable(notification);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public long insert(long employeeId, String type, String title, String message, String link) {
        Number id = jdbc.queryForObject("""
            INSERT INTO hr.notification (employee_id, type, title, message, link)
            VALUES (:employeeId, :type, :title, :message, :link)
            RETURNING notification_id
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("type", type)
                .addValue("title", title)
                .addValue("message", message)
                .addValue("link", link),
            Number.class);
        return id.longValue();
    }

    public int markRead(long notificationId, long employeeId) {
        return jdbc.update("""
            UPDATE hr.notification SET is_read = TRUE
             WHERE notification_id = :id AND employee_id = :employeeId
            """, Map.of("id", notificationId, "employeeId", employeeId));
    }

    public Optional<String> findEmployeeEmail(long employeeId) {
        try {
            String email = jdbc.queryForObject("""
                SELECT NULLIF(BTRIM(email), '')
                  FROM hr.employee
                 WHERE employee_id = :employeeId
                """, Map.of("employeeId", employeeId), String.class);
            return Optional.ofNullable(email);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
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

    private NotificationDto mapHrNotification(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new NotificationDto(
            rs.getLong("notification_id"),
            rs.getLong("employee_id"),
            null,
            null,
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("message"),
            rs.getString("link"),
            rs.getBoolean("is_read"),
            rs.getTimestamp("created_at").toInstant()
        );
    }
}
