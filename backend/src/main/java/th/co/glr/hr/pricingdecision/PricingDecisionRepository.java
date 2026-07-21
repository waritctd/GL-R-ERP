package th.co.glr.hr.pricingdecision;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesViewDto;

/**
 * Persistence for the Step 3 aggregate (V72): {@code sales.pricing_decision},
 * {@code sales.pricing_decision_item}. Persistence only — no permission checks, no workflow
 * validation, no margin/price arithmetic; see {@link PricingDecisionService} for all of that.
 */
@Repository
public class PricingDecisionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PricingDecisionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextDecisionCode() {
        Long seq = jdbc.queryForObject("SELECT nextval('sales.pricing_decision_code_seq')", Map.of(), Long.class);
        return "PCD-" + Year.now() + "-" + String.format("%04d", seq == null ? 0 : seq);
    }

    /** Serializes every mutating operation on a given pricing request against every other one
     * (create-review, approve, return-to-import, and — via the same key — Step 2's own
     * costing/customer-revision advisory locks) for the lifetime of the caller's transaction. */
    public void lockPricingRequest(long pricingRequestId) {
        jdbc.query("SELECT pg_advisory_xact_lock(:pricingRequestId)",
            Map.of("pricingRequestId", pricingRequestId), (rs, rowNum) -> 0);
    }

    public record CreateDecisionResult(long decisionId, boolean created) {}

    /** Caller must hold {@link #lockPricingRequest} for the current transaction before calling
     * this — mirrors {@code PricingCostingRepository.createDraft}'s own contract. */
    public CreateDecisionResult createDraft(long pricingRequestId, long pricingCostingId, BigDecimal defaultMarginPct,
                                            String currency, BigDecimal fxRateUsed, String fxSource,
                                            LocalDate fxEffectiveDate, String ceoNote, String clientRequestId,
                                            long actorId) {
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            Optional<PricingDecisionDto> replay = findByClientRequestId(actorId, clientRequestId);
            if (replay.isPresent()) {
                return new CreateDecisionResult(replay.get().id(), false);
            }
        }
        Optional<PricingDecisionDto> open = findOpenDraft(pricingRequestId);
        if (open.isPresent()) {
            return new CreateDecisionResult(open.get().id(), false);
        }
        Integer nextVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(decision_version_no), 0) + 1
              FROM sales.pricing_decision
             WHERE pricing_request_id = :pricingRequestId
            """, Map.of("pricingRequestId", pricingRequestId), Integer.class);
        Long id = jdbc.queryForObject("""
            INSERT INTO sales.pricing_decision
                (decision_code, pricing_request_id, pricing_costing_id, decision_version_no, status,
                 default_margin_pct, currency, fx_rate_used, fx_source, fx_effective_date, ceo_note,
                 client_request_id, created_by)
            VALUES
                (:code, :pricingRequestId, :pricingCostingId, :versionNo, 'DRAFT',
                 :defaultMarginPct, :currency, :fxRateUsed, :fxSource, :fxEffectiveDate, :ceoNote,
                 CAST(:clientRequestId AS uuid), :createdBy)
            RETURNING pricing_decision_id
            """,
            new MapSqlParameterSource()
                .addValue("code", nextDecisionCode())
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("pricingCostingId", pricingCostingId)
                .addValue("versionNo", nextVersion == null ? 1 : nextVersion)
                .addValue("defaultMarginPct", defaultMarginPct)
                .addValue("currency", currency)
                .addValue("fxRateUsed", fxRateUsed)
                .addValue("fxSource", fxSource)
                .addValue("fxEffectiveDate", fxEffectiveDate)
                .addValue("ceoNote", ceoNote)
                .addValue("clientRequestId", clientRequestId)
                .addValue("createdBy", actorId),
            Long.class);
        return new CreateDecisionResult(id == null ? 0L : id, true);
    }

    public record WriteItem(
        long pricingRequestItemId, long pricingCostingItemId, String requestedUnitBasis,
        BigDecimal requestedQuantity, BigDecimal normalizedQuantityPieces,
        BigDecimal frozenLandedCostPerPieceThb, BigDecimal frozenLandedCostPerRequestedUnitThb,
        String currency, BigDecimal proposedMarginPct, BigDecimal proposedSellingPrice) {}

    public void insertItems(long decisionId, List<WriteItem> items) {
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            WriteItem item = items.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("decisionId", decisionId)
                .addValue("pricingRequestItemId", item.pricingRequestItemId())
                .addValue("pricingCostingItemId", item.pricingCostingItemId())
                .addValue("requestedUnitBasis", item.requestedUnitBasis())
                .addValue("requestedQuantity", item.requestedQuantity())
                .addValue("normalizedQuantityPieces", item.normalizedQuantityPieces())
                .addValue("frozenLandedCostPerPieceThb", item.frozenLandedCostPerPieceThb())
                .addValue("frozenLandedCostPerRequestedUnitThb", item.frozenLandedCostPerRequestedUnitThb())
                .addValue("currency", item.currency())
                .addValue("proposedMarginPct", item.proposedMarginPct())
                .addValue("proposedSellingPrice", item.proposedSellingPrice());
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.pricing_decision_item
                (pricing_decision_id, pricing_request_item_id, pricing_costing_item_id, requested_unit_basis,
                 requested_quantity, normalized_quantity_pieces, frozen_landed_cost_per_piece_thb,
                 frozen_landed_cost_per_requested_unit_thb, currency, proposed_margin_pct,
                 proposed_selling_price_per_requested_unit)
            VALUES
                (:decisionId, :pricingRequestItemId, :pricingCostingItemId, :requestedUnitBasis,
                 :requestedQuantity, :normalizedQuantityPieces, :frozenLandedCostPerPieceThb,
                 :frozenLandedCostPerRequestedUnitThb, :currency, :proposedMarginPct,
                 :proposedSellingPrice)
            """, batch);
    }

    public int updateDecisionNote(long id, String ceoNote) {
        return jdbc.update("""
            UPDATE sales.pricing_decision
               SET ceo_note = COALESCE(:ceoNote, ceo_note),
                   updated_at = now()
             WHERE pricing_decision_id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", id).addValue("ceoNote", ceoNote));
    }

    public record ItemUpdate(
        long itemId, BigDecimal marginPct, BigDecimal sellingPrice, BigDecimal discountCeilingPct,
        BigDecimal minimumSellingPrice, String decisionNote) {}

    /** Returns the number of rows actually touched — the caller compares this against the
     * requested item count to detect an itemId that does not belong to this decision. */
    public int updateItems(long decisionId, List<ItemUpdate> updates) {
        if (updates.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[updates.size()];
        for (int i = 0; i < updates.size(); i++) {
            ItemUpdate u = updates.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("decisionId", decisionId)
                .addValue("itemId", u.itemId())
                .addValue("marginPct", u.marginPct())
                .addValue("sellingPrice", u.sellingPrice())
                .addValue("discountCeilingPct", u.discountCeilingPct())
                .addValue("minimumSellingPrice", u.minimumSellingPrice())
                .addValue("decisionNote", u.decisionNote());
        }
        int[] counts = jdbc.batchUpdate("""
            UPDATE sales.pricing_decision_item
               SET proposed_margin_pct = COALESCE(:marginPct, proposed_margin_pct),
                   proposed_selling_price_per_requested_unit =
                       COALESCE(:sellingPrice, proposed_selling_price_per_requested_unit),
                   discount_ceiling_pct = COALESCE(:discountCeilingPct, discount_ceiling_pct),
                   minimum_selling_price_per_requested_unit =
                       COALESCE(:minimumSellingPrice, minimum_selling_price_per_requested_unit),
                   decision_note = COALESCE(:decisionNote, decision_note),
                   updated_at = now()
             WHERE pricing_decision_item_id = :itemId
               AND pricing_decision_id = :decisionId
               AND EXISTS (
                   SELECT 1 FROM sales.pricing_decision pd
                    WHERE pd.pricing_decision_id = :decisionId AND pd.status = 'DRAFT')
            """, batch);
        int total = 0;
        for (int c : counts) {
            total += c;
        }
        return total;
    }

    public int updateDefaultMargin(long decisionId, BigDecimal defaultMarginPct) {
        return jdbc.update("""
            UPDATE sales.pricing_decision
               SET default_margin_pct = :defaultMarginPct,
                   updated_at = now()
             WHERE pricing_decision_id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", decisionId).addValue("defaultMarginPct", defaultMarginPct));
    }

    public record ApprovedItem(long itemId, BigDecimal approvedMarginPct, BigDecimal approvedSellingPrice) {}

    public void approveItems(long decisionId, List<ApprovedItem> items) {
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ApprovedItem item = items.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("decisionId", decisionId)
                .addValue("itemId", item.itemId())
                .addValue("approvedMarginPct", item.approvedMarginPct())
                .addValue("approvedSellingPrice", item.approvedSellingPrice());
        }
        jdbc.batchUpdate("""
            UPDATE sales.pricing_decision_item
               SET approved_margin_pct = :approvedMarginPct,
                   approved_selling_price_per_requested_unit = :approvedSellingPrice,
                   updated_at = now()
             WHERE pricing_decision_item_id = :itemId AND pricing_decision_id = :decisionId
            """, batch);
    }

    /**
     * Compare-and-set DRAFT -&gt; APPROVED. Rowcount 0 means either the decision was not DRAFT
     * (already approved/returned by a concurrent caller, or this is a stale id) — the caller
     * (service), inside the same advisory-lock-held transaction, is responsible for
     * distinguishing "already approved by me" (idempotent replay via
     * {@code approveClientRequestId}) from a genuine conflict.
     */
    public int approve(long id, long actorId, String ceoNote, String approveClientRequestId) {
        return jdbc.update("""
            UPDATE sales.pricing_decision
               SET status = 'APPROVED',
                   approved_by = :actorId,
                   approved_at = now(),
                   ceo_note = COALESCE(:ceoNote, ceo_note),
                   approve_client_request_id = CAST(:approveClientRequestId AS uuid),
                   updated_at = now()
             WHERE pricing_decision_id = :id AND status = 'DRAFT'
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("actorId", actorId)
                .addValue("ceoNote", ceoNote)
                .addValue("approveClientRequestId", approveClientRequestId));
    }

    public int returnToImport(long id, String returnReason) {
        return jdbc.update("""
            UPDATE sales.pricing_decision
               SET status = 'RETURNED',
                   return_reason = :reason,
                   returned_at = now(),
                   updated_at = now()
             WHERE pricing_decision_id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", id).addValue("reason", returnReason));
    }

    public Optional<PricingDecisionDto> find(long id) {
        try {
            PricingDecisionDto dto = jdbc.queryForObject(baseSelect() + " WHERE pd.pricing_decision_id = :id",
                Map.of("id", id), (rs, rowNum) -> mapDecision(rs, findItems(id)));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<PricingDecisionDto> findOpenDraft(long pricingRequestId) {
        try {
            PricingDecisionDto dto = jdbc.queryForObject(baseSelect() + """
                 WHERE pd.pricing_request_id = :pricingRequestId AND pd.status = 'DRAFT'
                """,
                Map.of("pricingRequestId", pricingRequestId),
                (rs, rowNum) -> mapDecision(rs, findItems(rs.getLong("pricing_decision_id"))));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<PricingDecisionDto> findByClientRequestId(long createdBy, String clientRequestId) {
        try {
            PricingDecisionDto dto = jdbc.queryForObject(baseSelect() + """
                 WHERE pd.created_by = :createdBy AND pd.client_request_id = CAST(:clientRequestId AS uuid)
                """,
                new MapSqlParameterSource().addValue("createdBy", createdBy).addValue("clientRequestId", clientRequestId),
                (rs, rowNum) -> mapDecision(rs, findItems(rs.getLong("pricing_decision_id"))));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<PricingDecisionDto> findByApproveClientRequestId(long approvedBy, String approveClientRequestId) {
        try {
            PricingDecisionDto dto = jdbc.queryForObject(baseSelect() + """
                 WHERE pd.approved_by = :approvedBy
                   AND pd.approve_client_request_id = CAST(:clientRequestId AS uuid)
                """,
                new MapSqlParameterSource().addValue("approvedBy", approvedBy).addValue("clientRequestId", approveClientRequestId),
                (rs, rowNum) -> mapDecision(rs, findItems(rs.getLong("pricing_decision_id"))));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<PricingDecisionDto> findByPricingRequest(long pricingRequestId) {
        return jdbc.query(baseSelect() + """
             WHERE pd.pricing_request_id = :pricingRequestId
             ORDER BY pd.decision_version_no, pd.pricing_decision_id
            """, Map.of("pricingRequestId", pricingRequestId),
            (rs, rowNum) -> mapDecision(rs, findItems(rs.getLong("pricing_decision_id"))));
    }

    public List<PricingDecisionItemDto> findItems(long decisionId) {
        return jdbc.query("""
            SELECT pdi.pricing_decision_item_id, pdi.pricing_decision_id, pdi.pricing_request_item_id,
                   pdi.pricing_costing_item_id, pri.brand, pri.model, pri.product_description,
                   pci.factory_name, pdi.requested_unit_basis, pdi.requested_quantity,
                   pdi.normalized_quantity_pieces, pdi.frozen_landed_cost_per_piece_thb,
                   pdi.frozen_landed_cost_per_requested_unit_thb, pdi.currency, pdi.proposed_margin_pct,
                   pdi.approved_margin_pct, pdi.proposed_selling_price_per_requested_unit,
                   pdi.approved_selling_price_per_requested_unit, pdi.discount_ceiling_pct,
                   pdi.minimum_selling_price_per_requested_unit, pdi.decision_note,
                   pdi.created_at, pdi.updated_at
              FROM sales.pricing_decision_item pdi
              JOIN sales.pricing_request_item pri ON pri.pricing_request_item_id = pdi.pricing_request_item_id
              JOIN sales.pricing_costing_item pci ON pci.pricing_costing_item_id = pdi.pricing_costing_item_id
             WHERE pdi.pricing_decision_id = :decisionId
             ORDER BY pdi.pricing_decision_item_id
            """, Map.of("decisionId", decisionId), (rs, rowNum) -> mapItem(rs));
    }

    /** Design correction 2's sales-facing projection — see {@link PricingDecisionDtos.PricingDecisionSalesViewDto}. */
    public Optional<PricingDecisionSalesViewDto> findApprovedSalesView(long pricingRequestId) {
        try {
            PricingDecisionSalesViewDto header = jdbc.queryForObject("""
                SELECT pricing_decision_id, currency, approved_at
                  FROM sales.pricing_decision
                 WHERE pricing_request_id = :pricingRequestId AND status = 'APPROVED'
                """, Map.of("pricingRequestId", pricingRequestId),
                (rs, rowNum) -> new PricingDecisionSalesViewDto(pricingRequestId, rs.getLong("pricing_decision_id"),
                    rs.getString("currency"), rs.getTimestamp("approved_at").toInstant(), List.of()));
            List<PricingDecisionSalesItemDto> items = jdbc.query("""
                SELECT pdi.pricing_request_item_id, pri.brand, pri.model, pri.product_description,
                       pdi.requested_unit_basis, pdi.requested_quantity,
                       pdi.approved_selling_price_per_requested_unit, pdi.discount_ceiling_pct,
                       pdi.minimum_selling_price_per_requested_unit
                  FROM sales.pricing_decision_item pdi
                  JOIN sales.pricing_request_item pri ON pri.pricing_request_item_id = pdi.pricing_request_item_id
                 WHERE pdi.pricing_decision_id = :decisionId
                 ORDER BY pdi.pricing_decision_item_id
                """, Map.of("decisionId", header.pricingDecisionId()),
                (rs, rowNum) -> new PricingDecisionSalesItemDto(
                    rs.getLong("pricing_request_item_id"), rs.getString("brand"), rs.getString("model"),
                    rs.getString("product_description"), rs.getString("requested_unit_basis"),
                    rs.getBigDecimal("requested_quantity"), rs.getBigDecimal("approved_selling_price_per_requested_unit"),
                    rs.getBigDecimal("discount_ceiling_pct"), rs.getBigDecimal("minimum_selling_price_per_requested_unit")));
            return Optional.of(new PricingDecisionSalesViewDto(
                header.pricingRequestId(), header.pricingDecisionId(), header.currency(), header.approvedAt(), items));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private String baseSelect() {
        return """
            SELECT pricing_decision_id, decision_code, pricing_request_id, pricing_costing_id,
                   decision_version_no, status, default_margin_pct, currency, fx_rate_used, fx_source,
                   fx_effective_date, ceo_note, return_reason, created_by, created_at, updated_at,
                   approved_by, approved_at, returned_at
              FROM sales.pricing_decision pd
            """;
    }

    private PricingDecisionDto mapDecision(ResultSet rs, List<PricingDecisionItemDto> items) throws SQLException {
        return new PricingDecisionDto(
            rs.getLong("pricing_decision_id"),
            rs.getString("decision_code"),
            rs.getLong("pricing_request_id"),
            rs.getLong("pricing_costing_id"),
            rs.getInt("decision_version_no"),
            rs.getString("status"),
            rs.getBigDecimal("default_margin_pct"),
            rs.getString("currency"),
            rs.getBigDecimal("fx_rate_used"),
            rs.getString("fx_source"),
            rs.getDate("fx_effective_date").toLocalDate(),
            rs.getString("ceo_note"),
            rs.getString("return_reason"),
            nullableLong(rs, "created_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            nullableLong(rs, "approved_by"),
            instant(rs, "approved_at"),
            instant(rs, "returned_at"),
            items
        );
    }

    private PricingDecisionItemDto mapItem(ResultSet rs) throws SQLException {
        return new PricingDecisionItemDto(
            rs.getLong("pricing_decision_item_id"),
            rs.getLong("pricing_decision_id"),
            rs.getLong("pricing_request_item_id"),
            rs.getLong("pricing_costing_item_id"),
            rs.getString("brand"),
            rs.getString("model"),
            rs.getString("product_description"),
            rs.getString("factory_name"),
            rs.getString("requested_unit_basis"),
            rs.getBigDecimal("requested_quantity"),
            rs.getBigDecimal("normalized_quantity_pieces"),
            rs.getBigDecimal("frozen_landed_cost_per_piece_thb"),
            rs.getBigDecimal("frozen_landed_cost_per_requested_unit_thb"),
            rs.getString("currency"),
            rs.getBigDecimal("proposed_margin_pct"),
            rs.getBigDecimal("approved_margin_pct"),
            rs.getBigDecimal("proposed_selling_price_per_requested_unit"),
            rs.getBigDecimal("approved_selling_price_per_requested_unit"),
            rs.getBigDecimal("discount_ceiling_pct"),
            rs.getBigDecimal("minimum_selling_price_per_requested_unit"),
            rs.getString("decision_note"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
