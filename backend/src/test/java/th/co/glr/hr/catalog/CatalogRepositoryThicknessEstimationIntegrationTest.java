package th.co.glr.hr.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.catalog.CatalogRepository.CatalogThicknessEstimationInputs;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * SPEC-PREFILL.md ladder A: the two REPOSITORY methods {@link
 * PricingRequestThicknessSuggestionServiceTest} mocks — {@link
 * CatalogRepository#findSiblingThicknessesMm} and {@link
 * CatalogRepository#findThicknessEstimationInputs} — against real Postgres, so the SQL itself (the
 * ACTIVE-version filter, the collection scoping, and V164's view columns) is proven, not just the
 * Java glue around it. {@link ThicknessEstimatorTest} covers the DECISION those two feed; this
 * class covers the DATA.
 *
 * <p>Checked by hand: repointing {@code findThicknessEstimationInputs} at the RAW {@code
 * p.sqm_per_box} column instead of {@code vpp.true_sqm_per_box} turns {@link
 * #sqmPerBoxIsTheLinearMetreCorrectedFigureForAPerLinearMRow} red (6.0 instead of the corrected
 * 0.42) and nothing else — reverted immediately after.
 */
class CatalogRepositoryThicknessEstimationIntegrationTest extends AbstractPostgresIntegrationTest {

    private CatalogRepository catalog() {
        return new CatalogRepository(jdbc);
    }

    private void setCollection(long priceId, String collection) {
        jdbc.update("UPDATE price_catalog.product_prices SET collection = :c WHERE price_id = :id",
            new MapSqlParameterSource().addValue("id", priceId).addValue("c", collection));
    }

    private void setThickness(long priceId, BigDecimal thicknessMm) {
        jdbc.update("UPDATE price_catalog.product_prices SET thickness_mm = :t WHERE price_id = :id",
            new MapSqlParameterSource().addValue("id", priceId).addValue("t", thicknessMm));
    }

    private void setBoxWeight(long priceId, BigDecimal kgPerBox, BigDecimal sqmPerBox) {
        jdbc.update("""
            UPDATE price_catalog.product_prices SET kg_per_box = :kg, sqm_per_box = :sqm WHERE price_id = :id
            """,
            new MapSqlParameterSource().addValue("id", priceId).addValue("kg", kgPerBox).addValue("sqm", sqmPerBox));
    }

    private long factoryOf(long priceId) {
        return jdbc.queryForObject(
            "SELECT factory_id FROM price_catalog.product_prices WHERE price_id = :id",
            Map.of("id", priceId), Long.class);
    }

    // ── findSiblingThicknessesMm ─────────────────────────────────────────────────────────────────

    @Test
    void returnsOneEntryPerSiblingRowSharingFactoryAndCollection() {
        long a = insertCatalogProduct("SiblingTest F1", "IT", "SIB-A", new BigDecimal("40"), "EUR", "per_sqm");
        long b = insertCatalogProduct("SiblingTest F1", "IT", "SIB-B", new BigDecimal("40"), "EUR", "per_sqm");
        long c = insertCatalogProduct("SiblingTest F1", "IT", "SIB-C", new BigDecimal("40"), "EUR", "per_sqm");
        setCollection(a, "Bianco");
        setCollection(b, "Bianco");
        setCollection(c, "Bianco");
        setThickness(a, null);
        setThickness(b, new BigDecimal("9"));
        setThickness(c, new BigDecimal("9"));

        List<BigDecimal> siblings = catalog().findSiblingThicknessesMm(factoryOf(a), "Bianco", a);

        assertThat(siblings).hasSize(2);
        assertThat(siblings).allSatisfy(t -> assertThat(t).isEqualByComparingTo("9"));
    }

    @Test
    void excludesTheRowItselfEvenIfItHasAThickness() {
        // Realistically this row's own thickness would already have resolved rung 1 and this
        // method would never be called for it — asserted anyway, defensively, per this method's
        // own Javadoc.
        long a = insertCatalogProduct("SiblingTest F2", "IT", "SIB-D", new BigDecimal("40"), "EUR", "per_sqm");
        setCollection(a, "Verde");
        setThickness(a, new BigDecimal("9"));

        List<BigDecimal> siblings = catalog().findSiblingThicknessesMm(factoryOf(a), "Verde", a);

        assertThat(siblings).isEmpty();
    }

    @Test
    void aDifferentCollectionAtTheSameFactoryIsNotASibling() {
        long a = insertCatalogProduct("SiblingTest F3", "IT", "SIB-E", new BigDecimal("40"), "EUR", "per_sqm");
        long b = insertCatalogProduct("SiblingTest F3", "IT", "SIB-F", new BigDecimal("40"), "EUR", "per_sqm");
        setCollection(a, "Bianco");
        setCollection(b, "Nero");
        setThickness(b, new BigDecimal("9"));

        assertThat(catalog().findSiblingThicknessesMm(factoryOf(a), "Bianco", a)).isEmpty();
    }

    /** ACTIVE-only, matching {@link CatalogRepository#findPricingKeys}'s "resolve as of today" semantics — an ARCHIVED sibling is not evidence for a NEW line's thickness. */
    @Test
    void aSiblingOnANonActiveVersionIsExcluded() {
        long a = insertCatalogProduct("SiblingTest F4", "IT", "SIB-G", new BigDecimal("40"), "EUR", "per_sqm");
        long archived = insertCatalogProduct("SiblingTest F4", "IT", "SIB-H", new BigDecimal("40"), "EUR",
            "per_sqm", "ARCHIVED");
        setCollection(a, "Bianco");
        setCollection(archived, "Bianco");
        setThickness(archived, new BigDecimal("9"));

        assertThat(catalog().findSiblingThicknessesMm(factoryOf(a), "Bianco", a)).isEmpty();
    }

    // ── findThicknessEstimationInputs ────────────────────────────────────────────────────────────

    @Test
    void returnsFactoryNameAndBoxWeightForAnOrdinaryRow() {
        long id = insertCatalogProduct("BoxWeightTest F1", "IT", "BW-A", new BigDecimal("40"), "EUR", "per_sqm");
        setBoxWeight(id, new BigDecimal("22.5"), new BigDecimal("1.44"));

        CatalogThicknessEstimationInputs inputs = catalog().findThicknessEstimationInputs(id).orElseThrow();

        assertThat(inputs.factoryName()).isEqualTo("BoxWeightTest F1");
        assertThat(inputs.kgPerBox()).isEqualByComparingTo("22.5");
        assertThat(inputs.sqmPerBox()).isEqualByComparingTo("1.44");
    }

    /**
     * The load-bearing case: for a {@code per_linear_m} row the raw {@code sqm_per_box} column
     * holds LINEAR METRES mislabelled as square metres (V153's own column comment). This method
     * must return the CORRECTED {@code true_sqm_per_box}, not the raw column — proving V164 wired
     * the box-weight estimator to the same correction the sqm-per-piece side already relies on.
     */
    @Test
    void sqmPerBoxIsTheLinearMetreCorrectedFigureForAPerLinearMRow() {
        long id = insertCatalogProduct("BoxWeightTest F2", "IT", "BW-B", new BigDecimal("18"), "EUR", "per_linear_m");
        jdbc.update("""
            UPDATE price_catalog.product_prices
               SET width_mm = 70, height_mm = 600,
                   sqm_per_linear_m = round(least(70, 600) / 1000.0, 6),
                   sqm_per_box = 6.0, kg_per_box = 25
             WHERE price_id = :id
            """, Map.of("id", id));

        CatalogThicknessEstimationInputs inputs = catalog().findThicknessEstimationInputs(id).orElseThrow();

        // true_sqm_per_box = raw sqm_per_box (6.0, actually linear metres) * sqm_per_linear_m
        // (0.07) = 0.42 -- the box's REAL footprint area, not the mislabelled 6.0.
        assertThat(inputs.sqmPerBox()).isEqualByComparingTo("0.42");
        assertThat(inputs.kgPerBox()).isEqualByComparingTo("25");
    }

    @Test
    void aNonExistentPriceIdYieldsEmpty() {
        assertThat(catalog().findThicknessEstimationInputs(-1L)).isEqualTo(Optional.empty());
    }

    /** Reads through {@code v_priceable_product}, which is ACTIVE-only — a DRAFT/ARCHIVED row's box weight must not feed an estimate either, matching every other read off that view. */
    @Test
    void aNonActiveVersionRowYieldsEmpty() {
        long id = insertCatalogProduct("BoxWeightTest F3", "IT", "BW-C", new BigDecimal("40"), "EUR", "per_sqm",
            "ARCHIVED");
        setBoxWeight(id, new BigDecimal("22.5"), new BigDecimal("1.44"));

        assertThat(catalog().findThicknessEstimationInputs(id)).isEqualTo(Optional.empty());
    }
}
