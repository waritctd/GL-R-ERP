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
 * branch of the arithmetic — see {@link WastageCalculator}'s class Javadoc for the meeting rule
 * (pieces round UP).
 */
class WastageCalculatorTest {

    /** {@code sqmPerPiece} derived from a target piecesPerSqm so the roundtrip
     * {@code round(1/sqmPerPiece, 2)} lands back on exactly that figure. */
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
        assertThat(r.piecesAfterWastage()).isEqualTo(267);
        assertThat(r.piecesFinal()).isEqualTo(268);
        assertThat(r.boxes()).isEqualTo(67);
    }

    @Test
    void item5_area124_piecesPerSqm2_78_wastage10pct_ppb4_roundsTo380() {
        Result r = WastageCalculator.calculate(new Input(
            sqmPerPieceFor("2.78"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("124"), null,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 4,
            new BigDecimal("100"), BigDecimal.ZERO));

        assertThat(r.piecesBeforeWastage()).isEqualTo(345);
        assertThat(r.piecesAfterWastage()).isEqualTo(380);
        assertThat(r.piecesFinal()).isEqualTo(380);
        assertThat(r.boxes()).isEqualTo(95);
    }

    /**
     * Item1: the human-made reference printed 870 (rounding down twice — 790.91 -> 790,
     * 869.1 -> 869 rounded to 870 for the box). The meeting rule is round UP throughout, which
     * this test pins at 872 — the divergence is recorded, not silently "fixed" back to 870.
     */
    @Test
    void item1_area569_piecesPerSqm1_39_wastage10pct_ppb2_roundsTo872_notTheHumanReference870() {
        Result r = WastageCalculator.calculate(new Input(
            sqmPerPieceFor("1.39"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("569"), null,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 2,
            new BigDecimal("100"), BigDecimal.ZERO));

        assertThat(r.piecesPerSqm()).isEqualByComparingTo("1.39");
        assertThat(r.piecesBeforeWastage()).isEqualTo(791);
        assertThat(r.piecesAfterWastage()).isEqualTo(871);
        assertThat(r.piecesFinal()).isEqualTo(872);
        assertThat(r.piecesFinal()).isNotEqualTo(870);
        assertThat(r.boxes()).isEqualTo(436);
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
    void wastageModeNone_leavesPiecesUnchanged() {
        Result r = WastageCalculator.calculate(new Input(
            sqmPerPieceFor("2"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, null,
            new BigDecimal("50"), BigDecimal.ZERO));

        assertThat(r.piecesBeforeWastage()).isEqualTo(20);
        assertThat(r.piecesAfterWastage()).isEqualTo(20);
        assertThat(r.piecesFinal()).isEqualTo(20);
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
    void piecesQuantityMode_stillReportsPiecesPerSqm_whenSqmPerPieceGiven() {
        Result r = WastageCalculator.calculate(new Input(
            sqmPerPieceFor("2.78"), WastageCalculator.QUANTITY_MODE_PIECES, null, 50,
            WastageCalculator.WASTAGE_MODE_NONE, null, null,
            new BigDecimal("50"), BigDecimal.ZERO));

        assertThat(r.piecesPerSqm()).isEqualByComparingTo("2.78");
        assertThat(r.piecesBeforeWastage()).isEqualTo(50);
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
    void zeroDiscount_netUnitPriceEqualsUnitPrice() {
        Result r = WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, 3,
            WastageCalculator.WASTAGE_MODE_NONE, null, null,
            new BigDecimal("123.456"), null));

        assertThat(r.netUnitPrice()).isEqualByComparingTo("123.46"); // rounded HALF_UP to 2dp
        assertThat(r.lineAmount()).isEqualByComparingTo(new BigDecimal("123.46").multiply(BigDecimal.valueOf(3)));
    }

    @Test
    void subtotalVatGrandTotal_roundToTwoDecimals() {
        BigDecimal subtotal = WastageCalculator.subtotal(List.of(
            new BigDecimal("100.00"), new BigDecimal("50.005"), new BigDecimal("0.005")));
        // 100.00 + 50.005 + 0.005 = 150.01 exactly, then rounded (already 2dp after HALF_UP on
        // the raw sum) -- values already rounded per-line in practice, but subtotal() itself
        // also rounds its own sum for callers (e.g. calculate-line preview) that pass unrounded
        // figures.
        assertThat(subtotal).isEqualByComparingTo("150.01");

        BigDecimal vat = WastageCalculator.vat(subtotal);
        assertThat(vat).isEqualByComparingTo("10.50"); // round(150.01 * 0.07, 2) = 10.5007 -> 10.50

        BigDecimal grand = WastageCalculator.grandTotal(subtotal, vat);
        assertThat(grand).isEqualByComparingTo("160.51");
    }

    // ── REMOVED (owner ruling 2026-09-12, "2) ไม่มีค่อยคำนวนเอง") ───────────────────────────────
    // This class used to carry parseSqmPerPieceFromSize/detectUnit -- a cm-vs-mm GUESS from a
    // free-text size string -- and nine tests here (readsWidthByHeightAsCentimetres,
    // returnsNullWhenUnparseable, the six H4 "same tile, different notation" cases, and
    // ignoresIncidentalLettersThatMerelyContainAUnitToken) pinned that guess's behaviour. Deleted
    // along with the method: it got a REAL catalog product wrong ("200x300" read as centimetres --
    // a 2m x 3m tile, 6.0 sqm/piece -- against the owner's own document for that product, 0.061
    // sqm/piece; wrong by ~98x). The regression that guards against this heuristic ever coming
    // back now lives on {@code DealQuotationService} instead, since resolution is now the
    // service's job (catalog lookup), not this pure-arithmetic class's -- see
    // {@code DealQuotationServiceSqmPerPieceTest#sqmPerPiece_200x300_isNeverSilentlyReadAsCentimetres}.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void areaMode_missingSqmPerPiece_throws() {
        assertThatThrownBy(() -> WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, null, BigDecimal.TEN, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void piecesMode_missingPiecesInput_throws() {
        assertThatThrownBy(() -> WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            WastageCalculator.WASTAGE_MODE_NONE, null, null, BigDecimal.TEN, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** M4: {@code DealQuotationService#buildItem} added an {@code ArithmeticException} catch
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

    // ── H4 sanity bound: reject an sqmPerPiece outside [0.001, 10] rather than silently pricing
    // off a unit mistake. ──────────────────────────────────────────────────────────────────────

    @Test
    void sqmPerPiece_tooSmall_rejectedWithThaiMessage() {
        assertThatThrownBy(() -> WastageCalculator.calculate(new Input(
            new BigDecimal("0.0001"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, null, BigDecimal.TEN, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ขนาดสินค้าไม่สมเหตุสมผล");
    }

    @Test
    void sqmPerPiece_tooLarge_rejectedWithThaiMessage() {
        // The sanity bound doesn't care how an implausible figure was arrived at -- 72 sqm/piece
        // is refused whether it came from a mislabeled unit, a bad catalog row, or anything else
        // that resolved sqmPerPiece; it refuses the RESULT, not any particular source of it.
        assertThatThrownBy(() -> WastageCalculator.calculate(new Input(
            new BigDecimal("72"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, null, BigDecimal.TEN, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ขนาดสินค้าไม่สมเหตุสมผล");
    }

    // ── M4: wastageValue bounds are mode-dependent ────────────────────────────────────────────

    @Test
    void wastageValuePercent_over100_rejected() {
        assertThatThrownBy(() -> WastageCalculator.calculate(new Input(
            sqmPerPieceFor("2"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), null,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("150"), null,
            new BigDecimal("50"), BigDecimal.ZERO)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wastageValuePieces_over1Million_rejected() {
        assertThatThrownBy(() -> WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_PIECES, new BigDecimal("1000001"), null,
            new BigDecimal("50"), BigDecimal.ZERO)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wastageValue_negative_rejected() {
        assertThatThrownBy(() -> WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_PIECES, new BigDecimal("-1"), null,
            new BigDecimal("50"), BigDecimal.ZERO)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── quotation v3 (owner feedback pass 3, 2026-09-11) — SPECIAL_SQM + ADJUSTMENT ──────────

    /**
     * THE pinned test for S1's ราคาพิเศษ formula. All FOUR of the owner's independent documents,
     * to the satang. The v3 spec is explicit that a formula reproducing only SOME of these is
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

    /**
     * The ROUNDING ORDER, pinned separately from the four figures above so a regression names its
     * own cause. Rounding the ตร.ม./แผ่น reciprocal to 2dp FIRST is what makes QN6900648 come out
     * at 1,210.25: dividing by the unrounded 1.3888… gives 1,211.21 instead — 96 satang OVER the
     * printed figure, i.e. this rounding is worth real money on a line, not a cosmetic digit.
     */
    @Test
    void netPerPieceFromSpecialSqm_dividesByThe2dpReciprocal_notTheExactOne() {
        BigDecimal exact = new BigDecimal("1800").divide(new BigDecimal("1.07"), 10, java.math.RoundingMode.HALF_UP)
            .divide(BigDecimal.ONE.divide(new BigDecimal("0.72"), 10, java.math.RoundingMode.HALF_UP),
                2, java.math.RoundingMode.HALF_UP);
        assertThat(exact).isEqualByComparingTo("1211.21");
        assertThat(WastageCalculator.netPerPieceFromSpecialSqm(
            new BigDecimal("1800"), new BigDecimal("0.72"))).isEqualByComparingTo("1210.25");
    }

    /** ...and the VAT-stripped figure must NOT be rounded to 2dp before the division — a third
     * rounding there costs QN6900648 one satang (1,210.24 against the printed 1,210.25). */
    @Test
    void netPerPieceFromSpecialSqm_doesNotRoundTheVatStrippedFigureFirst() {
        BigDecimal roundedFirst = new BigDecimal("1800")
            .divide(new BigDecimal("1.07"), 2, java.math.RoundingMode.HALF_UP)
            .divide(new BigDecimal("1.39"), 2, java.math.RoundingMode.HALF_UP);
        assertThat(roundedFirst).isEqualByComparingTo("1210.24");
        assertThat(WastageCalculator.netPerPieceFromSpecialSqm(
            new BigDecimal("1800"), new BigDecimal("0.72"))).isEqualByComparingTo("1210.25");
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

    /** The reciprocal is a DIVISOR in the money math now, so its 2dp value is pinned in its own
     * right — these are the very figures the printed sub-line carries ("ตร.ม.ๆละ 2.78 แผ่น"). */
    @Test
    void piecesPerSqm_roundsTheReciprocalTo2dp() {
        assertThat(WastageCalculator.piecesPerSqm(new BigDecimal("0.36"))).isEqualByComparingTo("2.78");
        assertThat(WastageCalculator.piecesPerSqm(new BigDecimal("0.72"))).isEqualByComparingTo("1.39");
    }

    /**
     * S3's ส่วนลดพิเศษ, pinned against the owner's own QN6900704-2: 3% of
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

    @Test
    void adjustmentAmount_returnsThePositiveMagnitude_andHandlesAZeroBase() {
        // The sign lives in the row's quantity (-1), never here.
        assertThat(WastageCalculator.adjustmentAmount(new BigDecimal("1000"), new BigDecimal("10")))
            .isEqualByComparingTo("100.00");
        assertThat(WastageCalculator.adjustmentAmount(BigDecimal.ZERO, new BigDecimal("3")))
            .isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> WastageCalculator.adjustmentAmount(new BigDecimal("1000"), null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
