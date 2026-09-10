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

    @Test
    void parseSqmPerPieceFromSize_readsWidthByHeightAsCentimetres() {
        // The worked example in docs/sales/quotation-v2-plan.md: "60x120" -> 0.72 sqm.
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("60x120")).isEqualByComparingTo("0.72");
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("60X60")).isEqualByComparingTo("0.36");
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("60×60")).isEqualByComparingTo("0.36");
    }

    @Test
    void parseSqmPerPieceFromSize_returnsNullWhenUnparseable() {
        assertThat(WastageCalculator.parseSqmPerPieceFromSize(null)).isNull();
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("")).isNull();
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("round")).isNull();
    }

    // ── H4: "600x1200" used to resolve as 72 sqm (unconditional ÷100 on each dimension) — a
    // 100x under-quote. All six cases below describe THE SAME PHYSICAL TILE (60cm x 120cm, or
    // 60cm x 60cm) under different notations, and must all resolve to the same figure. ──────────

    @Test
    void parseSqmPerPieceFromSize_bareCentimetreNotation_60x120() {
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("60x120")).isEqualByComparingTo("0.72");
    }

    @Test
    void parseSqmPerPieceFromSize_bareMillimetreNotation_600x1200_noLongerReads100xTooBig() {
        // The H4 regression case: NOT 72 (the old unconditional-cm bug), 0.72 (mm, by magnitude).
        BigDecimal result = WastageCalculator.parseSqmPerPieceFromSize("600x1200");
        assertThat(result).isEqualByComparingTo("0.72");
        assertThat(result).isNotEqualByComparingTo("72");
    }

    @Test
    void parseSqmPerPieceFromSize_explicitCmToken_60x120Cm() {
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("60 x 120 cm")).isEqualByComparingTo("0.72");
    }

    @Test
    void parseSqmPerPieceFromSize_explicitMmToken_600x1200Mm() {
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("600X1200 mm")).isEqualByComparingTo("0.72");
    }

    @Test
    void parseSqmPerPieceFromSize_thicknessSuffixIgnored_60x60x0_9() {
        // The regex only reads the first WxH pair; a trailing "x0.9" thickness must not confuse it
        // into reading "60" against "0.9".
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("60x60x0.9")).isEqualByComparingTo("0.36");
    }

    @Test
    void parseSqmPerPieceFromSize_largeFormatMillimetreSlab_598x598x18_noExplicitToken() {
        // A real large-format porcelain slab notation (598mm x 598mm x 18mm thick), with no
        // explicit unit token — must resolve by magnitude alone as millimetres, not centimetres
        // (which would wrongly claim a 35.76 sqm single tile).
        BigDecimal result = WastageCalculator.parseSqmPerPieceFromSize("598X598X18");
        assertThat(result).isEqualByComparingTo("0.357604");
        assertThat(result).isLessThan(new BigDecimal("1"));
    }

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
    void sqmPerPiece_tooLarge_rejectedWithThaiMessage_evenWhenAnExplicitUnitTokenProducedIt() {
        // An explicit (but mislabeled) unit token can still hand the calculator an unreasonable
        // figure -- "600x1200 cm" claims the pair is centimetres despite plainly being millimetre-
        // scale, resolving to 72 sqm per piece. The sanity bound is the backstop for exactly this:
        // it does not re-litigate the unit, it just refuses an implausible RESULT.
        BigDecimal mislabeled = WastageCalculator.parseSqmPerPieceFromSize("600x1200 cm");
        assertThat(mislabeled).isEqualByComparingTo("72");
        assertThatThrownBy(() -> WastageCalculator.calculate(new Input(
            mislabeled, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), null,
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

    @Test
    void parseSqmPerPieceFromSize_ignoresIncidentalLettersThatMerelyContainAUnitToken() {
        // Review finding MED-2: an unanchored "mm" match flipped "60x60 Summer" to millimetres
        // (0.0036 m2/piece, a 100x piece over-count inside the sanity bound).
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("60x60 Summer")).isEqualByComparingTo("0.36");
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("60x120 Command")).isEqualByComparingTo("0.72");
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("600x1200 mm")).isEqualByComparingTo("0.72");
        assertThat(WastageCalculator.parseSqmPerPieceFromSize("600x1200mm")).isEqualByComparingTo("0.72");
    }
}
