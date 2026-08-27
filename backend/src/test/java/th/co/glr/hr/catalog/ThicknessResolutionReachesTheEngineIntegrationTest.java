package th.co.glr.hr.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * {@code CatalogRepository#findThicknessMm} is the single seam through which the landed-cost
 * engine learns a product's thickness, and thickness selects the freight band — a band that can
 * differ by ฿50,000 per shipment. These tests pin what that seam does and does not resolve.
 *
 * <p>The interesting case is the middle one. Four of the nine factory workbooks carry no thickness
 * column at all, so 9,411 catalogue rows have a NULL {@code thickness_mm} that no parser can
 * recover. Before this change the engine read {@code product_prices} directly, so every one of
 * those rows failed costing permanently and no CEO entry could change that. Reading through
 * {@code v_priceable_product} is what makes the defaults table reach a price.
 */
class ThicknessResolutionReachesTheEngineIntegrationTest extends AbstractPostgresIntegrationTest {

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

    @Test
    void aRowsOwnThicknessStillWinsOverAnyDefault() {
        long id = insertCatalogProduct("TR F1", "IT", "OWN-1",
            new BigDecimal("74.50"), "EUR", "per_sqm", "ACTIVE");
        jdbc.update("UPDATE price_catalog.product_prices SET thickness_mm = 9, collection = 'X' WHERE price_id = :id",
            Map.of("id", id));
        // A conflicting default must NOT override a value the factory actually published.
        jdbc.update("""
            INSERT INTO price_catalog.collection_thickness_default (factory_id, collection, thickness_mm)
            VALUES (:f, 'X', 20)
            """, new MapSqlParameterSource().addValue("f", factoryOf(id)));

        // isEqualByComparingTo, not contains(): Optional.contains uses BigDecimal.equals, which is
        // SCALE-sensitive — 9 and 9.00 are unequal there, which would fail on a true value.
        assertThat(catalog().findThicknessMm(id).orElseThrow()).isEqualByComparingTo("9");
    }

    /** The whole point of the repoint: a CEO-entered default now reaches the engine. */
    @Test
    void aCeoSuppliedDefaultResolvesWhereTheFactoryPublishedNone() {
        long id = insertCatalogProduct("TR F2", "ES", "DEF-1",
            new BigDecimal("40.00"), "EUR", "per_sqm", "ACTIVE");
        clearOwnThickness(id);
        setCollection(id, "ALTEA");

        // Before: product_prices.thickness_mm is NULL, so costing failed permanently.
        assertThat(catalog().findThicknessMm(id))
            .as("nothing entered yet — must still refuse, never guess")
            .isEmpty();

        jdbc.update("""
            INSERT INTO price_catalog.collection_thickness_default (factory_id, collection, thickness_mm)
            VALUES (:f, 'ALTEA', 8.5)
            """, new MapSqlParameterSource().addValue("f", factoryOf(id)));

        assertThat(catalog().findThicknessMm(id).orElseThrow()).isEqualByComparingTo("8.5");
    }

    @Test
    void nothingEnteredAnywhereStillResolvesToEmptySoCostingFailsLoudly() {
        long id = insertCatalogProduct("TR F3", "ES", "NONE-1",
            new BigDecimal("40.00"), "EUR", "per_sqm", "ACTIVE");
        clearOwnThickness(id);
        setCollection(id, "UNCOVERED");

        assertThat(catalog().findThicknessMm(id)).isEmpty();
    }

    /**
     * The behavioural narrowing the view brings: costing must not read a superseded price list's
     * thickness. {@code PricingRequestService#submit}'s catalog gate already rejects a non-ACTIVE
     * link, so this is defence in depth.
     */
    @Test
    void anArchivedPriceListVersionResolvesToEmptyEvenWithAThicknessStored() {
        long id = insertCatalogProduct("TR F4", "IT", "ARCH-1",
            new BigDecimal("74.50"), "EUR", "per_sqm", "ARCHIVED");
        jdbc.update("UPDATE price_catalog.product_prices SET thickness_mm = 9 WHERE price_id = :id",
            Map.of("id", id));

        assertThat(catalog().findThicknessMm(id))
            .as("a stale version's thickness must not price a live deal")
            .isEmpty();
    }

    @Test
    void anUnknownPriceIdResolvesToEmptyRatherThanThrowing() {
        assertThat(catalog().findThicknessMm(-1L)).isEmpty();
    }
}
