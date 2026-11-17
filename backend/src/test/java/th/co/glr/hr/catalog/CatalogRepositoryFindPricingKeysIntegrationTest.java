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

/** CatalogRepository#findPricingKeys is the batched seam LandedCostCalculator now reads for BOTH thickness and
 * origin country. This class covers exactly those. Mutation-checked (2026-09 review, F1). The mutation was
 * reverted immediately after; this class was green again (447/447) on the next run. */
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

    /** The case F1 requires to go RED under the reviewer's mutation (repointing the thickness SELECT at
     * product_prices directly, which drops the default-fallback LATERAL join). */
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

    /** The asymmetry findPricingKeys's own Javadoc says the two-query split preserves: a price id on a non-ACTIVE
     * version yields NULL thickness while its origin country still resolves. Both sides pinned on the SAME row. */
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

    /** Item 3: an absent price_id must still get a map entry (never a missing key), equal to what
     * Optional.empty().orElse(null) gave the old per-item callers. */
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

    /** Drives 501 DISTINCT ids through CatalogRepository#findPricingKeys — one more than PRICE_ID_CHUNK_SIZE — so
     * the chunking loop runs twice. */
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
