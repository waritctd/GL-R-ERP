package th.co.glr.hr.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    /** #findPricingKeys chunk size — defensive, not a real Postgres limit (an IN list binds each element as its own
     * parameter, comfortably clear of the 65535-parameter protocol ceiling even at several thousand items). */
    private static final int PRICE_ID_CHUNK_SIZE = 500;

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Replacement for two now-deleted single-row lookups: findThicknessMm and findOriginCountryCode. Do not
     * collapse this to one query believing it would change what gets costed; it would not, only what the error
     * says. */
    public Map<Long, CatalogPricingKey> findPricingKeys(Collection<Long> priceIds) {
        if (priceIds == null || priceIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(priceIds));
        Map<Long, BigDecimal> thicknessById = new HashMap<>();
        Map<Long, String> countryById = new HashMap<>();
        for (int start = 0; start < distinctIds.size(); start += PRICE_ID_CHUNK_SIZE) {
            List<Long> chunk = distinctIds.subList(start, Math.min(start + PRICE_ID_CHUNK_SIZE, distinctIds.size()));
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("priceIds", chunk);
            // Thickness: the SAME view and ACTIVE-only semantics the deleted findThicknessMm used (F1) — see this method's
            // own Javadoc for why this cannot also carry origin country.
            jdbc.query("""
                SELECT price_id, thickness_mm
                  FROM price_catalog.v_priceable_product
                 WHERE price_id IN (:priceIds)
                """, params, rs -> {
                    thicknessById.put(rs.getLong("price_id"), rs.getBigDecimal("thickness_mm"));
                });
            // Origin country: the SAME base-table join and NO version filter — the old findOriginCountryCode's exact
            // semantics (V151: price_catalog.factories.country via product_prices.factory_id, never
            // sales.factory_config.country).
            jdbc.query("""
                SELECT pp.price_id, f.country
                  FROM price_catalog.product_prices pp
                  JOIN price_catalog.factories f ON f.factory_id = pp.factory_id
                 WHERE pp.price_id IN (:priceIds)
                """, params, rs -> {
                    countryById.put(rs.getLong("price_id"), rs.getString("country"));
                });
        }
        Map<Long, CatalogPricingKey> result = new HashMap<>();
        for (Long priceId : distinctIds) {
            result.put(priceId, new CatalogPricingKey(thicknessById.get(priceId), countryById.get(priceId)));
        }
        return result;
    }

    /** price_id -> price_catalog.factories.name, for the ids that resolve to a factory (absent otherwise). */
    public Map<Long, String> findFactoryNames(Collection<Long> priceIds) {
        if (priceIds == null || priceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        jdbc.query("""
            SELECT pp.price_id, f.name
              FROM price_catalog.product_prices pp
              JOIN price_catalog.factories f ON f.factory_id = pp.factory_id
             WHERE pp.price_id IN (:priceIds)
            """, new MapSqlParameterSource().addValue("priceIds", new ArrayList<>(new LinkedHashSet<>(priceIds))),
            rs -> { names.put(rs.getLong("price_id"), rs.getString("name")); });
        return names;
    }

    /** One price row's batched pricing-lookup keys (P1b.2) — a null field means "did not resolve", mirroring the
     * deleted single-row findThicknessMm/findOriginCountryCode's Optional.empty() exactly (see #findPricingKeys). */
    public record CatalogPricingKey(BigDecimal thicknessMm, String originCountryCode) {}

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
                   pp.color, pp.surface, pp.size_raw, pp.size_cm,
                   pp.price, pp.currency, pp.price_unit, pp.sqm_per_piece,
                   pp.thickness_mm, pp.pcs_per_box, pp.sqm_per_box, f.country AS origin_country_code,
                   pp.width_mm, pp.height_mm
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
                rs.getString("size_cm"),
                rs.getBigDecimal("price"),
                rs.getString("currency"),
                rs.getString("price_unit"),
                rs.getBigDecimal("sqm_per_piece"),
                rs.getBigDecimal("thickness_mm"),
                rs.getBigDecimal("pcs_per_box"),
                rs.getBigDecimal("sqm_per_box"),
                rs.getString("origin_country_code"),
                rs.getBigDecimal("width_mm"),
                rs.getBigDecimal("height_mm")
            )
        );
    }

    /** One catalog price row's sqm_per_piece/price_unit/width_mm/ height_mm, by its price_id — used by
     * DealQuotationService #resolveSqmPerPiece to resolve a tile's ตร.ม./แผ่น from the catalogue rather than
     * guessing the unit of a free-text size string (owner ruling 2026-09-12, "2) ไม่มีค่อยคำนวนเอง"). */
    public record CatalogSqmBasis(BigDecimal sqmPerPiece, String priceUnit, BigDecimal widthMm, BigDecimal heightMm) {}

    public Optional<CatalogSqmBasis> findSqmBasis(long priceId) {
        List<CatalogSqmBasis> rows = jdbc.query(
            """
            SELECT sqm_per_piece, price_unit, width_mm, height_mm
              FROM price_catalog.product_prices
             WHERE price_id = :priceId
            """,
            Map.of("priceId", priceId),
            (rs, i) -> new CatalogSqmBasis(
                rs.getBigDecimal("sqm_per_piece"),
                rs.getString("price_unit"),
                rs.getBigDecimal("width_mm"),
                rs.getBigDecimal("height_mm")
            )
        );
        return rows.stream().findFirst();
    }

    /** Batched twin of #findSqmBasis — same SELECT shape, one round trip for every distinct priceId instead of one
     * call per row. Added for DealQuotationRepository's item-mapping path (owner ruling 2026-09-12, "normalize it
     * in the database so its the same in unit. */
    public Map<Long, CatalogSqmBasis> findSqmBases(Collection<Long> priceIds) {
        if (priceIds == null || priceIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(priceIds));
        Map<Long, CatalogSqmBasis> result = new HashMap<>();
        for (int start = 0; start < distinctIds.size(); start += PRICE_ID_CHUNK_SIZE) {
            List<Long> chunk = distinctIds.subList(start, Math.min(start + PRICE_ID_CHUNK_SIZE, distinctIds.size()));
            jdbc.query(
                """
                SELECT price_id, sqm_per_piece, price_unit, width_mm, height_mm
                  FROM price_catalog.product_prices
                 WHERE price_id IN (:priceIds)
                """,
                new MapSqlParameterSource().addValue("priceIds", chunk),
                rs -> {
                    result.put(rs.getLong("price_id"), new CatalogSqmBasis(
                        rs.getBigDecimal("sqm_per_piece"),
                        rs.getString("price_unit"),
                        rs.getBigDecimal("width_mm"),
                        rs.getBigDecimal("height_mm")
                    ));
                }
            );
        }
        return result;
    }
}
