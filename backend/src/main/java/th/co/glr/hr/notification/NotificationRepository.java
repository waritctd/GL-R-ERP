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
        Map.entry("CUSTOMER_QUOTATION_CANCELLED", "ใบเสนอราคาลูกค้าถูกยกเลิก"),
        // Step 5: Customer Decision and Commercial Revisions.
        Map.entry("CUSTOMER_QUOTATION_ACCEPTED", "ลูกค้ายอมรับใบเสนอราคาแล้ว"),
        Map.entry("CUSTOMER_QUOTATION_REJECTED", "ลูกค้าปฏิเสธใบเสนอราคา"),
        Map.entry("CUSTOMER_QUOTATION_REVISION_REQUESTED", "ลูกค้าขอแก้ไขใบเสนอราคา"),
        Map.entry("CUSTOMER_QUOTATION_EXPIRED", "ใบเสนอราคาลูกค้าหมดอายุ"),
        // Step 6: Deposit, Payment, and Order Confirmation.
        Map.entry("ORDER_CONFIRMED", "ยืนยันคำสั่งซื้อแล้ว"),
        Map.entry("DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION", "สร้างร่างใบแจ้งยอดเงินรับมัดจำแล้ว"),
        // TicketService.reserveStock — a rep declaring stock coverage on their OWN deal. Without
        // an entry here it would fall through to the generic "อัปเดตสถานะคำขอราคา", which reads as
        // routine pipeline noise; the whole point of this notification is that it is not.
        Map.entry("STOCK_RESERVED", "พนักงานขายประกาศสินค้าจากสต็อกเอง")
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
     * Notify every active employee the given sales role resolves to.
     * Division mapping mirrors DivisionAccessPolicy — extended for sales module roles.
     *
     * <p>{@code import}/{@code ceo}/{@code sales} each resolve to a whole ฝ่าย.
     * {@code sales_manager} does not: it resolves to the ฝ่ายขาย members whose position marks
     * them a ผู้จัดการ, so it is a strict subset of {@code sales} and the two are not
     * interchangeable. An unknown role resolves to nobody and inserts nothing (a silent no-op,
     * as it always has been) — so a typo'd role name notifies no one rather than everyone.
     */
    public void notifyByRole(String role, long ticketId, String type, String message) {
        notifyByRoleInternal(role, type, message, "/tickets/" + ticketId);
    }

    public void notifyByRoleForPricingRequest(String role, long pricingRequestId, String type, String message) {
        notifyByRoleInternal(role, type, message, "/pricing-requests/" + pricingRequestId);
    }

    private void notifyByRoleInternal(String role, String type, String message, String link) {
        String recipientFilter = switch (role) {
            case "import" -> "d.source_code ILIKE 'PCIM%'";
            case "ceo"    -> "d.source_code ILIKE 'MD%' OR d.source_code ILIKE 'MN%'";
            case "sales"  -> "d.source_code ILIKE 'SA%'";
            // The one recipient here that is NOT a whole ฝ่าย. Deliberately identical to
            // CommissionRepository#findSalesManagerApproverEmployeeIds — the same people who
            // already sign a rep's commission off are the ones who supervise a rep's commission
            // INPUTS (TicketService.reserveStock). Two different answers to "who is the sales
            // manager" is the drift worth avoiding; if that predicate ever moves, move this one
            // with it. Note "sales" above would fan out to the reps themselves, including the one
            // who just declared, so it is not a substitute.
            case "sales_manager" -> "d.source_code ILIKE 'SA%' AND p.name_th LIKE '%ผู้จัดการ%'";
            default -> null;
        };
        if (recipientFilter == null) return;

        // LEFT JOIN, not JOIN: only the sales_manager branch reads hr.position, and an employee
        // with a null position_id must stay reachable by the three division-only branches exactly
        // as before. (An inner join here would silently un-notify them.)
        jdbc.update("""
            INSERT INTO hr.notification (employee_id, type, title, message, link)
            SELECT e.employee_id, :type, :title, :message, :link
              FROM hr.employee e
              JOIN hr.division d ON d.division_id = e.division_id
              LEFT JOIN hr.position p ON p.position_id = e.position_id
             WHERE (%s) AND e.is_active = TRUE
            """.formatted(recipientFilter),
            new MapSqlParameterSource()
                .addValue("type", type)
                .addValue("title", ticketEventTitle(type))
                .addValue("message", message)
                .addValue("link", link));
    }

    private String ticketEventTitle(String type) {
        return TICKET_EVENT_TITLES.getOrDefault(type, "อัปเดตสถานะคำขอราคา");
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
