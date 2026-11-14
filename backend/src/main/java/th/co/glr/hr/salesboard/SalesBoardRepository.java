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
 * The board read model: every ACTIVE deal with its last touch ({@link QuietRule}) and the subtotal + language of its
 * latest customer-facing quotation. Scope lives in the WHERE clause: {@link #activeDealsOwnedBy} adds
 * {@code t.created_by = :ownerId}; {@link #activeDealsAllReps} has no owner predicate and is reached only through
 * {@code SalesBoardService}'s sales_manager/ceo gate.
 */
@Repository
public class SalesBoardRepository {
    /** A quotation that has reached the customer: DEAL_DIRECT APPROVED, legacy ISSUED and its later SENT/ACCEPTED. */
    private static final List<String> VALUE_STATUSES = List.of("APPROVED", "ISSUED", "SENT", "ACCEPTED");

    private static final String BOARD_SELECT = """
        SELECT t.ticket_id, t.title, t.customer_name, p.name AS project_name,
               t.created_by AS owner_id,
               NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS owner_name,
               t.sales_stage, t.designer_name, t.win_probability, t.expected_quarter,
               %1$s AS last_touched_at,
               %2$s AS quiet,
               q.total_amount, q.document_language
          FROM sales.ticket t
          JOIN hr.employee e ON e.employee_id = t.created_by
          LEFT JOIN customers.project p ON p.project_id = t.project_id
          LEFT JOIN LATERAL (
              SELECT vq.total_amount, vq.document_language
                FROM sales.quotation vq
               WHERE vq.ticket_id = t.ticket_id AND vq.doc_status IN (:valueStatuses)
               ORDER BY COALESCE(vq.approved_at, vq.issued_at) DESC NULLS LAST, vq.quotation_id DESC
               LIMIT 1
          ) q ON TRUE
         WHERE t.lifecycle = 'ACTIVE'
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
        return new BoardDeal(
            rs.getLong("ticket_id"), rs.getString("title"), rs.getString("customer_name"),
            rs.getString("project_name"), rs.getLong("owner_id"), rs.getString("owner_name"),
            stage, DealStage.repCodeOf(stage), DealStage.boardColumnOf(stage),
            grandTotal(rs.getBigDecimal("total_amount"), rs.getString("document_language")),
            rs.getString("designer_name"), winProbability, rs.getString("expected_quarter"),
            lastTouched == null ? null : lastTouched.toInstant(),
            (int) Math.max(0, quietMillis / 86_400_000L), rs.getBoolean("quiet"));
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
