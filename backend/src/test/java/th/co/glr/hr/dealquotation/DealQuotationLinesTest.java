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

    // ── REFINEMENT (prod QT-2026-0034-1, quotation_id=32, 2026-09-15): a rep who retypes ขนาด to a
    // genuinely DIFFERENT size on a row that still carries a catalogue link must see THEIR size,
    // not the linked catalogue's -- the catalogue-first rule above was only ever meant to catch
    // the SAME size typed in an unreliable unit (the 200x300mm regression). ────────────────────

    /** The exact prod bug: catalogue is 600x600mm, rep retyped "30x60" (a genuinely different
     * tile) -- must print the rep's typed size, not "60 cm x 60 cm". */
    @Test
    void sizeLine_typedSizeDiffersFromCatalogue_30x60_printsTypedText_notTheCatalogue600x600() {
        assertThat(DealQuotationLines.sizeLine("30x60", new BigDecimal("20"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("ขนาด 30x60 x 20 mm (ขนาดโดยประมาณ)")
            .doesNotContain("60 cm x 60 cm");
    }

    /** The second prod line: "3x60" against the same 600x600mm catalogue row. */
    @Test
    void sizeLine_typedSizeDiffersFromCatalogue_3x60_printsTypedText_notTheCatalogue600x600() {
        assertThat(DealQuotationLines.sizeLine("3x60", new BigDecimal("20"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("ขนาด 3x60 x 20 mm (ขนาดโดยประมาณ)")
            .doesNotContain("60 cm x 60 cm");
    }

    /** Typed in millimetres, exactly matching the catalogue's own mm figures -- still prints the
     * catalogue's (identical) centimetre conversion, same as always. */
    @Test
    void sizeLine_typedSizeMatchesCatalogueInMillimetres_printsCatalogueCentimetres() {
        assertThat(DealQuotationLines.sizeLine("600x600", new BigDecimal("20"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("ขนาด 60 cm x 60 cm x 20 mm (ขนาดโดยประมาณ)");
    }

    /** Typed in centimetres, exactly matching the catalogue's face size once converted. */
    @Test
    void sizeLine_typedSizeMatchesCatalogueInCentimetres_printsCatalogueCentimetres() {
        assertThat(DealQuotationLines.sizeLine("60x60", new BigDecimal("20"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("ขนาด 60 cm x 60 cm x 20 mm (ขนาดโดยประมาณ)");
    }

    /** Spaces around the separator and a trailing unit are tolerated by the parser. */
    @Test
    void sizeLine_typedSizeWithSpacesAndUnit_stillRecognisedAsMatchingTheCatalogue() {
        assertThat(DealQuotationLines.sizeLine("60 x 60 cm", new BigDecimal("20"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("ขนาด 60 cm x 60 cm x 20 mm (ขนาดโดยประมาณ)");
    }

    /** Order-insensitive: "120x60" (rep wrote width/height swapped) still matches a 600x1200mm
     * catalogue row -- printed as the catalogue states it, width first. */
    @Test
    void sizeLine_typedSizeMatchesCatalogue_orderInsensitive_120x60_vs600x1200() {
        assertThat(DealQuotationLines.sizeLine("120x60", new BigDecimal("9"),
            new BigDecimal("600"), new BigDecimal("1200")))
            .isEqualTo("ขนาด 60 cm x 120 cm x 9 mm (ขนาดโดยประมาณ)");
    }

    /** A decimal pair, matched against the catalogue's own millimetre figures converted to
     * centimetres (60mm/246mm -> 6cm/24.6cm) -- exercises BigDecimal#compareTo, not float. */
    @Test
    void sizeLine_typedSizeMatchesCatalogue_decimalPair_6x24point6_vs60x246mm() {
        assertThat(DealQuotationLines.sizeLine("6x24.6", new BigDecimal("9"),
            new BigDecimal("60"), new BigDecimal("246")))
            .isEqualTo("ขนาด 6 cm x 24.6 cm x 9 mm (ขนาดโดยประมาณ)");
    }

    /** Unparseable typed text with catalogue dimensions present is unchanged behaviour: the
     * catalogue wins, exactly as it did before this refinement. */
    @Test
    void sizeLine_unparseableTypedText_withCatalogueDimensions_stillPrintsCatalogue() {
        assertThat(DealQuotationLines.sizeLine("รูปทรงอิสระ", new BigDecimal("9"),
            new BigDecimal("600"), new BigDecimal("1200")))
            .isEqualTo("ขนาด 60 cm x 120 cm x 9 mm (ขนาดโดยประมาณ)");
    }

    /** The EN variant of the exact prod bug: typed "30x60" against a 600x600mm catalogue row must
     * print the rep's text, not the catalogue's centimetre conversion. */
    @Test
    void english_sizeLine_typedSizeDiffersFromCatalogue_printsTypedText() {
        assertThat(DealQuotationLines.sizeLine(EN, "30x60", new BigDecimal("20"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("Size 30x60 x 20 mm (approx.)")
            .doesNotContain("60 cm x 60 cm");
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
            "(พื้นที่ 87 ตร.ม.ๆละ 2.78 แผ่น รวม 242 แผ่น + เผื่อ 10% และปัดขึ้นเต็มกล่อง = 268 แผ่น) (บรรจุ 4 แผ่น/กล่อง)");
    }

    @Test
    void calculationLine_piecesQuantityMode() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            100, WastageCalculator.WASTAGE_MODE_PIECES, new BigDecimal("5"), 108, 12);

        assertThat(line).isEqualTo("(จำนวน 100 แผ่น + เผื่อ 5 แผ่น และปัดขึ้นเต็มกล่อง = 108 แผ่น) (บรรจุ 12 แผ่น/กล่อง)");
    }

    @Test
    void calculationLine_noneWastage_omitsWastagePhrase() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), new BigDecimal("2"),
            20, WastageCalculator.WASTAGE_MODE_NONE, null, 24, 12);

        assertThat(line).isEqualTo("(พื้นที่ 10 ตร.ม.ๆละ 2 แผ่น รวม 20 แผ่น และปัดขึ้นเต็มกล่อง = 24 แผ่น) (บรรจุ 12 แผ่น/กล่อง)");
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

        assertThat(line).isEqualTo("(พื้นที่ 10 ตร.ม.ๆละ 2 แผ่น รวม 20 แผ่น และปัดขึ้นเต็มกล่อง = 20 แผ่น) (บรรจุ 4 แผ่น/กล่อง)");
        assertThat(line).doesNotContain("เผื่อ");
    }

    /** Same zero-omission, PIECES wastage mode: no "+ เผื่อ 0 แผ่น". */
    @Test
    void calculationLine_zeroPiecesWastage_omitsTheWastagePhraseEntirely() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            100, WastageCalculator.WASTAGE_MODE_PIECES, BigDecimal.ZERO, 100, 12);

        assertThat(line).isEqualTo("(จำนวน 100 แผ่น และปัดขึ้นเต็มกล่อง = 100 แผ่น) (บรรจุ 12 แผ่น/กล่อง)");
        assertThat(line).doesNotContain("เผื่อ");
    }

    /** "Format ตัวเลขในคำอธิบายขอ comma ด้วย" -- pinned against the owner's own export line
     * ("รวม 4917 แผ่น + เผื่อ 5% และปัดขึ้นเต็มกล่อง = 5180 แผ่น" must read "4,917" / "5,180"), with a
     * >999 area and pieces-per-box thrown in so every magnitude in the line is checked. */
    @Test
    void calculationLine_largeCounts_getThousandsCommas() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("1200"), new BigDecimal("16.39"),
            4917, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("5"), 5180, 1000);

        assertThat(line).isEqualTo(
            "(พื้นที่ 1,200 ตร.ม.ๆละ 16.39 แผ่น รวม 4,917 แผ่น + เผื่อ 5% และปัดขึ้นเต็มกล่อง = 5,180 แผ่น) "
                + "(บรรจุ 1,000 แผ่น/กล่อง)");
    }

    /** The owner's own example numbers (area 300, 4917 → 5180) from her report: "(พื้นที่ 300
     * ตร.ม.ๆละ 16.39 แผ่น รวม 4917 แผ่น + เผื่อ 5% และปัดขึ้นเต็มกล่อง = 5180 แผ่น)" — her quote is cut off
     * right after "= 5180 แผ่น)" with no box-count tail shown, so this pins the same "และปัดขึ้นเต็มกล่อง"
     * (piecesPerBox present, matching her line) with the tail the real method always appends
     * alongside it, rather than guessing at a piecesPerBox value she did not report. */
    @Test
    void calculationLine_matchesTheOwnersOwnExportNumbers() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("300"), new BigDecimal("16.39"),
            4917, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("5"), 5180, 10);

        assertThat(line).isEqualTo(
            "(พื้นที่ 300 ตร.ม.ๆละ 16.39 แผ่น รวม 4,917 แผ่น + เผื่อ 5% และปัดขึ้นเต็มกล่อง = 5,180 แผ่น) "
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

    // ── owner ruling 2026-09-13: the ENGLISH document's item lines ─────────────────────────────

    private static final String EN = WastageCalculator.DOCUMENT_LANGUAGE_EN;
    private static final String TH = WastageCalculator.DOCUMENT_LANGUAGE_TH;
    private static final java.util.regex.Pattern THAI = java.util.regex.Pattern.compile("[\\u0E00-\\u0E7F]");

    /** The owner-approved preview, BIOARCH example, all three lines verbatim. */
    @Test
    void english_ownerApprovedBioarchPreview_allThreeLinesVerbatim() {
        assertThat(DealQuotationLines.descriptionLine(EN, "BIOARCH", "BARGE GRIGIA", "ONDULATO", "APGBBK15",
            "200x305", new BigDecimal("9")))
            .isEqualTo("Tile Model BIOARCH Color BARGE GRIGIA Finish ONDULATO No.APGBBK15");
        assertThat(DealQuotationLines.sizeLine(EN, "200x305", new BigDecimal("9"),
            new BigDecimal("200"), new BigDecimal("305")))
            .isEqualTo("Size 20 cm x 30.5 cm x 9 mm (approx.)");
        assertThat(DealQuotationLines.calculationLine(EN, WastageCalculator.QUANTITY_MODE_AREA,
            new BigDecimal("300"), new BigDecimal("16.39"), 4917, WastageCalculator.WASTAGE_MODE_PERCENT,
            new BigDecimal("5"), 5180, 20))
            .isEqualTo("(Area 300 sqm @ 16.39 pcs/sqm = 4,917 pcs + 5% allowance, rounded up to full boxes"
                + " = 5,180 pcs) (20 pcs/box)");
    }

    @Test
    void english_piecesQuantityMode_piecesWastage() {
        assertThat(DealQuotationLines.calculationLine(EN, WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            200, WastageCalculator.WASTAGE_MODE_PIECES, new BigDecimal("10"), 220, 20))
            .isEqualTo("(Quantity 200 pcs + 10 pcs allowance, rounded up to full boxes = 220 pcs) (20 pcs/box)");
    }

    @Test
    void english_noPiecesPerBox_dropsTheRoundingClauseAndTheBoxTail() {
        assertThat(DealQuotationLines.calculationLine(EN, WastageCalculator.QUANTITY_MODE_AREA,
            new BigDecimal("10"), new BigDecimal("2"), 20, WastageCalculator.WASTAGE_MODE_PERCENT,
            new BigDecimal("10"), 22, null))
            .isEqualTo("(Area 10 sqm @ 2 pcs/sqm = 20 pcs + 10% allowance = 22 pcs)");
    }

    @Test
    void english_zeroOrNoneWastage_printsNoAllowanceClause() {
        assertThat(DealQuotationLines.calculationLine(EN, WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            100, WastageCalculator.WASTAGE_MODE_PIECES, BigDecimal.ZERO, 108, 12))
            .isEqualTo("(Quantity 100 pcs, rounded up to full boxes = 108 pcs) (12 pcs/box)");
        assertThat(DealQuotationLines.calculationLine(EN, WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            1500, WastageCalculator.WASTAGE_MODE_NONE, null, 1500, null))
            .isEqualTo("(Quantity 1,500 pcs = 1,500 pcs)");
    }

    /** No thickness: the size goes INLINE between Finish and No., exactly like the Thai line. */
    @Test
    void english_noThickness_sizeInline_andNoSeparateSizeRow() {
        assertThat(DealQuotationLines.descriptionLine(EN, "Reverso Cement", "Grigio", "Matt", "BS66R13GP",
            "60x60", null))
            .isEqualTo("Tile Model Reverso Cement Color Grigio Finish Matt Size 60x60 cm. No.BS66R13GP");
        assertThat(DealQuotationLines.descriptionLine(EN, "Reverso Cement", null, null, null, "600x1200 mm", null))
            .isEqualTo("Tile Model Reverso Cement Size 600x1200 mm");
        assertThat(DealQuotationLines.sizeLine(EN, "60x60", null, null, null)).isNull();
    }

    @Test
    void english_sizeLine_fallbacksMirrorTheThaiOnes() {
        assertThat(DealQuotationLines.sizeLine(EN, "60x60", new BigDecimal("10"), null, null))
            .isEqualTo("Size 60x60 x 10 mm (approx.)");
        assertThat(DealQuotationLines.sizeLine(EN, null, new BigDecimal("10"), null, null))
            .isEqualTo("Size 10 mm (approx.)");
    }

    @Test
    void english_tileUnitIsPCS_andOnlyTheThaiTileUnitIsTranslatedOnRead() {
        assertThat(DealQuotationLines.tileUnit(EN)).isEqualTo("PCS");
        assertThat(DealQuotationLines.tileUnit(TH)).isEqualTo("แผ่น");
        assertThat(DealQuotationLines.printedTileUnit(EN, "แผ่น")).isEqualTo("PCS");
        assertThat(DealQuotationLines.printedTileUnit(EN, null)).isEqualTo("PCS");
        assertThat(DealQuotationLines.printedTileUnit(EN, "SQM")).isEqualTo("SQM");
        // Thai: the stored value, null included, passed straight through.
        assertThat(DealQuotationLines.printedTileUnit(TH, "แผ่น")).isEqualTo("แผ่น");
        assertThat(DealQuotationLines.printedTileUnit(TH, null)).isNull();
        assertThat(DealQuotationLines.printedTileUnit(null, "แผ่น")).isEqualTo("แผ่น");
    }

    /** Ruling 4, verbatim: Gregorian year, month name spelled out. */
    @Test
    void english_adjustmentDescription_ownerWording() {
        assertThat(DealQuotationLines.adjustmentDescription(EN, new BigDecimal("3"), LocalDate.of(2026, 7, 31)))
            .isEqualTo("Special discount 3% for orders placed by July 31, 2026");
        assertThat(DealQuotationLines.adjustmentDescription(EN, new BigDecimal("3"), null))
            .isEqualTo("Special discount 3%");
        assertThat(DealQuotationLines.adjustmentDescription(EN, new BigDecimal("2.50"), LocalDate.of(2026, 1, 5)))
            .isEqualTo("Special discount 2.5% for orders placed by January 5, 2026");
        assertThat(DealQuotationLines.adjustmentDescription(EN, null, null)).isEqualTo("Special discount");
    }

    /** The month name must not follow the JVM's default locale. */
    @Test
    void english_adjustmentDescription_isNotLocalisedByAThaiDefaultLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("th-TH-u-ca-buddhist"));
            assertThat(DealQuotationLines.adjustmentDescription(EN, new BigDecimal("3"), LocalDate.of(2026, 7, 31)))
                .isEqualTo("Special discount 3% for orders placed by July 31, 2026");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    /** Read-time resolution of a STORED (Thai, write-time) adjustment description. */
    @Test
    void printedAdjustmentDescription_englishRederives_thaiPassesTheStoredTextThrough() {
        LocalDate deadline = LocalDate.of(2026, 7, 31);
        String storedPct = "ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569";
        assertThat(DealQuotationLines.printedAdjustmentDescription(EN, storedPct, new BigDecimal("3"), deadline))
            .isEqualTo("Special discount 3% for orders placed by July 31, 2026");
        // A flat adjustment whose stored text is the system's own Thai composition.
        assertThat(DealQuotationLines.printedAdjustmentDescription(EN,
            "ส่วนลดพิเศษ สำหรับการสั่งซื้อภายใน 31/07/2569", null, deadline))
            .isEqualTo("Special discount for orders placed by July 31, 2026");
        assertThat(DealQuotationLines.printedAdjustmentDescription(EN, "ส่วนลดพิเศษ", null, null))
            .isEqualTo("Special discount");
        // A flat adjustment with the rep's OWN wording is printed as typed.
        assertThat(DealQuotationLines.printedAdjustmentDescription(EN, "Loyalty rebate", null, deadline))
            .isEqualTo("Loyalty rebate");
        // Thai: whatever was stored, untouched.
        assertThat(DealQuotationLines.printedAdjustmentDescription(TH, storedPct, new BigDecimal("3"), deadline))
            .isEqualTo(storedPct);
        assertThat(DealQuotationLines.printedAdjustmentDescription(null, "anything", new BigDecimal("3"), deadline))
            .isEqualTo("anything");
    }

    @Test
    void english_specialPriceLine_isNeverPrinted() {
        assertThat(DealQuotationLines.specialPriceLine(EN, new BigDecimal("1350"))).isNull();
    }

    /** No Thai character in ANY English line variant. */
    @Test
    void english_noVariantContainsAThaiCharacter() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (BigDecimal thickness : new BigDecimal[] {null, new BigDecimal("9")}) {
            lines.add(DealQuotationLines.descriptionLine(EN, "M", "C", "T", "P", "60x60", thickness));
            lines.add(DealQuotationLines.sizeLine(EN, "60x60", thickness, new BigDecimal("600"), new BigDecimal("600")));
        }
        for (String quantityMode : new String[] {WastageCalculator.QUANTITY_MODE_AREA, WastageCalculator.QUANTITY_MODE_PIECES}) {
            for (String wastageMode : new String[] {WastageCalculator.WASTAGE_MODE_PERCENT,
                    WastageCalculator.WASTAGE_MODE_PIECES, WastageCalculator.WASTAGE_MODE_NONE}) {
                for (Integer box : new Integer[] {null, 20}) {
                    lines.add(DealQuotationLines.calculationLine(EN, quantityMode, BigDecimal.TEN, BigDecimal.TWO,
                        20, wastageMode, BigDecimal.ONE, 40, box));
                }
            }
        }
        lines.add(DealQuotationLines.adjustmentDescription(EN, BigDecimal.ONE, LocalDate.of(2026, 7, 31)));
        lines.add(DealQuotationLines.tileUnit(EN));
        assertThat(lines).allSatisfy(line -> {
            if (line != null) assertThat(THAI.matcher(line).find()).as(line).isFalse();
        });
    }

    /**
     * ⚠️ The Thai regression: every language-aware overload called with TH (and with null) returns
     * EXACTLY what the pre-existing Thai-only method returns, across the variant matrix. The legacy
     * methods' own expected strings above are unchanged by this branch.
     */
    @Test
    void thai_languageAwareOverloadsAreByteIdenticalToTheLegacyMethods() {
        for (String lang : new String[] {TH, null, "XX"}) {
            for (BigDecimal thickness : new BigDecimal[] {null, new BigDecimal("9")}) {
                assertThat(DealQuotationLines.descriptionLine(lang, "M", "C", "T", "P", "60x60", thickness))
                    .isEqualTo(DealQuotationLines.descriptionLine("M", "C", "T", "P", "60x60", thickness));
                assertThat(DealQuotationLines.sizeLine(lang, "60x60", thickness, new BigDecimal("600"), null))
                    .isEqualTo(DealQuotationLines.sizeLine("60x60", thickness, new BigDecimal("600"), null));
            }
            for (String quantityMode : new String[] {WastageCalculator.QUANTITY_MODE_AREA, WastageCalculator.QUANTITY_MODE_PIECES}) {
                for (String wastageMode : new String[] {WastageCalculator.WASTAGE_MODE_PERCENT,
                        WastageCalculator.WASTAGE_MODE_PIECES, WastageCalculator.WASTAGE_MODE_NONE}) {
                    for (Integer box : new Integer[] {null, 20}) {
                        assertThat(DealQuotationLines.calculationLine(lang, quantityMode, new BigDecimal("1200"),
                                new BigDecimal("16.39"), 4917, wastageMode, new BigDecimal("5"), 5180, box))
                            .isEqualTo(DealQuotationLines.calculationLine(quantityMode, new BigDecimal("1200"),
                                new BigDecimal("16.39"), 4917, wastageMode, new BigDecimal("5"), 5180, box));
                    }
                }
            }
            assertThat(DealQuotationLines.specialPriceLine(lang, new BigDecimal("1350")))
                .isEqualTo(DealQuotationLines.specialPriceLine(new BigDecimal("1350")));
            assertThat(DealQuotationLines.adjustmentDescription(lang, new BigDecimal("3"), LocalDate.of(2026, 7, 31)))
                .isEqualTo(DealQuotationLines.adjustmentDescription(new BigDecimal("3"), LocalDate.of(2026, 7, 31)));
        }
    }

    // ── owner decision 2026-09-13: the English per-sqm row ──────────────────────────────────────

    /** Her QN6900933's sub-lines, verbatim: trailing zeros dropped, the stated precision kept. */
    @Test
    void boxLine_matchesQN6900933() {
        assertThat(DealQuotationLines.boxLine(28, new BigDecimal("0.6"))).isEqualTo("(1 box = 28 pcs = 0.6 sqm)");
        assertThat(DealQuotationLines.boxLine(60, new BigDecimal("0.600000"))).isEqualTo("(1 box = 60 pcs = 0.6 sqm)");
        assertThat(DealQuotationLines.boxLine(66, new BigDecimal("0.495000"))).isEqualTo("(1 box = 66 pcs = 0.495 sqm)");
        assertThat(DealQuotationLines.boxLine(1000, new BigDecimal("1.44"))).isEqualTo("(1 box = 1,000 pcs = 1.44 sqm)");
        assertThat(DealQuotationLines.boxLine(null, new BigDecimal("0.6"))).isNull();
        assertThat(DealQuotationLines.boxLine(28, null)).isNull();
    }

    @Test
    void tilePrint_englishPerSqm_areaMode() {
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(EN, "SPECIAL_SQM",
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("300"), new BigDecimal("16.39"), 4917,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("5"), 5180, 20, 259, new BigDecimal("0.61"),
            new BigDecimal("5180"), "SQM", new BigDecimal("10.00"));
        assertThat(p.calculationLine()).isEqualTo(
            "(Area 300 sqm @ 16.39 pcs/sqm = 4,917 pcs + 5% allowance, rounded up to full boxes = 5,180 pcs = 259 boxes)");
        assertThat(p.subLine()).isEqualTo("(1 box = 20 pcs = 0.61 sqm)");
        assertThat(p.quantity()).isEqualByComparingTo("157.99");
        assertThat(p.unit()).isEqualTo("SQM");
    }

    @Test
    void tilePrint_englishPerSqm_piecesMode_QN6900933Row1_andSingularBox() {
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(EN, "SPECIAL_SQM",
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 3360, WastageCalculator.WASTAGE_MODE_NONE, null,
            3360, 28, 120, new BigDecimal("0.6"), new BigDecimal("3360"), "SQM", new BigDecimal("64.00"));
        assertThat(p.calculationLine()).isEqualTo("(Quantity 3,360 pcs, rounded up to full boxes = 3,360 pcs = 120 boxes)");
        assertThat(p.subLine()).isEqualTo("(1 box = 28 pcs = 0.6 sqm)");
        assertThat(p.quantity()).isEqualByComparingTo("72.00");
        assertThat(DealQuotationLines.tilePrint(EN, "SPECIAL_SQM", WastageCalculator.QUANTITY_MODE_PIECES, null, null,
                10, WastageCalculator.WASTAGE_MODE_NONE, null, 28, 28, 1, new BigDecimal("0.6"), BigDecimal.TEN,
                "SQM", BigDecimal.ONE).calculationLine())
            .isEqualTo("(Quantity 10 pcs, rounded up to full boxes = 28 pcs = 1 box)");
    }

    /** English NET/DIRECT_NET: pieces, PCS, the ordinary English line — sqmPerBox is ignored. */
    @Test
    void tilePrint_englishOtherModes_printPiecesEvenWithBoxData() {
        for (String mode : new String[] {"NET", "DIRECT_NET"}) {
            DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(EN, mode,
                WastageCalculator.QUANTITY_MODE_PIECES, null, null, 3360, WastageCalculator.WASTAGE_MODE_NONE, null,
                3360, 28, 120, new BigDecimal("0.6"), new BigDecimal("3360"), "แผ่น", null);
            assertThat(p.quantity()).isEqualByComparingTo("3360");
            assertThat(p.unit()).isEqualTo("PCS");
            assertThat(p.subLine()).isNull();
            assertThat(p.calculationLine()).isEqualTo("(Quantity 3,360 pcs, rounded up to full boxes = 3,360 pcs) (28 pcs/box)");
        }
    }

    /** ⚠️ Thai SPECIAL_SQM with a sqm/box on the row: byte-identical to the legacy methods. */
    @Test
    void tilePrint_thaiSpecialSqm_isByteIdenticalToTheLegacyPrint_evenWithBoxData() {
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(TH, "SPECIAL_SQM",
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("87"), new BigDecimal("2.78"), 242,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 268, 4, 67, new BigDecimal("1.44"),
            new BigDecimal("268"), "แผ่น", new BigDecimal("1350"));
        assertThat(p.calculationLine()).isEqualTo(DealQuotationLines.calculationLine(WastageCalculator.QUANTITY_MODE_AREA,
            new BigDecimal("87"), new BigDecimal("2.78"), 242, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 268, 4));
        assertThat(p.quantity()).isEqualByComparingTo("268");
        assertThat(p.unit()).isEqualTo("แผ่น");
        assertThat(p.subLine()).isEqualTo(DealQuotationLines.specialPriceLine(new BigDecimal("1350")));
    }

    /** A hand-edited English per-sqm row with no box data prints pieces — it never invents an area. */
    @Test
    void tilePrint_englishPerSqmWithoutBoxData_printsPieces() {
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(EN, "SPECIAL_SQM",
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 10, WastageCalculator.WASTAGE_MODE_NONE, null,
            10, null, null, null, BigDecimal.TEN, "SQM", new BigDecimal("64"));
        assertThat(p.quantity()).isEqualByComparingTo("10");
        assertThat(p.subLine()).isNull();
    }

    // ── Owner-approved "sell loose pieces" (2026-09-16, V182) ─────────────────────────────────

    /** The two shorter {@code calculationLine} overloads (every test above this section) default
     * {@code roundToFullBox} true and are unaffected by this feature — proven directly rather than
     * merely inferred from those tests still passing. */
    @Test
    void calculationLine_shorterOverloads_defaultRoundToFullBoxTrue() {
        String viaShort = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            40, 10);
        String viaExplicitTrue = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            40, 10, true);
        assertThat(viaShort).isEqualTo(viaExplicitTrue)
            .isEqualTo("(จำนวน 32 แผ่น และปัดขึ้นเต็มกล่อง = 40 แผ่น) (บรรจุ 10 แผ่น/กล่อง)");
    }

    /** The headline Thai example from the design: no wastage, so the intermediate "= N แผ่น"
     * clause is skipped entirely (it would just restate quantityPart's own 32) and the line goes
     * straight from quantityPart to the box/loose split. */
    @Test
    void calculationLine_thaiLoosePieces_noWastage_skipsRedundantIntermediateClause() {
        String line = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, 10, false);
        assertThat(line).isEqualTo("(จำนวน 32 แผ่น = 3 กล่อง + 2 แผ่น) (บรรจุ 10 แผ่น/กล่อง)");
    }

    /** The headline Thai example WITH wastage: the intermediate "= 29 แผ่น" clause DOES print
     * (piecesFinal differs from the quantityPart's own 28 because of the 5% allowance), followed
     * by the box/loose split — both clauses, not either alone. */
    @Test
    void calculationLine_thaiLoosePieces_withAreaAndWastage_printsBothClauses() {
        String line = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), new BigDecimal("2.78"), 28,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("5"), 29, 10, false);
        assertThat(line).isEqualTo(
            "(พื้นที่ 10 ตร.ม.ๆละ 2.78 แผ่น รวม 28 แผ่น + เผื่อ 5% = 29 แผ่น = 2 กล่อง + 9 แผ่น) (บรรจุ 10 แผ่น/กล่อง)");
    }

    /** loose = 0: an exact multiple prints "= N กล่อง" with no "+ M แผ่น" tail. */
    @Test
    void calculationLine_thaiLoosePieces_looseIsZero_printsBoxesOnlyNoPlusClause() {
        String line = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 30, WastageCalculator.WASTAGE_MODE_NONE, null,
            30, 10, false);
        assertThat(line).isEqualTo("(จำนวน 30 แผ่น = 3 กล่อง) (บรรจุ 10 แผ่น/กล่อง)");
    }

    /** full boxes = 0: fewer pieces than one box prints "= N แผ่น" with NO "กล่อง" wording at
     * all — never "0 กล่อง + N แผ่น". */
    @Test
    void calculationLine_thaiLoosePieces_fullBoxesIsZero_printsPiecesOnlyNoBoxWord() {
        String line = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 7, WastageCalculator.WASTAGE_MODE_NONE, null,
            7, 10, false);
        assertThat(line).isEqualTo("(จำนวน 7 แผ่น = 7 แผ่น) (บรรจุ 10 แผ่น/กล่อง)");
    }

    /** PIECES-mode wastage variant of the "both clauses" case, so the intermediate-clause rule is
     * pinned for both wastage modes, not only PERCENT. */
    @Test
    void calculationLine_thaiLoosePieces_piecesWastage_printsBothClauses() {
        String line = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 100, WastageCalculator.WASTAGE_MODE_PIECES,
            new BigDecimal("15"), 115, 12, false);
        assertThat(line).isEqualTo("(จำนวน 100 แผ่น + เผื่อ 15 แผ่น = 115 แผ่น = 9 กล่อง + 7 แผ่น) (บรรจุ 12 แผ่น/กล่อง)");
    }

    /** No box data at all: roundToFullBox=false has nothing to change — byte-identical to the
     * no-box default line (no "และปัดขึ้นเต็มกล่อง" wording either way, since hasBox is false). */
    @Test
    void calculationLine_roundToFullBoxFalse_noPiecesPerBox_isUnaffected() {
        String withFalse = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, null, false);
        String withTrue = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, null, true);
        assertThat(withFalse).isEqualTo(withTrue).isEqualTo("(จำนวน 32 แผ่น = 32 แผ่น)");
    }

    // ── English mirrors of the six Thai shapes above ──────────────────────────────────────────

    @Test
    void calculationLine_englishLoosePieces_noWastage_skipsRedundantIntermediateClause() {
        String line = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, 10, false);
        assertThat(line).isEqualTo("(Quantity 32 pcs = 3 boxes + 2 pcs) (10 pcs/box)");
    }

    @Test
    void calculationLine_englishLoosePieces_withAreaAndWastage_printsBothClauses() {
        String line = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), new BigDecimal("2.78"), 28,
            WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("5"), 29, 10, false);
        assertThat(line).isEqualTo(
            "(Area 10 sqm @ 2.78 pcs/sqm = 28 pcs + 5% allowance = 29 pcs = 2 boxes + 9 pcs) (10 pcs/box)");
    }

    @Test
    void calculationLine_englishLoosePieces_looseIsZero_printsBoxesOnlyNoPlusClause() {
        String line = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 30, WastageCalculator.WASTAGE_MODE_NONE, null,
            30, 10, false);
        assertThat(line).isEqualTo("(Quantity 30 pcs = 3 boxes) (10 pcs/box)");
    }

    @Test
    void calculationLine_englishLoosePieces_fullBoxesIsZero_printsPcsOnlyNoBoxWord() {
        String line = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 7, WastageCalculator.WASTAGE_MODE_NONE, null,
            7, 10, false);
        assertThat(line).isEqualTo("(Quantity 7 pcs = 7 pcs) (10 pcs/box)");
    }

    /** Singular "box"/"pc" at exactly 1 — the one shape none of the Thai tests can pin, since Thai
     * has no singular/plural distinction. */
    @Test
    void calculationLine_englishLoosePieces_singularBoxAndPcAtExactlyOne() {
        // 1 box + 1 loose pc.
        assertThat(DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 11, WastageCalculator.WASTAGE_MODE_NONE, null,
            11, 10, false))
            .isEqualTo("(Quantity 11 pcs = 1 box + 1 pc) (10 pcs/box)");
        // 1 box, loose = 0 (no "+ N pcs" tail at all, so no loose-plural to check here).
        assertThat(DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 10, WastageCalculator.WASTAGE_MODE_NONE, null,
            10, 10, false))
            .isEqualTo("(Quantity 10 pcs = 1 box) (10 pcs/box)");
        // 0 full boxes, exactly 1 loose piece.
        assertThat(DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 1, WastageCalculator.WASTAGE_MODE_NONE, null,
            1, 10, false))
            .isEqualTo("(Quantity 1 pcs = 1 pc) (10 pcs/box)");
    }

    @Test
    void calculationLine_englishLoosePieces_piecesWastage_printsBothClauses() {
        String line = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 100, WastageCalculator.WASTAGE_MODE_PIECES,
            new BigDecimal("15"), 115, 12, false);
        assertThat(line).isEqualTo("(Quantity 100 pcs + 15 pcs allowance = 115 pcs = 9 boxes + 7 pcs) (12 pcs/box)");
    }

    @Test
    void calculationLine_englishRoundToFullBoxFalse_noPiecesPerBox_isUnaffected() {
        String withFalse = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, null, false);
        String withTrue = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, null, true);
        // Unlike the Thai default branch's box-gated echo, the English default branch always
        // echoes "= N pcs" regardless of hasBox (pre-existing behaviour, untouched by this
        // feature) — so this is "(Quantity 32 pcs = 32 pcs)", not "(Quantity 32 pcs)".
        assertThat(withFalse).isEqualTo(withTrue).isEqualTo("(Quantity 32 pcs = 32 pcs)");
    }

    /** {@code tilePrint} threads roundToFullBox into the ordinary (non-per-sqm) branch — proven
     * once at that layer so a regression there (e.g. a dropped argument) is caught even if
     * {@code calculationLine} itself stays correct. */
    @Test
    void tilePrint_threadsRoundToFullBoxIntoTheOrdinaryBranch() {
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(TH, "NET",
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, 10, 3, null, new BigDecimal("32"), "แผ่น", null, false);
        assertThat(p.calculationLine()).isEqualTo("(จำนวน 32 แผ่น = 3 กล่อง + 2 แผ่น) (บรรจุ 10 แผ่น/กล่อง)");
    }

    /** The 15-argument {@code tilePrint} overload (every test above this section) defaults
     * roundToFullBox true and is unaffected by this feature. */
    @Test
    void tilePrint_fifteenArgOverload_defaultsRoundToFullBoxTrue() {
        DealQuotationLines.TilePrint viaShort = DealQuotationLines.tilePrint(TH, "NET",
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            40, 10, 4, null, new BigDecimal("40"), "แผ่น", null);
        assertThat(viaShort.calculationLine()).isEqualTo("(จำนวน 32 แผ่น และปัดขึ้นเต็มกล่อง = 40 แผ่น) (บรรจุ 10 แผ่น/กล่อง)");
    }
}
