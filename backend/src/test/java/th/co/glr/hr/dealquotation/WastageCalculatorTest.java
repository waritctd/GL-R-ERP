package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.WastageCalculator.Input;
import th.co.glr.hr.dealquotation.WastageCalculator.Result;

/**
 * Pins docs/sales/quotation-v2-plan.md's own worked reference figures (QN6900595-3, items 1/3/5) plus every
 * branch of the arithmetic — see {@link WastageCalculator}'s class Javadoc for the
 * rounding rule (every fraction of a piece rounds UP, not HALF_UP).
 */
class WastageCalculatorTest {

    /** sqmPerPiece derived from a target piecesPerSqm so the roundtrip round(1/sqmPerPiece, 2) lands back on
     * exactly that figure. */
    private static BigDecimal sqmPerPieceFor(String piecesPerSqm) {
        return BigDecimal.ONE.divide(new BigDecimal(piecesPerSqm), 10, RoundingMode.HALF_UP);
    }

    @Test
    void item3_area87_piecesPerSqm2_78_wastage10pct_ppb4_roundsTo268() {
        Result r = WastageCalculator.calculate(new Input(
            sqmPerPieceFor("2.78"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("87"), null,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 4,
            new BigDecimal("100"), BigDecimal.ZERO));

        assertThat(r.piecesPerSqm()).isEqualByComparingTo("2.78");
        assertThat(r.piecesBeforeWastage()).isEqualTo(242);
        // Any fraction of a piece rounds UP. 242 × 1.10 = 266.20 → 267
        // (HALF_UP gave 266); the box multiple lands on the same printed 268 either way.
        assertThat(r.piecesAfterWastage()).isEqualTo(267);
        assertThat(r.piecesFinal()).isEqualTo(268);
        assertThat(r.boxes()).isEqualTo(67);
    }

    /** A fraction as small as a hundredth of a piece still rounds up: 0.01 above a whole number
     * of pieces is one more piece, in the area step and in the wastage step alike. */
    @Test
    void anyFractionOfAPieceRoundsUp_areaStepAndWastageStep() {
        // 2,793 × 1.39 = 3,882.27 → 3,883 (QN6900981-1 row 1 printed 3,882 under the old rule).
        Result area = WastageCalculator.calculate(new Input(
            new BigDecimal("0.72"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("2793"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, null,
            new BigDecimal("100"), BigDecimal.ZERO));
        assertThat(area.piecesBeforeWastage()).isEqualTo(3883);
        assertThat(area.piecesFinal()).isEqualTo(3883);

        // 100 pieces + 0.5% = 100.5 → 101; + 0.1% = 100.1 → 101 as well.
        for (String pct : List.of("0.5", "0.1")) {
            Result wastage = WastageCalculator.calculate(new Input(
                null, WastageCalculator.QUANTITY_MODE_PIECES, null, 100,
                WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal(pct), null,
                new BigDecimal("100"), BigDecimal.ZERO));
            assertThat(wastage.piecesAfterWastage()).as("+%s%%", pct).isEqualTo(101);
        }

        // A fractional flat wastage (2.1 pieces) adds 3.
        Result flat = WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, 100,
            WastageCalculator.WASTAGE_MODE_PIECES, new BigDecimal("2.1"), null,
            new BigDecimal("100"), BigDecimal.ZERO));
        assertThat(flat.piecesAfterWastage()).isEqualTo(103);

        // An exact whole number stays: 100 × 1.39 = 139.00, and 139 + 0% = 139.
        Result exact = WastageCalculator.calculate(new Input(
            new BigDecimal("0.72"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("100"), null,
            WastageCalculator.WASTAGE_MODE_PERCENT, BigDecimal.ZERO, null,
            new BigDecimal("100"), BigDecimal.ZERO));
        assertThat(exact.piecesBeforeWastage()).isEqualTo(139);
        assertThat(exact.piecesAfterWastage()).isEqualTo(139);
    }

    @Test
    void wastageModePieces_addsFlatPieceCount() {
        Result r = WastageCalculator.calculate(new Input(
            sqmPerPieceFor("2"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), null,
            WastageCalculator.WASTAGE_MODE_PIECES, new BigDecimal("5"), null,
            new BigDecimal("50"), BigDecimal.ZERO));

        // before = ceil(10 * 2) = 20; PIECES wastage: 20 + 5 = 25; no ppb -> final = 25.
        assertThat(r.piecesBeforeWastage()).isEqualTo(20);
        assertThat(r.piecesAfterWastage()).isEqualTo(25);
        assertThat(r.piecesFinal()).isEqualTo(25);
        assertThat(r.boxes()).isNull();
    }

    @Test
    void noPiecesPerBox_finalEqualsAfterWastage_boxesNull() {
        Result r = WastageCalculator.calculate(new Input(
            sqmPerPieceFor("2"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), null,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 0, // 0 => "no box rounding"
            new BigDecimal("50"), BigDecimal.ZERO));

        assertThat(r.piecesAfterWastage()).isEqualTo(22); // ceil(20 * 1.10) = 22
        assertThat(r.piecesFinal()).isEqualTo(22);
        assertThat(r.boxes()).isNull();
    }

    @Test
    void piecesQuantityMode_usesPiecesInputDirectly_noAreaNeeded() {
        Result r = WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, 100,
            WastageCalculator.WASTAGE_MODE_NONE, null, 12,
            new BigDecimal("50"), BigDecimal.ZERO));

        assertThat(r.piecesPerSqm()).isNull();
        assertThat(r.piecesBeforeWastage()).isEqualTo(100);
        assertThat(r.piecesAfterWastage()).isEqualTo(100);
        // ceil(100/12)*12 = 9*12 = 108
        assertThat(r.piecesFinal()).isEqualTo(108);
        assertThat(r.boxes()).isEqualTo(9);
    }

    @Test
    void discountPct_reducesNetUnitPrice_andLineAmount() {
        Result r = WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, null,
            new BigDecimal("100"), new BigDecimal("10")));

        assertThat(r.netUnitPrice()).isEqualByComparingTo("90.00");
        assertThat(r.lineAmount()).isEqualByComparingTo("900.00"); // 10 pieces * 90
    }

    @Test
    void subtotalVatGrandTotal_roundToTwoDecimals() {
        BigDecimal subtotal = WastageCalculator.subtotal(List.of(
            new BigDecimal("100.00"), new BigDecimal("50.005"), new BigDecimal("0.005")));
        // 100.00 + 50.005 + 0.005 = 150.01 exactly, then rounded (already 2dp after HALF_UP on the raw sum) -- values
        // already rounded per-line in practice, but subtotal() itself also rounds its own sum for callers (e.g.
        // calculate-line preview) that pass unrounded figures.
        assertThat(subtotal).isEqualByComparingTo("150.01");

        BigDecimal vat = WastageCalculator.vat(subtotal);
        assertThat(vat).isEqualByComparingTo("10.50"); // round(150.01 * 0.07, 2) = 10.5007 -> 10.50

        BigDecimal grand = WastageCalculator.grandTotal(subtotal, vat);
        assertThat(grand).isEqualByComparingTo("160.51");
    }

    // ── No size-string parsing here ───────────────────────────────────────────────────────────
    // This class must not GUESS a cm-vs-mm unit from a free-text size string: that got a REAL
    // catalog product wrong ("200x300" read as centimetres -- a 2m x 3m tile, 6.0 sqm/piece --
    // against the owner's own document for that product, 0.061 sqm/piece; wrong by ~98x).
    // sqm/piece resolution is the service's job (catalog lookup), not this pure-arithmetic
    // class's, so the regression guard against the heuristic lives on {@code DealQuotationService}
    // -- see
    // {@code DealQuotationServiceSqmPerPieceTest#sqmPerPiece_200x300_isNeverSilentlyReadAsCentimetres}.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** {@code DealQuotationService#buildItem} catches {@code ArithmeticException}
     * (alongside {@code IllegalArgumentException}) precisely because {@code BigDecimal#
     * intValueExact} inside this class can throw it for an absurd-but-not-otherwise-invalid
     * input -- pinned here at the SOURCE. {@code areaSqm} itself is bounded at the DTO layer
     * (bean validation, {@code @DecimalMax("999999")}), not inside this pure class, so an areaSqm
     * this large can only reach here via a caller that bypasses the DTO (exactly what an
     * integration test calling the service directly does -- see
     * {@code DealQuotationIntegrationTest#createItem_absurdAreaSqm_isBadRequestNot500}). */
    @Test
    void areaMode_absurdlyLargeAreaSqm_throwsArithmeticExceptionNotSilentOverflow() {
        assertThatThrownBy(() -> WastageCalculator.calculate(new Input(
            BigDecimal.ONE, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("1E30"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, null, BigDecimal.TEN, null)))
            .isInstanceOf(ArithmeticException.class);
    }

    // H4 sanity bound: reject an sqmPerPiece outside [0.001, 10] rather than silently pricing off a unit mistake.

    @Test
    void sqmPerPiece_tooSmall_rejectedWithThaiMessage() {
        assertThatThrownBy(() -> WastageCalculator.calculate(new Input(
            new BigDecimal("0.0001"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, null, BigDecimal.TEN, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ขนาดสินค้าไม่สมเหตุสมผล");
    }

    // ── wastageValue bounds are mode-dependent ────────────────────────────────────────────

    @Test
    void wastageValuePercent_over100_rejected() {
        assertThatThrownBy(() -> WastageCalculator.calculate(new Input(
            sqmPerPieceFor("2"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), null,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("150"), null,
            new BigDecimal("50"), BigDecimal.ZERO)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── SPECIAL_SQM + ADJUSTMENT ──────────────────────────────────────────────────────────────

    /**
     * THE pinned test for the ราคาพิเศษ formula. All FOUR of the owner's independent documents,
     * to the satang. A formula reproducing only SOME of these is
     * wrong — the naive "strip VAT, then multiply by ตร.ม./แผ่น" reading gets #1 and #2 right and
     * both 0.72 cases wrong, so a two-case test would have passed it.
     */
    @Test
    void netPerPieceFromSpecialSqm_reproducesAllFourOwnerDocuments() {
        // QN6900704-2 item 1 — ราคาพิเศษ 1,350 บาท/ตร.ม., 0.36 ตร.ม./แผ่น (2.78 แผ่น/ตร.ม.)
        assertThat(WastageCalculator.netPerPieceFromSpecialSqm(
            new BigDecimal("1350"), new BigDecimal("0.36"))).isEqualByComparingTo("453.84");
        // QN6900704-2 item 2 — ราคาพิเศษ 1,400
        assertThat(WastageCalculator.netPerPieceFromSpecialSqm(
            new BigDecimal("1400"), new BigDecimal("0.36"))).isEqualByComparingTo("470.65");
        // QN6900648 — ราคาพิเศษ 1,800, 0.72 ตร.ม./แผ่น (1.39 แผ่น/ตร.ม.)
        assertThat(WastageCalculator.netPerPieceFromSpecialSqm(
            new BigDecimal("1800"), new BigDecimal("0.72"))).isEqualByComparingTo("1210.25");
        // QN6900981-1 — ราคาพิเศษ 790
        assertThat(WastageCalculator.netPerPieceFromSpecialSqm(
            new BigDecimal("790"), new BigDecimal("0.72"))).isEqualByComparingTo("531.16");
    }

    @Test
    void netPerPieceFromSpecialSqm_rejectsNonPositiveOrMissingInputs() {
        assertThatThrownBy(() -> WastageCalculator.netPerPieceFromSpecialSqm(null, new BigDecimal("0.36")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WastageCalculator.netPerPieceFromSpecialSqm(BigDecimal.ZERO, new BigDecimal("0.36")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WastageCalculator.netPerPieceFromSpecialSqm(new BigDecimal("-1"), new BigDecimal("0.36")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WastageCalculator.netPerPieceFromSpecialSqm(new BigDecimal("1350"), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WastageCalculator.netPerPieceFromSpecialSqm(new BigDecimal("1350"), BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * ส่วนลดพิเศษ, pinned against the owner's own QN6900704-2: 3% of
     * 149,767.20 + 96,012.60 + 528,269.76 + 499,224.00 = 1,273,273.56 is 38,198.21, exactly the
     * printed figure. (The unrounded product is 38,198.2068, so this also pins HALF_UP.)
     */
    @Test
    void adjustmentAmount_reproducesTheOwnersPrintedDiscount() {
        BigDecimal base = new BigDecimal("149767.20")
            .add(new BigDecimal("96012.60"))
            .add(new BigDecimal("528269.76"))
            .add(new BigDecimal("499224.00"));
        assertThat(base).isEqualByComparingTo("1273273.56");
        assertThat(WastageCalculator.adjustmentAmount(base, new BigDecimal("3")))
            .isEqualByComparingTo("38198.21");
    }

    // ── English per-sqm quantity = boxes × supplier sqm/box ─────────

    /** Her QN6900933, all three rows, to the cent. */
    @Test
    void sqmQuantityFromBoxes_reproducesQN6900933() {
        assertThat(WastageCalculator.sqmQuantityFromBoxes(120, new BigDecimal("0.6"))).isEqualByComparingTo("72.00");
        assertThat(WastageCalculator.sqmQuantityFromBoxes(30, new BigDecimal("0.6"))).isEqualByComparingTo("18.00");
        assertThat(WastageCalculator.sqmQuantityFromBoxes(114, new BigDecimal("0.495"))).isEqualByComparingTo("56.43");
        assertThat(WastageCalculator.sqmQuantityFromBoxes(114, new BigDecimal("0.495")).scale()).isEqualTo(2);
        // HALF_UP at the cent: 1 × 0.005 = 0.005 → 0.01 (HALF_EVEN would give 0.00).
        assertThat(WastageCalculator.sqmQuantityFromBoxes(1, new BigDecimal("0.005"))).isEqualByComparingTo("0.01");
    }

    // ── English per-sqm quantity with NO box area ─────────────────────────────────────────────

    @Test
    void sqmQuantityFromPieces_isRound2OfPiecesTimesSqmPerPiece() {
        assertThat(WastageCalculator.sqmQuantityFromPieces(28, new BigDecimal("0.36"))).isEqualByComparingTo("10.08");
        assertThat(WastageCalculator.sqmQuantityFromPieces(28, new BigDecimal("0.36")).scale()).isEqualTo(2);
        // Same rounding discipline as sqmQuantityFromBoxes: HALF_UP at the cent.
        assertThat(WastageCalculator.sqmQuantityFromPieces(1, new BigDecimal("0.005"))).isEqualByComparingTo("0.01");
        assertThat(WastageCalculator.sqmQuantityFromPieces(0, new BigDecimal("0.36"))).isEqualByComparingTo("0.00");
    }

    // ── "Sell loose pieces" ─────────────────────────────────

    /** The 9-arg legacy Input constructor (every test above this section) defaults roundToFullBox true —
     * byte-for-byte the only behaviour that existed before this feature. Proven once, directly, rather than merely
     * assumed from the tests above still passing unchanged. */
    @Test
    void legacyNineArgInput_defaultsRoundToFullBoxTrue() {
        Result viaLegacy = WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, 32,
            WastageCalculator.WASTAGE_MODE_NONE, null, 10, new BigDecimal("50"), BigDecimal.ZERO));
        Result viaExplicitTrue = WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, 32,
            WastageCalculator.WASTAGE_MODE_NONE, null, 10, new BigDecimal("50"), BigDecimal.ZERO, true));
        assertThat(viaLegacy.piecesFinal()).isEqualTo(viaExplicitTrue.piecesFinal()).isEqualTo(40);
        assertThat(viaLegacy.boxes()).isEqualTo(viaExplicitTrue.boxes()).isEqualTo(4);
        assertThat(viaLegacy.loosePieces()).isEqualTo(viaExplicitTrue.loosePieces()).isEqualTo(0);
    }

    /** The headline case: 32 pieces, box of 10, no wastage — 3 full boxes plus 2 loose, and piecesFinal is the
     * UNROUNDED 32, not the old ceiling of 40. */
    @Test
    void roundToFullBoxFalse_splitsIntoFullBoxesPlusLooseRemainder() {
        Result r = WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, 32,
            WastageCalculator.WASTAGE_MODE_NONE, null, 10, new BigDecimal("50"), BigDecimal.ZERO, false));

        assertThat(r.piecesBeforeWastage()).isEqualTo(32);
        assertThat(r.piecesAfterWastage()).isEqualTo(32);
        assertThat(r.piecesFinal()).isEqualTo(32); // NOT 40 — the point of the feature.
        assertThat(r.boxes()).isEqualTo(3);
        assertThat(r.loosePieces()).isEqualTo(2);
    }

    /** PERCENT wastage still applies (rounds up, like everywhere else) before the loose-pieces split — only
     * the BOX step stops rounding, not the wastage step upstream of it. */
    @Test
    void roundToFullBoxFalse_percentWastage_appliesBeforeTheLooseSplit() {
        Result r = WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, 100,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 12,
            new BigDecimal("50"), BigDecimal.ZERO, false));

        assertThat(r.piecesBeforeWastage()).isEqualTo(100);
        assertThat(r.piecesAfterWastage()).isEqualTo(110); // 100 * 1.10 = 110.0, exact
        assertThat(r.piecesFinal()).isEqualTo(110); // NOT ceil(110/12)*12 = 120
        assertThat(r.boxes()).isEqualTo(9); // 110 / 12 = 9 full boxes
        assertThat(r.loosePieces()).isEqualTo(2); // 110 - 9*12
    }

}
