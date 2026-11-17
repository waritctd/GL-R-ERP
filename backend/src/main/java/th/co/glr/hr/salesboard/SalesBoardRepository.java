package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.salesboard.SalesBoardDtos.BoardDeal;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.PaymentTrack;
import th.co.glr.hr.ticket.QuietRule;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.WinProbabilityDefaults;

/**
 * The board read model: every open deal (ACTIVE, not CLOSED_PAID — the money is in; DELIVERED stays, money is still
 * due) with its last touch ({@link QuietRule}), its latest activity, and the subtotal + language + currency of its
 * value quotation. Value quotation, in order: a live one (APPROVED/ISSUED/SENT/ACCEPTED) before an EXPIRED one
 * (REJECTED/SUPERSEDED/CANCELLED never count); within that, the headline party's (★ ยอดหลัก, deal_party.is_headline)
 * first; then the latest by COALESCE(approved_at, issued_at). A deal with no value quotation shows its pipeline-import
 * value ({@code imported_value}, V215, THB) instead. Scope lives in the WHERE clause:
 * {@link #activeDealsOwnedBy} adds {@code t.created_by = :ownerId}; {@link #activeDealsAllReps} has no owner
 * predicate and is reached only through {@code SalesBoardService}'s sales_manager/ceo gate. Rows come ordered by
 * last touch ascending, then ticket id — mockApi.salesBoard mirrors this ORDER BY. The two
 * {@code returningPausedDeals*} reads (V220) take the same row shape for PAUSED deals whose วันที่จะกลับมาดู has
 * arrived ({@link QuietRule#returningSql}), with the same owner predicate split.
 *
 * <p>Slice 6.2 card fields: the value quotation's own {@code number}, {@code buyer_name}, the earliest OPEN DEPOSIT
 * {@code expected_payment} week and the deposit-received fact come from this statement; the due date and overdue flag
 * come from {@link TicketRepository#paymentDueStates} — ONE more statement per read for the whole row set (never one
 * per row), and the finance summary's own derivation, never a copy of it. Every read keeps its WHERE clause unchanged,
 * so the new fields ride on rows the caller could already see.
 */
@Repository
public class SalesBoardRepository {
    /** Reached the customer (DEAL_DIRECT APPROVED, legacy ISSUED/SENT/ACCEPTED), plus EXPIRED as a flagged fallback. */
    private static final List<String> VALUE_STATUSES = List.of("APPROVED", "ISSUED", "SENT", "ACCEPTED", "EXPIRED");

    /** Every column a board row needs; the WHERE is the caller's ({@link #ACTIVE_WHERE} or {@link #RETURNING_WHERE}),
     * so a returning paused deal is read with exactly the same value / last-touch / last-activity logic.
     * {@code days_quiet} (whole days since the last touch) is computed HERE, from this statement's {@code now()} — the
     * same clock as the {@code quiet} flag and as the Postgres-written timestamps it measures. It used to be the JVM
     * clock minus a Postgres timestamp, which read N-1 for an N-day deal whenever the JVM clock lagged the database's
     * and reordered ควรตามต่อ (review round 2, L-1). */
    private static final String ROW_SELECT = """
        SELECT t.ticket_id, t.title, t.customer_name, p.name AS project_name,
               t.created_by AS owner_id,
               NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS owner_name,
               t.sales_stage, t.designer_name, t.win_probability, t.expected_quarter, t.imported_value,
               t.pause_return_on, t.estimated_value,
               %1$s AS last_touched_at,
               GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (now() - %1$s)) / 86400))::int AS days_quiet,
               %2$s AS quiet,
               %3$s AS returning,
               q.total_amount, q.document_language, q.currency, q.expired, q.number AS quotation_number,
               t.buyer_name,
               (SELECT MIN(ep.expected_week) FROM sales.expected_payment ep
                 WHERE ep.ticket_id = t.ticket_id AND ep.kind = 'DEPOSIT' AND ep.status = 'OPEN') AS expected_deposit_week,
               (t.payment_status IN (:depositInHandStatuses)
                AND COALESCE(t.deposit_policy, '') NOT IN (:depositBypassPolicies)) AS deposit_received,
               la.kind AS last_activity_kind, la.note AS last_activity_note
          FROM sales.ticket t
          JOIN hr.employee e ON e.employee_id = t.created_by
          LEFT JOIN customers.project p ON p.project_id = t.project_id
          LEFT JOIN LATERAL (
              SELECT vq.total_amount, vq.document_language, vq.currency, vq.doc_status = 'EXPIRED' AS expired,
                     vq.number
                FROM sales.quotation vq
                LEFT JOIN sales.deal_party vp ON vp.party_id = vq.party_id
               WHERE vq.ticket_id = t.ticket_id AND vq.doc_status IN (:valueStatuses)
               ORDER BY (vq.doc_status <> 'EXPIRED') DESC, COALESCE(vp.is_headline, FALSE) DESC,
                        COALESCE(vq.approved_at, vq.issued_at) DESC NULLS LAST, vq.quotation_id DESC
               LIMIT 1
          ) q ON TRUE
          LEFT JOIN LATERAL (
              SELECT a.kind, a.note FROM sales.deal_activity a
               WHERE a.ticket_id = t.ticket_id
               ORDER BY a.created_at DESC, a.id DESC
               LIMIT 1
          ) la ON TRUE
        """.formatted(QuietRule.lastTouchedSql("t"), QuietRule.quietSql("t"), QuietRule.returningSql("t"));

    /** The board: open ACTIVE deals. */
    private static final String ACTIVE_WHERE = """
         WHERE t.lifecycle = 'ACTIVE'
           AND COALESCE(t.sales_stage, '') <> 'CLOSED_PAID'
        """;

    /** Paused deals whose วันที่จะกลับมาดู has arrived (QuietRule#returningSql) — follow-up / quiet figures only. */
    private static final String RETURNING_WHERE = " WHERE " + QuietRule.returningSql("t") + """
           AND COALESCE(t.sales_stage, '') <> 'CLOSED_PAID'
        """;

    private static final String BOARD_SELECT = ROW_SELECT + ACTIVE_WHERE;
    private static final String RETURNING_SELECT = ROW_SELECT + RETURNING_WHERE;

    /** The payment-track states at which the deposit is in hand (DocumentChainRules#DEPOSIT_PAID_STATUSES minus
     * FULLY_PAID, which leaves the board as CLOSED_PAID once delivered). A bypass policy (DepositPolicy#NON_REQUIRED)
     * reaches AWAITING_FINAL_PAYMENT without any deposit, so it is excluded below. */
    private static final List<String> DEPOSIT_IN_HAND_STATUSES =
        List.of(PaymentTrack.DEPOSIT_PAID, PaymentTrack.AWAITING_FINAL_PAYMENT);

    private final NamedParameterJdbcTemplate jdbc;
    private final QuietRule quietRule;
    private final TicketRepository tickets;

    @Autowired
    public SalesBoardRepository(NamedParameterJdbcTemplate jdbc, QuietRule quietRule, TicketRepository tickets) {
        this.jdbc = jdbc;
        this.quietRule = quietRule;
        this.tickets = tickets;
    }

    /** Tests and tools that build the board by hand: the due-date facts read through a TicketRepository on the same
     * connection and quiet rule. */
    public SalesBoardRepository(NamedParameterJdbcTemplate jdbc, QuietRule quietRule) {
        this(jdbc, quietRule, new TicketRepository(jdbc, quietRule));
    }

    /** The caller's own ACTIVE deals — the deal's owner ({@code created_by}), not its keyer. */
    public List<BoardDeal> activeDealsOwnedBy(long ownerId) {
        return withPaymentDue(jdbc.query(BOARD_SELECT + " AND t.created_by = :ownerId ORDER BY last_touched_at, t.ticket_id",
            params().addValue("ownerId", ownerId), this::map));
    }

    /** Every ACTIVE deal of every rep. Only for the sales_manager/ceo team overview. */
    public List<BoardDeal> activeDealsAllReps() {
        return withPaymentDue(jdbc.query(BOARD_SELECT + " ORDER BY last_touched_at, t.ticket_id", params(), this::map));
    }

    /** The caller's own paused deals whose วันที่จะกลับมาดู has arrived — owner ({@code created_by}) scoped in the
     * WHERE clause exactly as {@link #activeDealsOwnedBy}. Same row order. */
    public List<BoardDeal> returningPausedDealsOwnedBy(long ownerId) {
        return withPaymentDue(jdbc.query(
            RETURNING_SELECT + " AND t.created_by = :ownerId ORDER BY last_touched_at, t.ticket_id",
            params().addValue("ownerId", ownerId), this::map));
    }

    /** Every rep's returning paused deals. Only for the sales_manager/ceo team overview, like
     * {@link #activeDealsAllReps}. */
    public List<BoardDeal> returningPausedDealsAllReps() {
        return withPaymentDue(jdbc.query(RETURNING_SELECT + " ORDER BY last_touched_at, t.ticket_id", params(),
            this::map));
    }

    /** The bind parameters every board read needs: the value statuses, the quiet threshold, and the Asia/Bangkok
     * "today" QuietRule#returningSql compares the return date with. */
    private org.springframework.jdbc.core.namedparam.MapSqlParameterSource params() {
        return new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("valueStatuses", VALUE_STATUSES)
            .addValue("quietDays", quietRule.quietDays())
            .addValue("returnToday", quietRule.bangkokToday(), java.sql.Types.DATE)
            .addValue("depositInHandStatuses", DEPOSIT_IN_HAND_STATUSES)
            .addValue("depositBypassPolicies", List.copyOf(DepositPolicy.NON_REQUIRED));
    }

    /** The rows with the finance summary's due date and overdue flag (TicketRepository#paymentDueStates): one
     * statement for the whole list, in the rows' own order. */
    private List<BoardDeal> withPaymentDue(List<BoardDeal> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<Long, TicketRepository.PaymentDueState> due =
            tickets.paymentDueStates(rows.stream().map(BoardDeal::ticketId).toList());
        return rows.stream().map(d -> {
            TicketRepository.PaymentDueState state = due.get(d.ticketId());
            return state == null ? d : d.withPaymentDue(state.dueDate(), state.overdue());
        }).toList();
    }

    private BoardDeal map(ResultSet rs, int rowNum) throws SQLException {
        String stage = rs.getString("sales_stage");
        Timestamp lastTouched = rs.getTimestamp("last_touched_at");
        int override = rs.getInt("win_probability");
        Integer winProbability = rs.wasNull()
            ? (stage == null ? null : WinProbabilityDefaults.defaultFor(stage))
            : Integer.valueOf(override);
        // No customer-facing quotation yet: fall back to the value the rep's pipeline workbook stated (V215,
        // written only by the one-time pipeline import, always THB). A quotation's value always wins.
        BigDecimal value = grandTotal(rs.getBigDecimal("total_amount"), rs.getString("document_language"));
        String currency = rs.getString("currency");
        if (value == null && rs.getBigDecimal("imported_value") != null) {
            value = rs.getBigDecimal("imported_value");
            currency = "THB";
        }
        // Last fallback (V220): the rep's own มูลค่าโดยประมาณ, THB, only when neither a quotation nor the import gives one.
        if (value == null && rs.getBigDecimal("estimated_value") != null) {
            value = rs.getBigDecimal("estimated_value");
            currency = "THB";
        }
        return new BoardDeal(
            rs.getLong("ticket_id"), rs.getString("title"), rs.getString("customer_name"),
            rs.getString("project_name"), rs.getLong("owner_id"), rs.getString("owner_name"),
            stage, DealStage.repCodeOf(stage), DealStage.boardColumnOf(stage),
            value, currency, rs.getBoolean("expired"),
            rs.getString("designer_name"), winProbability, rs.getString("expected_quarter"),
            lastTouched == null ? null : lastTouched.toInstant(),
            rs.getInt("days_quiet"), rs.getBoolean("quiet"),
            rs.getString("last_activity_kind"), rs.getString("last_activity_note"),
            rs.getBoolean("returning"), rs.getObject("pause_return_on", java.time.LocalDate.class),
            rs.getString("quotation_number"), rs.getString("buyer_name"),
            rs.getObject("expected_deposit_week", java.time.LocalDate.class), rs.getBoolean("deposit_received"),
            null, false);
    }

    /** The quotation's grand total, derived exactly as DealQuotationRepository#mapQuotation derives it. */
    private static BigDecimal grandTotal(BigDecimal subtotal, String documentLanguage) {
        if (subtotal == null) {
            return null;
        }
        String language = documentLanguage == null ? WastageCalculator.DOCUMENT_LANGUAGE_TH : documentLanguage;
        return WastageCalculator.grandTotal(subtotal, WastageCalculator.vat(subtotal, language));
    }
}
