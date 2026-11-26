package th.co.glr.hr.digest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.dashboard.DashboardRepository;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.QuietRule;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;

/** The reads behind the Monday digests. Both reuse existing definitions: account's money to-do
 * ({@link DashboardRepository#ACCOUNT_MONEY_TODO} inside {@link TicketRepository#accountScopePredicate()}, as the
 * finance outstanding total does) and the quiet rule ({@link QuietRule#quietSql}). */
@Repository
public class WeeklyDigestRepository {
    public record AccountItem(long ticketId, String title, String customer, String repName, BigDecimal amount,
                              int daysWaiting) {}

    public record StaleDeal(long ticketId, String title, String customer, int quietDays) {}

    public record PendingNote(String title, String link, int daysAgo) {}

    private static final String TITLE = "COALESCE(NULLIF(TRIM(t.title), ''), t.customer_name, t.code)";

    private final NamedParameterJdbcTemplate jdbc;
    private final QuietRule quietRule;

    public WeeklyDigestRepository(NamedParameterJdbcTemplate jdbc, QuietRule quietRule) {
        this.jdbc = jdbc;
        this.quietRule = quietRule;
    }

    /** Deals waiting on account for money, longest wait first. Days waited = since the deal's latest payment-track
     * event (confirmed / notice / deposit paid / awaiting final / payment recorded), else since it was created. */
    public List<AccountItem> accountItems() {
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("bypassPolicies", DepositPolicy.NON_REQUIRED)
            .addValue("kinds", List.of(TicketEventKind.CUSTOMER_CONFIRMED, TicketEventKind.DEPOSIT_NOTICE_ISSUED,
                TicketEventKind.DEPOSIT_PAID, TicketEventKind.AWAITING_FINAL_PAYMENT,
                TicketEventKind.PAYMENT_RECORDED));
        TicketRepository.addAccountScopeParams(p);
        return jdbc.query("""
            SELECT t.ticket_id, %1$s AS title, t.customer_name,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), '-') AS rep_name,
                   GREATEST((%2$s) - (%3$s), 0) AS amount,
                   GREATEST((now() AT TIME ZONE 'Asia/Bangkok')::date - (COALESCE(
                       (SELECT max(ev.created_at) FROM sales.ticket_event ev
                         WHERE ev.ticket_id = t.ticket_id AND ev.kind IN (:kinds)), t.created_at)
                       AT TIME ZONE 'Asia/Bangkok')::date, 0) AS days_waiting
              FROM sales.ticket t
              LEFT JOIN hr.employee e ON e.employee_id = t.created_by
             WHERE %4$s AND %5$s
             ORDER BY days_waiting DESC, t.ticket_id
            """.formatted(TITLE, TicketRepository.payableAmountSelect("t.ticket_id"),
                TicketRepository.paidAmountSelect("t.ticket_id"), TicketRepository.accountScopePredicate(),
                DashboardRepository.ACCOUNT_MONEY_TODO), p,
            (rs, n) -> new AccountItem(rs.getLong("ticket_id"), rs.getString("title"),
                rs.getString("customer_name"), rs.getString("rep_name"), rs.getBigDecimal("amount"),
                rs.getInt("days_waiting")));
    }

    /** Owner id -> number of ACTIVE deals, for owners with at least one. */
    public Map<Long, Integer> openDealCounts() {
        Map<Long, Integer> out = new LinkedHashMap<>();
        jdbc.query("SELECT created_by, COUNT(*) AS n FROM sales.ticket WHERE lifecycle = 'ACTIVE' GROUP BY 1",
            rs -> {
                out.put(rs.getLong("created_by"), rs.getInt("n"));
            });
        return out;
    }

    /** Owner id -> money received in [from, to) on the deals they own (signed: refunds subtract). */
    public Map<Long, BigDecimal> receivedByOwner(Instant from, Instant to) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        jdbc.query("SELECT t.created_by, " + TicketRepository.SIGNED_RECEIPT_SUM + " AS amount "
                + "FROM sales.payment_receipt r JOIN sales.ticket t ON t.ticket_id = r.ticket_id "
                + "WHERE r.received_at >= :from AND r.received_at < :to GROUP BY 1",
            new MapSqlParameterSource().addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)),
            rs -> {
                out.put(rs.getLong("created_by"), rs.getBigDecimal("amount"));
            });
        return out;
    }

    /** The employee's unread bell notifications from the last 30 days, newest first: the portal's own to-do inbox
     * for every role (warehouse, import, tax-invoice and approval flows all notify the person who must act). */
    public List<PendingNote> unreadNotifications(long employeeId) {
        return jdbc.query("""
            SELECT COALESCE(NULLIF(TRIM(n.title), ''), n.message) AS title, n.link,
                   GREATEST((now() AT TIME ZONE 'Asia/Bangkok')::date
                            - (n.created_at AT TIME ZONE 'Asia/Bangkok')::date, 0) AS days_ago
              FROM hr.notification n
             WHERE n.employee_id = :employeeId AND n.is_read = FALSE
               AND n.created_at > now() - interval '30 days'
             ORDER BY n.created_at DESC
             LIMIT 200
            """, new MapSqlParameterSource("employeeId", employeeId),
            (rs, n) -> new PendingNote(rs.getString("title"), rs.getString("link"), rs.getInt("days_ago")));
    }

    /** The owner's quiet deals (14+ days by default), quietest first. */
    public List<StaleDeal> staleDeals(long ownerId) {
        return jdbc.query("""
            SELECT t.ticket_id, %1$s AS title, t.customer_name,
                   GREATEST((now() AT TIME ZONE 'Asia/Bangkok')::date
                            - (%2$s AT TIME ZONE 'Asia/Bangkok')::date, 0) AS quiet_days
              FROM sales.ticket t
             WHERE t.created_by = :owner AND %3$s
             ORDER BY quiet_days DESC, t.ticket_id
            """.formatted(TITLE, QuietRule.lastTouchedSql("t"), QuietRule.quietSql("t")),
            new MapSqlParameterSource().addValue("owner", ownerId).addValue("quietDays", quietRule.quietDays()),
            (rs, n) -> new StaleDeal(rs.getLong("ticket_id"), rs.getString("title"), rs.getString("customer_name"),
                rs.getInt("quiet_days")));
    }
}
