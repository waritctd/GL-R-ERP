package th.co.glr.hr.pricing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingClearanceFeeDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingDutyRateDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.CountryDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFreightRateDto;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.ClearanceFeeRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.CreatePricingFormulaConfigRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.DutyRateRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.FreightRateRequest;

/**
 * BRANCH 1: config storage only. See {@code V109__pricing_formula_config.sql} for the versioning
 * model -- one parent row owns three child lists; a new version always writes a full new parent
 * plus a full new set of children, and old versions (and their children) are retained for audit,
 * never UPDATEd or DELETEd.
 */
@Repository
public class PricingFormulaConfigRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PricingFormulaConfigRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PricingFormulaConfigDto> findCurrent() {
        PricingFormulaConfigDto parent;
        try {
            parent = jdbc.queryForObject("""
                SELECT formula_config_id, version, insurance_value_factor, insurance_rate,
                       insurance_buffer, cost_buffer, selling_buffer, default_margin_pct,
                       selling_price_round_up_to, is_current, effective_from, updated_at
                  FROM sales.pricing_formula_config
                 WHERE is_current = TRUE
                """, Map.of(), (rs, i) -> mapParent(rs));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
        if (parent == null) {
            return Optional.empty();
        }

        long formulaConfigId = parent.formulaConfigId();
        List<PricingFreightRateDto> freightRates = jdbc.query("""
            SELECT r.freight_rate_id, r.origin_country_code, c.name_th AS origin_country_name,
                   r.thickness_min_mm, r.thickness_max_mm,
                   r.qty_min_sqm, r.qty_max_sqm, r.amount_thb
              FROM sales.pricing_freight_rate r
              JOIN price_catalog.country c ON c.country_code = r.origin_country_code
             WHERE r.formula_config_id = :formulaConfigId
             ORDER BY c.name_th, r.thickness_min_mm, r.qty_min_sqm
            """, Map.of("formulaConfigId", formulaConfigId), (rs, i) -> mapFreightRate(rs));

        List<PricingDutyRateDto> dutyRates = jdbc.query("""
            SELECT duty_rate_id, product_type, product_label, duty_pct
              FROM sales.pricing_duty_rate
             WHERE formula_config_id = :formulaConfigId
             ORDER BY product_type
            """, Map.of("formulaConfigId", formulaConfigId), (rs, i) -> mapDutyRate(rs));

        List<PricingClearanceFeeDto> clearanceFees = jdbc.query("""
            SELECT clearance_fee_id, qty_min_sqm, qty_max_sqm, amount_thb
              FROM sales.pricing_clearance_fee
             WHERE formula_config_id = :formulaConfigId
             ORDER BY qty_min_sqm
            """, Map.of("formulaConfigId", formulaConfigId), (rs, i) -> mapClearanceFee(rs));

        List<CountryDto> countries = jdbc.query("""
            SELECT country_code, name_en, name_th
              FROM price_catalog.country
             ORDER BY name_th
            """, Map.of(), (rs, i) -> new CountryDto(
                rs.getString("country_code"), rs.getString("name_en"), rs.getString("name_th")));

        return Optional.of(new PricingFormulaConfigDto(
            parent.formulaConfigId(), parent.version(),
            parent.insuranceValueFactor(), parent.insuranceRate(), parent.insuranceBuffer(),
            parent.costBuffer(), parent.sellingBuffer(), parent.defaultMarginPct(),
            parent.sellingPriceRoundUpTo(), parent.isCurrent(), parent.effectiveFrom(), parent.updatedAt(),
            freightRates, dutyRates, clearanceFees, countries));
    }

    @Transactional
    public PricingFormulaConfigDto createNewVersion(CreatePricingFormulaConfigRequest request, Long updatedBy) {
        // Flip the existing current row (if any) to not-current -- never UPDATE its scalar values,
        // never touch its children.
        jdbc.update("UPDATE sales.pricing_formula_config SET is_current = FALSE WHERE is_current = TRUE", Map.of());

        Integer maxVersion = jdbc.queryForObject(
            "SELECT COALESCE(MAX(version), 0) FROM sales.pricing_formula_config", Map.of(), Integer.class);
        int nextVersion = (maxVersion != null ? maxVersion : 0) + 1;

        LocalDate effectiveFrom = request.effectiveFrom() != null ? request.effectiveFrom() : LocalDate.now();

        long formulaConfigId = jdbc.queryForObject("""
            INSERT INTO sales.pricing_formula_config
                (version, insurance_value_factor, insurance_rate, insurance_buffer, cost_buffer,
                 selling_buffer, default_margin_pct, selling_price_round_up_to,
                 is_current, effective_from, updated_by, updated_at)
            VALUES
                (:version, :insuranceValueFactor, :insuranceRate, :insuranceBuffer, :costBuffer,
                 :sellingBuffer, :defaultMarginPct, :sellingPriceRoundUpTo,
                 TRUE, :effectiveFrom, :updatedBy, now())
            RETURNING formula_config_id
            """,
            new MapSqlParameterSource()
                .addValue("version", nextVersion)
                .addValue("insuranceValueFactor", request.insuranceValueFactor())
                .addValue("insuranceRate", request.insuranceRate())
                .addValue("insuranceBuffer", request.insuranceBuffer())
                .addValue("costBuffer", request.costBuffer())
                .addValue("sellingBuffer", request.sellingBuffer())
                .addValue("defaultMarginPct", request.defaultMarginPct())
                .addValue("sellingPriceRoundUpTo", request.sellingPriceRoundUpTo())
                .addValue("effectiveFrom", effectiveFrom)
                .addValue("updatedBy", updatedBy),
            Long.class);

        for (FreightRateRequest freight : request.freightRates()) {
            jdbc.update("""
                INSERT INTO sales.pricing_freight_rate
                    (formula_config_id, origin_country_code, thickness_min_mm, thickness_max_mm,
                     qty_min_sqm, qty_max_sqm, amount_thb)
                VALUES
                    (:formulaConfigId, :originCountryCode, :thicknessMinMm, :thicknessMaxMm,
                     :qtyMinSqm, :qtyMaxSqm, :amountThb)
                """,
                new MapSqlParameterSource()
                    .addValue("formulaConfigId", formulaConfigId)
                    .addValue("originCountryCode", freight.originCountryCode())
                    .addValue("thicknessMinMm", freight.thicknessMinMm())
                    .addValue("thicknessMaxMm", freight.thicknessMaxMm())
                    .addValue("qtyMinSqm", freight.qtyMinSqm())
                    .addValue("qtyMaxSqm", freight.qtyMaxSqm())
                    .addValue("amountThb", freight.amountThb()));
        }

        for (DutyRateRequest duty : request.dutyRates()) {
            jdbc.update("""
                INSERT INTO sales.pricing_duty_rate (formula_config_id, product_type, product_label, duty_pct)
                VALUES (:formulaConfigId, :productType, :productLabel, :dutyPct)
                """,
                new MapSqlParameterSource()
                    .addValue("formulaConfigId", formulaConfigId)
                    .addValue("productType", duty.productType())
                    .addValue("productLabel", duty.productLabel())
                    .addValue("dutyPct", duty.dutyPct()));
        }

        for (ClearanceFeeRequest clearance : request.clearanceFees()) {
            jdbc.update("""
                INSERT INTO sales.pricing_clearance_fee (formula_config_id, qty_min_sqm, qty_max_sqm, amount_thb)
                VALUES (:formulaConfigId, :qtyMinSqm, :qtyMaxSqm, :amountThb)
                """,
                new MapSqlParameterSource()
                    .addValue("formulaConfigId", formulaConfigId)
                    .addValue("qtyMinSqm", clearance.qtyMinSqm())
                    .addValue("qtyMaxSqm", clearance.qtyMaxSqm())
                    .addValue("amountThb", clearance.amountThb()));
        }

        return findCurrent().orElseThrow();
    }

    private PricingFormulaConfigDto mapParent(ResultSet rs) throws SQLException {
        return new PricingFormulaConfigDto(
            rs.getLong("formula_config_id"),
            rs.getInt("version"),
            rs.getBigDecimal("insurance_value_factor"),
            rs.getBigDecimal("insurance_rate"),
            rs.getBigDecimal("insurance_buffer"),
            rs.getBigDecimal("cost_buffer"),
            rs.getBigDecimal("selling_buffer"),
            rs.getBigDecimal("default_margin_pct"),
            rs.getBigDecimal("selling_price_round_up_to"),
            rs.getBoolean("is_current"),
            rs.getDate("effective_from").toLocalDate(),
            rs.getTimestamp("updated_at").toInstant(),
            List.of(), List.of(), List.of(), List.of());
    }

    private PricingFreightRateDto mapFreightRate(ResultSet rs) throws SQLException {
        return new PricingFreightRateDto(
            rs.getLong("freight_rate_id"),
            rs.getString("origin_country_code"),
            rs.getString("origin_country_name"),
            rs.getBigDecimal("thickness_min_mm"),
            rs.getBigDecimal("thickness_max_mm"),
            rs.getBigDecimal("qty_min_sqm"),
            rs.getBigDecimal("qty_max_sqm"),
            rs.getBigDecimal("amount_thb"));
    }

    private PricingDutyRateDto mapDutyRate(ResultSet rs) throws SQLException {
        return new PricingDutyRateDto(
            rs.getLong("duty_rate_id"),
            rs.getString("product_type"),
            rs.getString("product_label"),
            rs.getBigDecimal("duty_pct"));
    }

    private PricingClearanceFeeDto mapClearanceFee(ResultSet rs) throws SQLException {
        return new PricingClearanceFeeDto(
            rs.getLong("clearance_fee_id"),
            rs.getBigDecimal("qty_min_sqm"),
            rs.getBigDecimal("qty_max_sqm"),
            rs.getBigDecimal("amount_thb"));
    }
}
