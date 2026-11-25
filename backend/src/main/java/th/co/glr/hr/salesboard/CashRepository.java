package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * The two cash reads behind {@link SalesBoardService#cashForMonth}, grouped by deal owner ({@code t.created_by}, the
 * board's owner). Scope lives in the WHERE clause: {@code ownerId} non-null adds {@code t.created_by = :ownerId}; null
 * (the team) has no owner predicate and is reached only through the service's sales_manager/ceo gate.
 *
 * <p>Month edges are Asia/Bangkok: receipts are bounded by Bangkok midnight as an instant, and dated by
 * {@code received_at AT TIME ZONE 'Asia/Bangkok'}. Expected rows on lost or cancelled deals never count.
 *
 * <p>Money cutoff ({@code app.sales.money-from}): when set, only tickets whose order confirmation
 * ({@link TicketRepository#orderConfirmedAtExpr}) is at or after Bangkok midnight of that date count, in both reads.
 * A ticket with no confirmation never counts. Null = no cutoff.
 */
@Repository
public class CashRepository {
    static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    /** One owner × expected week: the forecast and the three OPEN buckets. */
    public record ExpectedWeekRow(long ownerId, LocalDate week, BigDecimal forecast, BigDecimal deliveredAwaiting,
                                  BigDecimal deliveriesScheduled, BigDecimal depositsExpected) {}

    /** One owner × Bangkok calendar day: the signed THB received. */
    public record ReceivedDayRow(long ownerId, LocalDate day, BigDecimal received) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final LocalDate moneyFrom;

    /** No cutoff: hand-wired tests and tools. */
    public CashRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, (LocalDate) null);
    }

    public CashRepository(NamedParameterJdbcTemplate jdbc, LocalDate moneyFrom) {
        this.jdbc = jdbc;
        this.moneyFrom = moneyFrom;
    }

    @Autowired
    CashRepository(NamedParameterJdbcTemplate jdbc, @Value("${app.sales.money-from:}") String moneyFrom) {
        this(jdbc, moneyFrom == null || moneyFrom.isBlank() ? null : LocalDate.parse(moneyFrom.trim()));
    }

    /** The cutoff date, or null when money from every order counts. */
    public LocalDate moneyFrom() {
        return moneyFrom;
    }

    private String moneyPredicate(MapSqlParameterSource p) {
        if (moneyFrom == null) {
            return "";
        }
        p.addValue("moneyFrom", Timestamp.from(moneyFrom.atStartOfDay(BANGKOK).toInstant()))
            .addValue("promoted", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)
            .addValue("confirmed", TicketEventKind.CUSTOMER_CONFIRMED);
        return " AND " + TicketRepository.orderConfirmedAtExpr("t") + " >= :moneyFrom";
    }

    /**
     * Expected payments whose week belongs to [from, to] by the ISO rule: a week belongs to the month holding its
     * THURSDAY ({@code expected_week + 3}), so the week of Mon 30 Nov – Fri 4 Dec is December's (review M6).
     * forecast = the full planned amount of OPEN and RECEIVED rows; the three buckets carry only what is still to come
     * of an OPEN row ({@code amount - received_amount}, review M1). An OPEN row counts only on an ACTIVE deal (ON_HOLD /
     * DORMANT deals expect nothing now); a RECEIVED row counts on any deal that is not lost or cancelled.
     */
    public List<ExpectedWeekRow> expectedByWeek(Long ownerId, LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", from).addValue("to", to);
        String owner = ownerPredicate(ownerId, p) + moneyPredicate(p);
        return jdbc.query("""
            SELECT t.created_by AS owner_id, ep.expected_week,
                   COALESCE(SUM(ep.amount), 0) AS forecast,
                   COALESCE(SUM(ep.amount - ep.received_amount) FILTER (WHERE ep.status = 'OPEN'
                       AND ep.kind IN ('ON_DELIVERY', 'BALANCE') AND t.sales_stage = 'DELIVERED'), 0)
                       AS delivered_awaiting,
                   COALESCE(SUM(ep.amount - ep.received_amount) FILTER (WHERE ep.status = 'OPEN'
                       AND ep.kind = 'ON_DELIVERY' AND t.sales_stage IN ('DELIVERY_SCHEDULING', 'PROCUREMENT')), 0)
                       AS deliveries_scheduled,
                   COALESCE(SUM(ep.amount - ep.received_amount) FILTER (WHERE ep.status = 'OPEN'
                       AND ep.kind = 'DEPOSIT'), 0) AS deposits_expected
              FROM sales.expected_payment ep
              JOIN sales.ticket t ON t.ticket_id = ep.ticket_id
             WHERE ((ep.status = 'OPEN' AND t.lifecycle = 'ACTIVE')
                    OR (ep.status = 'RECEIVED' AND t.lifecycle NOT IN ('CLOSED_LOST', 'CANCELLED')))
               AND ep.expected_week + 3 BETWEEN :from AND :to
            """ + owner + " GROUP BY t.created_by, ep.expected_week ORDER BY t.created_by, ep.expected_week",
            p, (rs, n) -> new ExpectedWeekRow(rs.getLong("owner_id"), rs.getObject("expected_week", LocalDate.class),
                rs.getBigDecimal("forecast"), rs.getBigDecimal("delivered_awaiting"),
                rs.getBigDecimal("deliveries_scheduled"), rs.getBigDecimal("deposits_expected")));
    }

    /**
     * Weekly report only: OPEN rows on ACTIVE deals that no {@link #expectedByWeek} bucket takes (e.g. ON_DELIVERY
     * still at ORDER_RECEIVED), keyed by kind in the same three fields: BALANCE in deliveredAwaiting, ON_DELIVERY in
     * deliveriesScheduled. DEPOSIT always has its bucket. {@code forecast} is zero.
     */
    public List<ExpectedWeekRow> unbucketedOpenByWeek(Long ownerId, LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", from).addValue("to", to);
        String owner = ownerPredicate(ownerId, p) + moneyPredicate(p);
        return jdbc.query("""
            SELECT t.created_by AS owner_id, ep.expected_week,
                   COALESCE(SUM(ep.amount - ep.received_amount) FILTER (WHERE ep.kind = 'BALANCE'
                       AND t.sales_stage <> 'DELIVERED'), 0) AS balance_left,
                   COALESCE(SUM(ep.amount - ep.received_amount) FILTER (WHERE ep.kind = 'ON_DELIVERY'
                       AND t.sales_stage NOT IN ('DELIVERED', 'DELIVERY_SCHEDULING', 'PROCUREMENT')), 0)
                       AS on_delivery_left
              FROM sales.expected_payment ep
              JOIN sales.ticket t ON t.ticket_id = ep.ticket_id
             WHERE ep.status = 'OPEN' AND t.lifecycle = 'ACTIVE' AND ep.amount - ep.received_amount > 0
               AND ep.expected_week + 3 BETWEEN :from AND :to
            """ + owner + " GROUP BY t.created_by, ep.expected_week",
            p, (rs, n) -> new ExpectedWeekRow(rs.getLong("owner_id"), rs.getObject("expected_week", LocalDate.class),
                BigDecimal.ZERO, rs.getBigDecimal("balance_left"), rs.getBigDecimal("on_delivery_left"),
                BigDecimal.ZERO));
    }

    /**
     * THB receipts received on Bangkok days [from, toInclusive], signed as TicketRepository.SIGNED_RECEIPT_SUM
     * (ADJUSTMENT subtracts). Every source counts (MANUAL and EXPRESS alike) — cash is cash, and the upload dedupes
     * on its own document number so it is never in here twice. A PRE_PORTAL deposit counts on the date the rep gave it.
     */
    public List<ReceivedDayRow> receivedByDay(Long ownerId, LocalDate from, LocalDate toInclusive) {
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("fromTs", Timestamp.from(from.atStartOfDay(BANGKOK).toInstant()))
            .addValue("toTs", Timestamp.from(toInclusive.plusDays(1).atStartOfDay(BANGKOK).toInstant()));
        String owner = ownerPredicate(ownerId, p) + moneyPredicate(p);
        return jdbc.query("""
            SELECT t.created_by AS owner_id, (r.received_at AT TIME ZONE 'Asia/Bangkok')::date AS day,
                   SUM(CASE WHEN r.kind = 'ADJUSTMENT' THEN -r.amount ELSE r.amount END) AS received
              FROM sales.payment_receipt r
              JOIN sales.ticket t ON t.ticket_id = r.ticket_id
             WHERE r.received_at >= :fromTs AND r.received_at < :toTs
               AND r.currency = 'THB'
            """ + owner + " GROUP BY 1, 2 ORDER BY 1, 2",
            p, (rs, n) -> new ReceivedDayRow(rs.getLong("owner_id"), rs.getObject("day", LocalDate.class),
                rs.getBigDecimal("received")));
    }

    private static String ownerPredicate(Long ownerId, MapSqlParameterSource p) {
        if (ownerId == null) {
            return "";
        }
        p.addValue("ownerId", ownerId);
        return " AND t.created_by = :ownerId";
    }
}
