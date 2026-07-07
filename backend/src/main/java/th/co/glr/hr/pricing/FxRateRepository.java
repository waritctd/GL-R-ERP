package th.co.glr.hr.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FxRateRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FxRateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FxRateDto> findAll() {
        return jdbc.query("""
            SELECT fx_rate_id, currency, rate_to_thb, effective_date, updated_at
              FROM sales.fx_rates
             ORDER BY currency
            """, Map.of(), (rs, i) -> new FxRateDto(
                rs.getLong("fx_rate_id"),
                rs.getString("currency"),
                rs.getBigDecimal("rate_to_thb"),
                rs.getDate("effective_date").toLocalDate(),
                rs.getTimestamp("updated_at").toInstant()));
    }

    public Optional<FxRateDto> findByCurrency(String currency) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT fx_rate_id, currency, rate_to_thb, effective_date, updated_at
                  FROM sales.fx_rates WHERE currency = :currency
                """,
                Map.of("currency", currency),
                (rs, i) -> new FxRateDto(
                    rs.getLong("fx_rate_id"),
                    rs.getString("currency"),
                    rs.getBigDecimal("rate_to_thb"),
                    rs.getDate("effective_date").toLocalDate(),
                    rs.getTimestamp("updated_at").toInstant())));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public FxRateDto upsert(String currency, BigDecimal rateToThb, LocalDate effectiveDate, Long updatedBy) {
        LocalDate date = effectiveDate != null ? effectiveDate : LocalDate.now();
        jdbc.update("""
            INSERT INTO sales.fx_rates (currency, rate_to_thb, effective_date, updated_by, updated_at)
            VALUES (:currency, :rate, :date, :updatedBy, now())
            ON CONFLICT (currency) DO UPDATE
            SET rate_to_thb    = EXCLUDED.rate_to_thb,
                effective_date = EXCLUDED.effective_date,
                updated_by     = EXCLUDED.updated_by,
                updated_at     = now()
            """,
            new MapSqlParameterSource()
                .addValue("currency", currency)
                .addValue("rate", rateToThb)
                .addValue("date", date)
                .addValue("updatedBy", updatedBy));
        return findByCurrency(currency).orElseThrow();
    }
}
