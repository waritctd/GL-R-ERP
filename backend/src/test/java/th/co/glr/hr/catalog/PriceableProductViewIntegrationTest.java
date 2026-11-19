package th.co.glr.hr.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** V153: the per-sqm basis, the thickness fallback chain, and the view the pricing engine will read. These
 * tests pin the conversion in each direction, and pin that a row which cannot be converted is reported as
 * unpriceable rather than silently given a wrong number. */
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
    void perPiecePriceIsDividedByTheAreaOfOnePiece() {
        long id = insertCatalogProduct("V153 F2", "IT", "PC-1",
            new BigDecimal("18.00"), "EUR", "per_piece", "ACTIVE");
        // 0.36 m2 per piece -> 18.00 / 0.36 = 50.00 per m2
        setGeometry(id, "60x60", 600, 600, new BigDecimal("9"), new BigDecimal("0.36"), null);

        assertThat(view(id).pricePerSqm()).isEqualByComparingTo("50.00");
    }

    @Test
    void aRowWithNoConvertibleUnitIsReportedUnpriceableRatherThanGuessed() {
        long id = insertCatalogProduct("V153 F5", "IT", "UNK-1",
            new BigDecimal("15.00"), "EUR", "unknown", "ACTIVE");
        setGeometry(id, "080x600", 80, 600, new BigDecimal("9"), null, null);

        assertThat(view(id).pricePerSqm()).isNull();
        assertThat(view(id).status()).isEqualTo("NO_SQM_BASIS");
    }

}
