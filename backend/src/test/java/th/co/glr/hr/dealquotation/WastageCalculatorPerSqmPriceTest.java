package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Sales feedback #1 (แจ้งปัญหาการใช้งาน เสนอราคา, 2026-10-06) — "แก้เป็น ตร.ม. รวม VAT ทุกแบบ".
 *
 * <p>Sales read prices per ตร.ม. including VAT, and the editor made them convert ราคาตั้ง to a
 * per-แผ่น figure by hand ("ราคาตั้งไม่แปลงให้ ต้องแก้ไขเป็นราคา/แผ่นเอง") while it converted
 * ราคาพิเศษ for them. Owner ruling the same day: every price box is typed per ตร.ม. including VAT
 * — ราคาตั้ง in all three modes, and ราคาสุทธิ too — and the system works out the per-แผ่น price.
 * (A before-VAT reading of ราคาตั้ง/ราคาสุทธิ was tried that afternoon and withdrawn by the owner:
 * "the ราคา/ตรม that sales need to key … should be ตร.ม. รวม VAT".)
 *
 * <p>The conversion is NOT new arithmetic. It is the rule ราคาพิเศษ has used since 2026-09-11,
 * reconciled to the satang against four of the owner's own documents
 * ({@link WastageCalculator#netPerPieceFromSpecialSqm}); this pass only lets the other price boxes
 * use it, under a name that does not say "special". These tests pin exactly that: the same four
 * documents, the same rounding order, and that the two entry points are one function.
 */
class WastageCalculatorPerSqmPriceTest {

    /** QN6900704-2 #1 and #2, QN6900648, QN6900981-1 — the figures in
     * {@link WastageCalculator#netPerPieceFromSpecialSqm}'s own Javadoc table. */
    @Test
    void perPiecePrice_reproducesTheOwnersFourDocuments() {
        assertThat(perPiece("1350", "0.36")).isEqualByComparingTo("453.84");
        assertThat(perPiece("1400", "0.36")).isEqualByComparingTo("470.65");
        assertThat(perPiece("1800", "0.72")).isEqualByComparingTo("1210.25");
        assertThat(perPiece("790", "0.72")).isEqualByComparingTo("531.16");
    }

    /** The whole point of reusing it: a ราคาตั้ง and a ราคาพิเศษ typed as the SAME per-ตร.ม. figure
     * must come out as the SAME per-แผ่น figure, on every tile size — or a row whose list and
     * special prices are equal would print a discount that does not exist. */
    @Test
    void itIsOneRule_whicheverPriceBoxTheFigureWasTypedInto() {
        String[] prices = {"0.01", "99.99", "350", "790", "1234.56", "1800", "25000"};
        String[] areas = {"0.01", "0.0225", "0.09", "0.36", "0.72", "1.44", "2.88"};
        for (String price : prices) {
            for (String area : areas) {
                assertThat(perPiece(price, area))
                    .as("%s บาท/ตร.ม. on a %s ตร.ม. tile", price, area)
                    .isEqualByComparingTo(WastageCalculator.netPerPieceFromSpecialSqm(
                        new BigDecimal(price), new BigDecimal(area)));
            }
        }
    }

    /** The VAT in the keyed figure comes OUT here — the document adds 7% back once, at its foot.
     * Leaving it in would charge it twice: 1,800 on the owner's 60×120 would be 1,294.96 a piece
     * instead of her 1,210.25, and every total would be 7% too high. */
    @Test
    void theVatInTheKeyedPrice_isTakenOut() {
        BigDecimal vatLeftIn = new BigDecimal("1800").divide(new BigDecimal("1.39"), 2, java.math.RoundingMode.HALF_UP);
        assertThat(vatLeftIn).isEqualByComparingTo("1294.96");
        assertThat(perPiece("1800", "0.72")).isEqualByComparingTo("1210.25").isNotEqualByComparingTo(vatLeftIn);
    }

    /** The rounding ORDER is the rule (see netPerPieceFromSpecialSqm): the pieces-per-ตร.ม. figure
     * is rounded to 2dp first — it is the number the document prints ("ตร.ม.ๆละ 1.39 แผ่น") — and
     * the VAT-stripped price is divided by THAT. Dividing by the exact reciprocal gives 1,211.21
     * for the owner's 1,800 / 0.72 row, not her 1,210.25. */
    @Test
    void itDividesByTheTwoDecimalPiecesPerSqm_notTheExactReciprocal() {
        BigDecimal naive = new BigDecimal("1800").divide(new BigDecimal("1.07"), 10, java.math.RoundingMode.HALF_UP)
            .multiply(new BigDecimal("0.72")).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(naive).isEqualByComparingTo("1211.21");
        assertThat(perPiece("1800", "0.72")).isEqualByComparingTo("1210.25").isNotEqualByComparingTo(naive);
    }

    @Test
    void theResultIsMoney_twoDecimalPlaces() {
        assertThat(perPiece("1800", "0.72").scale()).isEqualTo(2);
        assertThat(perPiece("0.01", "2.88").scale()).isEqualTo(2);
    }

    /** Why the typed per-ตร.ม. figure has to be STORED rather than recovered from the per-แผ่น one:
     * the conversion loses information. On a 10×10 tile (100 แผ่น/ตร.ม.), 1,800 becomes 16.82 a
     * piece, and 16.82 a piece is 1,799.74 per ตร.ม. — the editor would hand the rep back a price
     * they never typed. */
    @Test
    void theConversionCannotBeUndone_soTheTypedFigureMustBeKept() {
        BigDecimal perPiece = perPiece("1800", "0.01");
        assertThat(perPiece).isEqualByComparingTo("16.82");
        BigDecimal back = perPiece.multiply(new BigDecimal("100")).multiply(new BigDecimal("1.07"))
            .setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(back).isEqualByComparingTo("1799.74").isNotEqualByComparingTo("1800");
    }

    @Test
    void aMissingZeroOrNegativePrice_isRefused() {
        for (BigDecimal price : new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("-1")}) {
            assertThatThrownBy(() -> WastageCalculator.perPieceExVatFromSqmIncVat(price, new BigDecimal("0.72")))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** No area per piece, no conversion — never a silent fallback to reading the figure as a
     * per-แผ่น price, which would bill a 1,800-per-ตร.ม. tile at 1,800 a piece. */
    @Test
    void aMissingZeroOrAbsurdAreaPerPiece_isRefused() {
        for (BigDecimal area : new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("-0.36"),
                new BigDecimal("0.0001"), new BigDecimal("36")}) {
            assertThatThrownBy(() -> WastageCalculator.perPieceExVatFromSqmIncVat(new BigDecimal("1800"), area))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static BigDecimal perPiece(String perSqmIncVat, String sqmPerPiece) {
        return WastageCalculator.perPieceExVatFromSqmIncVat(new BigDecimal(perSqmIncVat), new BigDecimal(sqmPerPiece));
    }
}
