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
    private static final Map<String, String> TICKET_EVENT_TITLES = Map.ofEntries(
        Map.entry("SUBMITTED", "มีคำขอราคาใหม่"),
        Map.entry("PRICE_PROPOSED", "รอการอนุมัติราคา"),
        Map.entry("APPROVED", "ราคาได้รับการอนุมัติ"),
        Map.entry("REJECTED", "ราคาถูกตีกลับ"),
        Map.entry("REVISION_REQUESTED", "ขอแก้ไขเอกสาร"),
        // PricingRequestService.submit()/pickup() — added distinct from the
        // legacy "SUBMITTED" entry above (which collides with
        // TicketEventKind.SUBMITTED) so a pricing-request notification is no
        // longer indistinguishable from a ticket-submitted one.
        Map.entry("PRICING_REQUEST_SUBMITTED", "มีคำขอราคาใหม่"),
        Map.entry("PRICING_REQUEST_REVISED", "คำขอราคามี revision ใหม่"),
        Map.entry("PICKED_UP", "คำขอราคาถูกรับเรื่องแล้ว"),
        Map.entry("FACTORY_EMAIL_READY", "ร่างอีเมลโรงงานพร้อมตรวจ"),
        Map.entry("FACTORY_EMAIL_SENT", "ส่งคำขอโรงงานแล้ว"),
        Map.entry("FACTORY_RESPONSE_RECEIVED", "ได้รับราคาโรงงานแล้ว"),
        Map.entry("FACTORY_NEGOTIATION_STARTED", "เริ่มเจรจากับโรงงาน"),
        Map.entry("FACTORY_RESPONSE_READY_FOR_COSTING", "ราคาโรงงานพร้อมคำนวณต้นทุน"),
        Map.entry("FACTORY_RESPONSE_REVISED", "ราคาโรงงานมีฉบับแก้ไข"),
        Map.entry("FACTORY_NOT_AVAILABLE", "โรงงานไม่สามารถเสนอราคาได้"),
        Map.entry("PRICING_COSTING_STARTED", "เริ่มร่างต้นทุน"),
        Map.entry("PRICING_COSTING_CALCULATED", "คำนวณต้นทุนแล้ว"),
        Map.entry("PRICING_COSTING_SUBMITTED", "ส่งต้นทุนให้ CEO แล้ว"),
        Map.entry("PRICING_DECISION_STARTED", "CEO เริ่มพิจารณาราคาขาย"),
        Map.entry("PRICING_DECISION_APPROVED", "ราคาขายได้รับการอนุมัติแล้ว"),
        Map.entry("PRICING_DECISION_RETURNED", "CEO ตีกลับให้แก้ไขต้นทุน"),
        Map.entry("CUSTOMER_QUOTATION_ISSUED", "ออกใบเสนอราคาลูกค้าแล้ว"),
        Map.entry("CUSTOMER_QUOTATION_CANCELLED", "ใบเสนอราคาลูกค้าถูกยกเลิก")
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

    public void notifyEmployeeForPricingRequest(long employeeId, long pricingRequestId, String type, String message) {
        jdbc.update("""
            INSERT INTO hr.notification (employee_id, type, title, message, link)
            VALUES (:employeeId, :type, :title, :message, :link)
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("type", type)
                .addValue("title", ticketEventTitle(type))
                .addValue("message", message)
                .addValue("link", "/pricing-requests/" + pricingRequestId));
    }

    /**
     * Notify all employees whose division maps to the given sales role.
     * Division mapping mirrors DivisionAccessPolicy — extended for sales module roles.
     */
    public void notifyByRole(String role, long ticketId, String type, String message) {
        notifyByRoleInternal(role, type, message, "/tickets/" + ticketId);
    }

    public void notifyByRoleForPricingRequest(String role, long pricingRequestId, String type, String message) {
        notifyByRoleInternal(role, type, message, "/pricing-requests/" + pricingRequestId);
    }

    private void notifyByRoleInternal(String role, String type, String message, String link) {
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
                .addValue("link", link));
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
