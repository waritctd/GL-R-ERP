package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.salesboard.SalesBoardDtos.BoardDeal;
import th.co.glr.hr.ticket.DealStage;
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
 * last touch ascending, then ticket id — mockApi.salesBoard mirrors this ORDER BY.
 */
@Repository
public class SalesBoardRepository {
    /** Reached the customer (DEAL_DIRECT APPROVED, legacy ISSUED/SENT/ACCEPTED), plus EXPIRED as a flagged fallback. */
    private static final List<String> VALUE_STATUSES = List.of("APPROVED", "ISSUED", "SENT", "ACCEPTED", "EXPIRED");

    private static final String BOARD_SELECT = """
        SELECT t.ticket_id, t.title, t.customer_name, p.name AS project_name,
               t.created_by AS owner_id,
               NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS owner_name,
               t.sales_stage, t.designer_name, t.win_probability, t.expected_quarter, t.imported_value,
               %1$s AS last_touched_at,
               %2$s AS quiet,
               q.total_amount, q.document_language, q.currency, q.expired,
               la.kind AS last_activity_kind, la.note AS last_activity_note
          FROM sales.ticket t
          JOIN hr.employee e ON e.employee_id = t.created_by
          LEFT JOIN customers.project p ON p.project_id = t.project_id
          LEFT JOIN LATERAL (
              SELECT vq.total_amount, vq.document_language, vq.currency, vq.doc_status = 'EXPIRED' AS expired
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
         WHERE t.lifecycle = 'ACTIVE'
           AND COALESCE(t.sales_stage, '') <> 'CLOSED_PAID'
        """.formatted(QuietRule.lastTouchedSql("t"), QuietRule.quietSql("t"));

    private final NamedParameterJdbcTemplate jdbc;
    private final QuietRule quietRule;

    public SalesBoardRepository(NamedParameterJdbcTemplate jdbc, QuietRule quietRule) {
        this.jdbc = jdbc;
        this.quietRule = quietRule;
    }

    /** The caller's own ACTIVE deals — the deal's owner ({@code created_by}), not its keyer. */
    public List<BoardDeal> activeDealsOwnedBy(long ownerId) {
        return jdbc.query(BOARD_SELECT + " AND t.created_by = :ownerId ORDER BY last_touched_at, t.ticket_id",
            Map.of("ownerId", ownerId, "valueStatuses", VALUE_STATUSES, "quietDays", quietRule.quietDays()),
            this::map);
    }

    /** Every ACTIVE deal of every rep. Only for the sales_manager/ceo team overview. */
    public List<BoardDeal> activeDealsAllReps() {
        return jdbc.query(BOARD_SELECT + " ORDER BY last_touched_at, t.ticket_id",
            Map.of("valueStatuses", VALUE_STATUSES, "quietDays", quietRule.quietDays()), this::map);
    }

    private BoardDeal map(ResultSet rs, int rowNum) throws SQLException {
        String stage = rs.getString("sales_stage");
        Timestamp lastTouched = rs.getTimestamp("last_touched_at");
        long quietMillis = lastTouched == null ? 0 : System.currentTimeMillis() - lastTouched.getTime();
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
        return new BoardDeal(
            rs.getLong("ticket_id"), rs.getString("title"), rs.getString("customer_name"),
            rs.getString("project_name"), rs.getLong("owner_id"), rs.getString("owner_name"),
            stage, DealStage.repCodeOf(stage), DealStage.boardColumnOf(stage),
            value, currency, rs.getBoolean("expired"),
            rs.getString("designer_name"), winProbability, rs.getString("expected_quarter"),
            lastTouched == null ? null : lastTouched.toInstant(),
            (int) Math.max(0, quietMillis / 86_400_000L), rs.getBoolean("quiet"),
            rs.getString("last_activity_kind"), rs.getString("last_activity_note"));
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
