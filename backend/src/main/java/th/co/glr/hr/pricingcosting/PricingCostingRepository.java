package th.co.glr.hr.pricingcosting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingItemDto;

/**
 * V141 ("CEO owns costing"): this is now a WRITER surface for {@code
 * th.co.glr.hr.pricingdecision.PricingDecisionService} only — {@link PricingCostingService} is
 * read-only (see its own javadoc). {@link #createComputed}/{@link #replaceItemsPreservingOverrides}
 * are called from {@code PricingDecisionService#startReview}/{@code #recalculateCost}; {@link
 * #applyOverride}/{@link #clearOverride} from {@code PricingDecisionService#overrideItemCost}.
 */
@Repository
public class PricingCostingRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PricingCostingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextCostingCode() {
        Long seq = jdbc.queryForObject("SELECT nextval('sales.pricing_costing_code_seq')", Map.of(), Long.class);
        return "PCO-" + Year.now() + "-" + String.format("%04d", seq == null ? 0 : seq);
    }

    /**
     * Creates a costing straight at {@code SUBMITTED} — there is no DRAFT/CALCULATED stage any
     * more, since the CEO computes and freezes the cost in the same breath (plan 2.5). Landing
     * directly at SUBMITTED keeps {@code FactoryQuoteRepository#existsSubmittedCostingReferencingQuote}
     * (the attachment-deletion guard) working unchanged, and sidesteps {@code
     * uq_pricing_costing_open_draft} (a partial unique index on DRAFT/CALCULATED — never matched by
     * a row that is born SUBMITTED). {@code created_by}/{@code submitted_by} are both the CEO
     * ({@code actorId}) since one action does both here; {@code calculated_at}/{@code submitted_at}
     * are both {@code now()} for the same reason. Caller must hold {@link
     * th.co.glr.hr.pricingdecision.PricingDecisionRepository#lockPricingRequest} for the current
     * transaction (mirrors the old {@code createDraft}'s own contract) — no advisory lock is taken
     * here.
     */
    public long createComputed(long pricingRequestId, String note, long actorId, BigDecimal totalThb) {
        Integer nextVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version_no), 0) + 1
              FROM sales.pricing_costing
             WHERE pricing_request_id = :pricingRequestId
            """, Map.of("pricingRequestId", pricingRequestId), Integer.class);
        Long id = jdbc.queryForObject("""
            INSERT INTO sales.pricing_costing
                (costing_code, pricing_request_id, version_no, status, note, created_by,
                 calculated_at, submitted_by, submitted_at, total_landed_cost_thb)
            VALUES
                (:code, :pricingRequestId, :versionNo, 'SUBMITTED', :note, :actorId,
                 now(), :actorId, now(), :total)
            RETURNING pricing_costing_id
            """,
            new MapSqlParameterSource()
                .addValue("code", nextCostingCode())
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("versionNo", nextVersion == null ? 1 : nextVersion)
                .addValue("note", note)
                .addValue("actorId", actorId)
                .addValue("total", totalThb),
            Long.class);
        return id == null ? 0L : id;
    }

    /**
     * Writes the freshly computed rows for {@code costingId} as an UPSERT keyed on
     * {@code uq_pricing_costing_item_request_item (pricing_costing_id, pricing_request_item_id)}
     * (V62) — deliberately NOT delete-and-reinsert.
     *
     * <p><strong>Why an UPSERT and not a delete+reinsert:</strong> {@code
     * sales.pricing_decision_item.pricing_costing_item_id} is a {@code NOT NULL ... ON DELETE
     * RESTRICT} foreign key into these very rows (V72 L110), written once by {@code
     * PricingDecisionService#startReview} and never repointed; {@code
     * sales.factory_purchase_order_item} (V77) carries the identical restricted FK. Deleting the
     * item rows therefore ALWAYS violates that constraint the moment a decision exists — which,
     * for {@code recalculateCost}, is by construction every time. An UPSERT keeps each
     * {@code pricing_costing_item_id} PK stable, so every inbound FK stays valid across any number
     * of recalculations.
     *
     * <p><strong>How the override survives:</strong> structurally, not by copying. The
     * {@code DO UPDATE SET} list below names only the 34 COMPUTED columns and deliberately omits
     * all six override columns ({@code manual_landed_cost_per_unit_thb}, {@code override_reason},
     * {@code overridden_by}, {@code overridden_at}, {@code override_fx_rate},
     * {@code override_calc_config_version}), so an existing override is carried forward untouched
     * by the same statement that refreshes the computed figures — there is no snapshot/restore step
     * to forget or get wrong. Owner ruling (plan section 0): do NOT clear an override on
     * recalculate; preserve it and let the derived {@code overrideStale} (see {@link #mapItem})
     * make the drift loud instead. Because {@code fx_rate} and
     * {@code calculation_config_version} ARE refreshed here while {@code override_fx_rate} and
     * {@code override_calc_config_version} are not, an override whose world has moved becomes
     * stale automatically, with no extra bookkeeping.
     *
     * <p>Called for a brand-new costing (from {@code startReview}, where every row is an INSERT and
     * nothing has been overridden yet) and for an existing one being recalculated in place (from
     * {@code recalculateCost}, where every row is an UPDATE), so both call sites get the same
     * guarantee automatically.
     */
    public void replaceItemsPreservingOverrides(long costingId, List<PricingCostingWriteItem> items) {
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            PricingCostingWriteItem item = items.get(i);
            batch[i] = item.toParams(costingId);
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.pricing_costing_item
                (pricing_costing_id, pricing_request_item_id, factory_quote_id, factory_quote_item_id,
                 factory_quote_revision_no, factory_id, factory_name, supplier_quote_ref, raw_unit_price,
                 raw_currency, raw_unit, unit_basis, requested_quantity, requested_unit,
                 requested_unit_basis, normalized_quantity_pieces, linear_m_per_unit, sqm_per_unit,
                 pieces_per_box, fx_rate, fx_source, fx_effective_date, fx_fetched_at,
                 calculation_config_id, calculation_config_version, goods_cost_thb, freight_cost_thb,
                 insurance_cost_thb, import_duty_thb, inland_transport_cost_thb, other_cost_thb,
                 cif_cost_thb, landed_cost_per_unit_thb, total_landed_cost_thb, clearance_fee_thb,
                 product_type, calculated_at, calculation_snapshot)
            VALUES
                (:costingId, :pricingRequestItemId, :factoryQuoteId, :factoryQuoteItemId,
                 :factoryQuoteRevisionNo, :factoryId, :factoryName, :supplierQuoteRef, :rawUnitPrice,
                 :rawCurrency, :rawUnit, :unitBasis, :requestedQuantity, :requestedUnit,
                 :requestedUnitBasis, :normalizedQuantityPieces, :linearMPerUnit, :sqmPerUnit,
                 :piecesPerBox, :fxRate, :fxSource, :fxEffectiveDate, :fxFetchedAt,
                 :calculationConfigId, :calculationConfigVersion, :goodsCostThb, :freightCostThb,
                 :insuranceCostThb, :importDutyThb, :inlandTransportCostThb, :otherCostThb,
                 :cifCostThb, :landedCostPerUnitThb, :totalLandedCostThb, :clearanceFeeThb,
                 :productType, now(), CAST(:calculationSnapshot AS jsonb))
            ON CONFLICT (pricing_costing_id, pricing_request_item_id) DO UPDATE SET
                 factory_quote_id = EXCLUDED.factory_quote_id,
                 factory_quote_item_id = EXCLUDED.factory_quote_item_id,
                 factory_quote_revision_no = EXCLUDED.factory_quote_revision_no,
                 factory_id = EXCLUDED.factory_id,
                 factory_name = EXCLUDED.factory_name,
                 supplier_quote_ref = EXCLUDED.supplier_quote_ref,
                 raw_unit_price = EXCLUDED.raw_unit_price,
                 raw_currency = EXCLUDED.raw_currency,
                 raw_unit = EXCLUDED.raw_unit,
                 unit_basis = EXCLUDED.unit_basis,
                 requested_quantity = EXCLUDED.requested_quantity,
                 requested_unit = EXCLUDED.requested_unit,
                 requested_unit_basis = EXCLUDED.requested_unit_basis,
                 normalized_quantity_pieces = EXCLUDED.normalized_quantity_pieces,
                 linear_m_per_unit = EXCLUDED.linear_m_per_unit,
                 sqm_per_unit = EXCLUDED.sqm_per_unit,
                 pieces_per_box = EXCLUDED.pieces_per_box,
                 fx_rate = EXCLUDED.fx_rate,
                 fx_source = EXCLUDED.fx_source,
                 fx_effective_date = EXCLUDED.fx_effective_date,
                 fx_fetched_at = EXCLUDED.fx_fetched_at,
                 calculation_config_id = EXCLUDED.calculation_config_id,
                 calculation_config_version = EXCLUDED.calculation_config_version,
                 goods_cost_thb = EXCLUDED.goods_cost_thb,
                 freight_cost_thb = EXCLUDED.freight_cost_thb,
                 insurance_cost_thb = EXCLUDED.insurance_cost_thb,
                 import_duty_thb = EXCLUDED.import_duty_thb,
                 inland_transport_cost_thb = EXCLUDED.inland_transport_cost_thb,
                 other_cost_thb = EXCLUDED.other_cost_thb,
                 cif_cost_thb = EXCLUDED.cif_cost_thb,
                 landed_cost_per_unit_thb = EXCLUDED.landed_cost_per_unit_thb,
                 total_landed_cost_thb = EXCLUDED.total_landed_cost_thb,
                 clearance_fee_thb = EXCLUDED.clearance_fee_thb,
                 product_type = EXCLUDED.product_type,
                 calculated_at = EXCLUDED.calculated_at,
                 calculation_snapshot = EXCLUDED.calculation_snapshot
            """, batch);

        // Drop any line the recalculation no longer produces (the pricing request lost an item
        // between two costings of the same request). Deliberately NOT swallowed: if a
        // pricing_decision_item or factory_purchase_order_item still points at such a row, the
        // restricted FK refuses the delete and the whole transaction rolls back — which is the
        // correct outcome. Silently orphaning a priced line would be worse than a loud 500.
        List<Long> keptRequestItemIds = items.stream()
            .map(PricingCostingWriteItem::pricingRequestItemId)
            .toList();
        jdbc.update("""
            DELETE FROM sales.pricing_costing_item
             WHERE pricing_costing_id = :costingId
               AND (:hasKept = FALSE OR pricing_request_item_id NOT IN (:keptRequestItemIds))
            """,
            new MapSqlParameterSource()
                .addValue("costingId", costingId)
                .addValue("hasKept", !keptRequestItemIds.isEmpty())
                .addValue("keptRequestItemIds", keptRequestItemIds.isEmpty() ? List.of(-1L) : keptRequestItemIds));
    }

    /**
     * Writes the manual cost override plus its full provenance, stamped from the CALLER-supplied
     * {@code fxRate}/{@code configVersion} — the caller ({@code PricingDecisionService}) reads
     * these off the SAME item's current computed {@code fxRate}/{@code calculationConfigVersion}
     * before calling, so "override_fx_rate = fx_rate at the moment of override" holds by
     * construction. Re-confirming the same manual value after a recalculate re-stamps these to
     * whatever is current THEN, which is exactly how {@code overrideStale} clears — the CEO's
     * escape hatch.
     */
    public int applyOverride(long costingItemId, BigDecimal manualCost, String reason, long actorId,
                             BigDecimal fxRate, int configVersion) {
        return jdbc.update("""
            UPDATE sales.pricing_costing_item
               SET manual_landed_cost_per_unit_thb = :manualCost,
                   override_reason = :reason,
                   overridden_by = :actorId,
                   overridden_at = now(),
                   override_fx_rate = :fxRate,
                   override_calc_config_version = :configVersion
             WHERE pricing_costing_item_id = :costingItemId
            """,
            new MapSqlParameterSource()
                .addValue("costingItemId", costingItemId)
                .addValue("manualCost", manualCost)
                .addValue("reason", reason)
                .addValue("actorId", actorId)
                .addValue("fxRate", fxRate)
                .addValue("configVersion", configVersion));
    }

    /**
     * Clears an override back to "no override, use the computed figure" — nulls ALL SIX override
     * columns, not just the manual value itself, so the row returns to exactly the state it would
     * be in had it never been overridden (no leftover provenance for a since-cleared override to
     * be confused with). The CEO's reason for clearing is recorded in the {@code
     * pricing_request_event} trail by the caller, not on this row — a cleared override, by
     * definition, has no active override to attach a reason to.
     */
    public int clearOverride(long costingItemId, long actorId) {
        return jdbc.update("""
            UPDATE sales.pricing_costing_item
               SET manual_landed_cost_per_unit_thb = NULL,
                   override_reason = NULL,
                   overridden_by = :actorId,
                   overridden_at = now(),
                   override_fx_rate = NULL,
                   override_calc_config_version = NULL
             WHERE pricing_costing_item_id = :costingItemId
            """,
            new MapSqlParameterSource()
                .addValue("costingItemId", costingItemId)
                .addValue("actorId", actorId));
    }

    public Optional<PricingCostingDto> find(long costingId) {
        try {
            PricingCostingDto dto = jdbc.queryForObject(baseSelect() + " WHERE pc.pricing_costing_id = :costingId",
                Map.of("costingId", costingId), (rs, rowNum) -> mapCosting(rs, findItems(costingId)));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<PricingCostingDto> findByPricingRequest(long pricingRequestId) {
        return jdbc.query(baseSelect() + """
             WHERE pc.pricing_request_id = :pricingRequestId
             ORDER BY pc.version_no, pc.pricing_costing_id
            """, Map.of("pricingRequestId", pricingRequestId),
            (rs, rowNum) -> mapCosting(rs, findItems(rs.getLong("pricing_costing_id"))));
    }

    public List<PricingCostingItemDto> findItems(long costingId) {
        return jdbc.query("""
            SELECT pricing_costing_item_id, pricing_costing_id, pricing_request_item_id,
                   factory_quote_id, factory_quote_item_id, factory_quote_revision_no, factory_id,
                   factory_name, supplier_quote_ref, raw_unit_price, raw_currency, raw_unit,
                   unit_basis, requested_quantity, requested_unit, requested_unit_basis,
                   normalized_quantity_pieces, linear_m_per_unit, sqm_per_unit, pieces_per_box,
                   fx_rate, fx_source, fx_effective_date, fx_fetched_at, calculation_config_id,
                   calculation_config_version, goods_cost_thb, freight_cost_thb, insurance_cost_thb,
                   import_duty_thb, inland_transport_cost_thb, other_cost_thb, cif_cost_thb,
                   landed_cost_per_unit_thb, total_landed_cost_thb, clearance_fee_thb, product_type,
                   calculated_at, calculation_snapshot::text AS calculation_snapshot,
                   manual_landed_cost_per_unit_thb, override_reason, overridden_by, overridden_at,
                   override_fx_rate, override_calc_config_version
              FROM sales.pricing_costing_item
             WHERE pricing_costing_id = :costingId
             ORDER BY pricing_costing_item_id
            """, Map.of("costingId", costingId), (rs, rowNum) -> mapItem(rs));
    }

    private String baseSelect() {
        return """
            SELECT pricing_costing_id, costing_code, pricing_request_id, version_no, status,
                   note, created_by, created_at, updated_at, calculated_at,
                   submitted_by, submitted_at, total_landed_cost_thb
              FROM sales.pricing_costing pc
            """;
    }

    private PricingCostingDto mapCosting(ResultSet rs, List<PricingCostingItemDto> items) throws SQLException {
        return new PricingCostingDto(
            rs.getLong("pricing_costing_id"),
            rs.getString("costing_code"),
            rs.getLong("pricing_request_id"),
            rs.getInt("version_no"),
            rs.getString("status"),
            rs.getString("note"),
            nullableLong(rs, "created_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            instant(rs, "calculated_at"),
            nullableLong(rs, "submitted_by"),
            instant(rs, "submitted_at"),
            rs.getBigDecimal("total_landed_cost_thb"),
            items
        );
    }

    private PricingCostingItemDto mapItem(ResultSet rs) throws SQLException {
        BigDecimal landedCostPerUnitThb = rs.getBigDecimal("landed_cost_per_unit_thb");
        BigDecimal normalizedQuantityPieces = rs.getBigDecimal("normalized_quantity_pieces");
        BigDecimal manualLandedCostPerUnitThb = rs.getBigDecimal("manual_landed_cost_per_unit_thb");
        BigDecimal overrideFxRate = rs.getBigDecimal("override_fx_rate");
        Integer overrideCalcConfigVersion = (Integer) rs.getObject("override_calc_config_version");
        BigDecimal fxRate = rs.getBigDecimal("fx_rate");
        int calculationConfigVersion = rs.getInt("calculation_config_version");

        // Both computed columns (landedCostPerUnitThb, totalLandedCostThb) are already at their
        // own correct scale — landedCostPerUnitThb straight from a NUMERIC(18,4) column,
        // totalLandedCostThb via LandedCostCalculator's own money4(landedPerUnit * qtyPieces).
        // effectiveLandedCostPerUnitThb is a straight passthrough of one of two already-scale-4
        // values (manual or computed), so no further rounding applies to it — but
        // effectiveTotalLandedCostThb IS a fresh multiplication (scale 4 x scale 6), so it must be
        // rounded the SAME way totalLandedCostThb was, or the two would use inconsistent
        // precision for what is meant to be the same "per-line total" concept.
        BigDecimal effectiveLandedCostPerUnitThb = manualLandedCostPerUnitThb != null
            ? manualLandedCostPerUnitThb : landedCostPerUnitThb;
        BigDecimal effectiveTotalLandedCostThb = normalizedQuantityPieces == null
            ? null : effectiveLandedCostPerUnitThb.multiply(normalizedQuantityPieces).setScale(4, RoundingMode.HALF_UP);
        // Defensive on a missing provenance value too (the migration's CHECK constraint should
        // never allow that combination, but a comparison that cannot evaluate is treated as
        // stale, not silently "not stale").
        boolean overrideStale = manualLandedCostPerUnitThb != null
            && (overrideFxRate == null || overrideCalcConfigVersion == null
                || overrideFxRate.compareTo(fxRate) != 0
                || overrideCalcConfigVersion.intValue() != calculationConfigVersion);

        return new PricingCostingItemDto(
            rs.getLong("pricing_costing_item_id"),
            rs.getLong("pricing_costing_id"),
            rs.getLong("pricing_request_item_id"),
            rs.getLong("factory_quote_id"),
            rs.getLong("factory_quote_item_id"),
            rs.getInt("factory_quote_revision_no"),
            nullableLong(rs, "factory_id"),
            rs.getString("factory_name"),
            rs.getString("supplier_quote_ref"),
            rs.getBigDecimal("raw_unit_price"),
            rs.getString("raw_currency"),
            rs.getString("raw_unit"),
            rs.getString("unit_basis"),
            rs.getBigDecimal("requested_quantity"),
            rs.getString("requested_unit"),
            rs.getString("requested_unit_basis"),
            normalizedQuantityPieces,
            rs.getBigDecimal("linear_m_per_unit"),
            rs.getBigDecimal("sqm_per_unit"),
            rs.getBigDecimal("pieces_per_box"),
            fxRate,
            rs.getString("fx_source"),
            rs.getObject("fx_effective_date", java.time.LocalDate.class),
            instant(rs, "fx_fetched_at"),
            rs.getLong("calculation_config_id"),
            calculationConfigVersion,
            rs.getBigDecimal("goods_cost_thb"),
            rs.getBigDecimal("freight_cost_thb"),
            rs.getBigDecimal("insurance_cost_thb"),
            rs.getBigDecimal("import_duty_thb"),
            rs.getBigDecimal("inland_transport_cost_thb"),
            rs.getBigDecimal("other_cost_thb"),
            rs.getBigDecimal("cif_cost_thb"),
            landedCostPerUnitThb,
            rs.getBigDecimal("total_landed_cost_thb"),
            rs.getBigDecimal("clearance_fee_thb"),
            rs.getString("product_type"),
            rs.getTimestamp("calculated_at").toInstant(),
            rs.getString("calculation_snapshot"),
            manualLandedCostPerUnitThb,
            rs.getString("override_reason"),
            nullableLong(rs, "overridden_by"),
            instant(rs, "overridden_at"),
            overrideFxRate,
            overrideCalcConfigVersion,
            effectiveLandedCostPerUnitThb,
            effectiveTotalLandedCostThb,
            overrideStale
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    public record PricingCostingWriteItem(
        long pricingRequestItemId,
        long factoryQuoteId,
        long factoryQuoteItemId,
        int factoryQuoteRevisionNo,
        Long factoryId,
        String factoryName,
        String supplierQuoteRef,
        BigDecimal rawUnitPrice,
        String rawCurrency,
        String rawUnit,
        String unitBasis,
        BigDecimal requestedQuantity,
        String requestedUnit,
        String requestedUnitBasis,
        BigDecimal normalizedQuantityPieces,
        BigDecimal linearMPerUnit,
        BigDecimal sqmPerUnit,
        BigDecimal piecesPerBox,
        BigDecimal fxRate,
        String fxSource,
        java.time.LocalDate fxEffectiveDate,
        Instant fxFetchedAt,
        long calculationConfigId,
        int calculationConfigVersion,
        BigDecimal goodsCostThb,
        BigDecimal freightCostThb,
        BigDecimal insuranceCostThb,
        BigDecimal importDutyThb,
        BigDecimal inlandTransportCostThb,
        BigDecimal otherCostThb,
        BigDecimal cifCostThb,
        BigDecimal landedCostPerUnitThb,
        BigDecimal totalLandedCostThb,
        BigDecimal clearanceFeeThb,
        String productType,
        String calculationSnapshot
    ) {
        MapSqlParameterSource toParams(long costingId) {
            return new MapSqlParameterSource()
                .addValue("costingId", costingId)
                .addValue("pricingRequestItemId", pricingRequestItemId)
                .addValue("factoryQuoteId", factoryQuoteId)
                .addValue("factoryQuoteItemId", factoryQuoteItemId)
                .addValue("factoryQuoteRevisionNo", factoryQuoteRevisionNo)
                .addValue("factoryId", factoryId)
                .addValue("factoryName", factoryName)
                .addValue("supplierQuoteRef", supplierQuoteRef)
                .addValue("rawUnitPrice", rawUnitPrice)
                .addValue("rawCurrency", rawCurrency)
                .addValue("rawUnit", rawUnit)
                .addValue("unitBasis", unitBasis)
                .addValue("requestedQuantity", requestedQuantity)
                .addValue("requestedUnit", requestedUnit)
                .addValue("requestedUnitBasis", requestedUnitBasis)
                .addValue("normalizedQuantityPieces", normalizedQuantityPieces)
                .addValue("linearMPerUnit", linearMPerUnit)
                .addValue("sqmPerUnit", sqmPerUnit)
                .addValue("piecesPerBox", piecesPerBox)
                .addValue("fxRate", fxRate)
                .addValue("fxSource", fxSource)
                .addValue("fxEffectiveDate", fxEffectiveDate)
                .addValue("fxFetchedAt", fxFetchedAt == null ? null : Timestamp.from(fxFetchedAt))
                .addValue("calculationConfigId", calculationConfigId)
                .addValue("calculationConfigVersion", calculationConfigVersion)
                .addValue("goodsCostThb", goodsCostThb)
                .addValue("freightCostThb", freightCostThb)
                .addValue("insuranceCostThb", insuranceCostThb)
                .addValue("importDutyThb", importDutyThb)
                .addValue("inlandTransportCostThb", inlandTransportCostThb)
                .addValue("otherCostThb", otherCostThb)
                .addValue("cifCostThb", cifCostThb)
                .addValue("landedCostPerUnitThb", landedCostPerUnitThb)
                .addValue("totalLandedCostThb", totalLandedCostThb)
                .addValue("clearanceFeeThb", clearanceFeeThb)
                .addValue("productType", productType)
                .addValue("calculationSnapshot", calculationSnapshot == null ? "{}" : calculationSnapshot);
        }
    }
}
