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
     * tile) -- must print the rep's typed size, not "60 cm x 60 cm".
     *
     * <p>F2 fix (2026-09-16 review): "30x60" carries no unit token, so this now prints in the
     * catalogue's OWN cm format ("30 cm x 60 cm") rather than the ambiguous verbatim "30x60" the
     * old FALLBACK printed -- see {@link DealQuotationLines#sizeLine}'s Javadoc "F2" note. It is
     * still emphatically NOT the catalogue's "60 cm x 60 cm": the typed pair, read as centimetres
     * (the field's own label), not the catalogue's dims. */
    @Test
    void sizeLine_typedSizeDiffersFromCatalogue_30x60_printsTypedText_notTheCatalogue600x600() {
        assertThat(DealQuotationLines.sizeLine("30x60", new BigDecimal("20"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("ขนาด 30 cm x 60 cm x 20 mm (ขนาดโดยประมาณ)")
            .doesNotContain("60 cm x 60 cm");
    }

    /** The second prod line: "3x60" against the same 600x600mm catalogue row. F2 fix: unit-less,
     * so it now prints as cm, same as the "30x60" case above. */
    @Test
    void sizeLine_typedSizeDiffersFromCatalogue_3x60_printsTypedText_notTheCatalogue600x600() {
        assertThat(DealQuotationLines.sizeLine("3x60", new BigDecimal("20"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("ขนาด 3 cm x 60 cm x 20 mm (ขนาดโดยประมาณ)")
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
     * print the rep's text, not the catalogue's centimetre conversion. F2 fix: unit-less, so this
     * now prints in cm ("30 cm x 60 cm") rather than the ambiguous verbatim "30x60". */
    @Test
    void english_sizeLine_typedSizeDiffersFromCatalogue_printsTypedText() {
        assertThat(DealQuotationLines.sizeLine(EN, "30x60", new BigDecimal("20"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("Size 30 cm x 60 cm x 20 mm (approx.)")
            .doesNotContain("60 cm x 60 cm");
    }

    // ── F2 (2026-09-16 review): a size typed WITHOUT a unit must never read as millimetres ───────
    // Production bug: tile with แผ่น/กล่อง = 26, ขายแผ่นไม่เต็มกล่อง ticked, rep typed "14.8x14.8"
    // into the field labelled "ขนาด (ซม.)" -- printed "ขนาด 14.8x14.8 x 7 mm", reading as 14.8
    // MILLIMETRES. These are the exact wrong-way-round mutation-check fixtures for that fix:
    // reverting sizeLine to print the typed text verbatim again turns every one of these red.

    /** The exact production example: no catalogue link at all, typed "14.8x14.8" with no unit. */
    @Test
    void sizeLine_F2_productionExample_unitlessTypedSize_noCatalogue_printsInCentimetres() {
        assertThat(DealQuotationLines.sizeLine("14.8x14.8", new BigDecimal("7"), null, null))
            .isEqualTo("ขนาด 14.8 cm x 14.8 cm x 7 mm (ขนาดโดยประมาณ)")
            .as("must never read an unlabelled typed size as millimetres")
            .doesNotContain("14.8 mm");
    }

    /** The English mirror of the production example. */
    @Test
    void sizeLine_F2_productionExample_english_unitlessTypedSize_printsInCentimetres() {
        assertThat(DealQuotationLines.sizeLine(EN, "14.8x14.8", new BigDecimal("7"), null, null))
            .isEqualTo("Size 14.8 cm x 14.8 cm x 7 mm (approx.)")
            .doesNotContain("14.8 mm");
    }

    /** Same typed size, but the row DOES carry a catalogue link whose face size differs -- the
     * "typed text wins over the catalogue" REFINEMENT rule still applies; F2 only changes HOW the
     * winning typed text is formatted (cm, not verbatim) when it carries no unit. */
    @Test
    void sizeLine_F2_unitlessTypedSize_catalogueLinkedButDiffers_printsTypedTextInCentimetres() {
        assertThat(DealQuotationLines.sizeLine("14.8x14.8", new BigDecimal("7"),
            new BigDecimal("260"), new BigDecimal("260")))  // catalogue: 26cm x 26cm
            .isEqualTo("ขนาด 14.8 cm x 14.8 cm x 7 mm (ขนาดโดยประมาณ)")
            .doesNotContain("26 cm x 26 cm").doesNotContain("14.8 mm");
    }

    /** An EXPLICIT unit is untouched by F2 -- "14.8x14.8mm" says what it means and keeps printing
     * verbatim, exactly as it did before this fix. Genuinely different behaviour from the unitless
     * case directly above, on the identical numbers. */
    @Test
    void sizeLine_F2_explicitUnit_isUnaffected_staysVerbatim() {
        assertThat(DealQuotationLines.sizeLine("14.8x14.8mm", new BigDecimal("7"), null, null))
            .isEqualTo("ขนาด 14.8x14.8mm x 7 mm (ขนาดโดยประมาณ)");
        assertThat(DealQuotationLines.sizeLine("14.8x14.8 ซม.", new BigDecimal("7"), null, null))
            .isEqualTo("ขนาด 14.8x14.8 ซม. x 7 mm (ขนาดโดยประมาณ)");
    }

    /** An unparseable typed size is untouched by F2 -- printed verbatim, exactly as before. */
    @Test
    void sizeLine_F2_unparseable_isUnaffected_staysVerbatim() {
        assertThat(DealQuotationLines.sizeLine("รูปทรงพิเศษ", new BigDecimal("7"), null, null))
            .isEqualTo("ขนาด รูปทรงพิเศษ x 7 mm (ขนาดโดยประมาณ)");
    }

    /** A typed size that MATCHES the catalogue (order/unit-ambiguous, as today) keeps printing the
     * catalogue's own format exactly as before -- F2 only changes the two FALLBACK branches. */
    @Test
    void sizeLine_F2_unitlessTypedSize_matchesCatalogue_staysCatalogueFormat() {
        assertThat(DealQuotationLines.sizeLine("14.8x14.8", new BigDecimal("7"),
            new BigDecimal("148"), new BigDecimal("148")))  // catalogue: 14.8cm x 14.8cm, matches
            .isEqualTo("ขนาด 14.8 cm x 14.8 cm x 7 mm (ขนาดโดยประมาณ)");
    }

    // ── SHARED GRAMMAR vector table (owner complaint re-reported 2026-09-16, "แก้ขนาด/รหัสสินค้าเอง
    // แต่ PDF ยังใช้ค่าเดิม") -- this table's inputs and expected {width, height, unit} results are
    // ALSO asserted, verbatim, in frontend/src/features/quotations/quotationMeta.test.js's own
    // "shared size grammar" describe block, against `parseSizeText`. The two must never drift apart
    // again without both going red. ───────────────────────────────────────────────────────────────

    private static void assertParsed(String input, String width, String height,
                                     DealQuotationLines.SizeUnit unit) {
        DealQuotationLines.ParsedSize parsed = DealQuotationLines.parseTwoDimensions(input);
        assertThat(parsed).as("parsing %s", input).isNotNull();
        assertThat(parsed.width()).as("%s width", input).isEqualByComparingTo(width);
        assertThat(parsed.height()).as("%s height", input).isEqualByComparingTo(height);
        assertThat(parsed.unit()).as("%s unit", input).isEqualTo(unit);
    }

    private static void assertUnparsed(String input) {
        assertThat(DealQuotationLines.parseTwoDimensions(input)).as("parsing %s", input).isNull();
    }

    @Test
    void parseTwoDimensions_sharedGrammarVectors_basicSeparatorsAndCase() {
        assertParsed("30x60", "30", "60", null);
        assertParsed("30*60", "30", "60", null);
        assertParsed("30 X 60", "30", "60", null);
        assertParsed("30×60", "30", "60", null);
    }

    @Test
    void parseTwoDimensions_sharedGrammarVectors_englishUnits() {
        assertParsed("30x60cm", "30", "60", DealQuotationLines.SizeUnit.CM);
        assertParsed("30 cm x 60 cm", "30", "60", DealQuotationLines.SizeUnit.CM);
        assertParsed("300x600mm", "300", "600", DealQuotationLines.SizeUnit.MM);
        assertParsed("600x600mm", "600", "600", DealQuotationLines.SizeUnit.MM);
    }

    @Test
    void parseTwoDimensions_sharedGrammarVectors_thaiUnits() {
        assertParsed("30x60 ซม.", "30", "60", DealQuotationLines.SizeUnit.CM);
        assertParsed("30ซม.x60ซม.", "30", "60", DealQuotationLines.SizeUnit.CM);
        assertParsed("30x60 ซ.ม.", "30", "60", DealQuotationLines.SizeUnit.CM);
    }

    @Test
    void parseTwoDimensions_sharedGrammarVectors_decimalCommaAndThirdDimensionAndTrailingText() {
        // "29,7" is 29.7 -- decimal comma, not a thousands separator.
        assertParsed("29,7x59,7", "29.7", "59.7", null);
        // Third dimension (thickness) ignored, never a second dimension pair.
        assertParsed("30x60x1", "30", "60", null);
        assertParsed("60X60x0.9", "60", "60", null);
        // Trailing free text in parentheses ignored.
        assertParsed("30x60 (หนา 9)", "30", "60", null);
    }

    @Test
    void parseTwoDimensions_sharedGrammarVectors_unparseable() {
        assertUnparsed("รูปทรงอิสระ");
        assertUnparsed("60x");
        assertUnparsed("JOLLY 60x60");
        assertUnparsed("1,2X20 JOLLY COCO");
        assertUnparsed("0x60");
        assertUnparsed("");
        assertUnparsed(null);
    }

    // ── F2 (HIGH, 2026-09-16 review): Unicode whitespace normalisation. Java's `\s` is ASCII-only
    // and `String.trim()` strips only <= U+0020; JS's `\s` is Unicode-aware. Both engines must now
    // agree on every one of these -- see DealQuotationLines#normalize's own doc for which direction
    // each vector used to fail on. The SAME vectors are pinned in quotationMeta.test.js's own "F2"
    // describe block, against `parseSizeText` -- same inputs, same result, on both sides. ─────────
    @Test
    void parseTwoDimensions_sharedGrammarVectors_unicodeWhitespaceNormalisation() {
        assertParsed("30 x 60", "30", "60", null); // NBSP
        assertParsed("30x60 cm", "30", "60", DealQuotationLines.SizeUnit.CM);
        assertParsed("30x60　cm", "30", "60", DealQuotationLines.SizeUnit.CM); // ideographic space
        assertParsed("30 x 60", "30", "60", null); // thin space
        assertParsed("30 x 60", "30", "60", null); // narrow no-break space
        assertParsed("﻿30x60", "30", "60", null); // BOM / ZWNBSP
        assertParsed("30x60 ", "30", "60", null); // line separator
        // The OTHER direction: a bare control byte, which String.trim() has ALWAYS stripped (it
        // strips anything <= U+0020) -- this was never broken on the Java side, but is pinned here
        // so the two vector tables stay byte-for-byte identical.
        assertParsed("30x60", "30", "60", null);
        assertParsed("30x60", "30", "60", null);
    }

    // ── F1 (BLOCKER, 2026-09-16 review): catastrophic regex backtracking (ReDoS). Both timing
    // vectors below must stay well under 50ms; a regression in either the grammar fix or the length
    // guard alone would blow one of them up (the first is short enough to bypass the guard entirely
    // and exercises the grammar fix in isolation; the second is the reviewer's own reported shape,
    // which also exercises MAX_SIZE_TEXT_LENGTH). Measured on this exact (pre-fix) code: 253 ms /
    // 5,026 ms at 128 / 248 chars (the reviewer's own run measured 367 ms / 12,238 ms); the fixed
    // grammar/guard bring both down to ~0-1 ms. ─────────────────────────────────────────────────
    @Test
    void parseTwoDimensions_pathologicalWhitespace_underTheLengthGuard_doesNotCatastrophicallyBacktrack() {
        String attack = "30" + " ".repeat(18) + "x60" + " ".repeat(18) + "x1" + " ".repeat(18) + "!";
        assertThat(attack.length()).isLessThanOrEqualTo(64);
        long start = System.nanoTime();
        DealQuotationLines.ParsedSize result = DealQuotationLines.parseTwoDimensions(attack);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result).as("a trailing '!' must never match").isNull();
        assertThat(elapsedMs).as("must not catastrophically backtrack on pathological whitespace")
            .isLessThan(50);
    }

    @Test
    void parseTwoDimensions_pathologicalWhitespace_248chars_doesNotCatastrophicallyBacktrack() {
        // The reviewer's own reported shape (measured pre-fix at 12,238ms on the reviewer's code,
        // 5,026ms on this machine).
        String attack = "30" + " ".repeat(80) + "x60" + " ".repeat(80) + "x1" + " ".repeat(80) + "!";
        assertThat(attack).hasSize(248);
        long start = System.nanoTime();
        DealQuotationLines.ParsedSize result = DealQuotationLines.parseTwoDimensions(attack);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result).isNull();
        assertThat(elapsedMs).as("must not catastrophically backtrack on pathological whitespace")
            .isLessThan(50);
    }

    @Test
    void parseTwoDimensions_longerThanTheLengthGuard_isRejectedOutright_evenForAGenuineShape() {
        // 65 chars of otherwise-perfectly-parseable text (the padding is INTERNAL, between the first
        // number and the separator, so String.trim() cannot shrink it away) -- proves the length
        // guard itself, not just the regex fix, independent of any pathological shape.
        String genuineButLong = "30" + " ".repeat(60) + "x60";
        assertThat(genuineButLong.length()).isEqualTo(65);
        assertUnparsed(genuineButLong);
    }

    // ── The owner's exact 2026-09-16 bug: typed "30x60x1" against a linked 60x60cm catalogue tile
    // must print the rep's typed text, not the catalogue's -- the old TWO_DIMENSIONS grammar had no
    // third-dimension allowance, so this typed text failed to parse and sizeLine silently kept
    // printing the catalogue's 60x60. ────────────────────────────────────────────────────────────
    @Test
    void sizeLine_typedSizeWithThirdDimension_differsFromCatalogue_printsTypedText_theExact20260916Bug() {
        // F2 fix (2026-09-16 review): "30x60x1" carries no unit token on either of its first two
        // numbers (the third, thickness, is ignored and never carries a unit either) -- so this
        // now prints "30 cm x 60 cm" rather than the ambiguous verbatim "30x60x1".
        assertThat(DealQuotationLines.sizeLine("30x60x1", new BigDecimal("9"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("ขนาด 30 cm x 60 cm x 9 mm (ขนาดโดยประมาณ)")
            .doesNotContain("60 cm x 60 cm");
    }

    // ── Explicit-unit-wins (2026-09-16 fix): an explicit unit token checks ONLY that reading, never
    // falls back to also trying the other unit the way the unspecified-unit rule always has. ─────
    @Test
    void sizeLine_explicitMmUnit_differsFromCatalogueEvenThoughCmReadingWouldMatch() {
        // Catalogue is 60x60cm (600x600mm). Typed "600x600mm" is an explicit mm reading that DOES
        // match the catalogue's own mm figures, so this still prints the catalogue (unaffected).
        assertThat(DealQuotationLines.sizeLine("600x600mm", new BigDecimal("9"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("ขนาด 60 cm x 60 cm x 9 mm (ขนาดโดยประมาณ)");
        // Catalogue is 60x60cm (600x600mm). Typed "300x600mm" is explicitly millimetres -- a
        // genuinely different (smaller) tile -- and must print as typed even though nothing here
        // would ever coincidentally equal the catalogue's cm reading either.
        assertThat(DealQuotationLines.sizeLine("300x600mm", new BigDecimal("9"),
            new BigDecimal("600"), new BigDecimal("600")))
            .isEqualTo("ขนาด 300x600mm x 9 mm (ขนาดโดยประมาณ)")
            .doesNotContain("60 cm x 60 cm");
    }

    /**
     * F5 (test quality, 2026-09-16 review) — the test above passes even with unit resolution
     * DELETED from {@link DealQuotationLines#matchesCatalogFaceSize} (mutation-checked: forcing
     * {@code typed.unit()} to {@code null} there leaves both of its assertions green), because
     * neither of its two vectors ever lands on a DIFFERENT reading depending on whether the unit is
     * honoured — both "600x600mm" and "300x600mm" happen to agree with the (wrong) "check both
     * readings" answer too. This vector does not: catalogue 30cm x 60cm (300mm x 600mm), typed
     * "60x30mm" — an explicit MM reading that matches NEITHER the catalogue's real mm figures
     * (60,30 vs 300,600) NOR its cm figures directly (60,30 vs 30,60), but DOES match the catalogue's
     * cm figures order-swapped (60==60, 30==30) if the explicit unit is ignored and both readings are
     * checked regardless. Mutation-checked the same way: forcing {@code typed.unit()} to
     * {@code null} turns this test red (it wrongly starts printing the catalogue's "30 cm x 60 cm");
     * restoring the real unit resolution turns it green again — see the PR body for the run.
     */
    @Test
    void sizeLine_explicitMmUnit_pinsUnitResolution_mutationDiscriminating() {
        assertThat(DealQuotationLines.sizeLine("60x30mm", new BigDecimal("9"),
            new BigDecimal("300"), new BigDecimal("600")))
            .isEqualTo("ขนาด 60x30mm x 9 mm (ขนาดโดยประมาณ)")
            .doesNotContain("30 cm x 60 cm");
    }

    // ── FALLBACK: no catalogue dimensions -- print the rep's typed text EXACTLY as typed ────────

    /**
     * F2 fix (2026-09-16 review): a typed size with NO unit and NO catalogue to cross-check
     * against now prints in centimetres -- {@code "200 cm x 300 cm"} -- matching the field's own
     * label ("ขนาด (ซม.)"), rather than the old ambiguous verbatim "200x300". This is a deliberate
     * narrowing of the older "no splitting, no unit-guessing" caution this test used to document:
     * that caution was about NOT overriding a catalogue that says otherwise (the 200x300mm
     * regression test above, where a catalogue link disambiguates and still wins) -- it was never
     * meant to leave a genuinely unit-less, catalogue-less size printing as bare digits with no
     * unit at all, which is its own production bug (F2: "ขนาด 14.8x14.8 x 7 mm" read as 14.8mm).
     */
    @Test
    void sizeLine_noCatalogueDimensions_unitlessTypedText_printsInCentimetres() {
        assertThat(DealQuotationLines.sizeLine("200x300", new BigDecimal("9"), null, null))
            .isEqualTo("ขนาด 200 cm x 300 cm x 9 mm (ขนาดโดยประมาณ)");
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
    }

    /** F2 fix (2026-09-16 review): "60x120x5" DOES parse (the third number is the ignored
     * thickness, per the shared grammar's own third-dimension allowance -- see
     * {@code parseTwoDimensions_sharedGrammarVectors_decimalCommaAndThirdDimensionAndTrailingText}),
     * and carries no unit on either of its first two numbers -- so it now prints in centimetres,
     * same as any other unit-less typed pair, rather than staying bare digits with no unit. */
    @Test
    void sizeLine_noCatalogueDimensions_thirdDimensionUnitless_printsInCentimetres() {
        assertThat(DealQuotationLines.sizeLine("60x120x5", new BigDecimal("9"), null, null))
            .isEqualTo("ขนาด 60 cm x 120 cm x 9 mm (ขนาดโดยประมาณ)");
    }

    /** Only ONE catalogue dimension present (e.g. a corrupt row) is treated the same as neither --
     * a half-known geometry is not enough to print a face size from, so this falls back too.
     * F2 fix (2026-09-16 review): the fallback itself now prints an unparsed, unit-less pair in
     * centimetres ("60 cm x 120 cm") rather than the ambiguous verbatim "60x120". */
    @Test
    void sizeLine_onlyOneCatalogueDimension_fallsBackToTypedText() {
        assertThat(DealQuotationLines.sizeLine("60x120", new BigDecimal("9"),
            new BigDecimal("600"), null))
            .isEqualTo("ขนาด 60 cm x 120 cm x 9 mm (ขนาดโดยประมาณ)");
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

    /** Review fix F2 (2026-09-16): same as {@link #calculationLine_noneWastage_omitsWastagePhrase}
     * but with a STALE non-zero {@code wastageValue} left over from a previous PERCENT/PIECES mode
     * -- nothing clears it when the rep switches {@code wastageMode} to NONE. Byte-identical to the
     * null-value case, since {@code hasWastage} must be gated on the MODE, not merely a non-zero
     * value -- {@link WastageCalculator} never reads {@code wastageValue} in NONE mode either. */
    @Test
    void calculationLine_noneWastageWithStaleNonZeroValue_isUnaffected() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), new BigDecimal("2"),
            20, WastageCalculator.WASTAGE_MODE_NONE, new BigDecimal("7"), 24, 12);

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
     * not "+ เผื่อ 0%".
     *
     * <p>Wording-scan fix 1 (2026-09-17): this fixture's box math (piecesFinal 20, piecesPerBox 4)
     * happens to already be a whole number of boxes (20 / 4 = 5 exactly), so it now ALSO falls
     * under fix 1's "box rounding was a no-op" rule -- the old docstring's "byte-identical to what
     * a PERCENT line with real wastage would print around it" claim no longer holds in general (a
     * real-wastage line with the SAME already-whole box numbers changes the same way). Updated to
     * the new shape; {@link #calculationLine_zeroPiecesWastage_omitsTheWastagePhraseEntirely} right
     * below deliberately keeps its own piecesPerBox=12 NOT dividing piecesFinal=100, so that test
     * still pins the ORIGINAL rounding-phrase shape this docstring used to describe. */
    @Test
    void calculationLine_zeroPercentWastage_omitsTheWastagePhraseEntirely() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), new BigDecimal("2"),
            20, WastageCalculator.WASTAGE_MODE_PERCENT, BigDecimal.ZERO, 20, 4);

        assertThat(line).isEqualTo("(พื้นที่ 10 ตร.ม.ๆละ 2 แผ่น รวม 20 แผ่น = 5 กล่อง) (บรรจุ 4 แผ่น/กล่อง)");
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

    // ── Wording-scan fix 1 (2026-09-17): a whole-box count must NOT be echoed back with the
    // misleading "และปัดขึ้นเต็มกล่อง" ("rounded up") phrase when box rounding never rounded
    // anything -- 21 real production items print this shape. Pinned here directly against
    // #calculationLine (not only through #tilePrint) with the owner-approved wording verbatim. ──

    /** No wastage, already whole boxes: {@code (จำนวน 20 แผ่น = 2 กล่อง) (บรรจุ 10 แผ่น/กล่อง)}. */
    @Test
    void calculationLine_noWastage_alreadyWholeBoxes_printsBoxCount_noRoundingPhrase() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            20, WastageCalculator.WASTAGE_MODE_NONE, null, 20, 10);

        assertThat(line).isEqualTo("(จำนวน 20 แผ่น = 2 กล่อง) (บรรจุ 10 แผ่น/กล่อง)");
        // Wrong-way-round: the old rounding phrase and the pure "= 20 แผ่น" echo must not appear.
        assertThat(line).doesNotContain("ปัดขึ้น").doesNotContain("= 20 แผ่น");
    }

    /** Wastage that lands exactly on a whole box count:
     * {@code (จำนวน 18 แผ่น + เผื่อ 2 แผ่น = 20 แผ่น = 2 กล่อง) (บรรจุ 10 แผ่น/กล่อง)} -- the
     * wastage-adjusted total STILL prints (it is new information over quantityPart's own "18
     * แผ่น"), but the rounding phrase does not (box rounding itself was a no-op). */
    @Test
    void calculationLine_wastageLandsOnWholeBoxes_printsTotalAndBoxCount_noRoundingPhrase() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            18, WastageCalculator.WASTAGE_MODE_PIECES, new BigDecimal("2"), 20, 10);

        assertThat(line).isEqualTo("(จำนวน 18 แผ่น + เผื่อ 2 แผ่น = 20 แผ่น = 2 กล่อง) (บรรจุ 10 แผ่น/กล่อง)");
        assertThat(line).doesNotContain("ปัดขึ้น");
    }

    /** AREA mode, already whole boxes: {@code (พื้นที่ … รวม 26 แผ่น = 2 กล่อง) (บรรจุ 13
     * แผ่น/กล่อง)}. */
    @Test
    void calculationLine_areaMode_alreadyWholeBoxes_printsBoxCount_noRoundingPhrase() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("9.35"), new BigDecimal("2.78"),
            26, WastageCalculator.WASTAGE_MODE_NONE, null, 26, 13);

        assertThat(line).isEqualTo("(พื้นที่ 9.35 ตร.ม.ๆละ 2.78 แผ่น รวม 26 แผ่น = 2 กล่อง) (บรรจุ 13 แผ่น/กล่อง)");
        assertThat(line).doesNotContain("ปัดขึ้น");
    }

    /** Rounding that DID change the count stays EXACTLY as it always printed -- the wrong-way-round
     * twin of the three tests above, so this fix cannot be mistaken for "never print the rounding
     * phrase again". */
    @Test
    void calculationLine_roundingThatChangedTheCount_isUnaffectedByFix1() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            32, WastageCalculator.WASTAGE_MODE_NONE, null, 40, 10);

        assertThat(line).isEqualTo("(จำนวน 32 แผ่น และปัดขึ้นเต็มกล่อง = 40 แผ่น) (บรรจุ 10 แผ่น/กล่อง)");
        // The box COUNT ("= N กล่อง") is NOT printed on this branch -- only the box-area-per-sqm
        // variant and the "no rounding happened" branches print that; the trailing "(บรรจุ ...
        // แผ่น/กล่อง)" tail is a different thing (the box SIZE, always printed whenever hasBox) and
        // is expected here.
        assertThat(line).doesNotContain("= 4 กล่อง");
    }

    /** English mirror of the first test above. */
    @Test
    void english_noWastage_alreadyWholeBoxes_printsBoxCount_noRoundingPhrase() {
        String line = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            20, WastageCalculator.WASTAGE_MODE_NONE, null, 20, 10);

        assertThat(line).isEqualTo("(Quantity 20 pcs = 2 boxes) (10 pcs/box)");
        assertThat(line).doesNotContain("rounded").doesNotContain("= 20 pcs");
    }

    /** PIECES quantity mode also gets commas on its own count when it exceeds 999.
     * F1 fix (2026-09-16 review): no box, no wastage -- no trailing "=" echo any more. */
    @Test
    void calculationLine_piecesQuantityMode_largeCount_getsThousandsCommas() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            1500, WastageCalculator.WASTAGE_MODE_NONE, null, 1500, null);

        assertThat(line).isEqualTo("(จำนวน 1,500 แผ่น)");
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

    /**
     * F4 (LOW, 2026-09-16 review) — {@code SIZE_HAS_UNIT} used to only know {@code ซม}/{@code ซม.}/
     * {@code มม}/{@code มม.}, not the middle-dot Thai forms {@code ซ.ม.}/{@code ม.ม.} that
     * {@code TWO_DIMENSIONS}'s grammar already accepted, so a millimetre size typed with a dot
     * (e.g. "30x60 ม.ม.") was read as having NO unit and got " cm." appended on top of it — a
     * millimetre size printed as centimetres on a customer document. Fixed by sharing one
     * {@code UNIT_ALTERNATION} constant between the two patterns so they cannot drift apart again.
     */
    @Test
    void descriptionLine_recognisesTheMiddleDotThaiUnitForms_doesNotDoubleUpTheUnit() {
        assertThat(DealQuotationLines.descriptionLine("Reverso Cement", "Grigio", null, null, "30x60 ม.ม.", null))
            .isEqualTo("กระเบื้อง รุ่น Reverso Cement สี Grigio ขนาด 30x60 ม.ม.")
            .doesNotContain("cm.");
        assertThat(DealQuotationLines.descriptionLine("Reverso Cement", "Grigio", null, null, "30x60 ซ.ม.", null))
            .isEqualTo("กระเบื้อง รุ่น Reverso Cement สี Grigio ขนาด 30x60 ซ.ม.")
            .doesNotContain("cm.");
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
        // F1 fix (2026-09-16 review): no box, no wastage -- no trailing "=" echo any more.
        assertThat(DealQuotationLines.calculationLine(EN, WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            1500, WastageCalculator.WASTAGE_MODE_NONE, null, 1500, null))
            .isEqualTo("(Quantity 1,500 pcs)");
    }

    /** Review fix F2 (2026-09-16): the English mirror of
     * {@link #calculationLine_noneWastageWithStaleNonZeroValue_isUnaffected} -- a STALE non-zero
     * wastageValue under wastageMode=NONE must not resurrect the allowance clause. F1 fix
     * (2026-09-16 review): no box either, so no trailing "=" echo any more. */
    @Test
    void english_noneWastageWithStaleNonZeroValue_isUnaffected() {
        assertThat(DealQuotationLines.calculationLine(EN, WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            1500, WastageCalculator.WASTAGE_MODE_NONE, new BigDecimal("9"), 1500, null))
            .isEqualTo("(Quantity 1,500 pcs)");
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
        // F2 fix (2026-09-16 review): "60x60" carries no unit and no catalogue link, so this now
        // prints in centimetres, same as the Thai FALLBACK's own F2 fix.
        assertThat(DealQuotationLines.sizeLine(EN, "60x60", new BigDecimal("10"), null, null))
            .isEqualTo("Size 60 cm x 60 cm x 10 mm (approx.)");
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
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("300"), new BigDecimal("0.061"),
            new BigDecimal("16.39"), 4917, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("5"), 5180, 20,
            259, new BigDecimal("0.61"), new BigDecimal("5180"), "SQM", new BigDecimal("10.00"));
        assertThat(p.calculationLine()).isEqualTo(
            "(Area 300 sqm @ 16.39 pcs/sqm = 4,917 pcs + 5% allowance, rounded up to full boxes = 5,180 pcs = 259 boxes)");
        assertThat(p.subLine()).isEqualTo("(1 box = 20 pcs = 0.61 sqm)");
        assertThat(p.quantity()).isEqualByComparingTo("157.99");
        assertThat(p.unit()).isEqualTo("SQM");
    }

    @Test
    void tilePrint_englishPerSqm_piecesMode_QN6900933Row1_andSingularBox() {
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(EN, "SPECIAL_SQM",
            WastageCalculator.QUANTITY_MODE_PIECES, null, new BigDecimal("0.6"), null, 3360,
            WastageCalculator.WASTAGE_MODE_NONE, null, 3360, 28, 120, new BigDecimal("0.6"),
            new BigDecimal("3360"), "SQM", new BigDecimal("64.00"));
        // Wording-scan fix 1 (2026-09-17): 3,360 / 28 = 120 exactly -- box rounding was a no-op, so
        // the misleading "rounded up to full boxes" phrase and its pure "= 3,360 pcs" echo are both
        // dropped; the box count (already stated by this branch's own "= 259/120 boxes" tail either
        // way -- see #tilePrint_englishPerSqm_areaMode above, whose OWN box numbers genuinely did
        // round and so is untouched) is all that remains.
        assertThat(p.calculationLine()).isEqualTo("(Quantity 3,360 pcs = 120 boxes)");
        assertThat(p.subLine()).isEqualTo("(1 box = 28 pcs = 0.6 sqm)");
        assertThat(p.quantity()).isEqualByComparingTo("72.00");
        assertThat(DealQuotationLines.tilePrint(EN, "SPECIAL_SQM", WastageCalculator.QUANTITY_MODE_PIECES, null,
                new BigDecimal("0.6"), null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 28, 28, 1,
                new BigDecimal("0.6"), BigDecimal.TEN, "SQM", BigDecimal.ONE).calculationLine())
            .isEqualTo("(Quantity 10 pcs, rounded up to full boxes = 28 pcs = 1 box)");
    }

    /** English NET/DIRECT_NET: pieces, PCS, the ordinary English line — sqmPerBox is ignored. */
    @Test
    void tilePrint_englishOtherModes_printPiecesEvenWithBoxData() {
        for (String mode : new String[] {"NET", "DIRECT_NET"}) {
            DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(EN, mode,
                WastageCalculator.QUANTITY_MODE_PIECES, null, new BigDecimal("0.6"), null, 3360,
                WastageCalculator.WASTAGE_MODE_NONE, null, 3360, 28, 120, new BigDecimal("0.6"),
                new BigDecimal("3360"), "แผ่น", null);
            assertThat(p.quantity()).isEqualByComparingTo("3360");
            assertThat(p.unit()).isEqualTo("PCS");
            assertThat(p.subLine()).isNull();
            // Wording-scan fix 1 (2026-09-17): 3,360 / 28 = 120 exactly -- see the matching comment
            // on tilePrint_englishPerSqm_piecesMode_QN6900933Row1_andSingularBox above. This branch
            // (the plain calculationLine path, not the per-sqm box-area one) has no "= N boxes" tail
            // of its own before this fix, so the box count is genuinely NEW information here, not
            // merely an echo dropped from an existing one.
            assertThat(p.calculationLine()).isEqualTo("(Quantity 3,360 pcs = 120 boxes) (28 pcs/box)");
        }
    }

    /** ⚠️ Thai SPECIAL_SQM with a sqm/box on the row: byte-identical to the legacy methods. */
    @Test
    void tilePrint_thaiSpecialSqm_isByteIdenticalToTheLegacyPrint_evenWithBoxData() {
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(TH, "SPECIAL_SQM",
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("87"), new BigDecimal("0.36"),
            new BigDecimal("2.78"), 242, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 268, 4, 67,
            new BigDecimal("1.44"), new BigDecimal("268"), "แผ่น", new BigDecimal("1350"));
        assertThat(p.calculationLine()).isEqualTo(DealQuotationLines.calculationLine(WastageCalculator.QUANTITY_MODE_AREA,
            new BigDecimal("87"), new BigDecimal("2.78"), 242, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 268, 4));
        assertThat(p.quantity()).isEqualByComparingTo("268");
        assertThat(p.unit()).isEqualTo("แผ่น");
        assertThat(p.subLine()).isEqualTo(DealQuotationLines.specialPriceLine(new BigDecimal("1350")));
    }

    /** A hand-edited English per-sqm row with NO box area AND no resolved sqmPerPiece either prints
     * pieces — it never invents an area with nothing to derive one from. */
    @Test
    void tilePrint_englishPerSqmWithoutBoxDataOrSqmPerPiece_printsPieces() {
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(EN, "SPECIAL_SQM",
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, null, 10, WastageCalculator.WASTAGE_MODE_NONE, null,
            10, null, null, null, BigDecimal.TEN, "SQM", new BigDecimal("64"));
        assertThat(p.quantity()).isEqualByComparingTo("10");
        assertThat(p.subLine()).isNull();
    }

    // ── Option B (owner decision, 2026-09-16): ตร.ม./กล่อง OPTIONAL for English per-sqm ─────────

    /** Blank box area, no แผ่น/กล่อง either: quantity = round2(piecesFinal × sqmPerPiece), the
     * ordinary English piece-based calculation line (no box wording at all since hasBox is false),
     * and no box sub-line. */
    @Test
    void tilePrint_englishPerSqm_noBoxArea_noPiecesPerBox_derivesSqmFromPieces() {
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(EN, "SPECIAL_SQM",
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), new BigDecimal("0.36"),
            new BigDecimal("2.78"), 28, WastageCalculator.WASTAGE_MODE_NONE, null, 28, null, null, null,
            new BigDecimal("28"), "SQM", new BigDecimal("64.00"));
        // F1 fix (2026-09-16 review): the default branch used to echo "= N pcs" unconditionally,
        // even with no box and no wastage, so AREA mode's own "...= 28 pcs" got printed twice --
        // "(Area 10 sqm @ 2.78 pcs/sqm = 28 pcs = 28 pcs)". Now the trailing echo is gated on
        // hasBox || hasWastage, so AREA mode's own "= 28 pcs" (genuine area->pieces information)
        // prints once, not twice.
        assertThat(p.calculationLine()).isEqualTo("(Area 10 sqm @ 2.78 pcs/sqm = 28 pcs)");
        assertThat(p.quantity()).isEqualByComparingTo("10.08"); // 28 × 0.36
        assertThat(p.unit()).isEqualTo("SQM");
        assertThat(p.subLine()).isNull();
    }

    /** Blank box area, แผ่น/กล่อง FILLED, full-box rounding: quantity derives from the box-rounded
     * piecesFinal (not the pre-rounding piece count), and the ordinary English box-rounding wording
     * prints (no "= N boxes" tail — that only exists on the box-AREA branch). */
    @Test
    void tilePrint_englishPerSqm_noBoxArea_piecesPerBoxFilled_roundsToFullBox() {
        // boxes = 3 (floor(30 / 10)) — WastageCalculator#calculate always sets boxes from
        // piecesPerBox alone, regardless of whether a box AREA is present; realistic stored data.
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(EN, "SPECIAL_SQM",
            WastageCalculator.QUANTITY_MODE_PIECES, null, new BigDecimal("0.36"), null, 25,
            WastageCalculator.WASTAGE_MODE_NONE, null, 30, 10, 3, null, new BigDecimal("30"), "SQM",
            new BigDecimal("64.00"), true);
        assertThat(p.calculationLine()).isEqualTo("(Quantity 25 pcs, rounded up to full boxes = 30 pcs) (10 pcs/box)");
        assertThat(p.quantity()).isEqualByComparingTo("10.80"); // 30 × 0.36
        assertThat(p.unit()).isEqualTo("SQM");
        assertThat(p.subLine()).isNull();
    }

    /** Blank box area, แผ่น/กล่อง filled, loose pieces (roundToFullBox=false): quantity derives from
     * the EXACT wastage-adjusted piece count (unrounded), and the loose-pieces phrasing prints —
     * proving English per-sqm can now reach that branch, unlike before Option B. */
    @Test
    void tilePrint_englishPerSqm_noBoxArea_loosePieces_derivesSqmFromExactPieces() {
        // boxes = 3 (floor(32 / 10)) — same realism note as the full-box test above.
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(EN, "SPECIAL_SQM",
            WastageCalculator.QUANTITY_MODE_PIECES, null, new BigDecimal("0.36"), null, 32,
            WastageCalculator.WASTAGE_MODE_NONE, null, 32, 10, 3, null, new BigDecimal("32"), "SQM",
            new BigDecimal("64.00"), false);
        assertThat(p.calculationLine()).isEqualTo("(Quantity 32 pcs = 3 boxes + 2 pcs) (10 pcs/box)");
        assertThat(p.quantity()).isEqualByComparingTo("11.52"); // 32 × 0.36, NOT the box-rounded 40 × 0.36
        assertThat(p.unit()).isEqualTo("SQM");
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

    /** Review fix F2 (2026-09-16): a STALE non-zero {@code wastageValue} under
     * {@code wastageMode=NONE} used to make {@code hasWastage} read true here (it was gated on the
     * value alone, not the mode), which printed the duplicate intermediate clause this method's own
     * Javadoc says it exists to avoid: {@code (จำนวน 32 แผ่น = 32 แผ่น = 3 กล่อง + 2 แผ่น)} instead
     * of the byte-identical-to-no-wastage line below. */
    @Test
    void calculationLine_thaiLoosePieces_noneModeWithStaleNonZeroValue_doesNotDuplicateIntermediateClause() {
        String line = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE,
            new BigDecimal("12"), 32, 10, false);
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

    /** full boxes = 0, no wastage: fewer pieces than one box prints NO trailing "=" clause at all
     * -- the box/loose split would just be "= 7 แผ่น", a pure echo of quantityPart's own count.
     * F1 fix (2026-09-16 review): this used to print "(จำนวน 7 แผ่น = 7 แผ่น) (บรรจุ 10 แผ่น/กล่อง)",
     * the exact production-bug shape ("(จำนวน 15 แผ่น = 15 แผ่น) (บรรจุ 26 แผ่น/กล่อง)") this pass
     * fixes. */
    @Test
    void calculationLine_thaiLoosePieces_fullBoxesIsZero_printsPiecesOnlyNoBoxWord() {
        String line = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 7, WastageCalculator.WASTAGE_MODE_NONE, null,
            7, 10, false);
        assertThat(line).isEqualTo("(จำนวน 7 แผ่น) (บรรจุ 10 แผ่น/กล่อง)");
    }

    /** full boxes = 0, WITH wastage: the intermediate "= N แผ่น" clause DOES print, because
     * wastage moved piecesFinal away from quantityPart's own count -- it is not a duplicate. */
    @Test
    void calculationLine_thaiLoosePieces_fullBoxesIsZero_withWastage_printsTheAdjustedTotal() {
        String line = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 15, WastageCalculator.WASTAGE_MODE_PERCENT,
            new BigDecimal("5"), 16, 26, false);
        assertThat(line).isEqualTo("(จำนวน 15 แผ่น + เผื่อ 5% = 16 แผ่น) (บรรจุ 26 แผ่น/กล่อง)");
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
     * no-box default line (no "และปัดขึ้นเต็มกล่อง" wording either way, since hasBox is false).
     * F1 fix (2026-09-16 review): this line used to read "(จำนวน 32 แผ่น = 32 แผ่น)" -- a pure echo
     * of quantityPart's own count, with no box and no wastage to justify restating it. */
    @Test
    void calculationLine_roundToFullBoxFalse_noPiecesPerBox_isUnaffected() {
        String withFalse = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, null, false);
        String withTrue = DealQuotationLines.calculationLine(TH,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, null, true);
        assertThat(withFalse).isEqualTo(withTrue).isEqualTo("(จำนวน 32 แผ่น)");
    }

    // ── English mirrors of the six Thai shapes above ──────────────────────────────────────────

    @Test
    void calculationLine_englishLoosePieces_noWastage_skipsRedundantIntermediateClause() {
        String line = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, 10, false);
        assertThat(line).isEqualTo("(Quantity 32 pcs = 3 boxes + 2 pcs) (10 pcs/box)");
    }

    /** Review fix F2 (2026-09-16): the English mirror of
     * {@link #calculationLine_thaiLoosePieces_noneModeWithStaleNonZeroValue_doesNotDuplicateIntermediateClause}. */
    @Test
    void calculationLine_englishLoosePieces_noneModeWithStaleNonZeroValue_doesNotDuplicateIntermediateClause() {
        String line = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE,
            new BigDecimal("12"), 32, 10, false);
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

    /** F1 fix (2026-09-16 review): the English mirror of {@link
     * #calculationLine_thaiLoosePieces_fullBoxesIsZero_printsPiecesOnlyNoBoxWord} -- this used to
     * print "(Quantity 7 pcs = 7 pcs) (10 pcs/box)", the English shape of the same production
     * duplicate bug. */
    @Test
    void calculationLine_englishLoosePieces_fullBoxesIsZero_printsPcsOnlyNoBoxWord() {
        String line = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 7, WastageCalculator.WASTAGE_MODE_NONE, null,
            7, 10, false);
        assertThat(line).isEqualTo("(Quantity 7 pcs) (10 pcs/box)");
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
        // 0 full boxes, exactly 1 loose piece, no wastage: F1 fix -- no trailing "=" clause at
        // all (it would just echo "1 pcs"/"1 pc", the same count quantityPart already states).
        assertThat(DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 1, WastageCalculator.WASTAGE_MODE_NONE, null,
            1, 10, false))
            .isEqualTo("(Quantity 1 pcs) (10 pcs/box)");
    }

    @Test
    void calculationLine_englishLoosePieces_piecesWastage_printsBothClauses() {
        String line = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 100, WastageCalculator.WASTAGE_MODE_PIECES,
            new BigDecimal("15"), 115, 12, false);
        assertThat(line).isEqualTo("(Quantity 100 pcs + 15 pcs allowance = 115 pcs = 9 boxes + 7 pcs) (12 pcs/box)");
    }

    /** F1 fix (2026-09-16 review): the English default branch used to always echo "= N pcs"
     * regardless of hasBox/hasWastage, printing "(Quantity 32 pcs = 32 pcs)" here -- a pure echo,
     * the exact English-side duplicate the production bug report also names ("(Quantity 3,360 pcs
     * = 3,360 pcs)"). Now gated the same way as the Thai branch: {@code hasBox || hasWastage}. */
    @Test
    void calculationLine_englishRoundToFullBoxFalse_noPiecesPerBox_isUnaffected() {
        String withFalse = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, null, false);
        String withTrue = DealQuotationLines.calculationLine(EN,
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, null, true);
        assertThat(withFalse).isEqualTo(withTrue).isEqualTo("(Quantity 32 pcs)");
    }

    // ── F1 (2026-09-16 review) — wrong-way-round: the duplicate must NEVER appear ───────────────
    // These are the mutation-check fixtures for the F1 fix: reintroducing the unconditional
    // trailing "= N" clause (either branch) must turn these red. Written as an explicit
    // doesNotContain, independent in STYLE from the exact-equality assertions above, so a future
    // change to the surrounding wording (which would need the exact-match tests updated anyway)
    // still has an assertion that specifically targets the duplicate shape.

    @Test
    void calculationLine_thai_neverPrintsAPieceCountTwiceInARow() {
        // Every "no box, no wastage" and "loose pieces, zero full boxes, no wastage" shape.
        assertThat(DealQuotationLines.calculationLine(TH, WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            32, WastageCalculator.WASTAGE_MODE_NONE, null, 32, null)).doesNotContain("= 32 แผ่น)");
        assertThat(DealQuotationLines.calculationLine(TH, WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            15, WastageCalculator.WASTAGE_MODE_NONE, null, 15, 26, false)).doesNotContain("= 15 แผ่น)");
    }

    @Test
    void calculationLine_english_neverPrintsAPieceCountTwiceInARow() {
        assertThat(DealQuotationLines.calculationLine(EN, WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            32, WastageCalculator.WASTAGE_MODE_NONE, null, 32, null)).doesNotContain("= 32 pcs)");
        assertThat(DealQuotationLines.calculationLine(EN, WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            7, WastageCalculator.WASTAGE_MODE_NONE, null, 7, 10, false)).doesNotContain("= 7 pcs)");
    }

    /** {@code tilePrint} threads roundToFullBox into the ordinary (non-per-sqm) branch — proven
     * once at that layer so a regression there (e.g. a dropped argument) is caught even if
     * {@code calculationLine} itself stays correct. */
    @Test
    void tilePrint_threadsRoundToFullBoxIntoTheOrdinaryBranch() {
        DealQuotationLines.TilePrint p = DealQuotationLines.tilePrint(TH, "NET",
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            32, 10, 3, null, new BigDecimal("32"), "แผ่น", null, false);
        assertThat(p.calculationLine()).isEqualTo("(จำนวน 32 แผ่น = 3 กล่อง + 2 แผ่น) (บรรจุ 10 แผ่น/กล่อง)");
    }

    /** The 16-argument {@code tilePrint} overload (every test above this section) defaults
     * roundToFullBox true and is unaffected by this feature. */
    @Test
    void tilePrint_sixteenArgOverload_defaultsRoundToFullBoxTrue() {
        DealQuotationLines.TilePrint viaShort = DealQuotationLines.tilePrint(TH, "NET",
            WastageCalculator.QUANTITY_MODE_PIECES, null, null, null, 32, WastageCalculator.WASTAGE_MODE_NONE, null,
            40, 10, 4, null, new BigDecimal("40"), "แผ่น", null);
        assertThat(viaShort.calculationLine()).isEqualTo("(จำนวน 32 แผ่น และปัดขึ้นเต็มกล่อง = 40 แผ่น) (บรรจุ 10 แผ่น/กล่อง)");
    }
}
