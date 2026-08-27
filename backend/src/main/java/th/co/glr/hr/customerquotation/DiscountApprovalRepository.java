package th.co.glr.hr.customerquotation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.customerquotation.DiscountApprovalDtos.DiscountApprovalDto;

/**
 * Persistence for the CEO discount-approval workflow (V155) — see that migration's table comment
 * for the append-only, price-bound state machine. Persistence only, no permission checks, no
 * policy decisions; see {@link CustomerQuotationService} (the ask side: {@link #ensureRequested},
 * {@link #isApproved}) and {@link DiscountApprovalService} (the CEO decision side: {@link
 * #approve}, {@link #reject}) for all of that. Mirrors {@code CustomerQuotationRepository}'s own
 * shape/idioms.
 */
@Repository
public class DiscountApprovalRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public DiscountApprovalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ensures a live ask exists for exactly this (item, price) pair — the single write path every
     * below-minimum save goes through ({@code CustomerQuotationService#raiseDiscountApprovalsIfNeeded}).
     * A no-op (returns {@code false}) when this EXACT price is already covered — either already
     * APPROVED (nothing to ask for; issue() will already accept it) or already the live PENDING
     * ask for this item (repeat saves at an unchanged price never spam duplicate CEO requests).
     * Otherwise inserts a fresh PENDING row and returns {@code true} — including when the item's
     * most recent decision at this SAME price was REJECTED: any save that lands back on a
     * previously-rejected number re-opens it (a deliberate simplification over requiring a
     * literally-different number — see the class Javadoc on {@code CustomerQuotationService}).
     *
     * <p>Race-safe without an advisory lock: {@code uq_discount_approval_pending_item_price} (V155)
     * is the actual guard against two concurrent callers both inserting a PENDING row for the same
     * (item, price) — the {@code ON CONFLICT ... DO NOTHING} below targets it directly, so at most
     * one of two racing callers ever sees {@code true}.
     */
    public boolean ensureRequested(long quotationItemId, BigDecimal requestedFinalUnitPrice, long requestedBy) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("itemId", quotationItemId)
            .addValue("price", requestedFinalUnitPrice)
            .addValue("requestedBy", requestedBy);
        Boolean alreadyApproved = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM sales.quotation_item_discount_approval
                 WHERE quotation_item_id = :itemId AND status = 'APPROVED'
                   AND approved_final_unit_price = :price)
            """, params, Boolean.class);
        if (Boolean.TRUE.equals(alreadyApproved)) {
            return false;
        }
        List<Long> inserted = jdbc.queryForList("""
            INSERT INTO sales.quotation_item_discount_approval
                (quotation_item_id, status, requested_final_unit_price, requested_by, requested_at)
            VALUES (:itemId, 'PENDING', :price, :requestedBy, now())
            ON CONFLICT (quotation_item_id, requested_final_unit_price) WHERE status = 'PENDING'
                DO NOTHING
            RETURNING discount_approval_id
            """, params, Long.class);
        return !inserted.isEmpty();
    }

    /**
     * THE issue-gate question: has exactly {@code currentFinalUnitPrice} — the item's price RIGHT
     * NOW — been APPROVED for this item. An approval for any other price (including a price this
     * same item was approved at before it was edited) does not count; this is what makes the
     * price-binding invariant hold at the one call site that actually blocks a document
     * ({@code CustomerQuotationService#issue}).
     */
    public boolean isApproved(long quotationItemId, BigDecimal currentFinalUnitPrice) {
        Boolean approved = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM sales.quotation_item_discount_approval
                 WHERE quotation_item_id = :itemId AND status = 'APPROVED'
                   AND approved_final_unit_price = :price)
            """,
            new MapSqlParameterSource().addValue("itemId", quotationItemId).addValue("price", currentFinalUnitPrice),
            Boolean.class);
        return Boolean.TRUE.equals(approved);
    }

    /**
     * Current-status-per-line for one quotation (the sales/CEO-facing panel on that document).
     * Restricted to rows whose {@code requested_final_unit_price} equals the item's CURRENT
     * {@code final_unit_price} — NOT simply "the latest row for this item" — so a line that
     * bounced back to an already-decided price shows that decision (e.g. re-shows APPROVED
     * instead of a stale REJECTED from an intermediate price the line no longer holds), and a
     * line whose live ask has moved on from an old PENDING row never shows the abandoned one.
     * Among rows still matching the current price (there can be more than one only when the same
     * exact price was rejected and then re-asked — see {@link #ensureRequested}), the highest id
     * (the most recent decision) wins via {@code DISTINCT ON}. An item with no discount at all
     * currently has no row that could match (nothing is ever inserted for a price at/above the
     * CEO minimum), so it is silently absent from the result — exactly "nothing to show".
     */
    public List<DiscountApprovalDto> findCurrentByQuotationId(long quotationId) {
        return jdbc.query(baseSelect() + """
             WHERE qi.quotation_id = :quotationId
               AND qi.final_unit_price = da.requested_final_unit_price
             ORDER BY qi.quotation_item_id, da.discount_approval_id DESC
            """, java.util.Map.of("quotationId", quotationId), (rs, rowNum) -> mapRow(rs));
    }

    /**
     * The CEO's cross-quotation queue. Same current-price filter as {@link
     * #findCurrentByQuotationId} (a PENDING row whose item has since moved to a different price is
     * a stale ask nobody needs to act on — it is simply superseded by whatever row now matches the
     * live price) — so this never shows the CEO a request that would have zero effect if approved.
     */
    public List<DiscountApprovalDto> findPending() {
        return jdbc.query(baseSelect() + """
             WHERE da.status = 'PENDING'
               AND qi.final_unit_price = da.requested_final_unit_price
             ORDER BY da.requested_at
            """, java.util.Map.of(), (rs, rowNum) -> mapRow(rs));
    }

    public Optional<DiscountApprovalDto> findById(long discountApprovalId) {
        try {
            DiscountApprovalDto dto = jdbc.queryForObject(baseSelect() + " WHERE da.discount_approval_id = :id",
                java.util.Map.of("id", discountApprovalId), (rs, rowNum) -> mapRow(rs));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Compare-and-set PENDING -> APPROVED. {@code approved_final_unit_price} is copied from this
     * SAME row's own {@code requested_final_unit_price} (never from a caller-supplied value —
     * there is no "counter-offer" concept), which is the price-binding guarantee at the write
     * site. Rowcount 0 means not open for approval (already decided, or the id does not exist) —
     * the service distinguishes those for its own error message. */
    public int approve(long discountApprovalId, long decidedBy) {
        return jdbc.update("""
            UPDATE sales.quotation_item_discount_approval
               SET status = 'APPROVED', decided_by = :decidedBy, decided_at = now(),
                   approved_final_unit_price = requested_final_unit_price
             WHERE discount_approval_id = :id AND status = 'PENDING'
            """,
            new MapSqlParameterSource().addValue("id", discountApprovalId).addValue("decidedBy", decidedBy));
    }

    /** Compare-and-set PENDING -> REJECTED. {@code reason} is validated non-blank by the caller
     * ({@link DiscountApprovalService#reject}) — this layer only persists it. */
    public int reject(long discountApprovalId, long decidedBy, String reason) {
        return jdbc.update("""
            UPDATE sales.quotation_item_discount_approval
               SET status = 'REJECTED', decided_by = :decidedBy, decided_at = now(), rejection_reason = :reason
             WHERE discount_approval_id = :id AND status = 'PENDING'
            """,
            new MapSqlParameterSource().addValue("id", discountApprovalId).addValue("decidedBy", decidedBy)
                .addValue("reason", reason));
    }

    private String baseSelect() {
        return """
            SELECT da.discount_approval_id, da.quotation_item_id, qi.quotation_id, q.pricing_request_id,
                   q.number AS quotation_number, qi.description AS item_description,
                   da.status, da.requested_final_unit_price, da.requested_by,
                   NULLIF(TRIM(CONCAT_WS(' ', re.first_name_th, re.last_name_th)), '') AS requested_by_name,
                   da.requested_at, da.decided_by,
                   NULLIF(TRIM(CONCAT_WS(' ', de.first_name_th, de.last_name_th)), '') AS decided_by_name,
                   da.decided_at, da.approved_final_unit_price, da.rejection_reason
              FROM sales.quotation_item_discount_approval da
              JOIN sales.quotation_item qi ON qi.quotation_item_id = da.quotation_item_id
              JOIN sales.quotation q ON q.quotation_id = qi.quotation_id
              LEFT JOIN hr.employee re ON re.employee_id = da.requested_by
              LEFT JOIN hr.employee de ON de.employee_id = da.decided_by
            """;
    }

    private DiscountApprovalDto mapRow(ResultSet rs) throws SQLException {
        return new DiscountApprovalDto(
            rs.getLong("discount_approval_id"),
            rs.getLong("quotation_item_id"),
            rs.getLong("quotation_id"),
            rs.getLong("pricing_request_id"),
            rs.getString("quotation_number"),
            rs.getString("item_description"),
            rs.getString("status"),
            rs.getBigDecimal("requested_final_unit_price"),
            rs.getLong("requested_by"),
            rs.getString("requested_by_name"),
            instant(rs, "requested_at"),
            nullableLong(rs, "decided_by"),
            rs.getString("decided_by_name"),
            instant(rs, "decided_at"),
            rs.getBigDecimal("approved_final_unit_price"),
            rs.getString("rejection_reason")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
