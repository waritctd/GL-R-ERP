package th.co.glr.hr.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    /**
     * {@link #findPricingKeys} chunk size — defensive, not a real Postgres limit (an {@code IN}
     * list binds each element as its own parameter, comfortably clear of the 65535-parameter
     * protocol ceiling even at several thousand items). A pricing request realistically carries a
     * handful to a few dozen items, never hundreds, but chunking costs nothing and means this
     * method never needs revisiting if that assumption ever stops holding.
     */
    private static final int PRICE_ID_CHUNK_SIZE = 500;

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replacement for two now-deleted single-row lookups: {@code findThicknessMm} and {@code
     * findOriginCountryCode}. Both were {@code LandedCostCalculator}'s own single-row methods;
     * {@code findOriginCountryCode} was deleted first (verified by grep before removal, see this
     * method's own commit), and {@code findThicknessMm} was deleted by F1 (2026-09 review) once
     * this method took over as the engine's seam and its only remaining caller was its own test
     * (repointed at this method — see {@code CatalogRepositoryFindPricingKeysIntegrationTest} and
     * the repointed {@code ThicknessResolutionReachesTheEngineIntegrationTest}). Issue P1b.2:
     * {@code LandedCostCalculator#resolveItemPhysicals} used to call two single-row lookups PER
     * pricing-request item (2N round trips for N items, one of this class's own tables/views each
     * time); this resolves every distinct {@code priceId} in ONE round trip per field (chunked
     * defensively — see {@link #PRICE_ID_CHUNK_SIZE}), keyed by price_id so the caller reads each
     * item's physicals from an in-memory map afterwards.
     *
     * <p><b>Two queries, not one — deliberate, but for a narrower reason than it first looks.</b>
     * {@code price_catalog.v_priceable_product} exposes both {@code thickness_mm} and {@code
     * origin_country_code} from a single row, which looks like it would let one SELECT answer
     * both fields at once. Doing so would change what a NON-ACTIVE {@code price_id} returns: the
     * view filters {@code WHERE v.status = 'ACTIVE'} (V153), while the origin-country query below
     * ({@code product_prices} joined straight to {@code factories}, no {@code
     * price_list_versions} join at all) never applies that filter — so for a {@code price_id}
     * sitting on an ARCHIVED or DRAFT version, a combined query would return NULL for BOTH fields
     * where today only thickness does.
     *
     * <p><b>F3 correction — that is NOT a costable/uncostable behaviour change.</b> An earlier
     * version of this Javadoc called the two-query split necessary to avoid "a BEHAVIOUR CHANGE
     * on the origin-country side" — literally true, but overstating the stakes. {@code
     * LandedCostCalculator#uncostableReason} already marks an item UNCOSTABLE when EITHER field is
     * null, so a non-ACTIVE row is uncostable either way, merged query or not. What the split
     * actually buys is a more precise Thai reason message: today such a row can correctly name
     * only the missing thickness (origin country genuinely resolves off the base tables); a
     * combined query would make it claim both fields are missing when only one really is. That
     * precision is worth keeping — a CEO misled by a wrong "both missing" message could waste time
     * relinking a catalog row whose only real problem is a stale price-list version — but it is a
     * message-precision benefit, not a load-bearing correctness one. Do not collapse this to one
     * query believing it would change what gets costed; it would not, only what the error says.
     *
     * @return a map with one entry per DISTINCT id in {@code priceIds} that was passed in (even
     *         when neither field resolves, so the caller can distinguish "no catalog link at all"
     *         from "linked but nothing resolved" the same way the deleted single-row methods'
     *         {@code Optional.empty()} did — a missing map entry never happens for a requested id,
     *         only a {@link CatalogPricingKey} whose fields are null).
     */
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
            // Thickness: the SAME view and ACTIVE-only semantics the deleted findThicknessMm used
            // (F1) — see this method's own Javadoc for why this cannot also carry origin country.
            jdbc.query("""
                SELECT price_id, thickness_mm
                  FROM price_catalog.v_priceable_product
                 WHERE price_id IN (:priceIds)
                """, params, rs -> {
                    thicknessById.put(rs.getLong("price_id"), rs.getBigDecimal("thickness_mm"));
                });
            // Origin country: the SAME base-table join and NO version filter — the old
            // findOriginCountryCode's exact semantics (V151: price_catalog.factories.country via
            // product_prices.factory_id, never sales.factory_config.country).
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

    /** One price row's batched pricing-lookup keys (P1b.2) — a null field means "did not
     * resolve", mirroring the deleted single-row {@code findThicknessMm}/{@code
     * findOriginCountryCode}'s {@code Optional.empty()} exactly (see {@link #findPricingKeys}). */
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
