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

/** Persistence for the CEO discount-approval workflow (V155) — see that migration's table comment for the
 * append-only, price-bound state machine. Mirrors CustomerQuotationRepository's own shape/idioms. */
@Repository
public class DiscountApprovalRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public DiscountApprovalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ensures a live ask exists for exactly this (item, price) pair — the single write path every below-minimum
     * save goes through (CustomerQuotationService#raiseDiscountApprovalsIfNeeded). DO NOTHING below targets it
     * directly, so at most one of two racing callers ever sees true. */
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

    /** THE issue-gate question: has exactly currentFinalUnitPrice — the item's price RIGHT NOW — been APPROVED for
     * this item. */
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

    /** Current-status-per-line for one quotation (the sales/CEO-facing panel on that document). An item with no
     * discount at all currently has no row that could match (nothing is ever inserted for a price at/above the CEO
     * minimum), so it is silently absent from the result — exactly "nothing to show". */
    public List<DiscountApprovalDto> findCurrentByQuotationId(long quotationId) {
        return jdbc.query(baseSelect() + """
             WHERE qi.quotation_id = :quotationId
               AND qi.final_unit_price = da.requested_final_unit_price
             ORDER BY qi.quotation_item_id, da.discount_approval_id DESC
            """, java.util.Map.of("quotationId", quotationId), (rs, rowNum) -> mapRow(rs));
    }

    /** The CEO's cross-quotation queue. */
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

    /** Compare-and-set PENDING -> APPROVED. approved_final_unit_price is copied from this SAME row's own
     * requested_final_unit_price (never from a caller-supplied value — there is no "counter-offer" concept), which
     * is the price-binding guarantee at the write site. */
    public int approve(long discountApprovalId, long decidedBy) {
        return jdbc.update("""
            UPDATE sales.quotation_item_discount_approval
               SET status = 'APPROVED', decided_by = :decidedBy, decided_at = now(),
                   approved_final_unit_price = requested_final_unit_price
             WHERE discount_approval_id = :id AND status = 'PENDING'
            """,
            new MapSqlParameterSource().addValue("id", discountApprovalId).addValue("decidedBy", decidedBy));
    }

    /** Compare-and-set PENDING -> REJECTED. reason is validated non-blank by the caller
     * (DiscountApprovalService#reject) — this layer only persists it. */
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
