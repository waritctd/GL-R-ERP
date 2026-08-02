package th.co.glr.hr.pricing;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Single-row config backing the deal-create modal's "ราคาตั้ง (ประมาณการ)" display multiplier —
 * see V112__deal_estimate_markup.sql. Deliberately NOT {@code PriceCalcConfigRepository}: that
 * table is the real margin policy and this one is a coarse, openly-readable display figure with
 * no relationship to it beyond both living in the {@code sales} schema.
 */
@Repository
public class DealEstimateMarkupRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public DealEstimateMarkupRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code Optional.empty()} if the seeded singleton row is ever absent — matching
     * {@link PriceCalcConfigRepository#findCurrentByCountry}'s pattern rather than letting
     * {@code queryForObject} throw {@code EmptyResultDataAccessException} straight into a 500 on
     * a read every deal-create modal now performs.
     */
    public Optional<DealEstimateMarkupDto> findCurrent() {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT multiplier, updated_at, updated_by
                  FROM sales.deal_estimate_markup
                 WHERE id = 1
                """, Map.of(), (rs, i) -> map(rs)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public DealEstimateMarkupDto update(BigDecimal multiplier, Long updatedBy) {
        jdbc.update("""
            UPDATE sales.deal_estimate_markup
               SET multiplier = :multiplier, updated_at = now(), updated_by = :updatedBy
             WHERE id = 1
            """,
            new MapSqlParameterSource()
                .addValue("multiplier", multiplier)
                .addValue("updatedBy", updatedBy));
        // The row this UPDATE just targeted (WHERE id = 1) may not have existed to begin with —
        // same "singleton row absent" case findCurrent() now tolerates — so this can legitimately
        // come back empty rather than something callers should treat as an invariant violation.
        return findCurrent().orElseThrow();
    }

    private DealEstimateMarkupDto map(ResultSet rs) throws SQLException {
        // wasNull() reflects only the MOST RECENTLY read column, so it has to be captured right
        // after reading updated_by -- reading multiplier/updated_at afterwards first would make it
        // report on updated_at instead and silently turn every real updater id into null.
        long updatedByRaw = rs.getLong("updated_by");
        Long updatedBy = rs.wasNull() ? null : updatedByRaw;
        return new DealEstimateMarkupDto(
            rs.getBigDecimal("multiplier"),
            rs.getTimestamp("updated_at").toInstant(),
            updatedBy);
    }
}
