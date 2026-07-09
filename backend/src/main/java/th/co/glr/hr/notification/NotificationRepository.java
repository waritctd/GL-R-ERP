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
        return findEmployeeContact(employeeId).map(EmployeeContact::email);
    }

    public Optional<EmployeeContact> findEmployeeContact(long employeeId) {
        try {
            EmployeeContact contact = jdbc.queryForObject("""
                SELECT NULLIF(TRIM(CONCAT_WS(' ', first_name_th, last_name_th)), '') AS name,
                       NULLIF(BTRIM(email), '') AS email
                  FROM hr.employee
                 WHERE employee_id = :employeeId
                """,
                Map.of("employeeId", employeeId),
                (rs, rowNum) -> new EmployeeContact(rs.getString("name"), rs.getString("email")));
            return Optional.ofNullable(contact);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /**
     * Active employee IDs whose division maps to the given sales role, for role-based notification
     * fan-out (e.g. ticket events). Division mapping mirrors DivisionAccessPolicy, extended for sales
     * module roles. Callers (NotificationService.notifyByRole) drive each ID through notify() so the
     * in-app row and the optional email go through the one shared insert+send path — this repository
     * only resolves *who*, it doesn't write the notification itself.
     */
    public List<Long> findActiveEmployeeIdsByRole(String role) {
        String divisionFilter = switch (role) {
            case "import" -> "d.source_code ILIKE 'PCIM%'";
            case "ceo"    -> "d.source_code ILIKE 'MD%' OR d.source_code ILIKE 'MN%'";
            case "sales"  -> "d.source_code ILIKE 'SA%'";
            case "hr"     -> "d.source_code ILIKE 'HR%'";
            default -> null;
        };
        if (divisionFilter == null) return List.of();

        return jdbc.query("""
            SELECT e.employee_id
              FROM hr.employee e
              JOIN hr.division d ON d.division_id = e.division_id
             WHERE (%s) AND e.is_active = TRUE
            """.formatted(divisionFilter),
            Map.of(),
            (rs, rowNum) -> rs.getLong("employee_id"));
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
