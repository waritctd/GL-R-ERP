package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.WastageCalculator.Input;
import th.co.glr.hr.dealquotation.WastageCalculator.Result;

/**
 * Quotation arithmetic reconciliation (2026-09-15) — pure unit test, no DB, driving
 * {@link WastageCalculator} directly against nine real, owner-supplied, declared-100%-correct
 * printed GL&amp;R quotations. Every printed net/amount/pieces figure and every document's
 * subtotal/VAT/total is asserted to the satang. See the reconciliation plan for the source
 * document tables (QN6900971-4, QN6900971 ใบสรุป, QN6900981-1, QN6900902-6, QN6900704-2,
 * QN6900933, QN6900648, QN6900782-2, QN6900595-3) and the derived rules R-A through R-H.
 *
 * <p>A PLAIN row (list price × qty × (1 − discount), no wastage, no box rounding) is driven
 * through {@link WastageCalculator#calculate} with {@code QUANTITY_MODE_PIECES},
 * {@code piecesInput = quantity}, {@code WASTAGE_MODE_NONE} and no {@code piecesPerBox} — that
 * combination makes {@code piecesFinal == quantity} exactly, so {@code calculate}'s R-D line
 * amount formula reproduces {@code DealQuotationService#buildPlainItem} exactly without needing a
 * Spring context.
 */
class QuotationGoldenDocumentsTest {

    private static Result plainRow(BigDecimal listPrice, int qty, BigDecimal discountPct) {
        return WastageCalculator.calculate(new Input(
            null, WastageCalculator.QUANTITY_MODE_PIECES, null, qty,
            WastageCalculator.WASTAGE_MODE_NONE, null, null,
            listPrice, discountPct));
    }

    // ── D1 — QN6900971-4 (F-SM-002, TH, sanitary ware = PLAIN rows, unit ชุด, discount 20%) ────

    @Test
    void D1_QN6900971_4_plainRowsWithDiscount20pct_everyRowAndTotal() {
        BigDecimal discount20 = new BigDecimal("20");
        record Row(BigDecimal list, int qty, String net, String amount) {}
        List<Row> rows = List.of(
            new Row(new BigDecimal("58889.72"), 1, "47111.78", "47111.78"),
            new Row(new BigDecimal("12143.93"), 1, "9715.14", "9715.14"),
            new Row(new BigDecimal("8900.00"), 1, "7120.00", "7120.00"),
            new Row(new BigDecimal("36598.13"), 1, "29278.50", "29278.50"),
            new Row(new BigDecimal("35433.64"), 2, "28346.91", "56693.82"),
            new Row(new BigDecimal("45581.31"), 2, "36465.05", "72930.10"),
            new Row(new BigDecimal("36764.49"), 2, "29411.59", "58823.18"),
            // Bold rows in the plan: prove amount = round2(list × qty × (1 − pct/100)), NOT
            // round2(round2(net) × qty) — that double-rounded formula gives 35,799.64 /
            // 16,502.44 / 11,445.24 / 16,502.44 / 11,445.24 here instead.
            new Row(new BigDecimal("22374.77"), 2, "17899.82", "35799.63"),
            new Row(new BigDecimal("10314.02"), 2, "8251.22", "16502.43"),
            new Row(new BigDecimal("7153.27"), 2, "5722.62", "11445.23"),
            new Row(new BigDecimal("10314.02"), 2, "8251.22", "16502.43"),
            new Row(new BigDecimal("7153.27"), 2, "5722.62", "11445.23"));

        List<BigDecimal> amounts = new java.util.ArrayList<>();
        for (Row row : rows) {
            Result r = plainRow(row.list(), row.qty(), discount20);
            assertThat(r.netUnitPrice()).as("net for list %s", row.list()).isEqualByComparingTo(row.net());
            assertThat(r.lineAmount()).as("amount for list %s", row.list()).isEqualByComparingTo(row.amount());
            amounts.add(r.lineAmount());
        }

        BigDecimal subtotal = WastageCalculator.subtotal(amounts);
        assertThat(subtotal).isEqualByComparingTo("373367.47");
        BigDecimal vat = WastageCalculator.vat(subtotal);
        assertThat(vat).isEqualByComparingTo("26135.72");
        assertThat(WastageCalculator.grandTotal(subtotal, vat)).isEqualByComparingTo("399503.19");
    }

    // ── D2 — QN6900971 ใบสรุป (TH, five PLAIN rows qty 1, no discount, no unit) ────────────────

    @Test
    void D2_QN6900971_summary_fivePlainRowsNoDiscount_totals() {
        List<BigDecimal> amounts = List.of(
            plainRow(new BigDecimal("93225.42"), 1, BigDecimal.ZERO).lineAmount(),
            plainRow(new BigDecimal("93225.42"), 1, BigDecimal.ZERO).lineAmount(),
            plainRow(new BigDecimal("93225.42"), 1, BigDecimal.ZERO).lineAmount(),
            plainRow(new BigDecimal("140337.19"), 1, BigDecimal.ZERO).lineAmount(),
            // The summary document's own fifth line IS D1's document total — an intentional
            // real-world coincidence in the owner's data, not a test artifact.
            plainRow(new BigDecimal("373367.47"), 1, BigDecimal.ZERO).lineAmount());

        BigDecimal subtotal = WastageCalculator.subtotal(amounts);
        assertThat(subtotal).isEqualByComparingTo("793380.92");
        BigDecimal vat = WastageCalculator.vat(subtotal);
        assertThat(vat).isEqualByComparingTo("55536.66");
        assertThat(WastageCalculator.grandTotal(subtotal, vat)).isEqualByComparingTo("848917.58");
    }

    // ── D3 — QN6900981-1 (TH, TILE, AREA mode, SPECIAL_SQM 790 บาท/ตร.ม., 60x120 → 0.72, ────────
    // ── 2 pcs/box, no wastage) ───────────────────────────────────────────────────────────────

    @Test
    void D3_QN6900981_1_areaModeSpecialSqm_everyRowAndTotal() {
        BigDecimal sqmPerPiece = new BigDecimal("0.72");
        BigDecimal specialPriceSqm = new BigDecimal("790");
        BigDecimal net = WastageCalculator.netPerPieceFromSpecialSqm(specialPriceSqm, sqmPerPiece);
        assertThat(net).isEqualByComparingTo("531.16");

        // Row 1: area 2,793 → 2,793 × 1.39 = 3,882.27 HALF_UPs to 3,882 (an even box multiple, so
        // the printed pieces stay 3,882 — CEILING would give 3,883, box-rounding up to 3,884).
        Result row1 = WastageCalculator.calculate(new Input(
            sqmPerPiece, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("2793"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, 2,
            new BigDecimal("1299.00"), null));
        assertThat(row1.piecesBeforeWastage()).isEqualTo(3882);
        assertThat(row1.piecesFinal()).isEqualTo(3882);
        BigDecimal amount1 = net.multiply(BigDecimal.valueOf(row1.piecesFinal())).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount1).isEqualByComparingTo("2061963.12");

        // Row 2: area 274 → 274 × 1.39 = 380.86 HALF_UPs to 381 (CEILING agrees here — the
        // fraction is already > 0.5), box-rounds up to 382 ("381 แผ่น + เพื่อปัดลงกล่อง = 382").
        Result row2 = WastageCalculator.calculate(new Input(
            sqmPerPiece, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("274"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, 2,
            new BigDecimal("1299.00"), null));
        assertThat(row2.piecesBeforeWastage()).isEqualTo(381);
        assertThat(row2.piecesFinal()).isEqualTo(382);
        BigDecimal amount2 = net.multiply(BigDecimal.valueOf(row2.piecesFinal())).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount2).isEqualByComparingTo("202903.12");

        BigDecimal subtotal = WastageCalculator.subtotal(List.of(amount1, amount2));
        assertThat(subtotal).isEqualByComparingTo("2264866.24");
        BigDecimal vat = WastageCalculator.vat(subtotal);
        assertThat(vat).isEqualByComparingTo("158540.64");
        assertThat(WastageCalculator.grandTotal(subtotal, vat)).isEqualByComparingTo("2423406.88");
    }

    // ── D4 — QN6900902-6 (F-SM-008, EN, USD, six PLAIN rows, NET, no VAT) ────────────────────

    @Test
    void D4_QN6900902_6_englishPlainRowsNoVat_total() {
        record Row(BigDecimal price, int qty, String amount) {}
        List<Row> rows = List.of(
            new Row(new BigDecimal("140"), 117, "16380.00"),
            new Row(new BigDecimal("130"), 448, "58240.00"),
            new Row(new BigDecimal("27"), 85, "2295.00"),
            new Row(new BigDecimal("79"), 15, "1185.00"),
            new Row(new BigDecimal("128"), 283, "36224.00"),
            new Row(new BigDecimal("3300"), 1, "3300.00"));

        List<BigDecimal> amounts = new java.util.ArrayList<>();
        for (Row row : rows) {
            BigDecimal amount = plainRow(row.price(), row.qty(), BigDecimal.ZERO).lineAmount();
            assertThat(amount).as("D4 row price %s x %s", row.price(), row.qty()).isEqualByComparingTo(row.amount());
            amounts.add(amount);
        }

        BigDecimal subtotal = WastageCalculator.subtotal(amounts);
        assertThat(subtotal).isEqualByComparingTo("117624.00");
        // EN document: zero VAT (WastageCalculator#vatRateFor), grand total == subtotal.
        BigDecimal vat = WastageCalculator.vat(subtotal, WastageCalculator.DOCUMENT_LANGUAGE_EN);
        assertThat(vat).isEqualByComparingTo("0.00");
        assertThat(WastageCalculator.grandTotal(subtotal, vat)).isEqualByComparingTo("117624.00");
    }

    // ── D5 — QN6900704-2 (TH, TILE, PIECES mode, SPECIAL_SQM, 60x60 → 0.36, + 3% ADJUSTMENT) ────

    @Test
    void D5_QN6900704_2_piecesModeSpecialSqmWithAdjustment_everyRowAndTotal() {
        BigDecimal sqmPerPiece = new BigDecimal("0.36");

        BigDecimal net1350 = WastageCalculator.netPerPieceFromSpecialSqm(new BigDecimal("1350"), sqmPerPiece);
        assertThat(net1350).isEqualByComparingTo("453.84");
        BigDecimal net1400 = WastageCalculator.netPerPieceFromSpecialSqm(new BigDecimal("1400"), sqmPerPiece);
        assertThat(net1400).isEqualByComparingTo("470.65");

        Result row1 = WastageCalculator.calculate(new Input(
            sqmPerPiece, WastageCalculator.QUANTITY_MODE_PIECES, null, 329,
            WastageCalculator.WASTAGE_MODE_NONE, null, 3,
            new BigDecimal("881.46"), null));
        assertThat(row1.piecesFinal()).isEqualTo(330);
        BigDecimal amount1 = net1350.multiply(BigDecimal.valueOf(row1.piecesFinal())).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount1).isEqualByComparingTo("149767.20");

        Result row2 = WastageCalculator.calculate(new Input(
            sqmPerPiece, WastageCalculator.QUANTITY_MODE_PIECES, null, 202,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4,
            new BigDecimal("843.14"), null));
        assertThat(row2.piecesFinal()).isEqualTo(204);
        BigDecimal amount2 = net1400.multiply(BigDecimal.valueOf(row2.piecesFinal())).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount2).isEqualByComparingTo("96012.60");

        Result row3 = WastageCalculator.calculate(new Input(
            sqmPerPiece, WastageCalculator.QUANTITY_MODE_PIECES, null, 1161,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4,
            new BigDecimal("881.46"), null));
        assertThat(row3.piecesFinal()).isEqualTo(1164);
        BigDecimal amount3 = net1350.multiply(BigDecimal.valueOf(row3.piecesFinal())).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount3).isEqualByComparingTo("528269.76");

        Result row4 = WastageCalculator.calculate(new Input(
            sqmPerPiece, WastageCalculator.QUANTITY_MODE_PIECES, null, 1100,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4,
            new BigDecimal("900.63"), null));
        assertThat(row4.piecesFinal()).isEqualTo(1100);
        BigDecimal amount4 = net1350.multiply(BigDecimal.valueOf(row4.piecesFinal())).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount4).isEqualByComparingTo("499224.00");

        BigDecimal base = amount1.add(amount2).add(amount3).add(amount4);
        assertThat(base).isEqualByComparingTo("1273273.56");
        BigDecimal adjustment = WastageCalculator.adjustmentAmount(base, new BigDecimal("3"));
        assertThat(adjustment).isEqualByComparingTo("38198.21");

        BigDecimal subtotal = WastageCalculator.subtotal(List.of(amount1, amount2, amount3, amount4, adjustment.negate()));
        assertThat(subtotal).isEqualByComparingTo("1235075.35");
        BigDecimal vat = WastageCalculator.vat(subtotal);
        assertThat(vat).isEqualByComparingTo("86455.27");
        assertThat(WastageCalculator.grandTotal(subtotal, vat)).isEqualByComparingTo("1321530.62");
    }

    // ── D6 — QN6900933 (F-SM-008, EN per-sqm tiles) ──────────────────────────────────────────

    @Test
    void D6_QN6900933_englishPerSqmBoxes_everyRowAndTotal() {
        BigDecimal qty1 = WastageCalculator.sqmQuantityFromBoxes(120, new BigDecimal("0.6"));
        assertThat(qty1).isEqualByComparingTo("72.00");
        BigDecimal amount1 = qty1.multiply(new BigDecimal("64")).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount1).isEqualByComparingTo("4608.00");

        BigDecimal qty2 = WastageCalculator.sqmQuantityFromBoxes(30, new BigDecimal("0.6"));
        assertThat(qty2).isEqualByComparingTo("18.00");
        BigDecimal amount2 = qty2.multiply(new BigDecimal("36")).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount2).isEqualByComparingTo("648.00");

        BigDecimal qty3 = WastageCalculator.sqmQuantityFromBoxes(114, new BigDecimal("0.495"));
        assertThat(qty3).isEqualByComparingTo("56.43");
        BigDecimal amount3 = qty3.multiply(new BigDecimal("64")).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount3).isEqualByComparingTo("3611.52");

        BigDecimal subtotal = WastageCalculator.subtotal(List.of(amount1, amount2, amount3));
        assertThat(subtotal).isEqualByComparingTo("8867.52");
        BigDecimal vat = WastageCalculator.vat(subtotal, WastageCalculator.DOCUMENT_LANGUAGE_EN);
        assertThat(vat).isEqualByComparingTo("0.00");
        assertThat(WastageCalculator.grandTotal(subtotal, vat)).isEqualByComparingTo("8867.52");
    }

    // ── D7 — QN6900648 (TH, TILE, AREA mode, SPECIAL_SQM, 60x120 → 0.72, 2/box, no wastage) ────

    @Test
    void D7_QN6900648_areaModeSpecialSqm_everyRowAndTotal() {
        BigDecimal sqmPerPiece = new BigDecimal("0.72");
        BigDecimal net1800 = WastageCalculator.netPerPieceFromSpecialSqm(new BigDecimal("1800"), sqmPerPiece);
        assertThat(net1800).isEqualByComparingTo("1210.25");
        BigDecimal net860 = WastageCalculator.netPerPieceFromSpecialSqm(new BigDecimal("860"), sqmPerPiece);
        assertThat(net860).isEqualByComparingTo("578.23");

        Result row1 = WastageCalculator.calculate(new Input(
            sqmPerPiece, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("945"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, 2, new BigDecimal("1"), null));
        assertThat(row1.piecesBeforeWastage()).isEqualTo(1314); // 945 × 1.39 = 1,313.55 → HALF_UP 1,314
        assertThat(row1.piecesFinal()).isEqualTo(1314);
        BigDecimal amount1 = net1800.multiply(BigDecimal.valueOf(row1.piecesFinal())).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount1).isEqualByComparingTo("1590268.50");

        Result row2 = WastageCalculator.calculate(new Input(
            sqmPerPiece, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("339"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, 2, new BigDecimal("1"), null));
        assertThat(row2.piecesBeforeWastage()).isEqualTo(471); // 339 × 1.39 = 471.21 → HALF_UP 471
        assertThat(row2.piecesFinal()).isEqualTo(472); // 471 box-rounds up to 472
        BigDecimal amount2 = net1800.multiply(BigDecimal.valueOf(row2.piecesFinal())).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount2).isEqualByComparingTo("571238.00");

        Result row3 = WastageCalculator.calculate(new Input(
            sqmPerPiece, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("159"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, 2, new BigDecimal("1"), null));
        assertThat(row3.piecesBeforeWastage()).isEqualTo(221); // 159 × 1.39 = 221.01 → HALF_UP 221
        assertThat(row3.piecesFinal()).isEqualTo(222);
        BigDecimal amount3 = net860.multiply(BigDecimal.valueOf(row3.piecesFinal())).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount3).isEqualByComparingTo("128367.06");

        // Row 4: the OTHER headline R-B example — 511 × 1.39 = 710.29 HALF_UPs to 710 (already an
        // even box multiple, so the printed pieces stay 710); CEILING gives 711, an odd multiple
        // that box-rounds up again to 712.
        Result row4 = WastageCalculator.calculate(new Input(
            sqmPerPiece, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("511"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, 2, new BigDecimal("1"), null));
        assertThat(row4.piecesBeforeWastage()).isEqualTo(710);
        assertThat(row4.piecesFinal()).isEqualTo(710);
        BigDecimal amount4 = net1800.multiply(BigDecimal.valueOf(row4.piecesFinal())).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount4).isEqualByComparingTo("859277.50");

        Result row5 = WastageCalculator.calculate(new Input(
            sqmPerPiece, WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("1200"), null,
            WastageCalculator.WASTAGE_MODE_NONE, null, 2, new BigDecimal("1"), null));
        assertThat(row5.piecesBeforeWastage()).isEqualTo(1668); // exact, no rounding ambiguity
        assertThat(row5.piecesFinal()).isEqualTo(1668);
        BigDecimal amount5 = net1800.multiply(BigDecimal.valueOf(row5.piecesFinal())).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(amount5).isEqualByComparingTo("2018697.00");

        BigDecimal subtotal = WastageCalculator.subtotal(List.of(amount1, amount2, amount3, amount4, amount5));
        assertThat(subtotal).isEqualByComparingTo("5167848.06");
        BigDecimal vat = WastageCalculator.vat(subtotal);
        assertThat(vat).isEqualByComparingTo("361749.36");
        assertThat(WastageCalculator.grandTotal(subtotal, vat)).isEqualByComparingTo("5529597.42");
    }

    // ── D8 — QN6900782-2 (TH, three PLAIN rows, NET) ─────────────────────────────────────────

    @Test
    void D8_QN6900782_2_plainRowsNoDiscount_total() {
        List<BigDecimal> amounts = List.of(
            plainRow(new BigDecimal("1950"), 22, BigDecimal.ZERO).lineAmount(),
            plainRow(new BigDecimal("385"), 24, BigDecimal.ZERO).lineAmount(),
            plainRow(new BigDecimal("345"), 24, BigDecimal.ZERO).lineAmount());

        BigDecimal subtotal = WastageCalculator.subtotal(amounts);
        assertThat(subtotal).isEqualByComparingTo("60420.00");
        BigDecimal vat = WastageCalculator.vat(subtotal);
        assertThat(vat).isEqualByComparingTo("4229.40");
        assertThat(WastageCalculator.grandTotal(subtotal, vat)).isEqualByComparingTo("64649.40");
    }

    // ── D9 — QN6900595-3 (the spec's own recorded reference; R-B/R-C evidence, no VAT/total ────
    // ── figures were recorded for this one — see WastageCalculatorTest for the same pieces ─────
    // ── figures pinned with full Javadoc on the rounding order) ─────────────────────────────────

    @Test
    void D9_QN6900595_3_areaModePercentWastage_piecesOnly() {
        // item1: 569 ตร.ม., sqmPerPiece 0.72 (1.39 แผ่น/ตร.ม.), +10% wastage, 2/box → printed 870
        // (NOT 872 — see WastageCalculator's class Javadoc for the full R-B/R-C derivation).
        Result item1 = WastageCalculator.calculate(new Input(
            new BigDecimal("0.72"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("569"), null,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 2,
            new BigDecimal("100"), null));
        assertThat(item1.piecesFinal()).isEqualTo(870);

        // item3: 87 ตร.ม., sqmPerPiece 0.36 (2.78 แผ่น/ตร.ม.), +10% wastage, 4/box → printed 268.
        Result item3 = WastageCalculator.calculate(new Input(
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("87"), null,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 4,
            new BigDecimal("100"), null));
        assertThat(item3.piecesFinal()).isEqualTo(268);

        // item5: 124 ตร.ม., sqmPerPiece 0.36, +10% wastage, 4/box → printed 380. The intermediate
        // 124 × 2.78 = 344.72 → HALF_UP 345, then 345 × 1.10 = 379.5 → HALF_UP 380 (already an
        // even multiple of 4, so box-rounding is a no-op) — asserted explicitly because 345 → 380
        // happens to come out the same under CEILING too (379.5 ceils to 380 as well), so this
        // line alone does not discriminate R-C; it is regression coverage, not R-C's evidence
        // (that is QN6900595-3 item1 alone — see WastageCalculator's class Javadoc).
        Result item5 = WastageCalculator.calculate(new Input(
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("124"), null,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 4,
            new BigDecimal("100"), null));
        assertThat(item5.piecesBeforeWastage()).isEqualTo(345);
        assertThat(item5.piecesAfterWastage()).isEqualTo(380);
        assertThat(item5.piecesFinal()).isEqualTo(380);
    }
}
