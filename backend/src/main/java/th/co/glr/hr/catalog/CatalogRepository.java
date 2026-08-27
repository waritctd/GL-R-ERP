package th.co.glr.hr.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The RESOLVED thickness for one catalog price row — used by
     * {@code LandedCostCalculator#resolveThicknessMm} for V109's freight-band lookup. Returns empty
     * (never a fallback value) when nothing resolves — the caller turns that into a loud,
     * item-naming failure rather than guessing.
     *
     * <p>Reads {@code price_catalog.v_priceable_product}, not {@code product_prices} directly. The
     * view resolves V153's thickness chain: the row's own {@code thickness_mm} first, then the
     * CEO-maintained {@code collection_thickness_default} most-specific-first
     * ({@code (factory, collection, size_norm)} → {@code (factory, collection)} → {@code (factory)}).
     *
     * <p>Reading the base table skipped that chain entirely, which mattered: four of the nine
     * factory workbooks (Vives, Padana, Equipe, Bode) carry no thickness column at all, so 9,411
     * catalogue rows have a NULL {@code thickness_mm} that no parser can recover. Against the base
     * table every one of them failed costing permanently, and the CEO had no way to fix it short of
     * a migration. The defaults table is that way — but only if this reads through it.
     *
     * <p>"Never guessed, never defaulted" is UNCHANGED as a rule, and deliberately so: a wrong
     * thickness selects a freight band that can differ by ฿50,000 per shipment. What changed is
     * whose value counts. A CEO-entered collection default is supplied data, not a guess; the view
     * still yields NULL when nothing is entered, and this still fails loudly then.
     *
     * <p>One behavioural narrowing comes with the view: it filters to the ACTIVE price-list version,
     * so a {@code price_id} on an ARCHIVED or DRAFT version now returns empty rather than that
     * version's stale thickness. Costing off a superseded price list was never intended —
     * {@code PricingRequestService#submit}'s catalog gate already rejects a non-ACTIVE link — so
     * this is defence in depth rather than a new restriction.
     */
    public Optional<BigDecimal> findThicknessMm(long priceId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT thickness_mm FROM price_catalog.v_priceable_product WHERE price_id = :priceId",
                new MapSqlParameterSource().addValue("priceId", priceId), BigDecimal.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * The ISO 3166-1 alpha-2 origin country code for one catalog price row's factory —
     * {@code price_catalog.factories.country} via {@code price_catalog.product_prices.factory_id}.
     * This is V151's canonical source (the SAME column {@code
     * sales.pricing_freight_rate.origin_country_code} is normalised against, joined via {@code
     * price_catalog.country}) — used by {@code LandedCostCalculator#resolveOriginCountryCode} for
     * V109's freight-band lookup. Deliberately NOT {@code sales.factory_config.country}: that is a
     * free-text field on a different table (RFQ email routing settings) V151 never touched, and
     * reading it here would silently reintroduce the exact free-text/ISO-code mismatch V151's own
     * migration fixed. Returns empty (never a fallback) when the row does not exist — the caller
     * turns that into a loud, item-naming failure rather than guessing, the same as {@link
     * #findThicknessMm}.
     */
    public Optional<String> findOriginCountryCode(long priceId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT f.country
                  FROM price_catalog.product_prices pp
                  JOIN price_catalog.factories f ON f.factory_id = pp.factory_id
                 WHERE pp.price_id = :priceId
                """,
                new MapSqlParameterSource().addValue("priceId", priceId), String.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<CatalogDto> search(String q) {
        String pattern = q == null || q.isBlank() ? "%" : "%" + q.trim() + "%";
        return jdbc.query(
            """
            SELECT catalog_id, brand, collection, color, surface, size, factory, sqm_per_piece
              FROM sales.catalog
             WHERE brand      ILIKE :q
                OR collection ILIKE :q
                OR color      ILIKE :q
                OR factory    ILIKE :q
             ORDER BY brand, collection, color
             LIMIT 30
            """,
            Map.of("q", pattern),
            (rs, i) -> new CatalogDto(
                rs.getLong("catalog_id"),
                rs.getString("brand"),
                rs.getString("collection"),
                rs.getString("color"),
                rs.getString("surface"),
                rs.getString("size"),
                rs.getString("factory"),
                rs.getBigDecimal("sqm_per_piece")
            )
        );
    }

    public List<ProductPriceDto> searchProductPrices(String q, Long factoryId, int limit) {
        String pattern = q == null || q.isBlank() ? "%" : "%" + q.trim() + "%";
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("q", pattern)
            .addValue("limit", limit);

        String factoryClause = factoryId != null ? "AND pp.factory_id = :factoryId" : "";
        if (factoryId != null) params.addValue("factoryId", factoryId);

        return jdbc.query(
            """
            SELECT pp.price_id, f.factory_id, f.name AS factory_name,
                   pp.product_code, pp.grade, pp.collection, pp.product_name,
                   pp.color, pp.surface, pp.size_raw,
                   pp.price, pp.currency, pp.price_unit, pp.sqm_per_piece
              FROM price_catalog.product_prices pp
              JOIN price_catalog.price_list_versions plv ON plv.version_id = pp.version_id
              JOIN price_catalog.factories           f   ON f.factory_id   = pp.factory_id
             WHERE plv.status = 'ACTIVE'
               %s
               AND (
                     pp.product_code  ILIKE :q
                  OR pp.collection    ILIKE :q
                  OR pp.product_name  ILIKE :q
                  OR pp.color         ILIKE :q
                  OR pp.surface       ILIKE :q
                  OR f.name           ILIKE :q
               )
             ORDER BY f.name, pp.collection NULLS LAST, pp.product_code NULLS LAST
             LIMIT :limit
            """.formatted(factoryClause),
            params,
            (rs, i) -> new ProductPriceDto(
                rs.getLong("price_id"),
                rs.getLong("factory_id"),
                rs.getString("factory_name"),
                rs.getString("product_code"),
                rs.getString("grade"),
                rs.getString("collection"),
                rs.getString("product_name"),
                rs.getString("color"),
                rs.getString("surface"),
                rs.getString("size_raw"),
                rs.getBigDecimal("price"),
                rs.getString("currency"),
                rs.getString("price_unit"),
                rs.getBigDecimal("sqm_per_piece")
            )
        );
    }
}
