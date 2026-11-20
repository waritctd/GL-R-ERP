package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
import th.co.glr.hr.ticket.ProjectFold;
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
        SELECT t.ticket_id, t.code, t.parent_ticket_id, t.child_kind, t.lifecycle, t.title, t.customer_name, p.name AS project_name,
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
               %4$s AS awarded_buyer,
               (SELECT MIN(ep.expected_week) FROM sales.expected_payment ep
                 WHERE ep.ticket_id = t.ticket_id AND ep.kind = 'DEPOSIT' AND ep.status = 'OPEN') AS expected_deposit_week,
               (t.payment_status IN (:depositInHandStatuses)
                AND COALESCE(t.deposit_policy, '') NOT IN (:depositBypassPolicies)) AS deposit_received,
               la.kind AS last_activity_kind, la.note AS last_activity_note,
               EXISTS (SELECT 1 FROM sales.ticket ao WHERE ao.parent_ticket_id = t.ticket_id AND ao.child_kind = 'ADD_ON') AS has_add_on
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
        """.formatted(QuietRule.lastTouchedSql("t"), QuietRule.quietSql("t"), QuietRule.returningSql("t"), TicketRepository.AWARDED_BUYER_SQL);

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
            params().addValue("ownerId", ownerId), this::map), ownerId, false);
    }

    /** Every ACTIVE deal of every rep. Only for the sales_manager/ceo team overview. */
    public List<BoardDeal> activeDealsAllReps() {
        return fold(jdbc.query(BOARD_SELECT + " ORDER BY last_touched_at, t.ticket_id", params(), this::map), null, false);
    }

    /** The caller's own paused deals whose วันที่จะกลับมาดู has arrived — owner ({@code created_by}) scoped in the
     * WHERE clause exactly as {@link #activeDealsOwnedBy}. Same row order. */
    public List<BoardDeal> returningPausedDealsOwnedBy(long ownerId) {
        return fold(jdbc.query(RETURNING_SELECT + " AND t.created_by = :ownerId ORDER BY last_touched_at, t.ticket_id",
            params().addValue("ownerId", ownerId), this::map), ownerId, true);
    }

    /** Every rep's returning paused deals. Only for the sales_manager/ceo team overview, like
     * {@link #activeDealsAllReps}. */
    public List<BoardDeal> returningPausedDealsAllReps() {
        return fold(jdbc.query(RETURNING_SELECT + " ORDER BY last_touched_at, t.ticket_id", params(), this::map), null, true);
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
            .map(BoardDeal::leadTicketId).distinct().toList();
        return ids.isEmpty() ? Map.of() : tickets.paymentDueStates(ids);
    }

    /** {@code rows} with the state from {@code due} on each row from รอมัดจำ on; other rows unchanged, order kept. */
    public static List<BoardDeal> withPaymentDue(List<BoardDeal> rows,
                                                 Map<Long, TicketRepository.PaymentDueState> due) {
        return rows.stream().map(d -> withPaymentDue(d, due)).toList();
    }

    /** One row with its state from {@code due} when it is from รอมัดจำ on and has one; otherwise unchanged. */
    public static BoardDeal withPaymentDue(BoardDeal d, Map<Long, TicketRepository.PaymentDueState> due) {
        TicketRepository.PaymentDueState state = d.boardColumn() >= FIRST_MONEY_CHIP_COLUMN ? due.get(d.leadTicketId()) : null;
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
            null, false, 1, List.of(rs.getString("code")), 1, rs.getLong("ticket_id"), rs.getString("awarded_buyer"));
        return new Row(deal, rs.getString("code"), rs.getObject("parent_ticket_id", Long.class),
            rs.getString("child_kind"), rs.getString("lifecycle"), rs.getBoolean("has_add_on"));
    }

    /** A board row with the facts only the fold reads. */
    private record Row(BoardDeal deal, String code, Long parentId, String childKind, String lifecycle, boolean hasAddOns) {
        boolean isAddOn() {
            return "ADD_ON".equals(childKind) && parentId != null;
        }
    }

    /**
     * One row per project ({@link ProjectFold}, the single fold rule). {@code ownerScope} is the viewer's id for an
     * owner-scoped list (my-work): only that rep's own orders are counted, and a root that is not theirs lends its
     * code and title but is never linked to; null for the team lists, which see every order.
     */
    private List<BoardDeal> fold(List<Row> rows, Long ownerScope, boolean skipIfOpen) {
        ProjectFold.Ops<Row> ops = new ProjectFold.Ops<>() {
            public long id(Row r) { return r.deal().ticketId(); }
            public Long parentId(Row r) { return r.isAddOn() ? r.parentId() : null; }
            public boolean hasAddOns(Row r) { return r.hasAddOns(); }
            public String code(Row r) { return r.code(); }
            public boolean visible(Row r) { return ownerScope == null || r.deal().ownerId() == ownerScope; }
            public boolean open(Row r) {
                return "ACTIVE".equals(r.lifecycle()) && !"CLOSED_PAID".equals(r.deal().salesStage());
            }
            public boolean cancelled(Row r) { return "CANCELLED".equals(r.lifecycle()) || "CLOSED_LOST".equals(r.lifecycle()); }
            public Instant activity(Row r) { return r.deal().lastTouchedAt(); }
            public Row project(ProjectFold.Project<Row> p) { return projectRow(p); }
        };
        return ProjectFold.fold(rows, ops, this::relatedRows, skipIfOpen).stream().map(Row::deal).toList();
    }

    /** The chain's other orders (any lifecycle, any owner) the list lacks: the given roots and the add-ons of the
     * expanded roots, minus what is already listed. */
    private List<Row> relatedRows(java.util.Set<Long> roots, java.util.Set<Long> expandRoots, java.util.Set<Long> listed) {
        return jdbc.query(ROW_SELECT
                + " WHERE (t.ticket_id IN (:roots) OR (t.child_kind = 'ADD_ON' AND t.parent_ticket_id IN (:expand)))"
                + " AND t.ticket_id NOT IN (:listed)",
            params().addValue("roots", roots.isEmpty() ? java.util.Set.of(-1L) : roots).addValue("expand", expandRoots)
                .addValue("listed", listed), this::map);
    }

    private static Row projectRow(ProjectFold.Project<Row> p) {
        BoardDeal root = p.root().deal();
        BoardDeal lead = p.lead().deal();
        List<Row> counted = p.open().isEmpty() ? p.orders() : p.open();
        BigDecimal value = null;
        String currency = null;
        for (Row o : counted) {
            BigDecimal v = o.deal().value();
            if (v == null) {
                continue;
            }
            if (value == null) {
                value = v;
                currency = o.deal().currency();
            } else if (java.util.Objects.equals(currency, o.deal().currency())) {
                value = value.add(v);
            } // another currency is rare: the total then under-states, never mixes
        }
        BoardDeal owner = p.orders().contains(p.root()) ? root : lead;
        long linkId = p.mayOpenRoot() ? root.ticketId() : lead.ticketId();
        BoardDeal deal = lead.asProject(linkId, root, owner.ownerId(), owner.ownerName(), value, currency,
            p.count(), p.open().size(), p.codes(Row::code),
            ProjectFold.buyerLabel(p.orders(), o -> o.deal().awardedBuyerName()));
        return new Row(deal, p.root().code(), null, null, p.lead().lifecycle(), false);
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
