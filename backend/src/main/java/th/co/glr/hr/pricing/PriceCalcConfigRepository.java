package th.co.glr.hr.pricing;

import java.math.BigDecimal;
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

@Repository
public class PriceCalcConfigRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PriceCalcConfigRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PriceCalcConfigDto> findCurrentConfigs() {
        return jdbc.query("""
            SELECT config_id, version, country,
                   freight_per_sqm, insurance_per_sqm,
                   inland_factory_to_port_per_sqm, inland_port_to_warehouse_per_sqm,
                   import_duty_pct, margin_pct, is_current, effective_from, updated_at
              FROM sales.price_calc_config
             WHERE is_current = TRUE
             ORDER BY country
            """, Map.of(), (rs, i) -> map(rs));
    }

    public Optional<PriceCalcConfigDto> findCurrentByCountry(String country) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT config_id, version, country,
                       freight_per_sqm, insurance_per_sqm,
                       inland_factory_to_port_per_sqm, inland_port_to_warehouse_per_sqm,
                       import_duty_pct, margin_pct, is_current, effective_from, updated_at
                  FROM sales.price_calc_config
                 WHERE country = :country AND is_current = TRUE
                """,
                Map.of("country", country),
                (rs, i) -> map(rs)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Transactional
    public PriceCalcConfigDto createNewVersion(
        String country,
        BigDecimal freightPerSqm,
        BigDecimal insurancePerSqm,
        BigDecimal inlandFactoryToPortPerSqm,
        BigDecimal inlandPortToWarehousePerSqm,
        BigDecimal importDutyPct,
        BigDecimal marginPct,
        LocalDate effectiveFrom,
        Long updatedBy
    ) {
        // mark existing current as not current
        jdbc.update(
            "UPDATE sales.price_calc_config SET is_current = FALSE WHERE country = :country AND is_current = TRUE",
            Map.of("country", country));

        // get next version number
        Integer maxVersion = jdbc.queryForObject(
            "SELECT COALESCE(MAX(version), 0) FROM sales.price_calc_config WHERE country = :country",
            Map.of("country", country), Integer.class);
        int nextVersion = (maxVersion != null ? maxVersion : 0) + 1;

        LocalDate from = effectiveFrom != null ? effectiveFrom : LocalDate.now();
        jdbc.update("""
            INSERT INTO sales.price_calc_config
                (version, country,
                 freight_per_sqm, insurance_per_sqm,
                 inland_factory_to_port_per_sqm, inland_port_to_warehouse_per_sqm,
                 import_duty_pct, margin_pct,
                 is_current, effective_from, updated_by, updated_at)
            VALUES
                (:version, :country,
                 :freight, :insurance,
                 :inlandFactoryToPort, :inlandPortToWarehouse,
                 :importDuty, :margin,
                 TRUE, :effectiveFrom, :updatedBy, now())
            """,
            new MapSqlParameterSource()
                .addValue("version", nextVersion)
                .addValue("country", country)
                .addValue("freight", freightPerSqm)
                .addValue("insurance", insurancePerSqm)
                .addValue("inlandFactoryToPort", inlandFactoryToPortPerSqm)
                .addValue("inlandPortToWarehouse", inlandPortToWarehousePerSqm)
                .addValue("importDuty", importDutyPct)
                .addValue("margin", marginPct)
                .addValue("effectiveFrom", from)
                .addValue("updatedBy", updatedBy));

        return findCurrentByCountry(country).orElseThrow();
    }

    private PriceCalcConfigDto map(ResultSet rs) throws SQLException {
        return new PriceCalcConfigDto(
            rs.getLong("config_id"),
            rs.getInt("version"),
            rs.getString("country"),
            rs.getBigDecimal("freight_per_sqm"),
            rs.getBigDecimal("insurance_per_sqm"),
            rs.getBigDecimal("inland_factory_to_port_per_sqm"),
            rs.getBigDecimal("inland_port_to_warehouse_per_sqm"),
            rs.getBigDecimal("import_duty_pct"),
            rs.getBigDecimal("margin_pct"),
            rs.getBoolean("is_current"),
            rs.getDate("effective_from").toLocalDate(),
            rs.getTimestamp("updated_at").toInstant());
    }
}
