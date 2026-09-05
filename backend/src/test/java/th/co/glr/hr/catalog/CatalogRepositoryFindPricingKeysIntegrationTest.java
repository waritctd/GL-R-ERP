package th.co.glr.hr.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.catalog.CatalogRepository.CatalogPricingKey;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * F1 (2026-09 review): {@code CatalogRepository#findPricingKeys} is the batched seam
 * {@code LandedCostCalculator} now reads for BOTH thickness and origin country (P1b.2) — it
 * replaced the single-row {@code findThicknessMm} (deleted) and {@code findOriginCountryCode}
 * (deleted earlier) on the engine's hot path, and until this class existed it had NO test coverage
 * of its own at all: it was only reachable, indirectly, through
 * {@link ThicknessResolutionReachesTheEngineIntegrationTest}, which pins the thickness-resolution
 * behaviour that method shares with the deleted single-row lookup, but never touched this method's
 * OWN concerns — the origin-country pairing, the map's absent/duplicate-id semantics, or the
 * {@code PRICE_ID_CHUNK_SIZE} boundary. This class covers exactly those.
 *
 * <p><b>Mutation-checked (2026-09 review, F1).</b> Repointing the thickness SELECT below from
 * {@code price_catalog.v_priceable_product} to {@code price_catalog.product_prices} directly —
 * which drops BOTH the {@code collection_thickness_default} fallback chain AND the
 * {@code WHERE v.status = 'ACTIVE'} filter — was run against the full
 * {@code *Pricing*,*LandedCost*,*Fx*,*FactoryQuote*,*Catalog*,*Thickness*} surface (447 tests).
 * Exactly 4 went red, all for the two mechanisms this file exists to pin, and nothing else moved:
 * {@link #collectionThicknessDefaultFallbackChainResolvesThroughTheBatchedLookup} and
 * {@link #aNonActiveVersionYieldsNullThicknessButStillResolvesTheVersionAgnosticCountry} here,
 * plus {@code ThicknessResolutionReachesTheEngineIntegrationTest}'s
 * {@code aCeoSuppliedDefaultResolvesWhereTheFactoryPublishedNone} (same fallback-chain mechanism)
 * and {@code anArchivedPriceListVersionResolvesToEmptyEvenWithAThicknessStored} (same
 * ACTIVE-filter mechanism) — that class was repointed at this method by the same F1 fix, so it
 * independently pins the same two invariants. The mutation was reverted immediately after; this
 * class was green again (447/447) on the next run.
 */
class CatalogRepositoryFindPricingKeysIntegrationTest extends AbstractPostgresIntegrationTest {

    private CatalogRepository catalog() {
        return new CatalogRepository(jdbc);
    }

    private long factoryOf(long priceId) {
        return jdbc.queryForObject(
            "SELECT factory_id FROM price_catalog.product_prices WHERE price_id = :id",
            Map.of("id", priceId), Long.class);
    }

    private void setCollection(long priceId, String collection) {
        jdbc.update("UPDATE price_catalog.product_prices SET collection = :c WHERE price_id = :id",
            new MapSqlParameterSource().addValue("id", priceId).addValue("c", collection));
    }

    private void clearOwnThickness(long priceId) {
        jdbc.update("UPDATE price_catalog.product_prices SET thickness_mm = NULL WHERE price_id = :id",
            Map.of("id", priceId));
    }

    // ── item 1: the collection_thickness_default fallback chain — the mutation-check target ────

    /**
     * The case F1 requires to go RED under the reviewer's mutation (repointing the thickness
     * SELECT at {@code product_prices} directly, which drops the default-fallback LATERAL join).
     * Asserted on ONE fixture, both sides, per CLAUDE.md's testing rule: nothing entered anywhere
     * resolves null (never guessed, never a missing map entry), and a CEO-supplied default then
     * resolves — the same pair {@code ThicknessResolutionReachesTheEngineIntegrationTest} already
     * pins for the single-row method, repeated here through the BATCHED one, with origin country
     * (which the single-row method never carried) asserted alongside on the same fixture to show
     * the two fields resolve independently of each other.
     */
    @Test
    void collectionThicknessDefaultFallbackChainResolvesThroughTheBatchedLookup() {
        long id = insertCatalogProduct("PK F1", "ES", "PK-DEF-1",
            new BigDecimal("40.00"), "EUR", "per_sqm", "ACTIVE");
        clearOwnThickness(id);
        setCollection(id, "ALTEA");

        CatalogPricingKey before = catalog().findPricingKeys(List.of(id)).get(id);
        assertThat(before.thicknessMm())
            .as("nothing entered yet — must still refuse, never guess")
            .isNull();
        assertThat(before.originCountryCode())
            .as("origin country is unaffected by the thickness chain — must still resolve")
            .isEqualTo("ES");

        jdbc.update("""
            INSERT INTO price_catalog.collection_thickness_default (factory_id, collection, thickness_mm)
            VALUES (:f, 'ALTEA', 8.5)
            """, new MapSqlParameterSource().addValue("f", factoryOf(id)));

        CatalogPricingKey after = catalog().findPricingKeys(List.of(id)).get(id);
        assertThat(after.thicknessMm())
            .as("a CEO-supplied collection default must now reach the batched lookup")
            .isEqualByComparingTo("8.5");
        assertThat(after.originCountryCode()).isEqualTo("ES");
    }

    // ── item 2: the ACTIVE-filter asymmetry the two-query split exists to preserve ──────────────

    /**
     * The asymmetry {@code findPricingKeys}'s own Javadoc says the two-query split preserves: a
     * price id on a non-ACTIVE version yields NULL thickness (matching the deleted
     * {@code findThicknessMm}'s ACTIVE-only semantics, since thickness reads
     * {@code v_priceable_product}) while its origin country still resolves (matching the deleted
     * {@code findOriginCountryCode}'s version-agnostic semantics, since country reads the base
     * tables with no version join at all). Both sides pinned on the SAME row.
     */
    @Test
    void aNonActiveVersionYieldsNullThicknessButStillResolvesTheVersionAgnosticCountry() {
        long id = insertCatalogProduct("PK F2", "IT", "PK-ARCH-1",
            new BigDecimal("74.50"), "EUR", "per_sqm", "ARCHIVED");
        jdbc.update("UPDATE price_catalog.product_prices SET thickness_mm = 9 WHERE price_id = :id",
            Map.of("id", id));

        CatalogPricingKey key = catalog().findPricingKeys(List.of(id)).get(id);

        assertThat(key.thicknessMm())
            .as("a stale version's thickness must not price a live deal")
            .isNull();
        assertThat(key.originCountryCode())
            .as("origin country is version-agnostic — it must resolve even off an ARCHIVED version")
            .isEqualTo("IT");
    }

    // ── item 3 + 5: absent id — a real map entry with BOTH fields null, never a thrown exception ──

    /**
     * Item 3: an absent {@code price_id} must still get a map entry (never a missing key), equal
     * to what {@code Optional.empty().orElse(null)} gave the old per-item callers. Item 5: both
     * fields null simultaneously must resolve cleanly, not throw — this is the one fixture where
     * that combination arises naturally, since {@code price_catalog.factories.country} is
     * {@code NOT NULL} (V151): a REAL linked row can never have a null country, only an absent one
     * can have both fields null at once.
     */
    @Test
    void anAbsentPriceIdStillGetsAMapEntryWithBothFieldsNullRatherThanThrowing() {
        long absentId = -987_654_321L;

        Map<Long, CatalogPricingKey> result = catalog().findPricingKeys(List.of(absentId));

        assertThat(result).containsKey(absentId);
        CatalogPricingKey key = result.get(absentId);
        assertThat(key.thicknessMm()).isNull();
        assertThat(key.originCountryCode()).isNull();
    }

    // ── item 4: duplicate ids must not corrupt or duplicate map entries ─────────────────────────

    @Test
    void duplicateIdsInTheInputCollapseToOneCorrectMapEntry() {
        long id = insertCatalogProduct("PK F4", "CN", "PK-DUP-1",
            new BigDecimal("50.00"), "EUR", "per_sqm", "ACTIVE", new BigDecimal("11"));

        Map<Long, CatalogPricingKey> result = catalog().findPricingKeys(List.of(id, id, id));

        assertThat(result).hasSize(1);
        assertThat(result.get(id).thicknessMm()).isEqualByComparingTo("11");
        assertThat(result.get(id).originCountryCode()).isEqualTo("CN");
    }

    // ── item 6: PRICE_ID_CHUNK_SIZE = 500 crossed, without seeding 500 real catalog rows ─────────

    /**
     * Drives 501 DISTINCT ids through {@link CatalogRepository#findPricingKeys} — one more than
     * {@code PRICE_ID_CHUNK_SIZE} — so the chunking loop runs twice. Chose "drive more than 500
     * ids" over seeding 500 real catalog rows (disproportionate for what this pins) or touching
     * the chunk-size constant's visibility: only 3 ids are real, placed exactly at the positions
     * an off-by-one in the chunk slicing would corrupt — index 0 (first id of chunk 1), index 499
     * (LAST id of chunk 1 — the boundary an off-by-one most often drops), and index 500 (the only
     * id of chunk 2). The other 498 are fabricated NEGATIVE ids, guaranteed never to collide with
     * a real (positive, IDENTITY-generated) {@code price_id} — they exist purely to push the input
     * past the chunk boundary, and are asserted to resolve to null, same as the absent-id case.
     */
    @Test
    void moreThan500DistinctIdsCrossTheChunkBoundaryWithoutLosingOrCorruptingEitherSide() {
        long firstOfChunk1 = insertCatalogProduct("PK CHUNK A", "IT", "PK-CHUNK-1",
            new BigDecimal("30.00"), "EUR", "per_sqm", "ACTIVE", new BigDecimal("5"));
        long lastOfChunk1 = insertCatalogProduct("PK CHUNK B", "ES", "PK-CHUNK-2",
            new BigDecimal("30.00"), "EUR", "per_sqm", "ACTIVE", new BigDecimal("6"));
        long onlyOfChunk2 = insertCatalogProduct("PK CHUNK C", "CN", "PK-CHUNK-3",
            new BigDecimal("30.00"), "EUR", "per_sqm", "ACTIVE", new BigDecimal("7"));

        List<Long> ids = new ArrayList<>();
        ids.add(firstOfChunk1);           // index 0   -> first id of chunk 1
        for (int i = 0; i < 498; i++) {
            ids.add(-1_000_000L - i);     // index 1..498 -> filler, never resolves
        }
        ids.add(lastOfChunk1);            // index 499 -> LAST id of chunk 1
        ids.add(onlyOfChunk2);            // index 500 -> only id of chunk 2
        assertThat(ids).hasSize(501);

        Map<Long, CatalogPricingKey> result = catalog().findPricingKeys(ids);

        assertThat(result).hasSize(501);
        assertThat(result.get(firstOfChunk1).thicknessMm()).isEqualByComparingTo("5");
        assertThat(result.get(firstOfChunk1).originCountryCode()).isEqualTo("IT");
        assertThat(result.get(lastOfChunk1).thicknessMm()).isEqualByComparingTo("6");
        assertThat(result.get(lastOfChunk1).originCountryCode()).isEqualTo("ES");
        assertThat(result.get(onlyOfChunk2).thicknessMm()).isEqualByComparingTo("7");
        assertThat(result.get(onlyOfChunk2).originCountryCode()).isEqualTo("CN");
        for (int i = 0; i < 498; i++) {
            long fillerId = -1_000_000L - i;
            assertThat(result.get(fillerId).thicknessMm()).isNull();
            assertThat(result.get(fillerId).originCountryCode()).isNull();
        }
    }
}
