package th.co.glr.hr.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * V163 (ฝ่ายนำเข้า must supply ความหนา when the catalog has none): the (factory, collection) a
     * catalog price row belongs to — the routing key {@code PricingRequestItemThicknessService#setItemThickness}
     * needs to upsert the right {@code price_catalog.collection_thickness_default} row when a
     * pricing-request line's hand-entered thickness should land on the SHARED catalog default
     * rather than the line's own {@code thickness_mm_override}. Also the identity lookup
     * {@code PricingRequestThicknessSuggestionService} (SPEC-PREFILL, ladder A) starts from before
     * it can look for sibling products or a box-weight estimate — {@code factoryName} (added for
     * that caller) is what keys {@code ThicknessEstimator}'s per-factory density map.
     *
     * <p><b>Delegates to the batched {@link #findFactoryAndCollection(Collection)}</b> (SPEC-N1,
     * 2026-09 review) rather than keeping its own copy of the query. Kept as its own overload
     * because {@code PricingRequestItemThicknessService#setItemThickness} (line ~126) is a
     * genuinely PER-LINE endpoint — one line being edited at a time — unlike {@code
     * PricingRequestThicknessSuggestionService#attachSuggestions}, which used to call this once per
     * item in a loop (up to 3N queries for N items) and now prefetches every eligible item's link
     * in ONE batched call instead. See the batched overload's own Javadoc for the SQL and its
     * "un-filtered join" semantics.
     */
    public Optional<CatalogFactoryCollection> findFactoryAndCollection(long priceId) {
        return Optional.ofNullable(findFactoryAndCollection(List.of(priceId)).get(priceId));
    }

    /**
     * Batched form of {@link #findFactoryAndCollection(long)} (SPEC-N1, 2026-09 review) — the seam
     * {@code PricingRequestThicknessSuggestionService#attachSuggestions} now prefetches every
     * eligible item's factory/collection identity through, in ONE (chunked) round trip instead of
     * one query per item.
     *
     * <p>Deliberately reads the base {@code product_prices} table directly, with NO {@code
     * price_list_versions.status = 'ACTIVE'} filter — unlike {@link #findPricingKeys}, which is
     * ACTIVE-only because it answers "what would the pricing engine resolve right now". This
     * method answers a different question: "which factory/collection does this catalog ROW belong
     * to", an identity that does not change with the row's version status. Matches the SAME
     * un-filtered join {@link #findPricingKeys}'s origin-country half already uses, for the same
     * reason (see that method's own Javadoc).
     *
     * @return a map with an entry ONLY for a {@code priceId} that actually resolves a link — an id
     *         with none (a deleted row, say) is simply ABSENT from the map, never present with a
     *         null value, since {@link CatalogFactoryCollection} carries a primitive {@code
     *         factoryId} with no null representation to store one. This mirrors the deleted-row
     *         behaviour of the single-{@code long} overload's {@code Optional.empty()} exactly:
     *         {@code Optional.ofNullable(map.get(id))} recovers it.
     */
    public Map<Long, CatalogFactoryCollection> findFactoryAndCollection(Collection<Long> priceIds) {
        if (priceIds == null || priceIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(priceIds));
        Map<Long, CatalogFactoryCollection> result = new HashMap<>();
        for (int start = 0; start < distinctIds.size(); start += PRICE_ID_CHUNK_SIZE) {
            List<Long> chunk = distinctIds.subList(start, Math.min(start + PRICE_ID_CHUNK_SIZE, distinctIds.size()));
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("priceIds", chunk);
            jdbc.query("""
                SELECT p.price_id, p.factory_id, p.collection, f.name AS factory_name
                  FROM price_catalog.product_prices p
                  JOIN price_catalog.factories       f ON f.factory_id = p.factory_id
                 WHERE p.price_id IN (:priceIds)
                """, params, rs -> {
                    result.put(rs.getLong("price_id"), new CatalogFactoryCollection(
                        rs.getLong("factory_id"), rs.getString("factory_name"), rs.getString("collection")));
                });
        }
        return result;
    }

    /** A catalog price row's factory + collection identity — see {@link #findFactoryAndCollection(long)}. */
    public record CatalogFactoryCollection(long factoryId, String factoryName, String collection) {}

    /**
     * The identity {@link #findSiblingThicknessesMm(Collection)} groups sibling rows by — the SAME
     * (factory, collection) pair {@link CatalogFactoryCollection} carries, minus {@code
     * factoryName} (irrelevant to which rows count as siblings — {@code factoryId} alone already
     * determines it). {@code collection} may be {@code null}; two keys with a {@code null}
     * collection at the same factory are EQUAL (records use {@code Objects.equals} for reference
     * fields, and {@code null.equals(null)} reads as true there), matching the single-row query's
     * {@code IS NOT DISTINCT FROM} semantics exactly — a null collection keeps matching other
     * null-collection rows of the same factory, never rows of a different factory or a real
     * collection value.
     */
    public record FactoryCollectionKey(long factoryId, String collection) {}

    /**
     * One sibling candidate row: its OWN {@code price_id} — so a per-item caller can exclude
     * itself from a group SHARED with other items, since exclusion is a per-item concern, not a
     * per-key one (see {@link #findSiblingThicknessesMm(Collection)}'s own Javadoc) — and its
     * {@code thickness_mm}.
     */
    public record SiblingThicknessRow(long priceId, BigDecimal thicknessMm) {}

    /**
     * SPEC-PREFILL ladder A, rung 3 ("sibling products"): every OTHER row's own {@code
     * thickness_mm} in the same (factory, collection) — never {@code NULL}, never {@code
     * excludePriceId} itself (defensive; that row's own thickness is null by construction whenever
     * a caller reaches this method, since {@link ThicknessEstimator#fromSiblings} is only worth
     * calling once the catalog chain has already failed to resolve one for it — see {@code
     * PricingRequestThicknessSuggestionService}). One list entry per matching row (duplicates are
     * meaningful: they are how the caller counts "N รายการ" and tells agreement from disagreement),
     * so this deliberately returns a flat list rather than a distinct set or a GROUP BY count —
     * {@link ThicknessEstimator#fromSiblings} owns the agreement decision, not this query.
     *
     * <p>ACTIVE-only (joins {@code price_list_versions}), unlike {@link #findFactoryAndCollection(long)}
     * above: this answers "what would a reasonable sibling suggest RIGHT NOW", the same "resolve
     * as of today" question {@link #findPricingKeys} answers for the engine itself — a sibling
     * sitting on an ARCHIVED or DRAFT version is not evidence of what a NEW line should be priced
     * at today.
     *
     * <p><b>Delegates to the batched {@link #findSiblingThicknessesMm(Collection)}</b> (SPEC-N1,
     * 2026-09 review) for a single (factory, collection) key rather than keeping its own copy of
     * the query, applying the {@code excludePriceId} filter itself afterwards (the batched query
     * cannot apply it — see that overload's Javadoc for why exclusion is a per-item, not a
     * per-key, concern). This keeps this overload's own real-Postgres tests exercising the exact
     * same SQL as the batched form.
     */
    public List<BigDecimal> findSiblingThicknessesMm(long factoryId, String collection, long excludePriceId) {
        FactoryCollectionKey key = new FactoryCollectionKey(factoryId, collection);
        List<SiblingThicknessRow> rows = findSiblingThicknessesMm(List.of(key)).getOrDefault(key, List.of());
        List<BigDecimal> siblings = new ArrayList<>(rows.size());
        for (SiblingThicknessRow row : rows) {
            if (row.priceId() != excludePriceId) {
                siblings.add(row.thicknessMm());
            }
        }
        return siblings;
    }

    /**
     * Batched form of {@link #findSiblingThicknessesMm(long, String, long)} (SPEC-N1, 2026-09
     * review) — {@code PricingRequestThicknessSuggestionService#attachSuggestions} now prefetches
     * siblings for every DISTINCT (factory, collection) pair its eligible items resolve to, in ONE
     * (chunked) round trip, rather than one query per item. The row's own exclusion is deliberately
     * NOT applied here — it is a per-ITEM concern, not a per-KEY one, since two items can share a
     * key and each must exclude only ITSELF — so this returns EVERY matching row for the key, and
     * {@link #findSiblingThicknessesMm(long, String, long)} above (or {@code
     * PricingRequestThicknessSuggestionService} directly, for the batched caller) filters its own
     * {@code price_id} out afterwards.
     *
     * <p><b>Superset filter in SQL, exact match in Java.</b> A literal {@code (factory_id,
     * collection) IN ((:f1,:c1), ...)} row-value comparison never matches when {@code collection}
     * is {@code NULL} on either side — the same reason the single-key query needs {@code
     * IS NOT DISTINCT FROM} rather than {@code =}. Instead this fetches every row whose {@code
     * factory_id} is one of the requested keys' factories AND whose {@code COALESCE(collection,
     * '')} is one of the requested keys' (coalesced) collections — a SUPERSET that can include
     * combinations nobody asked for (factory A's "Rosso" rows when only factory A's "Bianco" and
     * factory B's "Rosso" were requested) — then groups the rows by the EXACT (factoryId, raw
     * collection) pair in Java, so a caller looking up a key it never asked for simply finds
     * nothing. This never fetches a whole factory's rows unfiltered (Padana alone has thousands of
     * thickness-bearing rows), because the collection filter still applies.
     *
     * <p><b>Chunked, and deduplicated by {@code price_id} across chunks.</b> Chunking here splits
     * the list of DISTINCT keys (mirroring {@link #findPricingKeys}'s id-list chunking), but unlike
     * a plain {@code price_id IN (:ids)} chunk split — where every id lands in exactly one,
     * disjoint chunk — this method's two-list cross-product filter means the SAME physical sibling
     * row could satisfy more than one chunk's query (e.g. a row for key (A,Y) can also match a
     * DIFFERENT chunk's superset if that chunk happens to carry factory A and collection Y from two
     * unrelated keys of its own). Accumulating rows into a {@code price_id}-keyed map per key
     * (rather than a plain list) before flattening to the returned list makes a row counted twice
     * across chunks collapse to one entry rather than inflate the sibling COUNT the Thai "N
     * รายการ" text is built from ({@link ThicknessEstimator#fromSiblings}). In practice a single
     * pricing request carries "a handful to a few dozen items" (far under {@link
     * #PRICE_ID_CHUNK_SIZE}), so this almost never crosses a chunk boundary at all — the dedup
     * exists so correctness does not depend on that staying true.
     *
     * @return one entry per DISTINCT {@link FactoryCollectionKey} that appears in {@code keys} AND
     *         resolves at least one sibling row; a key with none is simply ABSENT (never present
     *         with an empty list) — a caller reads a missing key with {@code
     *         getOrDefault(key, List.of())}, same result as the single-key overload's empty list.
     */
    public Map<FactoryCollectionKey, List<SiblingThicknessRow>> findSiblingThicknessesMm(Collection<FactoryCollectionKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        List<FactoryCollectionKey> distinctKeys = new ArrayList<>(new LinkedHashSet<>(keys));
        // price_id -> row, per key, so a row fetched twice across chunks (see this method's own
        // Javadoc) collapses to one entry instead of inflating the sibling count.
        Map<FactoryCollectionKey, LinkedHashMap<Long, SiblingThicknessRow>> accumulator = new HashMap<>();
        for (int start = 0; start < distinctKeys.size(); start += PRICE_ID_CHUNK_SIZE) {
            List<FactoryCollectionKey> chunk =
                distinctKeys.subList(start, Math.min(start + PRICE_ID_CHUNK_SIZE, distinctKeys.size()));
            Set<Long> factoryIds = new LinkedHashSet<>();
            Set<String> collections = new LinkedHashSet<>();
            for (FactoryCollectionKey key : chunk) {
                factoryIds.add(key.factoryId());
                collections.add(key.collection() == null ? "" : key.collection());
            }
            MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("factoryIds", new ArrayList<>(factoryIds))
                .addValue("collections", new ArrayList<>(collections));
            jdbc.query("""
                SELECT p.price_id, p.factory_id, p.collection, p.thickness_mm
                  FROM price_catalog.product_prices p
                  JOIN price_catalog.price_list_versions v ON v.version_id = p.version_id
                 WHERE p.factory_id IN (:factoryIds)
                   AND COALESCE(p.collection, '') IN (:collections)
                   AND p.thickness_mm IS NOT NULL
                   AND v.status = 'ACTIVE'
                """, params, rs -> {
                    FactoryCollectionKey rowKey =
                        new FactoryCollectionKey(rs.getLong("factory_id"), rs.getString("collection"));
                    long priceId = rs.getLong("price_id");
                    accumulator.computeIfAbsent(rowKey, k -> new LinkedHashMap<>())
                        .put(priceId, new SiblingThicknessRow(priceId, rs.getBigDecimal("thickness_mm")));
                });
        }
        Map<FactoryCollectionKey, List<SiblingThicknessRow>> result = new HashMap<>();
        for (Map.Entry<FactoryCollectionKey, LinkedHashMap<Long, SiblingThicknessRow>> entry : accumulator.entrySet()) {
            // Only keep keys someone actually asked for — the superset filter can otherwise
            // surface a combination nobody requested (see this method's own Javadoc).
            if (distinctKeys.contains(entry.getKey())) {
                result.put(entry.getKey(), new ArrayList<>(entry.getValue().values()));
            }
        }
        return result;
    }

    /**
     * SPEC-PREFILL ladder A, rung 4 ("box weight"): the three raw ingredients {@link
     * ThicknessEstimator#fromBoxWeight} needs for one catalog row — the factory NAME (keys the
     * density map), {@code kg_per_box} (V40 import, box-level, never priced-affecting on its own),
     * and the box's real footprint area. That area is {@code true_sqm_per_box}
     * (V153/V164), not the raw {@code sqm_per_box} column — for a {@code per_linear_m} row the raw
     * column holds LINEAR METRES mislabelled as square metres (V153's own column comment: a CITY
     * battiscopa reports 6.0 for a box that is really 0.42 m², a 14.3x overstatement), and feeding
     * that straight into a density-implied thickness would be wrong in exactly the same way. Reads
     * {@code price_catalog.v_priceable_product} — ACTIVE-only, matching {@link #findPricingKeys}'s
     * "resolve as of today" semantics — joined back to the base table for the two columns V164
     * added there but not to any pricing computation (see that migration's header).
     *
     * <p><b>Delegates to the batched {@link #findThicknessEstimationInputs(Collection)}</b>
     * (SPEC-N1, 2026-09 review) rather than keeping its own copy of the query, so this overload's
     * real-Postgres tests keep exercising the exact same SQL.
     */
    public Optional<CatalogThicknessEstimationInputs> findThicknessEstimationInputs(long priceId) {
        return Optional.ofNullable(findThicknessEstimationInputs(List.of(priceId)).get(priceId));
    }

    /**
     * Batched form of {@link #findThicknessEstimationInputs(long)} (SPEC-N1, 2026-09 review) — the
     * seam {@code PricingRequestThicknessSuggestionService#attachSuggestions} now prefetches every
     * still-eligible item's box-weight ingredients through, in ONE (chunked) round trip instead of
     * one query per item. Same {@code v_priceable_product}-joined-to-base-table shape as the
     * single-row overload — see that overload's Javadoc for why the two columns must come from
     * those exact two places.
     *
     * @return a map with an entry ONLY for a {@code priceId} that resolves (ACTIVE version, present
     *         in {@code v_priceable_product}) — an id with none is simply ABSENT, never present
     *         with a null value, mirroring the single-{@code long} overload's {@code
     *         Optional.empty()}: {@code Optional.ofNullable(map.get(id))} recovers it.
     */
    public Map<Long, CatalogThicknessEstimationInputs> findThicknessEstimationInputs(Collection<Long> priceIds) {
        if (priceIds == null || priceIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(priceIds));
        Map<Long, CatalogThicknessEstimationInputs> result = new HashMap<>();
        for (int start = 0; start < distinctIds.size(); start += PRICE_ID_CHUNK_SIZE) {
            List<Long> chunk = distinctIds.subList(start, Math.min(start + PRICE_ID_CHUNK_SIZE, distinctIds.size()));
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("priceIds", chunk);
            jdbc.query("""
                SELECT vpp.price_id, vpp.factory AS factory_name, vpp.true_sqm_per_box, p.kg_per_box
                  FROM price_catalog.v_priceable_product vpp
                  JOIN price_catalog.product_prices      p ON p.price_id = vpp.price_id
                 WHERE vpp.price_id IN (:priceIds)
                """, params, rs -> {
                    result.put(rs.getLong("price_id"), new CatalogThicknessEstimationInputs(
                        rs.getString("factory_name"), rs.getBigDecimal("true_sqm_per_box"), rs.getBigDecimal("kg_per_box")));
                });
        }
        return result;
    }

    /** The box-weight estimator's raw ingredients for one price row — see {@link #findThicknessEstimationInputs(long)}. */
    public record CatalogThicknessEstimationInputs(String factoryName, BigDecimal sqmPerBox, BigDecimal kgPerBox) {}

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
