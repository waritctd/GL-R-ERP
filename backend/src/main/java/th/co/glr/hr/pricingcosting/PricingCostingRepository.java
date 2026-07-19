package th.co.glr.hr.pricingcosting;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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

    public long createDraft(long pricingRequestId, String note, String clientRequestId, long actorId) {
        Optional<PricingCostingDto> open = findOpen(pricingRequestId);
        if (open.isPresent()) {
            return open.get().id();
        }
        Integer nextVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version_no), 0) + 1
              FROM sales.pricing_costing
             WHERE pricing_request_id = :pricingRequestId
            """, Map.of("pricingRequestId", pricingRequestId), Integer.class);
        Long id = jdbc.queryForObject("""
            INSERT INTO sales.pricing_costing
                (costing_code, pricing_request_id, version_no, status, note, client_request_id, created_by)
            VALUES
                (:code, :pricingRequestId, :versionNo, 'DRAFT', :note, CAST(:clientRequestId AS uuid), :createdBy)
            RETURNING pricing_costing_id
            """,
            new MapSqlParameterSource()
                .addValue("code", nextCostingCode())
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("versionNo", nextVersion == null ? 1 : nextVersion)
                .addValue("note", note)
                .addValue("clientRequestId", clientRequestId)
                .addValue("createdBy", actorId),
            Long.class);
        return id == null ? 0L : id;
    }

    public void replaceItems(long costingId, List<PricingCostingWriteItem> items) {
        jdbc.update("DELETE FROM sales.pricing_costing_item WHERE pricing_costing_id = :costingId",
            Map.of("costingId", costingId));
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            PricingCostingWriteItem item = items.get(i);
            batch[i] = item.toParams(costingId);
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.pricing_costing_item
                (pricing_costing_id, pricing_request_item_id, factory_quote_id, factory_quote_item_id,
                 factory_quote_revision_no, factory_id, factory_name, supplier_quote_ref, raw_unit_price,
                 raw_currency, raw_unit, unit_basis, requested_quantity, requested_unit, sqm_per_unit,
                 pieces_per_box, fx_rate, fx_source, fx_effective_date, fx_fetched_at,
                 calculation_config_id, calculation_config_version, goods_cost_thb, freight_cost_thb,
                 insurance_cost_thb, import_duty_thb, inland_transport_cost_thb, other_cost_thb,
                 cif_cost_thb, landed_cost_per_unit_thb, total_landed_cost_thb, calculated_at,
                 calculation_snapshot)
            VALUES
                (:costingId, :pricingRequestItemId, :factoryQuoteId, :factoryQuoteItemId,
                 :factoryQuoteRevisionNo, :factoryId, :factoryName, :supplierQuoteRef, :rawUnitPrice,
                 :rawCurrency, :rawUnit, :unitBasis, :requestedQuantity, :requestedUnit, :sqmPerUnit,
                 :piecesPerBox, :fxRate, :fxSource, :fxEffectiveDate, :fxFetchedAt,
                 :calculationConfigId, :calculationConfigVersion, :goodsCostThb, :freightCostThb,
                 :insuranceCostThb, :importDutyThb, :inlandTransportCostThb, :otherCostThb,
                 :cifCostThb, :landedCostPerUnitThb, :totalLandedCostThb, now(),
                 CAST(:calculationSnapshot AS jsonb))
            """, batch);
    }

    public int markCalculated(long costingId, java.math.BigDecimal total, String note) {
        return jdbc.update("""
            UPDATE sales.pricing_costing
               SET status = 'CALCULATED',
                   stale = FALSE,
                   stale_reason = NULL,
                   note = COALESCE(:note, note),
                   calculated_at = now(),
                   total_landed_cost_thb = :total,
                   updated_at = now()
             WHERE pricing_costing_id = :costingId
               AND status IN ('DRAFT', 'CALCULATED')
            """,
            new MapSqlParameterSource()
                .addValue("costingId", costingId)
                .addValue("total", total)
                .addValue("note", note));
    }

    public int submit(long costingId, long actorId, java.math.BigDecimal total, String note) {
        return jdbc.update("""
            UPDATE sales.pricing_costing
               SET status = 'SUBMITTED',
                   stale = FALSE,
                   stale_reason = NULL,
                   note = COALESCE(:note, note),
                   submitted_by = :actorId,
                   submitted_at = now(),
                   calculated_at = COALESCE(calculated_at, now()),
                   total_landed_cost_thb = :total,
                   updated_at = now()
             WHERE pricing_costing_id = :costingId
               AND status = 'CALCULATED'
               AND stale = FALSE
            """,
            new MapSqlParameterSource()
                .addValue("costingId", costingId)
                .addValue("actorId", actorId)
                .addValue("total", total)
                .addValue("note", note));
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

    public Optional<PricingCostingDto> findOpen(long pricingRequestId) {
        try {
            PricingCostingDto dto = jdbc.queryForObject(baseSelect() + """
                 WHERE pc.pricing_request_id = :pricingRequestId
                   AND pc.status IN ('DRAFT', 'CALCULATED')
                """,
                Map.of("pricingRequestId", pricingRequestId), (rs, rowNum) -> mapCosting(rs, findItems(rs.getLong("pricing_costing_id"))));
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
                   unit_basis, requested_quantity, requested_unit, sqm_per_unit, pieces_per_box,
                   fx_rate, fx_source, fx_effective_date, fx_fetched_at, calculation_config_id,
                   calculation_config_version, goods_cost_thb, freight_cost_thb, insurance_cost_thb,
                   import_duty_thb, inland_transport_cost_thb, other_cost_thb, cif_cost_thb,
                   landed_cost_per_unit_thb, total_landed_cost_thb, calculated_at,
                   calculation_snapshot::text AS calculation_snapshot
              FROM sales.pricing_costing_item
             WHERE pricing_costing_id = :costingId
             ORDER BY pricing_costing_item_id
            """, Map.of("costingId", costingId), (rs, rowNum) -> mapItem(rs));
    }

    private String baseSelect() {
        return """
            SELECT pricing_costing_id, costing_code, pricing_request_id, version_no, status,
                   stale, stale_reason, note, created_by, created_at, updated_at, calculated_at,
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
            rs.getBoolean("stale"),
            rs.getString("stale_reason"),
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
            rs.getBigDecimal("sqm_per_unit"),
            rs.getBigDecimal("pieces_per_box"),
            rs.getBigDecimal("fx_rate"),
            rs.getString("fx_source"),
            rs.getObject("fx_effective_date", java.time.LocalDate.class),
            instant(rs, "fx_fetched_at"),
            rs.getLong("calculation_config_id"),
            rs.getInt("calculation_config_version"),
            rs.getBigDecimal("goods_cost_thb"),
            rs.getBigDecimal("freight_cost_thb"),
            rs.getBigDecimal("insurance_cost_thb"),
            rs.getBigDecimal("import_duty_thb"),
            rs.getBigDecimal("inland_transport_cost_thb"),
            rs.getBigDecimal("other_cost_thb"),
            rs.getBigDecimal("cif_cost_thb"),
            rs.getBigDecimal("landed_cost_per_unit_thb"),
            rs.getBigDecimal("total_landed_cost_thb"),
            rs.getTimestamp("calculated_at").toInstant(),
            rs.getString("calculation_snapshot")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private java.time.Instant instant(ResultSet rs, String column) throws SQLException {
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
        java.math.BigDecimal rawUnitPrice,
        String rawCurrency,
        String rawUnit,
        String unitBasis,
        java.math.BigDecimal requestedQuantity,
        String requestedUnit,
        java.math.BigDecimal sqmPerUnit,
        java.math.BigDecimal piecesPerBox,
        java.math.BigDecimal fxRate,
        String fxSource,
        java.time.LocalDate fxEffectiveDate,
        java.time.Instant fxFetchedAt,
        long calculationConfigId,
        int calculationConfigVersion,
        java.math.BigDecimal goodsCostThb,
        java.math.BigDecimal freightCostThb,
        java.math.BigDecimal insuranceCostThb,
        java.math.BigDecimal importDutyThb,
        java.math.BigDecimal inlandTransportCostThb,
        java.math.BigDecimal otherCostThb,
        java.math.BigDecimal cifCostThb,
        java.math.BigDecimal landedCostPerUnitThb,
        java.math.BigDecimal totalLandedCostThb,
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
                .addValue("calculationSnapshot", calculationSnapshot == null ? "{}" : calculationSnapshot);
        }
    }
}
