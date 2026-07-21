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

    // Ticket-event types are short machine codes (e.g. "PRICE_PROPOSED"); hr.notification.title is
    // NOT NULL and human-facing, so map each to a short Thai label. Unmapped types (new ticket event
    // kinds added later) fall back to a generic title rather than failing the insert.
    private static final Map<String, String> TICKET_EVENT_TITLES = Map.of(
        "SUBMITTED", "มีคำขอราคาใหม่",
        "PRICE_PROPOSED", "รอการอนุมัติราคา",
        "APPROVED", "ราคาได้รับการอนุมัติ",
        "REJECTED", "ราคาถูกตีกลับ",
        "REVISION_REQUESTED", "ขอแก้ไขเอกสาร",
        // PricingRequestService.submit()/pickup() — added distinct from the
        // legacy "SUBMITTED" entry above (which collides with
        // TicketEventKind.SUBMITTED) so a pricing-request notification is no
        // longer indistinguishable from a ticket-submitted one.
        "PRICING_REQUEST_SUBMITTED", "มีคำขอราคาใหม่",
        "PICKED_UP", "คำขอราคาถูกรับเรื่องแล้ว"
    );

    public void notifyEmployee(long employeeId, long ticketId, String type, String message) {
        jdbc.update("""
            INSERT INTO hr.notification (employee_id, type, title, message, link)
            VALUES (:employeeId, :type, :title, :message, :link)
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("type", type)
                .addValue("title", ticketEventTitle(type))
                .addValue("message", message)
                .addValue("link", "/tickets/" + ticketId));
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
            INSERT INTO hr.notification (employee_id, type, title, message, link)
            SELECT e.employee_id, :type, :title, :message, :link
              FROM hr.employee e
              JOIN hr.division d ON d.division_id = e.division_id
             WHERE (%s) AND e.is_active = TRUE
            """.formatted(divisionFilter),
            new MapSqlParameterSource()
                .addValue("type", type)
                .addValue("title", ticketEventTitle(type))
                .addValue("message", message)
                .addValue("link", "/tickets/" + ticketId));
    }

    private String ticketEventTitle(String type) {
        return TICKET_EVENT_TITLES.getOrDefault(type, "อัปเดตสถานะใบขอราคา");
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
