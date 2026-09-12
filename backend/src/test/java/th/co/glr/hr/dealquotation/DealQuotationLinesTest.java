package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Pins the exact printed-line strings from docs/sales/quotation-v2-plan.md's "Printed lines" section. */
class DealQuotationLinesTest {

    @Test
    void descriptionLine_fullExample_withThickness_sizeGoesOnItsOwnLineInstead() {
        assertThat(DealQuotationLines.descriptionLine("Marvel Pro", "Calacatta", "Polished", "MP-001",
            "60x120", new BigDecimal("2")))
            .isEqualTo("กระเบื้อง รุ่น Marvel Pro สี Calacatta ผิว Polished No.MP-001");
    }

    @Test
    void descriptionLine_omitsBlankComponentsAndProductCode() {
        assertThat(DealQuotationLines.descriptionLine("Marvel Pro", null, "", null, null, new BigDecimal("2")))
            .isEqualTo("กระเบื้อง รุ่น Marvel Pro");
    }

    /** layout-spec §2 / plan §"Printed lines" item 6: no thickness → size goes INLINE on the
     * description line, before "No.{productCode}", and there is no separate ขนาด row at all. */
    @Test
    void descriptionLine_noThickness_sizeGoesInlineBeforeProductCode() {
        assertThat(DealQuotationLines.descriptionLine("Reverso Cement", "Grigio", null, "BS66R13GP",
            "60x60", null))
            .isEqualTo("กระเบื้อง รุ่น Reverso Cement สี Grigio ขนาด 60x60 cm. No.BS66R13GP");
    }

    // ── owner ruling 2026-09-12, verbatim: "normalize it in the database so its the same in
    // unit. make the size cm and the thickness mm" ──────────────────────────────────────────────
    //
    // Storage stays millimetres; sizeLine does the ONE presentation conversion, from the
    // CATALOGUE's own width_mm/height_mm (unambiguous, never inferred) -- never from the rep's
    // free-text sizeText, which the branch's own commit history shows mixes cm and mm typed sizes
    // in the very same column (see the regression test below).

    @Test
    void sizeLine_catalogueDimensions_printedInCentimetres_thicknessInMillimetres() {
        assertThat(DealQuotationLines.sizeLine("irrelevant typed text", new BigDecimal("9"),
            new BigDecimal("600"), new BigDecimal("1200")))
            .isEqualTo("ขนาด 60 cm x 120 cm x 9 mm (ขนาดโดยประมาณ)");
    }

    /** Trailing ".0" must never appear -- "60 cm", never "60.0 cm" -- even though width_mm/
     * height_mm/thicknessMm are BigDecimal and could easily carry a trailing zero. */
    @Test
    void sizeLine_catalogueDimensions_noTrailingPointZero() {
        assertThat(DealQuotationLines.sizeLine(null, new BigDecimal("9.00"),
            new BigDecimal("600.00"), new BigDecimal("1200.00")))
            .isEqualTo("ขนาด 60 cm x 120 cm x 9 mm (ขนาดโดยประมาณ)");
    }

    @Test
    void sizeLine_catalogueDimensions_fractionalCentimetres() {
        // 605mm / 10 = 60.5cm -- a real fraction must still print cleanly.
        assertThat(DealQuotationLines.sizeLine(null, new BigDecimal("0.9"),
            new BigDecimal("605"), new BigDecimal("600")))
            .isEqualTo("ขนาด 60.5 cm x 60 cm x 0.9 mm (ขนาดโดยประมาณ)");
    }

    /**
     * THE regression this task exists to fix, pinned exactly as specified: a catalogue tile whose
     * stored dimensions are 200 x 300 MILLIMETRES must print "20 cm x 30 cm", NOT "200 cm x 300
     * cm" -- the bug the previous ("owner feedback pass 3") version of this method had, because it
     * built the face size by splitting the REP'S TYPED "200x300" and unit-suffixing both halves
     * "cm" unconditionally, silently assuming the rep meant centimetres when the catalogue's own
     * geometry says this tile is actually 200mm x 300mm (20cm x 30cm).
     *
     * <p>Mutation-checked: reverting {@link DealQuotationLines#sizeLine} to build the face size
     * from {@code sizeText} again (splitting "200x300" on "x" and printing "200 cm x 300 cm")
     * turns this test red; restoring the catalogue-dimensions-first version turns it green again
     * -- verified by hand during implementation (see the PR body for the before/after run).
     */
    @Test
    void sizeLine_REGRESSION_catalogue200x300mm_prints20cmX30cm_notTheRepsTypedCentimetres() {
        assertThat(DealQuotationLines.sizeLine("200x300", new BigDecimal("9"),
            new BigDecimal("200"), new BigDecimal("300")))
            .isEqualTo("ขนาด 20 cm x 30 cm x 9 mm (ขนาดโดยประมาณ)")
            .as("must never read the rep's typed \"200x300\" as centimetres when the catalogue "
                + "says the tile is 200mm x 300mm")
            .doesNotContain("200 cm").doesNotContain("300 cm");
    }

    // ── FALLBACK: no catalogue dimensions -- print the rep's typed text EXACTLY as typed ────────

    @Test
    void sizeLine_noCatalogueDimensions_printsTheRepsTypedTextVerbatim_unconverted() {
        // No splitting, no unit-guessing -- this is exactly the shape the deleted heuristic got
        // wrong (a typed "200x300" is NOT reliably centimetres; see the regression test above).
        assertThat(DealQuotationLines.sizeLine("200x300", new BigDecimal("9"), null, null))
            .isEqualTo("ขนาด 200x300 x 9 mm (ขนาดโดยประมาณ)");
    }

    @Test
    void sizeLine_noCatalogueDimensions_alreadyUnitedTextStillPrintedVerbatim() {
        assertThat(DealQuotationLines.sizeLine("600x1200 mm", new BigDecimal("9"), null, null))
            .isEqualTo("ขนาด 600x1200 mm x 9 mm (ขนาดโดยประมาณ)");
        assertThat(DealQuotationLines.sizeLine("60x60 ซม.", new BigDecimal("2"), null, null))
            .isEqualTo("ขนาด 60x60 ซม. x 2 mm (ขนาดโดยประมาณ)");
    }

    @Test
    void sizeLine_noCatalogueDimensions_unparseableFaceSize_isPrintedAsTyped_notMangled() {
        assertThat(DealQuotationLines.sizeLine("รูปทรงอิสระ", new BigDecimal("9"), null, null))
            .isEqualTo("ขนาด รูปทรงอิสระ x 9 mm (ขนาดโดยประมาณ)");
        assertThat(DealQuotationLines.sizeLine("60x120x5", new BigDecimal("9"), null, null))
            .isEqualTo("ขนาด 60x120x5 x 9 mm (ขนาดโดยประมาณ)");
    }

    /** Only ONE catalogue dimension present (e.g. a corrupt row) is treated the same as neither --
     * a half-known geometry is not enough to print a face size from, so this falls back too. */
    @Test
    void sizeLine_onlyOneCatalogueDimension_fallsBackToTypedText() {
        assertThat(DealQuotationLines.sizeLine("60x120", new BigDecimal("9"),
            new BigDecimal("600"), null))
            .isEqualTo("ขนาด 60x120 x 9 mm (ขนาดโดยประมาณ)");
    }

    /** A blank face size, with no catalogue dimensions either, prints only the thickness -- no
     * dangling separator. */
    @Test
    void sizeLine_blankFaceSize_noCatalogueDimensions_printsOnlyTheThickness() {
        assertThat(DealQuotationLines.sizeLine(null, new BigDecimal("9"), null, null))
            .isEqualTo("ขนาด 9 mm (ขนาดโดยประมาณ)");
        assertThat(DealQuotationLines.sizeLine("  ", new BigDecimal("9"), null, null))
            .isEqualTo("ขนาด 9 mm (ขนาดโดยประมาณ)");
    }

    /** layout-spec §2: no thickness → null (not a blank/dangling line) — the size already went
     * inline on {@link DealQuotationLines#descriptionLine} instead, so there is no separate row.
     * Holds regardless of whether catalogue dimensions are available. */
    @Test
    void sizeLine_noThickness_returnsNull_noSeparateRow() {
        assertThat(DealQuotationLines.sizeLine("60x60", null, null, null)).isNull();
        assertThat(DealQuotationLines.sizeLine("60x60", null, new BigDecimal("600"), new BigDecimal("600")))
            .isNull();
    }

    @Test
    void calculationLine_areaMode_percentWastage_withBox() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("87"), new BigDecimal("2.78"),
            242, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 268, 4);

        assertThat(line).isEqualTo(
            "(พื้นที่ 87 ตร.ม.ๆละ 2.78 แผ่น รวม 242 แผ่น + เผื่อ 10% และปัดลงกล่อง = 268 แผ่น) (บรรจุ 4 แผ่น/กล่อง)");
    }

    @Test
    void calculationLine_piecesQuantityMode() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            100, WastageCalculator.WASTAGE_MODE_PIECES, new BigDecimal("5"), 108, 12);

        assertThat(line).isEqualTo("(จำนวน 100 แผ่น + เผื่อ 5 แผ่น และปัดลงกล่อง = 108 แผ่น) (บรรจุ 12 แผ่น/กล่อง)");
    }

    @Test
    void calculationLine_noneWastage_omitsWastagePhrase() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), new BigDecimal("2"),
            20, WastageCalculator.WASTAGE_MODE_NONE, null, 24, 12);

        assertThat(line).isEqualTo("(พื้นที่ 10 ตร.ม.ๆละ 2 แผ่น รวม 20 แผ่น และปัดลงกล่อง = 24 แผ่น) (บรรจุ 12 แผ่น/กล่อง)");
    }

    @Test
    void calculationLine_noPiecesPerBox_omitsRoundingAndBoxTail() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), new BigDecimal("2"),
            20, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 22, null);

        assertThat(line).isEqualTo("(พื้นที่ 10 ตร.ม.ๆละ 2 แผ่น รวม 20 แผ่น + เผื่อ 10% = 22 แผ่น)");
    }

    // ── owner feedback pass 3 (2026-09-11): zero wastage prints nothing, magnitudes get commas ──

    /** "ตัด '+ เผื่อ 0%' ออกทั้งหมด" -- a ZERO percent wastage must print no wastage phrase at all,
     * not "+ เผื่อ 0%". The rest of the line, piecesFinal included, is byte-identical to what a
     * PERCENT line with real wastage would print around it -- this is a printing change only, the
     * quantity math upstream (piecesFinal here still counts as though no wastage were applied) is
     * untouched by this method. */
    @Test
    void calculationLine_zeroPercentWastage_omitsTheWastagePhraseEntirely() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), new BigDecimal("2"),
            20, WastageCalculator.WASTAGE_MODE_PERCENT, BigDecimal.ZERO, 20, 4);

        assertThat(line).isEqualTo("(พื้นที่ 10 ตร.ม.ๆละ 2 แผ่น รวม 20 แผ่น และปัดลงกล่อง = 20 แผ่น) (บรรจุ 4 แผ่น/กล่อง)");
        assertThat(line).doesNotContain("เผื่อ");
    }

    /** Same zero-omission, PIECES wastage mode: no "+ เผื่อ 0 แผ่น". */
    @Test
    void calculationLine_zeroPiecesWastage_omitsTheWastagePhraseEntirely() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            100, WastageCalculator.WASTAGE_MODE_PIECES, BigDecimal.ZERO, 100, 12);

        assertThat(line).isEqualTo("(จำนวน 100 แผ่น และปัดลงกล่อง = 100 แผ่น) (บรรจุ 12 แผ่น/กล่อง)");
        assertThat(line).doesNotContain("เผื่อ");
    }

    /** "Format ตัวเลขในคำอธิบายขอ comma ด้วย" -- pinned against the owner's own export line
     * ("รวม 4917 แผ่น + เผื่อ 5% และปัดลงกล่อง = 5180 แผ่น" must read "4,917" / "5,180"), with a
     * >999 area and pieces-per-box thrown in so every magnitude in the line is checked. */
    @Test
    void calculationLine_largeCounts_getThousandsCommas() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("1200"), new BigDecimal("16.39"),
            4917, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("5"), 5180, 1000);

        assertThat(line).isEqualTo(
            "(พื้นที่ 1,200 ตร.ม.ๆละ 16.39 แผ่น รวม 4,917 แผ่น + เผื่อ 5% และปัดลงกล่อง = 5,180 แผ่น) "
                + "(บรรจุ 1,000 แผ่น/กล่อง)");
    }

    /** The owner's own example numbers (area 300, 4917 → 5180) from her report: "(พื้นที่ 300
     * ตร.ม.ๆละ 16.39 แผ่น รวม 4917 แผ่น + เผื่อ 5% และปัดลงกล่อง = 5180 แผ่น)" — her quote is cut off
     * right after "= 5180 แผ่น)" with no box-count tail shown, so this pins the same "และปัดลงกล่อง"
     * (piecesPerBox present, matching her line) with the tail the real method always appends
     * alongside it, rather than guessing at a piecesPerBox value she did not report. */
    @Test
    void calculationLine_matchesTheOwnersOwnExportNumbers() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("300"), new BigDecimal("16.39"),
            4917, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("5"), 5180, 10);

        assertThat(line).isEqualTo(
            "(พื้นที่ 300 ตร.ม.ๆละ 16.39 แผ่น รวม 4,917 แผ่น + เผื่อ 5% และปัดลงกล่อง = 5,180 แผ่น) "
                + "(บรรจุ 10 แผ่น/กล่อง)");
    }

    /** PIECES quantity mode also gets commas on its own count when it exceeds 999. */
    @Test
    void calculationLine_piecesQuantityMode_largeCount_getsThousandsCommas() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            1500, WastageCalculator.WASTAGE_MODE_NONE, null, 1500, null);

        assertThat(line).isEqualTo("(จำนวน 1,500 แผ่น = 1,500 แผ่น)");
    }

    @Test
    void descriptionLine_doesNotAppendCmWhenTheSizeTextAlreadyCarriesAUnit() {
        // Review finding LOW-2: "600x1200 mm" printed as "ขนาด 600x1200 mm cm." on the
        // null-thickness branch.
        assertThat(DealQuotationLines.descriptionLine("Reverso Cement", "Grigio", null, null, "600x1200 mm", null))
            .isEqualTo("กระเบื้อง รุ่น Reverso Cement สี Grigio ขนาด 600x1200 mm");
        assertThat(DealQuotationLines.descriptionLine("Reverso Cement", "Grigio", null, null, "60x60", null))
            .isEqualTo("กระเบื้อง รุ่น Reverso Cement สี Grigio ขนาด 60x60 cm.");
    }

    // ── quotation v3 (owner feedback pass 3, 2026-09-11) ─────────────────────────────────────

    @Test
    void specialPriceLine_matchesTheOwnersPrintedSubLine() {
        assertThat(DealQuotationLines.specialPriceLine(new BigDecimal("1350")))
            .isEqualTo("(ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)");
        assertThat(DealQuotationLines.specialPriceLine(new BigDecimal("790")))
            .isEqualTo("(ราคาพิเศษ 790 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)");
    }

    /** Null (not a blank string) so the caller OMITS the row — same contract as sizeLine. */
    @Test
    void specialPriceLine_isNullWhenThereIsNoSpecialPrice() {
        assertThat(DealQuotationLines.specialPriceLine(null)).isNull();
        assertThat(DealQuotationLines.specialPriceLine(BigDecimal.ZERO)).isNull();
    }

    /** Pinned against the owner's QN6900704-2, verbatim — including the ZERO-PADDED Buddhist-era
     * date ("31/07/2569"), where the v3 spec's own placeholder would have produced "31/7/2569". */
    @Test
    void adjustmentDescription_matchesTheOwnersPrintedDiscountLine() {
        assertThat(DealQuotationLines.adjustmentDescription(
            new BigDecimal("3"), LocalDate.of(2026, 7, 31)))
            .isEqualTo("ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569");
    }

    @Test
    void adjustmentDescription_dropsTheDeadlineClauseWhenThereIsNoDate() {
        assertThat(DealQuotationLines.adjustmentDescription(new BigDecimal("3"), null))
            .isEqualTo("ส่วนลดพิเศษ 3%");
        // A flat-baht adjustment has no percent to print either.
        assertThat(DealQuotationLines.adjustmentDescription(null, null)).isEqualTo("ส่วนลดพิเศษ");
        assertThat(DealQuotationLines.adjustmentDescription(null, LocalDate.of(2026, 7, 31)))
            .isEqualTo("ส่วนลดพิเศษ สำหรับการสั่งซื้อภายใน 31/07/2569");
    }

    @Test
    void adjustmentDescription_dropsTrailingZerosOnAFractionalPercent() {
        assertThat(DealQuotationLines.adjustmentDescription(
            new BigDecimal("2.50"), LocalDate.of(2026, 7, 31)))
            .isEqualTo("ส่วนลดพิเศษ 2.5% สำหรับการสั่งซื้อภายใน 31/07/2569");
    }
}
