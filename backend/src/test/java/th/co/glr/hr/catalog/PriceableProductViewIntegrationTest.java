package th.co.glr.hr.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * V152: the per-sqm basis, the thickness fallback chain, and the view the pricing engine will read.
 *
 * <p>The formula works entirely in square metres ({@code UC = TC / Q}, and both the freight and
 * clearance bands key off Q in sqm) while the catalogue stores whatever unit each factory quotes
 * in. These tests pin the conversion in each direction, and pin that a row which cannot be
 * converted is reported as unpriceable rather than silently given a wrong number.
 */
class PriceableProductViewIntegrationTest extends AbstractPostgresIntegrationTest {

    private record Row(String status, BigDecimal pricePerSqm, BigDecimal thickness) {}

    private Row view(long priceId) {
        return jdbc.queryForObject("""
            SELECT pricing_status, price_per_sqm, thickness_mm
              FROM price_catalog.v_priceable_product WHERE price_id = :id
            """, Map.of("id", priceId),
            (rs, i) -> new Row(rs.getString("pricing_status"),
                rs.getBigDecimal("price_per_sqm"), rs.getBigDecimal("thickness_mm")));
    }

    private void setGeometry(long priceId, String sizeRaw, Integer widthMm, Integer heightMm,
                             BigDecimal thicknessMm, BigDecimal sqmPerPiece, BigDecimal sqmPerBox) {
        jdbc.update("""
            UPDATE price_catalog.product_prices
               SET size_raw = :size, width_mm = :w, height_mm = :h, thickness_mm = :t,
                   sqm_per_piece = :spp, sqm_per_box = :spb,
                   sqm_per_linear_m = CASE WHEN price_unit = 'per_linear_m' AND :w IS NOT NULL
                                           THEN round(least(:w, :h) / 1000.0, 6) END
             WHERE price_id = :id
            """,
            new MapSqlParameterSource().addValue("id", priceId).addValue("size", sizeRaw)
                .addValue("w", widthMm).addValue("h", heightMm).addValue("t", thicknessMm)
                .addValue("spp", sqmPerPiece).addValue("spb", sqmPerBox));
    }

    @Test
    void perSqmPriceIsTheSourcePriceUnchanged() {
        long id = insertCatalogProduct("V152 F1", "IT", "SQM-1",
            new BigDecimal("74.50"), "EUR", "per_sqm", "ACTIVE");
        setGeometry(id, "60x120", 600, 1200, new BigDecimal("9"), null, new BigDecimal("1.44"));

        assertThat(view(id).pricePerSqm()).isEqualByComparingTo("74.50");
        assertThat(view(id).status()).isEqualTo("PRICEABLE");
    }

    @Test
    void perPiecePriceIsDividedByTheAreaOfOnePiece() {
        long id = insertCatalogProduct("V152 F2", "IT", "PC-1",
            new BigDecimal("18.00"), "EUR", "per_piece", "ACTIVE");
        // 0.36 m2 per piece -> 18.00 / 0.36 = 50.00 per m2
        setGeometry(id, "60x60", 600, 600, new BigDecimal("9"), new BigDecimal("0.36"), null);

        assertThat(view(id).pricePerSqm()).isEqualByComparingTo("50.00");
    }

    /**
     * The worked example from the design doc: CITY {@code RQ81 AVORIO BATTISCOPA R.}, 7x60 cm,
     * EUR 22.00 per linear metre. One linear metre of a 7 cm profile covers 0.07 m2, so the
     * per-sqm equivalent is 22.00 / 0.07 = EUR 314.285714.
     *
     * <p>min(width, height) rather than max: for a 7x60 battiscopa the 7 cm is the profile and the
     * 60 cm is the piece length, which "per linear metre" already accounts for.
     */
    @Test
    void linearMetrePriceIsDividedByTheProfileHeight() {
        long id = insertCatalogProduct("V152 F3", "IT", "ML-1",
            new BigDecimal("22.00"), "EUR", "per_linear_m", "ACTIVE");
        setGeometry(id, "7x60", 70, 600, new BigDecimal("8.5"), null, new BigDecimal("6"));

        assertThat(view(id).pricePerSqm()).isEqualByComparingTo("314.285714");
    }

    /** The mislabelled box quantity: 6 "m2/box" is really 6 linear metres = 0.42 m2. */
    @Test
    void perMetreBoxQuantityIsCorrectedFromLinearMetresToSquareMetres() {
        long id = insertCatalogProduct("V152 F4", "IT", "ML-2",
            new BigDecimal("22.00"), "EUR", "per_linear_m", "ACTIVE");
        setGeometry(id, "7x60", 70, 600, new BigDecimal("8.5"), null, new BigDecimal("6"));

        BigDecimal trueSqmPerBox = jdbc.queryForObject(
            "SELECT true_sqm_per_box FROM price_catalog.v_priceable_product WHERE price_id = :id",
            Map.of("id", id), BigDecimal.class);
        assertThat(trueSqmPerBox).isEqualByComparingTo("0.42");
    }

    @Test
    void aRowWithNoConvertibleUnitIsReportedUnpriceableRatherThanGuessed() {
        long id = insertCatalogProduct("V152 F5", "IT", "UNK-1",
            new BigDecimal("15.00"), "EUR", "unknown", "ACTIVE");
        setGeometry(id, "080x600", 80, 600, new BigDecimal("9"), null, null);

        assertThat(view(id).pricePerSqm()).isNull();
        assertThat(view(id).status()).isEqualTo("NO_SQM_BASIS");
    }

    // ── thickness fallback chain ─────────────────────────────────────────────

    @Test
    void aRowWithNoThicknessIsUnpriceableUntilTheCeoSuppliesADefault() {
        long id = insertCatalogProduct("V152 F6", "ES", "TH-1",
            new BigDecimal("40.00"), "EUR", "per_sqm", "ACTIVE");
        setGeometry(id, "20x20", 200, 200, null, null, null);
        jdbc.update("UPDATE price_catalog.product_prices SET collection = 'ALTEA' WHERE price_id = :id",
            Map.of("id", id));

        // No default anywhere: refuse, never guess -- a wrong thickness picks a freight band that
        // can differ by 50,000 THB per shipment.
        assertThat(view(id).status()).isEqualTo("NO_THICKNESS");
        assertThat(view(id).thickness()).isNull();

        Long factoryId = jdbc.queryForObject(
            "SELECT factory_id FROM price_catalog.product_prices WHERE price_id = :id",
            Map.of("id", id), Long.class);
        jdbc.update("""
            INSERT INTO price_catalog.collection_thickness_default (factory_id, collection, thickness_mm)
            VALUES (:f, 'ALTEA', 8.5)
            """, new MapSqlParameterSource().addValue("f", factoryId));

        assertThat(view(id).status()).isEqualTo("PRICEABLE");
        assertThat(view(id).thickness()).isEqualByComparingTo("8.5");
    }

    @Test
    void theMostSpecificThicknessDefaultWins() {
        long id = insertCatalogProduct("V152 F7", "ES", "TH-2",
            new BigDecimal("40.00"), "EUR", "per_sqm", "ACTIVE");
        setGeometry(id, "30x60", 300, 600, null, null, null);
        jdbc.update("UPDATE price_catalog.product_prices SET collection = 'BARNET' WHERE price_id = :id",
            Map.of("id", id));
        Long factoryId = jdbc.queryForObject(
            "SELECT factory_id FROM price_catalog.product_prices WHERE price_id = :id",
            Map.of("id", id), Long.class);

        // Three overlapping defaults, deliberately inserted least-specific first so a naive
        // "first row wins" implementation would pick the wrong one.
        jdbc.update("""
            INSERT INTO price_catalog.collection_thickness_default
                (factory_id, collection, size_norm, thickness_mm)
            VALUES (:f, NULL, NULL, 6), (:f, 'BARNET', NULL, 9), (:f, 'BARNET', '30X60', 12)
            """, new MapSqlParameterSource().addValue("f", factoryId));

        assertThat(view(id).thickness()).isEqualByComparingTo("12");
    }

    @Test
    void aThicknessOutsideTheSeededFreightBandsIsFlaggedNotSilentlyPriced() {
        long id = insertCatalogProduct("V152 F8", "IT", "TH-3",
            new BigDecimal("90.00"), "EUR", "per_sqm", "ACTIVE");
        // Freight bands cover [3,21) mm only; a 30 mm slab matches nothing.
        setGeometry(id, "120x120", 1200, 1200, new BigDecimal("30"), null, null);

        assertThat(view(id).status()).isEqualTo("THICKNESS_OUT_OF_BAND");
    }

    // ── version scoping ──────────────────────────────────────────────────────

    @Test
    void theViewShowsOnlyTheActiveVersion() {
        long active = insertCatalogProduct("V152 F9", "IT", "VER-1",
            new BigDecimal("50.00"), "EUR", "per_sqm", "ACTIVE");
        long draft = insertCatalogProduct("V152 F10", "IT", "VER-2",
            new BigDecimal("50.00"), "EUR", "per_sqm", "DRAFT");

        Integer activeSeen = jdbc.queryForObject(
            "SELECT count(*) FROM price_catalog.v_priceable_product WHERE price_id = :id",
            Map.of("id", active), Integer.class);
        Integer draftSeen = jdbc.queryForObject(
            "SELECT count(*) FROM price_catalog.v_priceable_product WHERE price_id = :id",
            Map.of("id", draft), Integer.class);

        assertThat(activeSeen).isEqualTo(1);
        assertThat(draftSeen).as("a DRAFT version must never reach the pricing engine").isZero();
    }
}
