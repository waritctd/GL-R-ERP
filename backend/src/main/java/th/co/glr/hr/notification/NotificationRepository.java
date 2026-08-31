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
    /**
     * Sales-pipeline notifications are emailed as well as shown in-app, and these four {@code
     * notify*} methods are the only route every one of them takes — see {@link
     * SalesNotificationMailer} for why the dispatch hangs off the repository rather than the nine
     * sales services. This collaborator decides nothing about mailboxes; it is told what was
     * written and routes from there.
     *
     * <p>There is deliberately <b>no</b> single-argument constructor. Wiring
     * {@link SalesNotificationMailer#NO_OP} is how a test says "in-app only", and it has to say so
     * out loud at the wiring site — a convenience constructor would let a test assert "no mail was
     * sent" against a collaborator that can never send, which is a green test that proves nothing.
     */
    private final SalesNotificationMailer salesMailer;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc, SalesNotificationMailer salesMailer) {
        this.jdbc = jdbc;
        this.salesMailer = salesMailer;
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

    /**
     * Resolves both the email address and display name in one query, so a rich notification email
     * (greeting by name, portal link) doesn't need a second round-trip. Returns the recipient
     * whenever the employee row exists - {@code email} may be {@code null} when the employee has no
     * address on file (an empty-string address is normalised to {@code null} by
     * {@code NULLIF(BTRIM(...), '')} below, same as a true SQL NULL). Returns empty only when there
     * is no employee with that id.
     *
     * <p>The address used to be the gate here too (this method returned empty whenever it was
     * missing, under the old name {@code findEmployeeEmail}). That is deliberately no longer the
     * contract: {@code app.mail.override-to} must be able to rescue an addressless employee by
     * redirecting the notification to a test inbox, and only {@link NotificationEmailService} - the
     * sole owner of that config - knows whether an override is configured. Gating on the address
     * here would make the override unreachable for exactly the employees it exists to rescue, so
     * that decision now lives entirely in {@link NotificationEmailService#send}; this method's job is
     * only to say whether the employee exists. {@code name} on its own may still be {@code null} (no
     * first name on file); callers already fall back to a generic greeting for that.
     *
     * <p><b>{@code name} is first name only</b> (owner ruling, mail-copy wording fix): was {@code
     * CONCAT_WS(' ', first_name_th, last_name_th)} (full name) until this change. Confirmed by
     * tracing every reader of {@link EmailRecipient#name()} before narrowing it -- it flows through
     * {@code NotificationService#notify}/{@code #sendEmailAfterCommit} into {@code
     * NotificationEmailService#send(..., recipientName, ...)}, and inside that class {@code
     * recipientName} is used ONLY to build the {@code textBody}/{@code htmlBody} greeting line
     * ("เรียน คุณ&lt;name&gt;,"); nothing else in this codebase reads {@link EmailRecipient#name()}. A
     * Thai greeting addresses someone by first name after "คุณ" (a title, not "Mr./Ms." -- "คุณสมชาย",
     * not "คุณสมชาย ใจดี"), so the full name read as stiff/translated rather than natural Thai.
     */
    public Optional<EmailRecipient> findEmployeeRecipient(long employeeId) {
        try {
            EmailRecipient recipient = jdbc.queryForObject("""
                SELECT NULLIF(BTRIM(email), '') AS email,
                       NULLIF(BTRIM(first_name_th), '') AS name
                  FROM hr.employee
                 WHERE employee_id = :employeeId
                """, Map.of("employeeId", employeeId),
                (rs, rowNum) -> new EmailRecipient(rs.getString("email"), rs.getString("name")));
            return Optional.ofNullable(recipient);
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
        // CEO discount-approval workflow, Phase 2 (V155).
        Map.entry("DISCOUNT_APPROVAL_REQUESTED", "รอ CEO อนุมัติส่วนลด"),
        Map.entry("DISCOUNT_APPROVED", "CEO อนุมัติส่วนลดแล้ว"),
        Map.entry("DISCOUNT_REJECTED", "CEO ปฏิเสธส่วนลด"),
        // Step 6: Deposit, Payment, and Order Confirmation.
        Map.entry("ORDER_CONFIRMED", "ยืนยันคำสั่งซื้อแล้ว"),
        Map.entry("DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION", "สร้างร่างใบแจ้งยอดเงินรับมัดจำแล้ว"),
        // TicketService.reserveStock — a rep declaring stock coverage on their OWN deal. Without
        // an entry here it would fall through to the generic "อัปเดตสถานะคำขอราคา", which reads as
        // routine pipeline noise; the whole point of this notification is that it is not.
        Map.entry("STOCK_RESERVED", "พนักงานขายประกาศสินค้าจากสต็อกเอง"),
        // profile/ProfileRequestService — profile-change-request notifications (2026-08-31).
        // ProfileRequestService emitted zero notifications before this; these three cover
        // submit (to HR) and approve/reject (to the requesting employee).
        Map.entry("PROFILE_REQUEST_SUBMITTED", "มีคำขอแก้ไขข้อมูลพนักงานรออนุมัติ"),
        Map.entry("PROFILE_REQUEST_APPROVED", "อนุมัติคำขอแก้ไขข้อมูลของคุณแล้ว"),
        Map.entry("PROFILE_REQUEST_REJECTED", "คำขอแก้ไขข้อมูลของคุณไม่ได้รับอนุมัติ"),
        // payroll/declaration/TaxAllowanceDeclarationService — ล.ย.01 reaches HR's queue
        // (2026-08-31). The employee-facing APPROVED/REJECTED/EXPIRED events for this same feature
        // do NOT go through this map: they carry an explicit title straight into
        // NotificationService#notify, the same call shape leave/overtime/welfare/attendance-
        // correction use, not the ticket-scoped fan-out this map serves.
        Map.entry("TAX_ALLOWANCE_SUBMITTED", "มีแบบ ล.ย.01 รอ HR ตรวจสอบ")
    );

    public void notifyEmployee(long employeeId, long ticketId, String type, String message) {
        notifyEmployeeAt(employeeId, type, message, "/tickets/" + ticketId);
    }

    public void notifyEmployeeForPricingRequest(long employeeId, long pricingRequestId, String type, String message) {
        notifyEmployeeAt(employeeId, type, message, "/pricing-requests/" + pricingRequestId);
    }

    /** ProfileRequestService#update, notifying the employee whose own request was reviewed. */
    public void notifyEmployeeOfProfileRequest(long employeeId, String type, String message) {
        notifyEmployeeAt(employeeId, type, message, "/profile");
    }

    private void notifyEmployeeAt(long employeeId, String type, String message, String link) {
        String title = ticketEventTitle(type);
        jdbc.update("""
            INSERT INTO hr.notification (employee_id, type, title, message, link)
            VALUES (:employeeId, :type, :title, :message, :link)
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("type", type)
                .addValue("title", title)
                .addValue("message", message)
                .addValue("link", link));
        // The Thai TITLE, never `type`. `type` is a machine code (PRICING_DECISION_APPROVED) and
        // mailing it would put a raw enum in a subject line at real people — the exact defect a
        // previous round shipped when TRAVEL_PER_DIEM reached employees. Passing the same string the
        // in-app row stores also means the bell and the inbox can never disagree about what happened.
        salesMailer.emailForEmployee(employeeId, title, message, link);
    }

    /**
     * Notify all employees whose division maps to the given sales role ({@code import}/{@code
     * sales}), or -- for {@code ceo} -- who match {@link CeoApproverRule#SQL_PREDICATE}, which is
     * a POSITION test (กรรมการผู้จัดการ) and ignores division entirely, unlike the plain division
     * mapping the other two roles use. It is deliberately NARROWER than the {@code ceo} role and
     * is <b>not</b> a mirror of {@code DivisionAccessPolicy#roleFor} -- see {@link CeoApproverRule}
     * for the owner ruling and the empty-set consequence.
     *
     * <p>{@code sales_manager} is different again: it resolves to the ฝ่ายขาย members whose
     * position marks them a ผู้จัดการ, so it is a strict subset of {@code sales} and the two are
     * not interchangeable -- {@code sales} alone would also fan out to the rep who triggered the
     * event, not just their supervisor.
     *
     * <p>The {@code hr.division} join is a {@code LEFT JOIN} so a {@code ceo} match with no
     * division is not silently dropped -- the predicate no longer reads {@code d} at all.
     * This does not change {@code import}/{@code sales}/{@code sales_manager}: each of those
     * predicates tests {@code d.source_code}, which is SQL {@code NULL} (never true) when {@code
     * d} fails to match, exactly as an absent INNER JOIN row would have excluded that employee --
     * confirmed by inspection, not just assumed. {@code hr.position} is likewise a {@code LEFT
     * JOIN}: only {@code sales_manager} reads it, and an employee with a null {@code position_id}
     * must stay reachable by the other, position-agnostic branches.
     *
     * <p>An unrecognised role resolves to nobody and inserts (and mails) nothing -- a silent
     * no-op, as it always has been -- so a typo'd role name notifies no one rather than everyone.
     */
    public void notifyByRole(String role, long ticketId, String type, String message) {
        notifyByRoleInternal(role, type, message, "/tickets/" + ticketId);
    }

    public void notifyByRoleForPricingRequest(String role, long pricingRequestId, String type, String message) {
        notifyByRoleInternal(role, type, message, "/pricing-requests/" + pricingRequestId);
    }

    /** ProfileRequestService#create, notifying HR that a new request is waiting on them. */
    public void notifyHrOfProfileRequest(String type, String message) {
        notifyHrAt(type, message, "/requests");
    }

    /**
     * General HR-fan-out entry point, generalized from {@link #notifyHrOfProfileRequest} (2026-08-31)
     * so a second HR-queue feature (ล.ย.01 submissions, which link to {@code /tax-allowance-review}
     * rather than {@code /requests}) does not need a second copy of the {@code "hr"} division
     * predicate. Both callers now share exactly one {@code notifyByRoleInternal("hr", ...)} path —
     * see that method's Javadoc for the predicate itself (mirrors {@code
     * DivisionAccessPolicy#roleFor}'s hr branch, not a naive prefix match).
     */
    public void notifyHrAt(String type, String message, String link) {
        notifyByRoleInternal("hr", type, message, link);
    }

    private void notifyByRoleInternal(String role, String type, String message, String link) {
        String divisionFilter = switch (role) {
            case "import" -> "d.source_code ILIKE 'PCIM%'";
            // Mirrors DivisionAccessPolicy#roleFor's hr branch ("hr".equals(divisionCode(employee))),
            // NOT a naive `d.source_code ILIKE 'HR%'` -- that was tried once already and found wrong
            // in two ways (EmployeeRepository#findHrEmployeeIds's S-3 review finding): it is a prefix
            // match where roleFor requires an EXACT "hr", and it has no fallback to the name_th
            // prefix for the real rows where source_code is NULL. Reused verbatim from the two
            // already-reviewed SQL mirrors of this same branch, PendingApproverSql
            // #SINGLE_ACTIVE_HR_NAME_SQL and EmployeeRepository#findHrEmployeeIds, aliases adjusted
            // to this method's own `d`/`p` joins. The NOT LIKE '%กรรมการ%' guard reproduces roleFor's
            // precedence: isExecutive is checked BEFORE "hr".equals(code), so an HR-division employee
            // who is also an executive resolves to "ceo" in Java and must not be notified here too.
            case "hr" -> """
                LOWER(TRIM(COALESCE(NULLIF(TRIM(d.source_code), ''), split_part(COALESCE(d.name_th, ''), '-', 1)))) = 'hr'
                AND regexp_replace(COALESCE(p.name_th, ''), '\\s+', '', 'g') NOT LIKE '%กรรมการ%'
                """;
            case "ceo"    -> CeoApproverRule.SQL_PREDICATE;
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
        // An unrecognised role writes nothing and mails nothing — the two channels stay in step even
        // on the do-nothing path.
        if (divisionFilter == null) return;

        String title = ticketEventTitle(type);
        // RETURNING, so the mail router is handed the employees that ACTUALLY received a row rather
        // than a second query's guess at them. The `sales`/`sales_manager` branches read the ids
        // (each delivers per recipient); import/account/ceo route to a fixed address and
        // deliberately mail even when this comes back empty — see SalesNotificationMailRouter.
        List<Long> notified = jdbc.queryForList("""
            INSERT INTO hr.notification (employee_id, type, title, message, link)
            SELECT e.employee_id, :type, :title, :message, :link
              FROM hr.employee e
              LEFT JOIN hr.division d ON d.division_id = e.division_id
              LEFT JOIN hr.position p ON p.position_id = e.position_id
             WHERE (%s) AND e.is_active = TRUE
            RETURNING employee_id
            """.formatted(divisionFilter),
            new MapSqlParameterSource()
                .addValue("type", type)
                .addValue("title", title)
                .addValue("message", message)
                .addValue("link", link),
            Long.class);
        salesMailer.emailForRole(role, notified, title, message, link);
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
