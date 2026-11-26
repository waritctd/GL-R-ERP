package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * The reads behind GET /api/sales/weekly-report that no existing repository has. Everything is attributed to the deal
 * owner ({@code t.created_by}) except visits, which keep their own owner (VisitRepository). {@code ownerId} null =
 * the whole team; the service has already gated who may ask for which.
 */
@Repository
public class WeeklyReportRepository {
    /** An order ticket as read; the service turns it into an {@link WeeklyReportDtos.WeeklyOrderRow}. */
    record OrderTicket(long ticketId, String code, String title, String customerName, String projectName, long ownerId,
                       String salesStage, String lifecycle, Instant confirmedAt, BigDecimal value,
                       BigDecimal received) {}

    /** What the estimate needs beyond the order row; null = none. {@code due}: delivery date + credit days. */
    record EstimateFacts(long ticketId, String depositPolicy, BigDecimal noticeTotal, LocalDate noticeDate, LocalDate eta,
                         boolean arrived, LocalDate due) {}

    record OpenPayment(long id, long ticketId, String kind, BigDecimal remaining, LocalDate week) {}

    /** One thing a rep did. {@code a} / {@code b}: the stage codes, the activity kind or the quotation number. */
    record Entry(long ownerId, Instant at, String kind, Long ticketId, String ticketTitle, String a, String b) {}

    private static final String TITLE = "COALESCE(NULLIF(TRIM(t.title), ''), t.customer_name)";
    private static final String OWNER = "(CAST(:ownerId AS BIGINT) IS NULL OR t.created_by = :ownerId)";

    /** Stage change, deal activity, quotation issued, visit marked done: D7's "entry". */
    private static final String ENTRIES = """
        SELECT t.created_by AS owner_id, e.created_at AS at, 'STAGE' AS kind, t.ticket_id, %1$s AS ticket_title,
               e.from_status AS a, e.to_status AS b
          FROM sales.ticket_event e JOIN sales.ticket t ON t.ticket_id = e.ticket_id
         WHERE e.kind = 'STAGE_CHANGED'
        UNION ALL
        SELECT t.created_by, a.created_at, 'ACTIVITY', t.ticket_id, %1$s, a.kind, NULL
          FROM sales.deal_activity a JOIN sales.ticket t ON t.ticket_id = a.ticket_id
        UNION ALL
        SELECT t.created_by, COALESCE(q.approved_at, q.issued_at), 'QUOTATION', t.ticket_id, %1$s, q.number, NULL
          FROM sales.quotation q JOIN sales.ticket t ON t.ticket_id = q.ticket_id
         WHERE COALESCE(q.approved_at, q.issued_at) IS NOT NULL
           AND q.doc_status NOT IN ('SUPERSEDED', 'CANCELLED', 'DRAFT', 'REJECTED')
        UNION ALL
        SELECT v.owner_id, v.done_at, 'VISIT', t.ticket_id, %1$s, NULL, NULL
          FROM sales.visit v LEFT JOIN sales.ticket t ON t.ticket_id = v.ticket_id
         WHERE v.status = 'DONE' AND v.done_at IS NOT NULL
        """.formatted(TITLE);

    private final NamedParameterJdbcTemplate jdbc;

    public WeeklyReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static MapSqlParameterSource params(Long ownerId) {
        return new MapSqlParameterSource().addValue("ownerId", ownerId, java.sql.Types.BIGINT);
    }

    private Map<Long, Integer> counts(String sql, MapSqlParameterSource p) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        jdbc.query(sql, p, rs -> {
            out.put(rs.getLong("owner_id"), rs.getInt("n"));
        });
        return out;
    }

    /** Owners with an ACTIVE deal: the team's rows even in a quiet week. */
    List<Long> activeOwners() {
        return jdbc.queryForList("SELECT DISTINCT created_by FROM sales.ticket WHERE lifecycle = 'ACTIVE'",
            Map.of(), Long.class);
    }

    /** Per owner, in [from, to): the week's counts keyed new / stage / lost / quotations / confirmed. */
    Map<String, Map<Long, Integer>> weekCounts(Long ownerId, Instant from, Instant to) {
        MapSqlParameterSource p = params(ownerId).addValue("from", Timestamp.from(from))
            .addValue("to", Timestamp.from(to));
        String ev = "SELECT t.created_by AS owner_id, COUNT(*) AS n FROM sales.ticket_event e "
            + "JOIN sales.ticket t ON t.ticket_id = e.ticket_id WHERE e.created_at >= :from AND e.created_at < :to"
            + " AND " + OWNER + " AND e.kind = '%s' GROUP BY 1";
        Map<String, Map<Long, Integer>> out = new LinkedHashMap<>();
        out.put("new", counts("SELECT t.created_by AS owner_id, COUNT(*) AS n FROM sales.ticket t "
            + "WHERE t.created_at >= :from AND t.created_at < :to AND t.parent_ticket_id IS NULL AND " + OWNER
            + " GROUP BY 1", p));
        out.put("stage", counts(ev.formatted(TicketEventKind.STAGE_CHANGED), p));
        out.put("lost", counts(ev.formatted(TicketEventKind.MARKED_LOST), p));
        out.put("quotations", counts("SELECT t.created_by AS owner_id, COUNT(*) AS n FROM sales.quotation q "
            + "JOIN sales.ticket t ON t.ticket_id = q.ticket_id WHERE COALESCE(q.approved_at, q.issued_at) >= :from "
            + "AND COALESCE(q.approved_at, q.issued_at) < :to "
            + "AND q.doc_status NOT IN ('SUPERSEDED', 'CANCELLED', 'DRAFT', 'REJECTED') AND " + OWNER + " GROUP BY 1", p));
        // The ticket's OWN confirmation (an add-on is its own order), not the root fallback the cutoff uses.
        p.addValue("promoted", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)
            .addValue("confirmed", TicketEventKind.CUSTOMER_CONFIRMED);
        String own = "(" + TicketRepository.orderConfirmedAtSelect("t.ticket_id") + ")";
        out.put("confirmed", counts("SELECT t.created_by AS owner_id, COUNT(*) AS n FROM sales.ticket t WHERE "
            + own + " >= :from AND " + own + " < :to AND " + OWNER + " GROUP BY 1", p));
        return out;
    }

    /** Entries made in [from, to), newest first. */
    List<Entry> entries(Long ownerId, Instant from, Instant to) {
        return jdbc.query("SELECT * FROM (" + ENTRIES + ") x WHERE at >= :from AND at < :to "
                + "AND (CAST(:ownerId AS BIGINT) IS NULL OR owner_id = :ownerId) ORDER BY at DESC",
            params(ownerId).addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)),
            (rs, n) -> new Entry(rs.getLong("owner_id"), rs.getTimestamp("at").toInstant(), rs.getString("kind"),
                rs.getObject("ticket_id", Long.class), rs.getString("ticket_title"), rs.getString("a"),
                rs.getString("b")));
    }

    /** Each owner's latest entry in the last 90 days (an owner with none is absent). */
    Map<Long, Instant> lastEntries(Long ownerId, Instant now) {
        Map<Long, Instant> out = new LinkedHashMap<>();
        jdbc.query("SELECT owner_id, MAX(at) AS at FROM (" + ENTRIES + ") x WHERE at >= :since "
                + "AND (CAST(:ownerId AS BIGINT) IS NULL OR owner_id = :ownerId) GROUP BY owner_id",
            params(ownerId).addValue("since", Timestamp.from(now.minus(java.time.Duration.ofDays(90)))), rs -> {
                out.put(rs.getLong("owner_id"), rs.getTimestamp("at").toInstant());
            });
        return out;
    }

    /**
     * Order tickets: an effective confirmation time ({@link TicketRepository#orderConfirmedAtExpr}) at or after
     * {@code moneyFrom} (any when null), ACTIVE, or COMPLETED and closed in [monthFrom, monthTo).
     */
    List<OrderTicket> orders(Long ownerId, Instant moneyFrom, Instant monthFrom, Instant monthTo) {
        MapSqlParameterSource p = params(ownerId).addValue("monthFrom", Timestamp.from(monthFrom))
            .addValue("monthTo", Timestamp.from(monthTo))
            .addValue("promoted", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)
            .addValue("confirmed", TicketEventKind.CUSTOMER_CONFIRMED);
        String cutoff = "";
        if (moneyFrom != null) {
            p.addValue("moneyFrom", Timestamp.from(moneyFrom));
            cutoff = " AND c.confirmed_at >= :moneyFrom";
        }
        return jdbc.query("""
            SELECT * FROM (
                SELECT t.ticket_id, t.code, %1$s AS title, t.customer_name, p.name AS project_name,
                       t.created_by AS owner_id, t.sales_stage, t.lifecycle, COALESCE(t.closed_at, t.updated_at) AS closed_at,
                       %2$s AS confirmed_at,
                       (%3$s) AS value,
                       (SELECT COALESCE(SUM(CASE WHEN r.kind = 'ADJUSTMENT' THEN -r.amount ELSE r.amount END), 0)
                          FROM sales.payment_receipt r WHERE r.ticket_id = t.ticket_id AND r.currency = 'THB')
                           AS received
                  FROM sales.ticket t
                  LEFT JOIN customers.project p ON p.project_id = t.project_id
                 WHERE %4$s) c
             WHERE c.confirmed_at IS NOT NULL
               AND (c.lifecycle = 'ACTIVE'
                    OR (c.lifecycle = 'COMPLETED' AND c.closed_at >= :monthFrom AND c.closed_at < :monthTo))
            """.formatted(TITLE, TicketRepository.orderConfirmedAtExpr("t"),
                TicketRepository.payableAmountSelect("t.ticket_id"), OWNER) + cutoff
                + " ORDER BY c.confirmed_at DESC, c.ticket_id",
            p, (rs, n) -> new OrderTicket(rs.getLong("ticket_id"), rs.getString("code"), rs.getString("title"),
                rs.getString("customer_name"), rs.getString("project_name"), rs.getLong("owner_id"),
                rs.getString("sales_stage"), rs.getString("lifecycle"),
                rs.getTimestamp("confirmed_at").toInstant(), rs.getBigDecimal("value"),
                rs.getBigDecimal("received")));
    }

    /** OPEN expected payments with something still to come on these tickets, earliest week first. */
    List<OpenPayment> openPayments(Set<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return new ArrayList<>();
        }
        return jdbc.query("""
            SELECT expected_payment_id, ticket_id, kind, amount - received_amount AS remaining, expected_week
              FROM sales.expected_payment
             WHERE ticket_id IN (:ids) AND status = 'OPEN' AND amount - received_amount > 0
             ORDER BY expected_week, expected_payment_id
            """, Map.of("ids", ticketIds),
            (rs, n) -> new OpenPayment(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBigDecimal(4),
                rs.getObject(5, LocalDate.class)));
    }

    /**
     * Facts for the "system estimate" of lines 3-5, for tickets with NO OPEN or RECEIVED expected payment of their own,
     * nor (for an add-on) on their root, whose row may already cover it: a rep's row is their word. THB orders only (an
     * accepted quotation in another currency is skipped). The latest ISSUED deposit notice; the goods, over the lines of
     * the ticket's ISSUED import requests: arrived = there is at least one line and every line's qty sits in arrived
     * rounds; eta = the latest ETA of the rounds still to come, or none when a line is not fully placed in a round or a
     * round still to come has no ETA (no date, no estimate); and the payment due date (delivery + credit days, else the
     * delivery date itself).
     */
    List<EstimateFacts> estimateFacts(Set<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return new ArrayList<>();
        }
        String notice = "(SELECT %s FROM sales.deposit_notice d WHERE d.ticket_id = t.ticket_id AND d.status = 'ISSUED'"
            + " ORDER BY d.version DESC, d.deposit_notice_id DESC LIMIT 1)";
        String lines = " FROM sales.import_request ir JOIN sales.import_request_item ii ON ii.import_request_id = ir.import_request_id"
            + " WHERE ir.ticket_id = t.ticket_id AND ir.status = 'ISSUED'";
        String placed = "COALESCE((SELECT SUM(l.qty) FROM sales.shipment_lot l JOIN sales.shipment_round sr"
            + " ON sr.shipment_round_id = l.shipment_round_id WHERE l.import_request_item_id = ii.import_request_item_id%s), 0)";
        String arrivedRound = " AND (sr.arrived_on IS NOT NULL OR sr.received_on IS NOT NULL)";
        String toCome = " FROM sales.shipment_round sr JOIN sales.shipment_lot l ON l.shipment_round_id = sr.shipment_round_id"
            + " JOIN sales.import_request ir ON ir.import_request_id = l.import_request_id WHERE ir.ticket_id = t.ticket_id"
            + " AND ir.status = 'ISSUED' AND sr.arrived_on IS NULL AND sr.received_on IS NULL";
        return jdbc.query("SELECT t.ticket_id, t.deposit_policy, " + notice.formatted("d.total_payable")
                + " AS notice_total, " + notice.formatted("d.issue_date") + " AS notice_date, CASE WHEN EXISTS (SELECT 1" + lines
                + " AND ii.qty > " + placed.formatted("") + ") OR EXISTS (SELECT 1" + toCome + " AND sr.eta_on IS NULL)"
                + " THEN NULL ELSE (SELECT MAX(sr.eta_on)" + toCome + ") END AS eta, (EXISTS (SELECT 1" + lines
                + ") AND NOT EXISTS (SELECT 1" + lines + " AND ii.qty > " + placed.formatted(arrivedRound) + ")) AS arrived,"
                + " COALESCE(" + TicketRepository.paymentDueDateScalarSql("t.ticket_id")
                + ", (SELECT (MAX(dr.delivered_at) AT TIME ZONE 'Asia/Bangkok')::date FROM sales.delivery_record dr"
                + " WHERE dr.ticket_id = t.ticket_id)) AS due"
                + " FROM sales.ticket t WHERE t.ticket_id IN (:ids) AND NOT EXISTS (SELECT 1 FROM sales.expected_payment ep"
                + " WHERE ep.status IN ('OPEN', 'RECEIVED') AND (ep.ticket_id = t.ticket_id"
                + " OR (t.child_kind = 'ADD_ON' AND ep.ticket_id = t.parent_ticket_id)))"
                + " AND NOT EXISTS (SELECT 1 FROM sales.quotation q WHERE q.ticket_id = t.ticket_id"
                + " AND q.doc_status = 'ACCEPTED' AND COALESCE(q.currency, 'THB') <> 'THB')",
            Map.of("ids", ticketIds),
            (rs, n) -> new EstimateFacts(rs.getLong("ticket_id"), rs.getString("deposit_policy"),
                rs.getBigDecimal("notice_total"), rs.getObject("notice_date", LocalDate.class),
                rs.getObject("eta", LocalDate.class), rs.getBoolean("arrived"), rs.getObject("due", LocalDate.class)));
    }
}
