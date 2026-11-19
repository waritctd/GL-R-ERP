package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * {@code expected_payment} week and the deposit-received fact come from this statement. The due date and overdue flag
 * do NOT: the reads leave them null / false, and {@link #withPaymentDue} adds them — the finance summary's own
 * derivation ({@link TicketRepository#paymentDueStates}), never a copy — only where a screen shows them (fix round 1:
 * {@code SalesBoardService} calls it for the two lists {@code DealBoard} renders), and only for cards from รอมัดจำ on.
 * Every read keeps its WHERE clause unchanged, so the new fields ride on rows the caller could already see.
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
        SELECT t.ticket_id, t.code, t.parent_ticket_id, t.child_kind, t.title, t.customer_name, p.name AS project_name,
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

    /** The first board column whose cards carry a money chip: รอมัดจำ (rep code S6, DealStage.boardColumnOf). */
    static final int FIRST_MONEY_CHIP_COLUMN = 4;

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
        return fold(jdbc.query(BOARD_SELECT + " AND t.created_by = :ownerId ORDER BY last_touched_at, t.ticket_id",
            params().addValue("ownerId", ownerId), this::map));
    }

    /** Every ACTIVE deal of every rep. Only for the sales_manager/ceo team overview. */
    public List<BoardDeal> activeDealsAllReps() {
        return fold(jdbc.query(BOARD_SELECT + " ORDER BY last_touched_at, t.ticket_id", params(), this::map));
    }

    /** The caller's own paused deals whose วันที่จะกลับมาดู has arrived — owner ({@code created_by}) scoped in the
     * WHERE clause exactly as {@link #activeDealsOwnedBy}. Same row order. */
    public List<BoardDeal> returningPausedDealsOwnedBy(long ownerId) {
        return fold(jdbc.query(RETURNING_SELECT + " AND t.created_by = :ownerId ORDER BY last_touched_at, t.ticket_id",
            params().addValue("ownerId", ownerId), this::map));
    }

    /** Every rep's returning paused deals. Only for the sales_manager/ceo team overview, like
     * {@link #activeDealsAllReps}. */
    public List<BoardDeal> returningPausedDealsAllReps() {
        return fold(jdbc.query(RETURNING_SELECT + " ORDER BY last_touched_at, t.ticket_id", params(), this::map));
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

    /**
     * The rows with the finance summary's due date and overdue flag (TicketRepository#paymentDueStates) on every card
     * from รอมัดจำ on ({@link #FIRST_MONEY_CHIP_COLUMN}); earlier cards, which never show a money chip, keep null /
     * false. At most ONE statement, none when no row qualifies; rows keep their order. Called by SalesBoardService only
     * for the lists DealBoard renders. The ids passed are bounded by those rows: the board reads carry no LIMIT, so it is
     * every open deal at S6 or later in the list (the caller's own on my-work, the team's on team-board).
     */
    public List<BoardDeal> withPaymentDue(List<BoardDeal> rows) {
        return withPaymentDue(rows, paymentDueOf(rows));
    }

    /**
     * The due state of every row from รอมัดจำ on, in ONE statement over all of them (none when no row qualifies) —
     * for a caller that shows rows of several lists as board cards (my-work: the board AND the returning paused rows of
     * ควรตามต่อ) and must not pay one statement per list.
     */
    public Map<Long, TicketRepository.PaymentDueState> paymentDueOf(java.util.Collection<BoardDeal> rows) {
        List<Long> ids = rows.stream().filter(d -> d.boardColumn() >= FIRST_MONEY_CHIP_COLUMN)
            .map(BoardDeal::ticketId).distinct().toList();
        return ids.isEmpty() ? Map.of() : tickets.paymentDueStates(ids);
    }

    /** {@code rows} with the state from {@code due} on each row from รอมัดจำ on; other rows unchanged, order kept. */
    public static List<BoardDeal> withPaymentDue(List<BoardDeal> rows,
                                                 Map<Long, TicketRepository.PaymentDueState> due) {
        return rows.stream().map(d -> withPaymentDue(d, due)).toList();
    }

    /** One row with its state from {@code due} when it is from รอมัดจำ on and has one; otherwise unchanged. */
    public static BoardDeal withPaymentDue(BoardDeal d, Map<Long, TicketRepository.PaymentDueState> due) {
        TicketRepository.PaymentDueState state = d.boardColumn() >= FIRST_MONEY_CHIP_COLUMN ? due.get(d.ticketId()) : null;
        return state == null ? d : d.withPaymentDue(state.dueDate(), state.overdue());
    }

    private Row map(ResultSet rs, int rowNum) throws SQLException {
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
        BoardDeal deal = new BoardDeal(
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
            null, false, 1, List.of(rs.getString("code")));
        return new Row(deal, rs.getString("code"), rs.getObject("parent_ticket_id", Long.class),
            rs.getString("child_kind"));
    }

    /** A board row with the facts only the fold reads. */
    private record Row(BoardDeal deal, String code, Long parentId, String childKind) {
        boolean isAddOn() {
            return "ADD_ON".equals(childKind) && parentId != null;
        }
    }

    /** One project's row while the orders are folded in: the card, its order codes and its summed value. */
    private static final class Slot {
        BoardDeal card;
        final List<String> codes = new ArrayList<>();
        int orders = 1;

        Slot(BoardDeal card, String code) {
            this.card = card;
            codes.add(code);
        }

        /** One more order: its code, and its value when the currencies match (other currencies are rare; the card
         * then keeps its own value, an under-stated mixed-currency total). */
        void add(Row order) {
            orders++;
            codes.add(order.code());
            BoardDeal d = order.deal();
            if (d.value() == null) {
                return;
            }
            if (card.value() == null) {
                card = card.withOrders(orders, d.value(), d.currency(), codes);
            } else if (java.util.Objects.equals(card.currency(), d.currency())) {
                card = card.withOrders(orders, card.value().add(d.value()), card.currency(), codes);
            }
        }

        BoardDeal done() {
            return card.withOrders(orders, card.value(), card.currency(), List.copyOf(codes));
        }
    }

    /**
     * One row per project (สั่งเพิ่ม, M3). An ADD_ON order never shows as its own row: it folds into its root when the
     * root is in the list (the root keeps its place; {@code orderCount} and {@code value} take the add-on). When the
     * root is not in the list (closed or paused) the project's card is the root, read here, carrying the live
     * add-on's stage and quiet state, so a project with a live add-on is open and stays under the root's number.
     * One exception: a root owned by someone else than the add-on (the caller's list is owner-scoped) is never read
     * for them; that add-on stays as its own row rather than leak the root. BID_SPLIT children are ordinary rows.
     * Row order is the query's (the first row of a project fixes its place).
     */
    private List<BoardDeal> fold(List<Row> rows) {
        java.util.Set<Long> listed = new java.util.HashSet<>();
        for (Row r : rows) {
            listed.add(r.deal().ticketId());
        }
        List<Long> missing = rows.stream().filter(Row::isAddOn).map(Row::parentId)
            .filter(id -> !listed.contains(id)).distinct().toList();
        Map<Long, Row> roots = new java.util.HashMap<>();
        if (!missing.isEmpty()) {
            for (Row root : jdbc.query(ROW_SELECT + " WHERE t.ticket_id IN (:rootIds)",
                params().addValue("rootIds", missing), this::map)) {
                roots.put(root.deal().ticketId(), root);
            }
        }
        Map<Long, Slot> slots = new LinkedHashMap<>();
        List<Object> out = new ArrayList<>();
        for (Row r : rows) {
            if (!r.isAddOn()) {
                Slot slot = new Slot(r.deal(), r.code());
                slots.put(r.deal().ticketId(), slot);
                out.add(slot);
            } else if (!listed.contains(r.parentId()) && !slots.containsKey(r.parentId())) {
                Row root = roots.get(r.parentId());
                if (root == null || root.deal().ownerId() != r.deal().ownerId()) {
                    out.add(r.deal()); // root unreadable for this list: never hide live work
                    continue;
                }
                Slot slot = new Slot(r.deal().underProject(root.deal())
                    .withOrders(1, root.deal().value(), root.deal().currency(), List.of()), root.code());
                slots.put(r.parentId(), slot);
                out.add(slot);
            }
        }
        for (Row r : rows) {
            Slot slot = r.isAddOn() ? slots.get(r.parentId()) : null;
            if (slot != null) {
                slot.add(r);
            }
        }
        return out.stream().map(o -> o instanceof Slot s ? s.done() : (BoardDeal) o).toList();
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
