package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderer;

/**
 * Quotation v3b (owner, 2026-09-11 overnight) — the ENGLISH quotation, form F-SM-008 (01),
 * through the REAL {@link DealQuotationRenderAdapter} and the REAL {@link QuotationRenderer}.
 *
 * <p>⚠️ <b>What this file can and cannot prove.</b> There is no F-SM-008 template FILE in this
 * repository — {@code templates/} holds only the Thai {@code quotation_template.xls} — so the
 * English document is produced by driving that same workbook with English labels and an English
 * footer, modelled on the owner's PDF samples. These tests therefore pin <b>our</b> English
 * document against <b>the differences the spec lists</b>. They are not, and cannot be, evidence of
 * fidelity to a form nobody has as a file.
 *
 * <p>One test per difference, plus the regression that actually matters:
 * {@link #thaiDocument_stillPrintsItsOwnLabelsTotalsAndBuddhistEraDate()}.
 *
 * <p>Column map (see {@code QuotationRenderer#fillItemMainRow}): 0 = seq/Items, 1 = description,
 * 2 = qty, 3 = unit, 4 = unit price, 5-6 = discount, 7 = net, 8 = amount. Items start at row 9.
 */
class DealQuotationEnglishFormTest {
    private static final int TITLE_ROW = 6;
    // The footer rows a SINGLE-PAGE render leaves at their native positions (post the renderer's
    // own #insertLine3ContinuationRow +1 shift and its -7 v2 remark compaction, both of which the
    // direct-deal path always applies): 38 - 7 = 31, and so on.
    private static final int V2_FOOTER_SHIFT = -7;
    private static final int SUBTOTAL_ROW = 38 + V2_FOOTER_SHIFT;
    private static final int VAT_ROW = 39 + V2_FOOTER_SHIFT;
    private static final int TOTAL_ROW = 40 + V2_FOOTER_SHIFT;
    private static final int LABELS_ROW = 44 + V2_FOOTER_SHIFT;
    private static final int NAMES_ROW = 45 + V2_FOOTER_SHIFT;
    private static final int DATES_ROW = 46 + V2_FOOTER_SHIFT;
    private static final int FORM_TAG_ROW = 47 + V2_FOOTER_SHIFT;
    private static final int VALUE_COL = 8;

    // ── header block ───────────────────────────────────────────────────────────────────────

    @Test
    void header_printsQUOTATIONAndTheThreeAsterisks() throws Exception {
        Sheet sheet = renderEnglish();
        assertThat(str(sheet, 0, 7).strip()).isEqualTo("QUOTATION");
        assertThat(str(sheet, 0, 7)).doesNotContain("ใบเสนอราคา");
        // "***" sits under the title at I2 and is COMMON to both forms — it is already in the
        // template, so nothing writes it. Pinned anyway: the spec lists it as an English-form
        // feature, and a future edit that blanked it would silently drop it from both documents.
        assertThat(str(sheet, 1, VALUE_COL)).isEqualTo("***");
    }

    @Test
    void header_printsDeptRefAndDCoRatherThanTheThaiLabels() throws Exception {
        Sheet sheet = renderEnglish();
        assertThat(str(sheet, 2, 7)).isEqualTo("Dept.");    // H3 was ฝ่าย
        assertThat(str(sheet, 3, 7)).isEqualTo("Ref.");     // H4 was เลขที่อ้างอิง
        assertThat(str(sheet, 4, 7)).isEqualTo("D.Co.");    // H5 was หน่วยงาน
        // The VALUES beside them are unchanged — same columns, same model fields.
        assertThat(str(sheet, 2, VALUE_COL)).isEqualTo("P003");
        assertThat(str(sheet, 3, VALUE_COL)).isEqualTo("QT-2026-0001");
        assertThat(str(sheet, 4, VALUE_COL)).isEqualTo("D002");
    }

    /**
     * ⚠️ The single easiest thing to get wrong: an English month NAME and a <b>CE</b> year, where
     * the Thai form prints the Buddhist era. 2026 CE is 2569 BE, so a copy of the Thai formatter
     * would print "September 8, 2569" and this test is what catches it.
     */
    @Test
    void header_printsAnEnglishMonthNameAndACommonEraYear() throws Exception {
        Sheet sheet = renderEnglish();
        assertThat(str(sheet, 3, 1)).isEqualTo("September 8, 2026");
        assertThat(str(sheet, 3, 1)).doesNotContain("2569");
        assertThat(str(sheet, 3, 0)).isEqualTo("Date"); // A4 was วันที่
    }

    @Test
    void header_printsAttnAddressAndE() throws Exception {
        Sheet sheet = renderEnglish();
        assertThat(str(sheet, 4, 0)).isEqualTo("Attn :");                 // A5 was เรียน
        assertThat(str(sheet, 4, 1)).isEqualTo("Ms. Aisha Rahman   /   Blue Lagoon Resort Pvt Ltd");
        assertThat(str(sheet, 5, 1))
            .contains("Address : 12 Boduthakurufaanu Magu, Male, Maldives")
            .contains("E : aisha@bluelagoon.mv")
            .contains("Tel. +960 330 1234");
        // No Thai left in the customer block.
        assertThat(str(sheet, 4, 1)).doesNotContain("คุณ");
        assertThat(str(sheet, 5, 1)).doesNotContain("โทร.");
    }

    // ── the eight column headings ──────────────────────────────────────────────────────────

    @Test
    void columnTitles_areTheEightEnglishHeadingsWithTheCurrencyOnAmount() throws Exception {
        Sheet sheet = renderEnglish();
        assertThat(str(sheet, TITLE_ROW, 0)).isEqualTo("Items");
        // Column B carries NO heading on the Thai template — the English form labels it, so this
        // is the one column title that is WRITTEN rather than replaced.
        assertThat(str(sheet, TITLE_ROW, 1)).isEqualTo("Description & Conditions");
        assertThat(str(sheet, TITLE_ROW, 2)).isEqualTo("Qty");
        assertThat(str(sheet, TITLE_ROW, 3)).isEqualTo("Unit");
        assertThat(str(sheet, TITLE_ROW, 4)).isEqualTo("Unit price");
        assertThat(str(sheet, TITLE_ROW, 5)).isEqualTo("Disc.");  // F:G is one merged pair
        assertThat(str(sheet, TITLE_ROW, 7)).isEqualTo("Net price");
        assertThat(str(sheet, TITLE_ROW, 8)).isEqualTo("Amount (USD)");
        assertThat(str(sheet, TITLE_ROW, 8)).doesNotContain("บาท");
    }

    // ── totals ─────────────────────────────────────────────────────────────────────────────

    /** {@code Grand Total (USD)} and NOTHING else — no subtotal row, and above all NO VAT row. */
    @Test
    void totals_printGrandTotalOnlyWithNoVatRowAndNoSubtotalRow() throws Exception {
        Sheet sheet = renderEnglish();

        // The subtotal row: label and value both gone.
        assertThat(blank(sheet, SUBTOTAL_ROW, 7)).isTrue();
        assertThat(blank(sheet, SUBTOTAL_ROW, VALUE_COL)).isTrue();
        // The VAT row: the ภาษีมูลค่าเพิ่ม label (E, merged E:G), the 0.07 rate (H) and the
        // computed VAT (I). All three — a surviving bare "0.07" on an English page is the exact
        // failure this asserts against.
        for (int c = 4; c <= VALUE_COL; c++) {
            assertThat(blank(sheet, VAT_ROW, c))
                .as("VAT row column %d must be blank on an English document", c).isTrue();
        }

        assertThat(str(sheet, TOTAL_ROW, 4)).isEqualTo("Grand Total (USD)");
        // 10 pieces x 100.00 = 1,000.00 and NOT 1,070.00 — the grand total IS the subtotal here.
        assertThat(sheet.getRow(TOTAL_ROW).getCell(VALUE_COL).getNumericCellValue()).isEqualTo(1000.00);
    }

    /** No VAT anywhere on the sheet, not merely on the two rows the previous test names. */
    @Test
    void totals_leaveNoVatTextAnywhereOnTheEnglishSheet() throws Exception {
        assertThat(allText(renderEnglish()))
            .noneMatch(t -> t.contains("ภาษีมูลค่าเพิ่ม"))
            .noneMatch(t -> t.contains("รวมเป็นเงิน"))
            .noneMatch(t -> t.contains("หมายเหตุ"))
            .noneMatch(t -> t.toUpperCase(java.util.Locale.ROOT).contains("VAT"));
    }

    // ── owner ruling 2026-09-13 (2): no blank bordered cells above Grand Total ────────────

    /**
     * The subtotal and VAT rows are emptied on an English document — and must not PRINT either. The
     * template's own borders on those cells survived the clearing, which printed two empty boxes
     * in column I between the table and Grand Total. Asserted on the single-page layout AND the
     * flowing (multi-page) one, where the footer block has been relocated.
     */
    @Test
    void totals_theEmptiedSubtotalAndVatRowsCarryNoBorderAndDoNotPrint_inBothLayouts() throws Exception {
        for (int extraTiles : new int[] {0, 14}) {
            Sheet sheet = render(realLinesQuotation(WastageCalculator.DOCUMENT_LANGUAGE_EN, extraTiles));
            int total = rowContaining(sheet, 4, "Grand Total (USD)");
            if (extraTiles == 0) {
                assertThat(total).as("single-page layout keeps the native total row").isEqualTo(TOTAL_ROW);
            } else {
                assertThat(total).as("flowing layout relocates the footer").isGreaterThan(TOTAL_ROW);
            }
            for (int r : new int[] {total - 2, total - 1}) {
                Row row = sheet.getRow(r);
                assertThat(row.getZeroHeight()).as("layout %d, row %d must not print", extraTiles, r).isTrue();
                for (int c = 0; c <= VALUE_COL; c++) {
                    assertThat(blank(sheet, r, c)).as("row %d col %d blank", r, c).isTrue();
                    Cell cell = row.getCell(c);
                    if (cell == null) continue;
                    var style = cell.getCellStyle();
                    assertThat(List.of(style.getBorderTop(), style.getBorderRight(), style.getBorderBottom(),
                            style.getBorderLeft()))
                        .as("layout %d, row %d col %d must carry no border", extraTiles, r, c)
                        .containsOnly(org.apache.poi.ss.usermodel.BorderStyle.NONE);
                }
            }
            // Grand Total sits DIRECTLY under the table box: the last visible row above it is the
            // box's own closing row (remark line 8, bottom rule across A..I).
            int above = total - 3;
            assertThat(sheet.getRow(above).getZeroHeight()).isFalse();
            assertThat(sheet.getRow(above).getCell(VALUE_COL).getCellStyle().getBorderBottom())
                .as("layout %d: the table box closes on the row directly above Grand Total", extraTiles)
                .isNotEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);
            assertThat(sheet.getRow(total).getZeroHeight()).isFalse();
            assertThat(sheet.getRow(total).getCell(VALUE_COL).getNumericCellValue()).isPositive();
        }
    }

    /** Wrong-way-round: the THAI totals rows still print, with their borders. */
    @Test
    void thaiDocument_subtotalAndVatRowsStillPrintWithTheirBorders() throws Exception {
        Sheet sheet = render(realLinesQuotation(WastageCalculator.DOCUMENT_LANGUAGE_TH, 0));
        for (int r : new int[] {SUBTOTAL_ROW, VAT_ROW}) {
            assertThat(sheet.getRow(r).getZeroHeight()).isFalse();
            assertThat(sheet.getRow(r).getCell(VALUE_COL).getCellStyle().getBorderRight())
                .isNotEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);
        }
        assertThat(str(sheet, SUBTOTAL_ROW, 7)).isEqualTo("รวมเป็นเงิน");
    }

    // ── owner ruling 2026-09-13 (1)(4): an English item table carries no Thai ──────────────

    private static final java.util.regex.Pattern THAI = java.util.regex.Pattern.compile("[\\u0E00-\\u0E7F]");

    /**
     * Every string cell from the first item row through the Grand Total row of an English render
     * is free of Thai script — tiles with and without thickness, AREA and PIECES, a DIRECT_NET row
     * whose net differs from its list price (the discount word), a freight row, and a ส่วนลดพิเศษ row
     * whose STORED description is the Thai write-time composition. Lines come from the real
     * DealQuotationLines, units and the adjustment text from the same read-time helpers the
     * repository mapping uses.
     */
    @Test
    void englishItemAndTotalsZone_containsNoThaiCharacter_inBothLayouts() throws Exception {
        for (int extraTiles : new int[] {0, 14}) {
            Sheet sheet = render(realLinesQuotation(WastageCalculator.DOCUMENT_LANGUAGE_EN, extraTiles));
            int total = rowContaining(sheet, 4, "Grand Total (USD)");
            List<String> offending = new ArrayList<>();
            for (int r = 9; r <= total; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                for (int c = 0; c <= VALUE_COL; c++) {
                    Cell cell = row.getCell(c);
                    if (cell != null && cell.getCellType() == CellType.STRING
                        && THAI.matcher(cell.getStringCellValue()).find()) {
                        offending.add("r" + r + "c" + c + ": " + cell.getStringCellValue());
                    }
                }
            }
            assertThat(offending).as("layout %d", extraTiles).isEmpty();
            // Positive landmarks too, so an EMPTY item zone cannot pass the scan above. The renderer
            // word-wraps a long line across rows at whitespace, so column B is re-joined first.
            assertThat(joinedColumn(sheet, 1, 9, total))
                .contains("Tile Model BIOARCH Color BARGE GRIGIA Finish ONDULATO No.APGBBK15")
                .contains("Size 20 cm x 30.5 cm x 9 mm (approx.)")
                .contains("Tile Model Reverso Cement Color Grigio Finish Matt Size 60x60 cm. No.BS66R13GP")
                .contains("(Area 300 sqm @ 16.39 pcs/sqm = 4,917 pcs + 5% allowance, rounded up to full boxes = 5,180 pcs) (20 pcs/box)")
                .contains("Special discount 3% for orders placed by July 31, 2026");
            assertThat(str(sheet, 9, 3)).isEqualTo("PCS");
            assertThat(str(sheet, 9, 6)).isEqualTo("Special");
            // Only the English per-sqm row gets the 2dp quantity format; a pieces row keeps the template's.
            assertThat(new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.US)
                .formatCellValue(sheet.getRow(9).getCell(2))).isEqualTo("5,180");
            // The paginated layout's page counter is English too.
            assertThat(THAI.matcher(sheet.getFooter().getCenter()).find())
                .as("layout %d footer: %s", extraTiles, sheet.getFooter().getCenter()).isFalse();
        }
    }

    /** Wrong-way-round: the same items on a Thai document still print their Thai lines, unit and word. */
    @Test
    void thaiDocument_itemTableKeepsItsThaiLinesUnitAndDiscountWord() throws Exception {
        Sheet sheet = render(realLinesQuotation(WastageCalculator.DOCUMENT_LANGUAGE_TH, 0));
        assertThat(joinedColumn(sheet, 1, 9, TOTAL_ROW))
            .contains("กระเบื้อง รุ่น BIOARCH สี BARGE GRIGIA ผิว ONDULATO No.APGBBK15")
            .contains("ขนาด 20 cm x 30.5 cm x 9 mm (ขนาดโดยประมาณ)")
            .contains("ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569");
        assertThat(str(sheet, 9, 3)).isEqualTo("แผ่น");
        assertThat(str(sheet, 9, 6)).isEqualTo("พิเศษ");
        assertThat(render(realLinesQuotation(WastageCalculator.DOCUMENT_LANGUAGE_TH, 14)).getFooter().getCenter())
            .isEqualTo("หน้า &P/&N");
        assertThat(allText(sheet)).doesNotContain("PCS", "Special");
    }

    /** A tile DTO with NO unit prints the language's own tile unit. */
    @Test
    void adapter_aTileWithNoUnitPrintsPCSInEnglishAndPhaenInThai() {
        DealQuotationItemDto noUnit = tileLines(1, WastageCalculator.DOCUMENT_LANGUAGE_EN, new BigDecimal("9"), null);
        assertThat(DealQuotationRenderAdapter.toRenderModel(
                withItems(englishQuotation(), List.of(noUnit)), null, null).items().get(0).unit())
            .isEqualTo("PCS");
        assertThat(DealQuotationRenderAdapter.toRenderModel(
                withItems(thaiQuotation(), List.of(noUnit)), null, null).items().get(0).unit())
            .isEqualTo("แผ่น");
    }

    // ── remarks ────────────────────────────────────────────────────────────────────────────

    @Test
    void remarks_areEnglishAndCarryTheComputedDepositLeadTimeAndValidity() throws Exception {
        Sheet sheet = renderEnglish();
        // FOOTER_START (22) is the "หมายเหตุ"/"Remarks" LABEL row; the eight lines are 23..30.
        assertThat(str(sheet, 22, 1)).isEqualTo("Remarks");
        List<String> remarks = new ArrayList<>();
        for (int r = 23; r <= 30; r++) {
            remarks.add(str(sheet, r, 1));
        }
        assertThat(remarks).hasSize(8);
        assertThat(remarks.get(0)).startsWith("1.The quantities above are as received on 01/09/2026");
        assertThat(remarks.get(1)).contains("A deposit of 30%").contains("30 days credit");
        assertThat(remarks.get(2)).isEqualTo("3.Delivery : item 1 approximately 30-45 days");
        assertThat(remarks.get(3)).startsWith("4.Payment by telegraphic transfer");
        assertThat(remarks.get(4)).isEqualTo("5.Price validity : 30 days from the date of this quotation.");
        assertThat(remarks).noneMatch(line -> line.contains("บริษัทฯ"));
    }

    /**
     * ⚠️ The remark date is CE too. The Thai block prints 01/09/<b>2569</b> for the same offer
     * date; getting this one wrong is invisible next to a correct header date, because it is
     * buried mid-sentence.
     */
    @Test
    void remarks_offerDateIsCommonEra() throws Exception {
        assertThat(str(renderEnglish(), 23, 1)).contains("01/09/2026").doesNotContain("2569");
    }

    // ── signature block ────────────────────────────────────────────────────────────────────

    @Test
    void signatureBlock_usesTheEnglishLabels() throws Exception {
        String labels = str(renderEnglish(), LABELS_ROW, 0);
        assertThat(labels)
            .contains("Printed by").contains("Quoted by")
            .contains("Approved by").contains("Ordered by");
        assertThat(labels)
            .doesNotContain("ผู้พิมพ์").doesNotContain("พนักงานขาย")
            .doesNotContain("ผู้จัดการฝ่ายขาย").doesNotContain("ผู้สั่งซื้อ");
        // The four labels stay in the SAME order as the Thai form's, so the approver's signature
        // image still anchors on slot 3.
        assertThat(labels.indexOf("Printed by")).isLessThan(labels.indexOf("Quoted by"));
        assertThat(labels.indexOf("Quoted by")).isLessThan(labels.indexOf("Approved by"));
        assertThat(labels.indexOf("Approved by")).isLessThan(labels.indexOf("Ordered by"));
    }

    @Test
    void signatureBlock_printsTheEnglishEmployeeNames() throws Exception {
        String names = str(renderEnglish(), NAMES_ROW, 0);
        assertThat(names)
            .contains("(Jintana Hanmontree)")
            .contains("(Jennet Longsakul)")
            .contains("(Rarm Itarat)")
            // ผู้สั่งซื้อ is the CUSTOMER's contact — there is no English name stored for a contact
            // anywhere, so the snapshot prints as typed.
            .contains("(Ms. Aisha Rahman)");
        assertThat(names).doesNotContain("จินตนา").doesNotContain("เจนเนตร").doesNotContain("ราม");
    }

    /**
     * The fallback that keeps the document usable while HR's {@code first_name_en} coverage is
     * incomplete: an employee with no English name prints their THAI name on the English form,
     * rather than an empty slot. Asserted wrong-way-round — the failure this guards against is a
     * BLANK signature line on a customer-facing document, not a Thai one.
     */
    @Test
    void signatureBlock_fallsBackToTheThaiNameWhenTheEmployeeHasNoEnglishOne() throws Exception {
        DealQuotationDto quotation = englishQuotation(q -> withNoEnglishNames(q));
        Sheet sheet = render(quotation);
        String names = str(sheet, NAMES_ROW, 0);
        assertThat(names).contains("(จินตนา หาญมนตรี)").contains("(เจนเนตร หลงสกุล)").contains("(ราม อิฐรัตน์)");
        // The placeholder is what a blank slot looks like — it must NOT appear for these three.
        assertThat(names).doesNotContain("(..........................)");
    }

    /** The signature dates are CE on the English form, and the empty ผู้สั่งซื้อ slot reads
     * "Date ..../..../....", not "วันที่ ..../..../....". */
    @Test
    void signatureBlock_datesAreCommonEraAndThePlaceholderIsEnglish() throws Exception {
        String dates = str(renderEnglish(), DATES_ROW, 0);
        assertThat(dates).contains("Date 8/9/2026");
        assertThat(dates).contains("Date ........./........./.........");
        assertThat(dates).doesNotContain("วันที่").doesNotContain("2569");
    }

    @Test
    void orderLine_isEnglish() throws Exception {
        Sheet sheet = renderEnglish();
        assertThat(str(sheet, 42 + V2_FOOTER_SHIFT, 2))
            .isEqualTo("Confirmed to order at the prices and conditions above");
    }

    // ── form tag ───────────────────────────────────────────────────────────────────────────

    @Test
    void formTag_readsFSM008() throws Exception {
        assertThat(str(renderEnglish(), FORM_TAG_ROW, VALUE_COL)).isEqualTo("F-SM-008 (01)");
    }

    // ── the regression that matters ────────────────────────────────────────────────────────

    /**
     * A TH document is identical whether or not {@code documentLanguage} is supplied: every cell is
     * compared, one by one, against the SAME model built through the pre-v3b twelve-argument
     * constructor — a model that has never heard of the field.
     *
     * <p>⚠️ <b>Read the name literally: this proves the FIELD is inert on the Thai path, NOT that
     * the Thai document is unchanged by this branch.</b> Both sides of the comparison run through
     * the SAME (post-change) renderer, so a defect that broke the Thai render outright would break
     * both sides equally and this test would stay green — CLAUDE.md's "mock MIRRORS a backend
     * computation" failure shape, in test form. Confirmed by mutation check: forcing
     * {@code english = true} for every document leaves this test GREEN and reds
     * {@link #thaiDocument_stillPrintsItsOwnLabelsTotalsAndBuddhistEraDate()} instead. That sibling
     * — plus the ~50 pre-existing Thai renderer/adapter/HTML-fidelity tests this branch does not
     * touch — is the real Thai regression evidence; this one covers the narrower question of
     * whether adding the field changed anything.
     */
    @Test
    void thaiDocument_isUnaffectedByWhetherTheLanguageFieldIsSuppliedAtAll() throws Exception {
        Sheet withLanguage = render(thaiQuotation());
        Sheet withoutLanguage = renderLegacyModel(
            DealQuotationRenderAdapter.toRenderModel(thaiQuotation(), null, null));

        assertThat(withLanguage.getLastRowNum()).isEqualTo(withoutLanguage.getLastRowNum());
        for (int r = 0; r <= withLanguage.getLastRowNum(); r++) {
            for (int c = 0; c <= 9; c++) {
                assertThat(cellText(withLanguage, r, c))
                    .as("row %d col %d", r, c)
                    .isEqualTo(cellText(withoutLanguage, r, c));
            }
        }
    }

    /**
     * ⚠️ <b>The Thai regression that actually bites.</b> The whole-sheet equality test above cannot
     * catch a defect that breaks BOTH of its sides; this one names the Thai landmarks positively,
     * so a Thai document that started printing English labels, lost its VAT row or gained a form
     * tag fails here. Mutation-checked: forcing every render into English mode reds exactly this
     * test (and its real-DB twin), and nothing else in the file.
     */
    @Test
    void thaiDocument_stillPrintsItsOwnLabelsTotalsAndBuddhistEraDate() throws Exception {
        Sheet sheet = render(thaiQuotation());
        assertThat(str(sheet, 0, 7)).contains("ใบเสนอราคา");
        assertThat(str(sheet, 2, 7)).isEqualTo("ฝ่าย");
        assertThat(str(sheet, 3, 0)).isEqualTo("วันที่");
        assertThat(str(sheet, 3, 1)).isEqualTo("8 กันยายน 2569");
        assertThat(str(sheet, 4, 0)).isEqualTo("เรียน");
        assertThat(str(sheet, TITLE_ROW, 0)).isEqualTo("ลำดับ");
        assertThat(str(sheet, TITLE_ROW, 8)).contains("เป็นเงิน");
        // The three totals rows are all still there, VAT included.
        assertThat(str(sheet, SUBTOTAL_ROW, 7)).isEqualTo("รวมเป็นเงิน");
        assertThat(str(sheet, VAT_ROW, 4)).isEqualTo("ภาษีมูลค่าเพิ่ม");
        assertThat(sheet.getRow(VAT_ROW).getCell(7).getNumericCellValue()).isEqualTo(0.07);
        assertThat(str(sheet, TOTAL_ROW, 7)).isEqualTo("รวมเป็นเงินทั้งสิ้น");
        // And the form tag stays BLANKED on the Thai path — a pre-existing decision the English
        // work must not reach into. (The English path writes F-SM-008 (01); see #formTag_readsFSM008.)
        assertThat(blank(sheet, FORM_TAG_ROW, VALUE_COL)).isTrue();
        assertThat(str(sheet, LABELS_ROW, 0)).contains("ผู้พิมพ์").contains("ผู้สั่งซื้อ");
    }

    // ── adapter-level: the currency and language reach the model ───────────────────────────

    @Test
    void adapter_carriesTheLanguageAndCurrencyOntoTheRenderModel() {
        QuotationRenderModel english = DealQuotationRenderAdapter.toRenderModel(englishQuotation(), null, null);
        assertThat(english.isEnglish()).isTrue();
        assertThat(english.currencyCode()).isEqualTo("USD");

        QuotationRenderModel thai = DealQuotationRenderAdapter.toRenderModel(thaiQuotation(), null, null);
        assertThat(thai.isEnglish()).isFalse();
        assertThat(thai.currencyCode()).isEqualTo("THB");
    }

    /** A model that never heard of the field (every legacy caller) is a Thai/THB document. */
    @Test
    void renderModel_defaultsToThaiAndBahtOnThePreV3bConstructor() {
        QuotationRenderModel legacy = new QuotationRenderModel(
            LocalDate.of(2026, 9, 8), "QT-1", null, null, "", "", "", null,
            List.of(), List.of(), null, false);
        assertThat(legacy.isEnglish()).isFalse();
        assertThat(legacy.currencyCode()).isEqualTo("THB");
    }



    @Test
    void remarks_carryTheOwnersBankBlockUnnumbered_whenItIsConfigured() throws Exception {
        Sheet sheet = renderEnglishWithBank(BANK_BLOCK);
        List<String> remarks = new ArrayList<>();
        for (int r = 23; r <= 30; r++) {
            remarks.add(str(sheet, r, 1));
        }
        // Still EXACTLY 8. REMARK_HEAD_ROWS has 8 slots and fewer falls back to a legacy 3-line
        // layout that leaves the template's Thai continuation rows visible on an English page —
        // so the bank block's three lines are paid for by merging two pairs of remarks, not by
        // overflowing the box.
        assertThat(remarks).hasSize(8);
        assertThat(remarks.get(2)).isEqualTo(BANK_BLOCK.get(0));
        assertThat(remarks.get(3)).isEqualTo(BANK_BLOCK.get(1));
        assertThat(remarks.get(4)).isEqualTo(BANK_BLOCK.get(2));
        // Unnumbered, exactly as hers are.
        assertThat(remarks.get(2)).doesNotStartWith("4.");
        assertThat(remarks.get(3)).doesNotStartWith("5.");
        // The placeholder must be GONE — printing both would offer two different payment routes.
        assertThat(remarks).noneMatch(line -> line.contains("proforma invoice"));
        // And the computed remarks survive the merge rather than being dropped to make room.
        assertThat(remarks.get(5)).isEqualTo("3.Delivery : item 1 approximately 30-45 days");
        // Numbering runs on 3, 4, 5 after the block — no gap at 4 for a customer to read as a
        // missing term (review F2).
        assertThat(remarks.get(6)).startsWith("4.Price validity : 30 days").contains("ISO and TIS tolerances");
        assertThat(remarks.get(7)).startsWith("5.Colours").contains("not returnable or exchangeable");
    }

    @Test
    void remarks_fallBackToTheProformaLine_whenTheBankBlockIsMissingOrHalfFilled() throws Exception {
        // Half a set of wire instructions is worse than none: a customer could act on a beneficiary
        // name with no account number. Anything short of all three lines prints the honest line.
        for (List<String> partial : List.of(
                List.<String>of(),
                List.of(BANK_BLOCK.get(0)),
                List.of(BANK_BLOCK.get(0), BANK_BLOCK.get(1)),
                List.of(BANK_BLOCK.get(0), "", BANK_BLOCK.get(2)),
                // Whitespace-only is blank too. Review F4: an isBlank→isEmpty refactor stayed green.
                List.of(BANK_BLOCK.get(0), "   ", BANK_BLOCK.get(2)),
                // A null line — Arrays.asList, since List.of refuses nulls.
                Arrays.asList(BANK_BLOCK.get(0), null, BANK_BLOCK.get(2)),
                // FOUR lines is not three. Review F5: `size() >= 3` stayed green, and the renderer
                // silently drops a ninth remark row rather than failing.
                List.of(BANK_BLOCK.get(0), BANK_BLOCK.get(1), BANK_BLOCK.get(2), "extra"))) {
            Sheet sheet = renderEnglishWithBank(partial);
            List<String> remarks = new ArrayList<>();
            for (int r = 23; r <= 30; r++) {
                remarks.add(str(sheet, r, 1));
            }
            assertThat(remarks).as("size for %s", partial).hasSize(8);
            assertThat(remarks.get(3)).as("fallback for %s", partial)
                .startsWith("4.Payment by telegraphic transfer");
            assertThat(remarks).as("no partial block for %s", partial)
                .noneMatch(line -> line.contains("003-92-1222-6"));
        }
    }

    @Test
    void everyEnglishRemarkLine_fitsItsNonWrappingCell_inBothLayouts() throws Exception {
        // Each remark is ONE merged B..I cell that NEVER wraps, so an over-long line is CUT at the
        // border rather than wrapped. Two merged lines at 145 and 151 characters printed
        // "…within ISO and TIS toler" and "…not returnab" on the PDF while every content assertion
        // here stayed green — the text in the cell was intact, it simply did not fit. Measured by
        // review: the pre-existing 130-character line 1 fits in both LibreOffice and Chromium, and
        // ~139 does not. Character count is a proxy for rendered width; for Latin text in this font
        // it tracks the measurement closely, but tighten the cap if a line near it ever clips.
        for (List<String> block : List.of(BANK_BLOCK, List.<String>of())) {
            Sheet sheet = renderEnglishWithBank(block);
            for (int r = 23; r <= 30; r++) {
                String line = str(sheet, r, 1);
                assertThat(line.length()).as("row %d (%s block): %s", r,
                    block.isEmpty() ? "no" : "with", line).isLessThanOrEqualTo(130);
            }
        }
    }

    // ── V178 — remark 7's second กำหนดยืนยันราคา variant (an exact date, not a day count) ────
    //
    // Owner ruling 2026-09-14 (superseding an earlier draft of this rule): the DATE variant is
    // gated on {@link DealQuotationRenderAdapter#hasSpecialPricing} — it prints ONLY on a
    // quotation that actually has special pricing (a discount, a ราคาพิเศษ, or a ส่วนลดพิเศษ
    // adjustment row). A quotation with no discount anywhere must NOT print it, even in DATE mode.

    /** DIRECT_NET with net {@code <} list price (rule b) — the exact case the owner's example
     * wording describes. Bank layout: DATE mode replaces "4.Price validity : N days …". */
    @Test
    void remarks_dateModeValidity_directNetWithDiscount_printsSpecialPriceLine_bankLayout() throws Exception {
        LocalDate until = LocalDate.of(2026, 10, 31);
        DealQuotationDto q = withPriceModeAndValidity(withItems(englishQuotation(), List.of(discountedDirectNetTile())),
            WastageCalculator.PRICE_MODE_DIRECT_NET, WastageCalculator.VALIDITY_MODE_DATE, until);
        Sheet sheet = renderLegacyModel(DealQuotationRenderAdapter.toRenderModel(q, null, null, BANK_BLOCK));
        List<String> remarks = new ArrayList<>();
        for (int r = 23; r <= 30; r++) remarks.add(str(sheet, r, 1));
        assertThat(remarks.get(6)).isEqualTo("4.Special price for orders with deposit paid by 31/10/2026; "
            + "sizes may vary slightly within ISO and TIS tolerances.");
    }

    /** Same fixture, no-bank layout: DATE mode replaces "5.Price validity : N days …". */
    @Test
    void remarks_dateModeValidity_directNetWithDiscount_printsSpecialPriceLine_noBankLayout() throws Exception {
        LocalDate until = LocalDate.of(2026, 10, 31);
        DealQuotationDto q = withPriceModeAndValidity(withItems(englishQuotation(), List.of(discountedDirectNetTile())),
            WastageCalculator.PRICE_MODE_DIRECT_NET, WastageCalculator.VALIDITY_MODE_DATE, until);
        Sheet sheet = renderLegacyModel(DealQuotationRenderAdapter.toRenderModel(q, null, null, List.of()));
        List<String> remarks = new ArrayList<>();
        for (int r = 23; r <= 30; r++) remarks.add(str(sheet, r, 1));
        assertThat(remarks.get(4)).isEqualTo("5.Special price for orders with deposit paid by 31/10/2026.");
    }

    /** DAYS-mode output is byte-identical to before this change, in both layouts — regardless of
     * special pricing (the gate only ever affects the DATE branch). */
    @Test
    void remarks_daysModeValidity_isUnchanged_inBothLayouts() throws Exception {
        DealQuotationDto q = withValidity(englishQuotation(), WastageCalculator.VALIDITY_MODE_DAYS, null);
        Sheet bank = renderLegacyModel(DealQuotationRenderAdapter.toRenderModel(q, null, null, BANK_BLOCK));
        Sheet noBank = renderLegacyModel(DealQuotationRenderAdapter.toRenderModel(q, null, null, List.of()));
        List<String> bankRemarks = new ArrayList<>();
        List<String> noBankRemarks = new ArrayList<>();
        for (int r = 23; r <= 30; r++) {
            bankRemarks.add(str(bank, r, 1));
            noBankRemarks.add(str(noBank, r, 1));
        }
        assertThat(bankRemarks.get(6)).startsWith("4.Price validity : 30 days").contains("ISO and TIS tolerances");
        assertThat(noBankRemarks.get(4)).isEqualTo("5.Price validity : 30 days from the date of this quotation.");
    }

    /** Same 130-character non-wrapping-cell guard as above, now also over the DATE-mode variant
     * in both layouts — the fixed prose plus a formatted date must still fit. */
    @Test
    void everyEnglishRemarkLine_fitsItsNonWrappingCell_inBothLayouts_dateMode() throws Exception {
        LocalDate until = LocalDate.of(2026, 10, 31);
        DealQuotationDto q = withPriceModeAndValidity(withItems(englishQuotation(), List.of(discountedDirectNetTile())),
            WastageCalculator.PRICE_MODE_DIRECT_NET, WastageCalculator.VALIDITY_MODE_DATE, until);
        for (List<String> block : List.of(BANK_BLOCK, List.<String>of())) {
            Sheet sheet = renderLegacyModel(DealQuotationRenderAdapter.toRenderModel(q, null, null, block));
            List<String> remarks = new ArrayList<>();
            for (int r = 23; r <= 30; r++) {
                String line = str(sheet, r, 1);
                assertThat(line.length()).as("row %d (%s block): %s", r,
                    block.isEmpty() ? "no" : "with", line).isLessThanOrEqualTo(130);
                remarks.add(line);
            }
            // Opus review (2026-09-14): the length-only loop above passes for a blank row too --
            // pin the actual 8-real-lines guarantee here as well, the same way
            // #everyEnglishRemarkLine_fitsItsNonWrappingCell_inBothLayouts's DAYS-mode sibling
            // does not need to (that one is covered by #remarks_daysModeValidity_isUnchanged, but
            // this DATE-mode fixture had no equivalent non-blank assertion until now).
            assertThat(remarks).as("%s block", block.isEmpty() ? "no" : "with")
                .hasSize(8).allMatch(l -> !l.isBlank());
        }
    }

    /** Thai twin — DIRECT_NET with net {@code <} list price, remark 7's DATE variant, the owner's
     * wording verbatim. */
    @Test
    void remarks_dateModeValidity_directNetWithDiscount_printsSpecialPriceLine_thai() throws Exception {
        LocalDate until = LocalDate.of(2026, 9, 30);
        DealQuotationDto q = withPriceModeAndValidity(withItems(thaiQuotation(), List.of(discountedDirectNetTile())),
            WastageCalculator.PRICE_MODE_DIRECT_NET, WastageCalculator.VALIDITY_MODE_DATE, until);
        Sheet sheet = render(q);
        // Thai never carries a bank block, so remark 7 is always index 6 -> row 23 + 6 = 29.
        // Owner wording, verbatim: exactly ONE ASCII space between "วันที่" and the date.
        assertThat(str(sheet, 29, 1))
            .isEqualTo("7.ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำภายในวันที่ 30/09/2569");
    }

    /** SPECIAL_SQM (rule a) — at least one TILE row is ALL rule (a) requires; {@link #tile()}'s
     * own discountPct (null) is irrelevant. DATE mode prints the DATE line. */
    @Test
    void remarks_dateModeValidity_specialSqm_printsSpecialPriceLine() throws Exception {
        LocalDate until = LocalDate.of(2026, 9, 30);
        DealQuotationDto q = withPriceModeAndValidity(thaiQuotation(),
            WastageCalculator.PRICE_MODE_SPECIAL_SQM, WastageCalculator.VALIDITY_MODE_DATE, until);
        Sheet sheet = render(q);
        assertThat(str(sheet, 29, 1))
            .isEqualTo("7.ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำภายในวันที่ 30/09/2569");
    }

    /** NET, every row at 0% discount, no adjustment row -> NO special pricing. Even a DATE-mode
     * row (only reachable here by constructing the DTO directly, bypassing
     * {@code DealQuotationService}'s own create/update refusal) must fall back to the ordinary
     * days line — the render adapter's OWN gate is what this pins, independent of the service. */
    @Test
    void remarks_dateModeValidity_netWithZeroDiscountAndNoAdjustment_fallsBackToDaysLine() throws Exception {
        LocalDate until = LocalDate.of(2026, 9, 30);
        DealQuotationDto q = withPriceModeAndValidity(withItems(thaiQuotation(), List.of(netZeroDiscountTile(1))),
            WastageCalculator.PRICE_MODE_NET, WastageCalculator.VALIDITY_MODE_DATE, until);
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(q)).isFalse();
        Sheet sheet = render(q);
        assertThat(str(sheet, 29, 1)).isEqualTo("7.กำหนดยืนยันราคา 30 วัน นับจากวันที่ในใบเสนอราคา");
    }

    /** NET at 0% PLUS an ADJUSTMENT row (rule e) -> DOES have special pricing; the DATE line
     * renders even though every priced TILE row itself carries no discount. */
    @Test
    void remarks_dateModeValidity_netZeroDiscountPlusAdjustmentRow_printsSpecialPriceLine() throws Exception {
        LocalDate until = LocalDate.of(2026, 9, 30);
        DealQuotationDto q = withPriceModeAndValidity(
            withItems(thaiQuotation(), List.of(netZeroDiscountTile(1), adjustmentRow(2))),
            WastageCalculator.PRICE_MODE_NET, WastageCalculator.VALIDITY_MODE_DATE, until);
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(q)).isTrue();
        Sheet sheet = render(q);
        assertThat(str(sheet, 29, 1))
            .isEqualTo("7.ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำภายในวันที่ 30/09/2569");
    }

    // ── owner feedback (2026-09-14) x V178: DATE-mode validity AND no lead time TOGETHER ──────
    //
    // The DATE variant (gated on hasSpecialPricing) and the lead-time-line drop (gated on
    // hasAnyLeadTime) are independent decisions computed from different data -- an ADJUSTMENT row
    // satisfies rule (e) of hasSpecialPricing on its own, and carries no lead-time fields at all --
    // DealQuotationService#buildAdjustmentItem always nulls originCountry/leadTimeMinDays/
    // leadTimeMaxDays regardless of what the input carries (unlike a PLAIN row's OPTIONAL lead time
    // since D1 -- see #plainRowWithLeadTime) -- so an ADJUSTMENT-only document exercises DATE mode
    // with hasAnyLeadTime=false. Both must compose correctly: the
    // line-drop-and-renumber must not disturb the DATE text, and the DATE text must survive being
    // renumbered down by one.

    /** Thai: the DATE line was remark 7 (index 6); after the drop it renumbers to remark 6
     * (index 5), text otherwise unchanged. */
    @Test
    void remarks_dateModeValidityAndNoLeadTime_compose_thai() throws Exception {
        LocalDate until = LocalDate.of(2026, 9, 30);
        // V182: a TILE row (with no lead time of its own) alongside the ADJUSTMENT row keeps this a
        // TILE document — see #tileNoLeadTime's own Javadoc — so it still exercises the tile remark
        // set's DATE-mode/no-lead-time composition rather than becoming a non-tile document.
        DealQuotationDto q = withPriceModeAndValidity(
            withItems(thaiQuotation(), List.of(tileNoLeadTime(1), adjustmentRow(2))),
            WastageCalculator.PRICE_MODE_NET, WastageCalculator.VALIDITY_MODE_DATE, until);
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(q)).isTrue();
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(q, null, null);
        assertThat(model.remarkLines()).hasSize(7).noneMatch(l -> l.contains("ระยะเวลานำเข้า"));
        assertThat(model.remarkLines().get(5))
            .isEqualTo("6.ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำภายในวันที่ 30/09/2569");

        Sheet sheet = renderLegacyModel(model);
        assertThat(str(sheet, 28, 1))
            .isEqualTo("6.ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำภายในวันที่ 30/09/2569");
        assertThat(str(sheet, 30, 1)).isBlank(); // row 30 no longer part of the box
    }

    /** English, both layouts: bank layout's DATE line was remark 4 (index 6), renumbers to 3
     * (index 5); no-bank layout's was remark 5 (index 4), renumbers to 4 (index 3). */
    @Test
    void remarks_dateModeValidityAndNoLeadTime_compose_english_bothLayouts() {
        LocalDate until = LocalDate.of(2026, 10, 31);
        // V182: see the Thai twin's own comment — a TILE row keeps this a TILE document.
        DealQuotationDto q = withPriceModeAndValidity(
            withItems(englishQuotation(), List.of(tileNoLeadTime(1), adjustmentRow(2))),
            WastageCalculator.PRICE_MODE_NET, WastageCalculator.VALIDITY_MODE_DATE, until);
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(q)).isTrue();

        QuotationRenderModel bank = DealQuotationRenderAdapter.toRenderModel(q, null, null, BANK_BLOCK);
        assertThat(bank.remarkLines()).hasSize(7).noneMatch(l -> l.contains("Delivery"));
        assertThat(bank.remarkLines().get(5))
            .startsWith("3.Special price for orders with deposit paid by 31/10/2026");

        QuotationRenderModel noBank = DealQuotationRenderAdapter.toRenderModel(q, null, null, List.of());
        assertThat(noBank.remarkLines()).hasSize(7).noneMatch(l -> l.contains("Delivery"));
        assertThat(noBank.remarkLines().get(3))
            .startsWith("4.Special price for orders with deposit paid by 31/10/2026");
    }

    /**
     * V182 (owner request, 2026-09-16): an all-PLAIN China→Maldives document (QN6900902-6) has NO
     * TILE line at all, so it now takes the NON-TILE remark set (see {@code
     * DealQuotationRenderAdapter#englishNonTileRemarkLines}) rather than the tile-oriented 7/8-line
     * one this test used to pin. Neither {@code plainRow} carries a lead time, so the set's own
     * lead-time line drops too, shrinking it further — 3 lines with no bank block, 6 with it (the
     * block always costs 3 unnumbered lines, never a numbered slot).
     *
     * <p>This test used to prove the OLD tile-remarks lead-time-drop-and-renumber path on an
     * all-PLAIN document; it now proves the non-tile set replaces that path entirely for exactly
     * this kind of document — see {@code remarks_anAllPlainDocument_keepsTheTileSet_whenATileLineIsPresent}
     * just below for the wrong-way-round guard (a MIXED document keeps the tile set unchanged).
     */
    @Test
    void remarks_anAllPlainDocument_printsTheNonTileSet_inBothLayouts() throws Exception {
        DealQuotationDto allPlain = englishQuotation(q -> withItems(q, List.of(
            plainRow(1, "Supply of Porcelain Tiles"), plainRow(2, "Freight China to Male"))));
        String noReturn = "3.Goods sold are not returnable or exchangeable. Please check the order "
            + "carefully before confirming or signing for delivery.";
        for (List<String> block : List.of(BANK_BLOCK, List.<String>of())) {
            String layout = block.isEmpty() ? "no bank" : "bank";
            QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(allPlain, null, null, block);
            List<String> expected = block.isEmpty()
                ? List.of(
                    "1.The price above includes delivery to the ground floor within the Bangkok "
                        + "Metropolitan Area, but excludes installation.",
                    "2.A deposit of 30% is required upon order confirmation, the balance on 30 days credit.",
                    noReturn)
                : List.of(
                    "1.The price above includes delivery to the ground floor within the Bangkok "
                        + "Metropolitan Area, but excludes installation.",
                    "2.A deposit of 30% is required upon order confirmation, the balance on 30 days credit.",
                    BANK_BLOCK.get(0), BANK_BLOCK.get(1), BANK_BLOCK.get(2),
                    noReturn);
            assertThat(model.remarkLines()).as("adapter, %s layout", layout).containsExactlyElementsOf(expected)
                .noneMatch(l -> l.contains("Delivery"));

            Sheet sheet = renderLegacyModel(model);
            for (int i = 0; i < expected.size(); i++) {
                assertThat(str(sheet, 23 + i, 1)).as("%s layout row %d", layout, 23 + i)
                    .isEqualTo(expected.get(i));
            }
            // The box closes directly under the last line — nothing past it is a leftover remark row.
            assertThat(str(sheet, 23 + expected.size(), 1)).as("%s layout: no leftover remark row", layout)
                .isBlank();
        }
    }

    /**
     * Wrong-way-round guard for V182: a MIXED document (one genuine tile line alongside a PLAIN
     * one) still has a tile line, so it keeps the OLD 7/8-line tile-oriented remark set —
     * byte-for-byte, per {@code DealQuotationRenderAdapter#hasAnyTileLine}. Uses the SAME two
     * items as {@link #remarks_anAllPlainDocument_printsTheNonTileSet_inBothLayouts} plus one tile,
     * so the only variable between the two tests is whether a tile line is present at all.
     *
     * <p>D4 (review, 2026-09-15): also carries the country/stock-wording guard that used to live on
     * {@code remarks_anAllPlainDocument_dropsTheDeliveryLineEntirely_inBothLayouts} (this test's own
     * pre-V182 ancestor) — the 2026-09-11 owner-feedback reversal means the TILE remark set must
     * never print "Italy"/"China"/"Thailand" wording again, and V182's rewrite of that ancestor test
     * (into {@code remarks_anAllPlainDocument_printsTheNonTileSet_inBothLayouts}, which now takes
     * the NON-TILE path and never reaches this constant at all) dropped the assertion with nothing
     * left asserting it anywhere. This is the ONE remaining test that still exercises the TILE
     * remark set's own English lead-time fallback ({@code EN_LINE3_FALLBACK}) on a document with no
     * lead time, so the guard belongs here now.
     */
    @Test
    void remarks_aMixedDocument_keepsTheTileRemarkSet_unchanged() throws Exception {
        DealQuotationDto mixed = englishQuotation(q -> withItems(q, List.of(
            tileNoLeadTime(1), plainRow(2, "Freight China to Male"))));
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(mixed, null, null, List.of());
        assertThat(model.remarkLines()).hasSize(7).noneMatch(l -> l.contains("Delivery"));
        assertThat(model.remarkLines().get(2)).startsWith("3.Payment by telegraphic transfer");
        assertThat(model.remarkLines().get(5)).startsWith("6.Colours and patterns");
        assertThat(model.remarkLines().get(6)).startsWith("7.Goods sold");
        // D4: the country/stock-wording guard restored — see this test's own Javadoc. Item 2's OWN
        // description ("Freight China to Male") legitimately contains "China"; that string lives in
        // model.remarkLines(), never in the item description, so it cannot false-positive here.
        assertThat(String.join("\n", model.remarkLines()))
            .doesNotContainIgnoringCase("italy").doesNotContainIgnoringCase("italian")
            .doesNotContainIgnoringCase("china").doesNotContainIgnoringCase("thailand");
    }

    /** The Thai twin of the test above, pinning the NON-TILE set's drop-and-renumber path — see
     * {@code DealQuotationRenderAdapter#nonTileRemarkLines}/{@code #dropLeadTimeLineAndRenumber}.
     * Neither {@code plainRow} carries a lead time, so remark 3 (ระยะเวลานำเข้า) drops entirely and
     * the no-return line renumbers from "4." down to "3.". */
    @Test
    void remarks_anAllPlainThaiDocument_printsTheNonTileSet_withLeadTimeLineDropped() throws Exception {
        DealQuotationDto allPlain = withItems(thaiQuotation(), List.of(
            plainRow(1, "ค่าขนส่งกระเบื้อง"), plainRow(2, "ค่าติดตั้ง")));
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(allPlain, null, null);
        assertThat(model.remarkLines()).containsExactly(
            "1.ราคาข้างต้นรวมค่าขนส่งถึงชั้น 1 ของหน่วยงานในเขตกทม. แต่ไม่รวมค่าติดตั้ง",
            "2.บริษัทฯ ขอรับมัดจำ 30% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือเครดิต 30 วัน",
            "3.ทางบริษัทฯ ไม่รับเปลี่ยนหรือคืนสินค้า กรุณาตรวจสอบ ความถูกต้องก่อนสั่งซื้อหรือลงชื่อรับสินค้า");

        Sheet sheet = renderLegacyModel(model);
        assertThat(str(sheet, 23, 1)).isEqualTo(model.remarkLines().get(0));
        assertThat(str(sheet, 24, 1)).isEqualTo(model.remarkLines().get(1));
        assertThat(str(sheet, 25, 1)).isEqualTo(model.remarkLines().get(2));
        assertThat(str(sheet, 25, 1)).doesNotContain("ระยะเวลานำเข้า");
        // The box closes directly under line 3 — row 26 onward is no longer part of it.
        assertThat(str(sheet, 26, 1)).isBlank();
    }

    /** Wrong-way-round Thai twin: a MIXED document keeps the tile remark set unchanged. */
    @Test
    void remarks_aMixedThaiDocument_keepsTheTileRemarkSet_unchanged() throws Exception {
        DealQuotationDto mixed = withItems(thaiQuotation(), List.of(
            tileNoLeadTime(1), plainRow(2, "ค่าติดตั้ง")));
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(mixed, null, null);
        assertThat(model.remarkLines()).hasSize(7).noneMatch(l -> l.contains("ระยะเวลานำเข้า"));
        assertThat(model.remarkLines().get(2)).startsWith("3.ขนาดของกระเบื้องจริง");
    }

    /**
     * V182: a PLAIN-only document whose rows DO carry a lead time — reachable since D1 wired an
     * OPTIONAL ระยะเวลานำเข้า input onto {@code QuotationPlainItemRow} (see
     * {@link #plainRowWithLeadTime}'s own comment) — prints the full FOUR-line non-tile set, not
     * the three-line dropped one. {@code DealQuotationRenderAdapter#nonTileLeadTimeRange} reads the
     * field off ANY item regardless of {@code lineType}, which is what lets this fixture exercise
     * the path directly at the render-adapter layer.
     */
    @Test
    void remarks_aPlainOnlyThaiDocumentWithLeadTime_printsAllFourNonTileLines() throws Exception {
        DealQuotationDto allPlain = withItems(thaiQuotation(), List.of(
            plainRowWithLeadTime(1, "ค่าขนส่งกระเบื้อง", 30, 45),
            plainRowWithLeadTime(2, "ค่าติดตั้ง", 30, 45)));
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(allPlain, null, null);
        assertThat(model.remarkLines()).containsExactly(
            "1.ราคาข้างต้นรวมค่าขนส่งถึงชั้น 1 ของหน่วยงานในเขตกทม. แต่ไม่รวมค่าติดตั้ง",
            "2.บริษัทฯ ขอรับมัดจำ 30% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือเครดิต 30 วัน",
            "3.กรณีโรงงานผู้ผลิตมีสินค้าพร้อมจัดส่ง ระยะเวลานำเข้า 30-45 วัน หลังจากได้รับมัดจำ 30% เรียบร้อยแล้ว",
            "4.ทางบริษัทฯ ไม่รับเปลี่ยนหรือคืนสินค้า กรุณาตรวจสอบ ความถูกต้องก่อนสั่งซื้อหรือลงชื่อรับสินค้า");

        Sheet sheet = renderLegacyModel(model);
        for (int i = 0; i < model.remarkLines().size(); i++) {
            assertThat(str(sheet, 23 + i, 1)).as("row %d", 23 + i).isEqualTo(model.remarkLines().get(i));
        }
        // The box closes directly under line 4 — row 27 onward is no longer part of it.
        assertThat(str(sheet, 27, 1)).isBlank();
    }

    /**
     * D2 (Opus review, 2026-09-15): every non-tile test above asserts cell TEXT only, which cannot
     * catch a geometry regression — the reviewer mutated {@code QuotationRenderer#compactRemarksSection}'s
     * closing call from {@code clampToPackedSlots(actualLineCount)} to
     * {@code Math.max(clampToPackedSlots(actualLineCount), REMARK_V2_MIN_LINES)} and all 213 tests
     * still passed, while the real render lost the box's bottom rule under line 4 and grew a
     * spurious rule under รวมเป็นเงินทั้งสิ้น. This pins the box's closing bottom rule on the ACTUAL
     * last packed row for the FOUR-line non-tile case — mirrors
     * {@code DealQuotationRenderAdapterV3Test#leadTime_noItemHasOne_rendererWritesExactlySevenRemarkRows}'s
     * own border assertion, applied to the non-tile set the tile-only test suite never exercised.
     */
    @Test
    void remarks_nonTileFourLine_boxClosesOnTheActualLastRow_notAHardcodedMinimum() throws Exception {
        DealQuotationDto allPlain = withItems(thaiQuotation(), List.of(
            plainRowWithLeadTime(1, "ค่าขนส่งกระเบื้อง", 30, 45),
            plainRowWithLeadTime(2, "ค่าติดตั้ง", 30, 45)));
        Sheet sheet = render(allPlain);
        // 4 lines -> rows 23..26; the closing bottom rule must sit on row 26, across every column.
        for (int c = 0; c <= 8; c++) {
            assertThat(sheet.getRow(26).getCell(c).getCellStyle().getBorderBottom())
                .as("row 26 col %d must carry the box's closing bottom rule", c)
                .isNotEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);
        }
        // Row 27 must NOT be pulled inside the box's side borders -- the REMARK_V2_MIN_LINES(7)
        // mutation above would leave the (unwritten) rows 27..29 sitting inside them.
        assertThat(borderLeftOrNone(sheet, 27, 1))
            .as("row 27 must not be inside the box's left border once it has shrunk to 4 lines")
            .isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);
    }

    /** D2 twin: the THREE-line case (lead time dropped, no lead time on any row). */
    @Test
    void remarks_nonTileThreeLine_boxClosesOnTheActualLastRow_notAHardcodedMinimum() throws Exception {
        DealQuotationDto allPlain = withItems(thaiQuotation(), List.of(
            plainRow(1, "ค่าขนส่งกระเบื้อง"), plainRow(2, "ค่าติดตั้ง")));
        Sheet sheet = render(allPlain);
        // 3 lines -> rows 23..25; the closing bottom rule must sit on row 25.
        for (int c = 0; c <= 8; c++) {
            assertThat(sheet.getRow(25).getCell(c).getCellStyle().getBorderBottom())
                .as("row 25 col %d must carry the box's closing bottom rule", c)
                .isNotEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);
        }
        assertThat(borderLeftOrNone(sheet, 26, 1))
            .as("row 26 must not be inside the box's left border once it has shrunk to 3 lines")
            .isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);
    }

    private org.apache.poi.ss.usermodel.BorderStyle borderLeftOrNone(Sheet sheet, int row, int col) {
        var cell = sheet.getRow(row) == null ? null : sheet.getRow(row).getCell(col);
        return cell == null ? org.apache.poi.ss.usermodel.BorderStyle.NONE : cell.getCellStyle().getBorderLeft();
    }

    /** The English twin, in both bank-block layouts. */
    @Test
    void remarks_aPlainOnlyEnglishDocumentWithLeadTime_printsAllFourNonTileLines_inBothLayouts() throws Exception {
        DealQuotationDto allPlain = englishQuotation(q -> withItems(q, List.of(
            plainRowWithLeadTime(1, "Supply of Porcelain Tiles", 30, 45),
            plainRowWithLeadTime(2, "Freight China to Male", 30, 45))));
        for (List<String> block : List.of(BANK_BLOCK, List.<String>of())) {
            String layout = block.isEmpty() ? "no bank" : "bank";
            QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(allPlain, null, null, block);
            List<String> expected = block.isEmpty()
                ? List.of(
                    "1.The price above includes delivery to the ground floor within the Bangkok "
                        + "Metropolitan Area, but excludes installation.",
                    "2.A deposit of 30% is required upon order confirmation, the balance on 30 days credit.",
                    "3.If the factory has the goods ready to ship, the import lead time is 30-45 days "
                        + "after the 30% deposit is received.",
                    "4.Goods sold are not returnable or exchangeable. Please check the order carefully "
                        + "before confirming or signing for delivery.")
                : List.of(
                    "1.The price above includes delivery to the ground floor within the Bangkok "
                        + "Metropolitan Area, but excludes installation.",
                    "2.A deposit of 30% is required upon order confirmation, the balance on 30 days credit.",
                    BANK_BLOCK.get(0), BANK_BLOCK.get(1), BANK_BLOCK.get(2),
                    "3.If the factory has the goods ready to ship, the import lead time is 30-45 days "
                        + "after the 30% deposit is received.",
                    "4.Goods sold are not returnable or exchangeable. Please check the order carefully "
                        + "before confirming or signing for delivery.");
            assertThat(model.remarkLines()).as("adapter, %s layout", layout).containsExactlyElementsOf(expected);

            Sheet sheet = renderLegacyModel(model);
            for (int i = 0; i < expected.size(); i++) {
                assertThat(str(sheet, 23 + i, 1)).as("%s layout row %d", layout, 23 + i)
                    .isEqualTo(expected.get(i));
            }
            assertThat(str(sheet, 23 + expected.size(), 1)).as("%s layout: no leftover remark row", layout)
                .isBlank();
        }
    }

    /** V182: a genuinely mixed lead time across non-tile rows prints the OVERALL span (min of the
     * mins, max of the maxes) — the sentence has room for only one {@code {min}-{max}} slot,
     * unlike the tile set's own per-item grouped list. */
    @Test
    void remarks_nonTileLeadTime_spansTheOverallRangeAcrossNonUniformItems() throws Exception {
        DealQuotationDto q = withItems(thaiQuotation(), List.of(
            plainRowWithLeadTime(1, "A", 20, 30), plainRowWithLeadTime(2, "B", 40, 50)));
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(q, null, null);
        assertThat(model.remarkLines().get(2))
            .isEqualTo("3.กรณีโรงงานผู้ผลิตมีสินค้าพร้อมจัดส่ง ระยะเวลานำเข้า 20-50 วัน หลังจากได้รับมัดจำ 30% เรียบร้อยแล้ว");
    }

    /**
     * V182: a non-tile document taking NO deposit ({@code depositPercent == 0}) drops just the
     * "หลังจากได้รับมัดจำ...เรียบร้อยแล้ว" clause (naming a deposit that does not exist would be
     * nonsensical) and keeps the lead-time claim itself — chosen deliberately over dropping the
     * whole line, since the import lead time is still true regardless of deposit terms.
     */
    @Test
    void remarks_nonTileNoDeposit_dropsOnlyTheDepositClause_keepsTheLeadTimeSentence() throws Exception {
        DealQuotationDto q = withDepositPercent(
            withItems(thaiQuotation(), List.of(plainRowWithLeadTime(1, "A", 30, 45))), 0);
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(q, null, null);
        assertThat(model.remarkLines().get(2))
            .isEqualTo("3.กรณีโรงงานผู้ผลิตมีสินค้าพร้อมจัดส่ง ระยะเวลานำเข้า 30-45 วัน");
    }

    /**
     * V182: an ADJUSTMENT-only item list counts as non-tile too — see {@code
     * DealQuotationRenderAdapter#hasAnyTileLine}'s own Javadoc. An ADJUSTMENT row never carries a
     * lead time, so the set drops to three lines.
     *
     * <p>Comment correction (review, 2026-09-16): this is NOT a real "credit-note-ish" document a
     * rep can actually save — {@code DealQuotationService#buildItems} refuses both an empty item
     * list and an all-ADJUSTMENT one before either is ever written (see its own Javadoc). Kept
     * anyway as a cheap guard on the render adapter's OWN logic: {@code toRenderModel} is a pure
     * function with no access to the service's save-time rules, so it must still answer
     * sensibly (never throw, never misclassify) if it is ever handed a list shaped like this one —
     * by a future caller, a test, or a re-render of data the current rules would no longer accept.
     */
    @Test
    void remarks_anAdjustmentOnlyDocument_countsAsNonTile() throws Exception {
        DealQuotationDto q = withItems(thaiQuotation(), List.of(adjustmentRow(1)));
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(q, null, null);
        assertThat(model.remarkLines()).containsExactly(
            "1.ราคาข้างต้นรวมค่าขนส่งถึงชั้น 1 ของหน่วยงานในเขตกทม. แต่ไม่รวมค่าติดตั้ง",
            "2.บริษัทฯ ขอรับมัดจำ 30% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือเครดิต 30 วัน",
            "3.ทางบริษัทฯ ไม่รับเปลี่ยนหรือคืนสินค้า กรุณาตรวจสอบ ความถูกต้องก่อนสั่งซื้อหรือลงชื่อรับสินค้า");
    }

    /** B1 must be the owner's own spelling from her F-SM-008 form, not the older "&amp; R." one. */
    @Test
    void header_printsTheCompanyNameAsTheOwnersFormSpellsIt() throws Exception {
        Sheet sheet = renderEnglish();
        assertThat(str(sheet, 0, 1).strip()).isEqualTo("G.L.&R. TAPS AND TILES COMPANY LIMITED");
    }

    /**
     * B2 runs into the badge image on the right; the old address clipped at "Bangkok 101". Measured
     * on the rendered PDF (2026-09-11, 300 dpi), the shortened line ends ~4 mm before the badge.
     * The RAW cell is pinned, indent included: the leading spaces clear the logo on the left, so a
     * longer indent pushes the same text into the badge just as a longer address would. The length
     * cap is the measured line (27-space indent + 73 characters) — character count stands in for
     * width, so re-measure on a PDF before ever raising it.
     */
    @Test
    void header_printsTheShortenedAddress_endingWithTheFullPostcode() throws Exception {
        Sheet sheet = renderEnglish();
        String raw = str(sheet, 1, 1);
        assertThat(raw.strip())
            .isEqualTo("201 Sukhumvit 63, Sukhumvit Road, North-Klongton, Wattana, Bangkok 10110");
        assertThat(raw.length()).isLessThanOrEqualTo(27 + 73);
    }

    @Test
    void thaiDocument_neverCarriesTheBankBlock_evenWhenConfigured() throws Exception {
        // The Thai หมายเหตุ block has no bank lines and must not grow them: its eight lines are the
        // template's own, and the owner's Thai documents carry payment terms without account details.
        Sheet sheet = renderLegacyModel(
            DealQuotationRenderAdapter.toRenderModel(thaiQuotation(), null, null, BANK_BLOCK));
        for (int r = 23; r <= 30; r++) {
            assertThat(str(sheet, r, 1)).doesNotContain("003-92-1222-6").doesNotContain("KASITHBK");
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────

    /** The owner's own bank block, verbatim from QN6900902-6 and QN6900933 (they agree). */
    private static final List<String> BANK_BLOCK = List.of(
        "Please arrange payment to the following bank account. Bank Name : Kasikorn Bank Public Company Limited",
        "Beneficiary name : G.L.& R. Taps and Tiles Co., Ltd. Beneficiary account number : 003-92-1222-6 Saving Account",
        "SWIFT code : KASITHBK");

    private Sheet renderEnglishWithBank(List<String> bankBlock) throws Exception {
        return renderLegacyModel(
            DealQuotationRenderAdapter.toRenderModel(englishQuotation(), null, null, bankBlock));
    }

    private Sheet renderEnglish() throws Exception {
        return render(englishQuotation());
    }

    private Sheet render(DealQuotationDto quotation) throws Exception {
        return renderLegacyModel(DealQuotationRenderAdapter.toRenderModel(quotation, null, null));
    }

    private Sheet renderLegacyModel(QuotationRenderModel model) throws Exception {
        byte[] xls = new QuotationRenderer().toXls(model);
        var wb = WorkbookFactory.create(new ByteArrayInputStream(xls));
        return wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
    }

    private String str(Sheet sheet, int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null) return "";
        Cell cell = r.getCell(col);
        if (cell == null || cell.getCellType() != CellType.STRING) return "";
        return cell.getStringCellValue();
    }

    private String cellText(Sheet sheet, int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null) return "<no-row>";
        Cell cell = r.getCell(col);
        if (cell == null) return "<no-cell>";
        return switch (cell.getCellType()) {
            case STRING -> "S:" + cell.getStringCellValue();
            case NUMERIC -> "N:" + cell.getNumericCellValue();
            case FORMULA -> "F:" + cell.getCellFormula();
            case BOOLEAN -> "B:" + cell.getBooleanCellValue();
            case BLANK -> "<blank>";
            default -> "<" + cell.getCellType() + ">";
        };
    }

    private boolean blank(Sheet sheet, int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null) return true;
        Cell cell = r.getCell(col);
        if (cell == null || cell.getCellType() == CellType.BLANK) return true;
        return cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank();
    }

    /** Column {@code col}'s string cells over rows {@code from..to}, joined with single spaces. */
    private String joinedColumn(Sheet sheet, int col, int from, int to) {
        List<String> parts = new ArrayList<>();
        for (int r = from; r <= to; r++) {
            String text = str(sheet, r, col);
            if (!text.isBlank()) parts.add(text.strip());
        }
        return String.join(" ", parts);
    }

    private int rowContaining(Sheet sheet, int col, String text) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            if (text.equals(str(sheet, r, col))) return r;
        }
        throw new AssertionError("no row with \"" + text + "\" in column " + col);
    }

    /**
     * A DIRECT_NET quotation whose printed strings come from the REAL {@link DealQuotationLines}
     * in {@code lang}: a BIOARCH tile with thickness (AREA, 5%, boxed), a PIECES tile with no
     * thickness (inline size), {@code extraTiles} more tiles (14 pushes the render into the flowing
     * layout), a freight row and a 3% ส่วนลดพิเศษ whose stored text is the Thai composition.
     */
    private DealQuotationDto realLinesQuotation(String lang, int extraTiles) {
        List<DealQuotationItemDto> items = new ArrayList<>();
        String storedUnit = "แผ่น"; // what the write path stores on every TILE row
        items.add(tileLines(1, lang, new BigDecimal("9"), DealQuotationLines.printedTileUnit(lang, storedUnit)));
        items.add(tileLines(2, lang, null, DealQuotationLines.printedTileUnit(lang, storedUnit)));
        for (int i = 0; i < extraTiles; i++) {
            items.add(tileLines(3 + i, lang, new BigDecimal("9"), DealQuotationLines.printedTileUnit(lang, storedUnit)));
        }
        int seq = items.size() + 1;
        items.add(plainRow(seq++, "Transportation Charges"));
        BigDecimal adj = new BigDecimal("30.00");
        LocalDate deadline = LocalDate.of(2026, 7, 31);
        String storedAdjustment = DealQuotationLines.adjustmentDescription(new BigDecimal("3"), deadline);
        items.add(new DealQuotationItemDto((long) seq, seq,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            adj, null, null, null, null, null,
            null, 0, 0, 0, null,
            adj, adj.negate(),
            DealQuotationLines.printedAdjustmentDescription(lang, storedAdjustment, new BigDecimal("3"), deadline),
            null, null,
            WastageCalculator.LINE_TYPE_ADJUSTMENT, BigDecimal.valueOf(-1), null, null, new BigDecimal("3"),
            deadline, null, null));
        DealQuotationDto base = WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(lang) ? englishQuotation() : thaiQuotation();
        DealQuotationDto q = withItems(base, items);
        return new DealQuotationDto(q.id(), q.number(), q.ticketId(), q.docStatus(), q.revisionNo(),
            q.parentQuotationId(), q.createdById(), q.createdByName(), q.createdByNameEn(),
            q.salesRepId(), q.salesRepName(), q.salesRepNameEn(), q.salesRepPhone(),
            q.submittedAt(), q.approvedById(), q.approvedByName(), q.approvedByNameEn(), q.approvedAt(),
            q.approvalNote(), q.quotationDate(), q.customerName(), q.customerAddress(),
            q.customerTaxId(), q.customerPhone(), q.contactId(), q.contactName(), q.contactPhone(),
            q.contactEmail(), q.projectName(), q.deptCode(), q.unitCode(), q.offerDate(),
            q.depositPercent(), q.remainderMode(), q.creditDays(), q.validityDays(),
            q.validityDate(), q.customerNotes(), WastageCalculator.PRICE_MODE_DIRECT_NET, q.documentLanguage(),
            q.subtotalAmount(), q.vatAmount(), q.grandTotal(), q.currency(),
            q.approverHasSignature(), items, q.createdAt(), q.updatedAt());
    }

    /** List price 14.00, net 12.50 (so DIRECT_NET prints the discount WORD), lines in {@code lang}. */
    private DealQuotationItemDto tileLines(int seq, String lang, BigDecimal thickness, String unit) {
        boolean inlineSize = thickness == null;
        String model = inlineSize ? "Reverso Cement" : "BIOARCH";
        String color = inlineSize ? "Grigio" : "BARGE GRIGIA";
        String texture = inlineSize ? "Matt" : "ONDULATO";
        String code = inlineSize ? "BS66R13GP" : "APGBBK15";
        String quantityMode = inlineSize ? WastageCalculator.QUANTITY_MODE_PIECES : WastageCalculator.QUANTITY_MODE_AREA;
        String wastageMode = inlineSize ? WastageCalculator.WASTAGE_MODE_PIECES : WastageCalculator.WASTAGE_MODE_PERCENT;
        BigDecimal wastage = inlineSize ? new BigDecimal("10") : new BigDecimal("5");
        int before = inlineSize ? 200 : 4917;
        int fin = inlineSize ? 210 : 5180;
        Integer box = inlineSize ? null : 20;
        BigDecimal area = inlineSize ? null : new BigDecimal("300");
        BigDecimal pps = new BigDecimal("16.39");
        BigDecimal net = new BigDecimal("12.50");
        return new DealQuotationItemDto((long) seq, seq, null, null, code, null, model, color, texture,
            inlineSize ? "60x60" : "200x305", thickness, new BigDecimal("0.061013"), quantityMode, area,
            inlineSize ? 200 : null, wastageMode, wastage, box, new BigDecimal("14.00"), null, null, 30, 45, null,
            pps, before, fin, fin, box == null ? null : fin / box, net, net.multiply(BigDecimal.valueOf(fin)),
            DealQuotationLines.descriptionLine(lang, model, color, texture, code, inlineSize ? "60x60" : "200x305",
                thickness),
            DealQuotationLines.sizeLine(lang, "200x305", thickness, new BigDecimal("200"), new BigDecimal("305")),
            DealQuotationLines.calculationLine(lang, quantityMode, area, pps, before, wastageMode, wastage, fin, box),
            WastageCalculator.LINE_TYPE_TILE, BigDecimal.valueOf(fin), unit, null, null, null,
            DealQuotationLines.specialPriceLine(lang, null), null);
    }

    private List<String> allText(Sheet sheet) {
        List<String> out = new ArrayList<>();
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c <= 9; c++) {
                Cell cell = row.getCell(c);
                if (cell != null && cell.getCellType() == CellType.STRING) {
                    out.add(cell.getStringCellValue());
                }
            }
        }
        return out;
    }

    private DealQuotationDto englishQuotation() {
        return englishQuotation(q -> q);
    }

    private DealQuotationDto englishQuotation(java.util.function.UnaryOperator<DealQuotationDto> mutate) {
        return mutate.apply(quotation(WastageCalculator.DOCUMENT_LANGUAGE_EN, "USD"));
    }

    private DealQuotationDto thaiQuotation() {
        return quotation(WastageCalculator.DOCUMENT_LANGUAGE_TH, "THB");
    }

    private DealQuotationDto withItems(DealQuotationDto q, List<DealQuotationItemDto> items) {
        return new DealQuotationDto(q.id(), q.number(), q.ticketId(), q.docStatus(), q.revisionNo(),
            q.parentQuotationId(), q.createdById(), q.createdByName(), q.createdByNameEn(),
            q.salesRepId(), q.salesRepName(), q.salesRepNameEn(), q.salesRepPhone(),
            q.submittedAt(), q.approvedById(), q.approvedByName(), q.approvedByNameEn(), q.approvedAt(),
            q.approvalNote(), q.quotationDate(), q.customerName(), q.customerAddress(),
            q.customerTaxId(), q.customerPhone(), q.contactId(), q.contactName(), q.contactPhone(),
            q.contactEmail(), q.projectName(), q.deptCode(), q.unitCode(), q.offerDate(),
            q.depositPercent(), q.remainderMode(), q.creditDays(), q.validityDays(),
            q.validityDate(), q.customerNotes(), q.priceMode(), q.documentLanguage(),
            q.subtotalAmount(), q.vatAmount(), q.grandTotal(), q.currency(),
            q.approverHasSignature(), items, q.createdAt(), q.updatedAt());
    }

    /** V178 — this quotation with its validity mode/date set explicitly (the canonical
     * constructor, since this is the ONE fixture that actually needs {@code validityMode}/
     * {@code validityUntil} to be non-default). */
    private DealQuotationDto withValidity(DealQuotationDto q, String validityMode, LocalDate validityUntil) {
        return withPriceModeAndValidity(q, q.priceMode(), validityMode, validityUntil);
    }

    /** Same as {@link #withValidity}, plus an explicit {@code priceMode} — remark 7's DATE variant
     * must print regardless of price mode (owner ruling: not gated on NET/SPECIAL_SQM/DIRECT_NET,
     * on any item discount, or on an adjustment row existing). */
    private DealQuotationDto withPriceModeAndValidity(DealQuotationDto q, String priceMode,
                                                       String validityMode, LocalDate validityUntil) {
        return new DealQuotationDto(q.id(), q.number(), q.ticketId(), q.docStatus(), q.revisionNo(),
            q.parentQuotationId(), q.createdById(), q.createdByName(), q.createdByNameEn(),
            q.salesRepId(), q.salesRepName(), q.salesRepNameEn(), q.salesRepPhone(),
            q.submittedAt(), q.approvedById(), q.approvedByName(), q.approvedByNameEn(), q.approvedAt(),
            q.approvalNote(), q.quotationDate(), q.customerName(), q.customerAddress(),
            q.customerTaxId(), q.customerPhone(), q.contactId(), q.contactName(), q.contactPhone(),
            q.contactEmail(), q.projectName(), q.deptCode(), q.unitCode(), q.offerDate(),
            q.depositPercent(), q.remainderMode(), q.creditDays(), q.validityDays(), q.validityDate(),
            validityMode, validityUntil,
            q.customerNotes(), priceMode, q.documentLanguage(),
            q.subtotalAmount(), q.vatAmount(), q.grandTotal(), q.currency(),
            q.approverHasSignature(), q.items(), q.createdAt(), q.updatedAt());
    }

    /** A PLAIN row: description, 1 lot at 500.00, no lead time — the ordinary case (a rep who
     * leaves ระยะเวลานำเข้า blank), still the common one even after D1 wired an OPTIONAL lead-time
     * input onto QuotationPlainItemRow (see {@link #plainRowWithLeadTime} for the other case). */
    private DealQuotationItemDto plainRow(int seq, String description) {
        return new DealQuotationItemDto((long) seq, seq,
            null, null, null, null, null, null, null, null,     // location … size text
            null, null, null, null, null, null, null, null,     // thickness … pieces per box
            new BigDecimal("500.00"), null, null,               // unit price, discount, origin
            null, null, null,                                   // lead time min/max, notes
            null, 0, 0, 0, null,                                // pieces per sqm … boxes (TILE only)
            new BigDecimal("500.00"), new BigDecimal("500.00"), // net, line amount
            description, null, null,
            WastageCalculator.LINE_TYPE_PLAIN, BigDecimal.ONE, "lot", null, null, null, null, null);
    }

    /** V182 fixture — a PLAIN row that DOES carry a lead time (สินค้า/บริการอื่น — sanitaryware
     * sold on ชุด, an owner example being QN6900971-4's own "ระยะเวลานำเข้า 75-90 วัน" row).
     *
     * <p>D1 correction (review, 2026-09-16): this Javadoc used to say a real PLAIN row can never
     * carry a lead time — that was true only because {@code QuotationPlainItemRow} had no input
     * for it; the field itself always lived on {@code DealQuotationItemDto} (shared with the TILE
     * row) and {@code DealQuotationService#buildPlainItem} always forwarded it. Now that the editor
     * has the control, this fixture is a faithful stand-in for a real saved row rather than an
     * unreachable one — see {@code DealQuotationIntegrationTest#plainRow_optionalLeadTime_reachesTheSavedItem_andPrintsInTheNonTileRemarks}
     * for the same shape proven through the real {@code ItemInput} → service → render path. Kept as
     * a direct-DTO fixture here (rather than going through the service in every test in this file)
     * because {@code DealQuotationRenderAdapter#nonTileLeadTimeRange} reads the field off ANY item
     * regardless of {@code lineType} — this is a render-adapter unit test, not a save-path one. */
    private DealQuotationItemDto plainRowWithLeadTime(int seq, String description, int minDays, int maxDays) {
        return new DealQuotationItemDto((long) seq, seq,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            new BigDecimal("500.00"), null, null,
            minDays, maxDays, null,
            null, 0, 0, 0, null,
            new BigDecimal("500.00"), new BigDecimal("500.00"),
            description, null, null,
            WastageCalculator.LINE_TYPE_PLAIN, BigDecimal.ONE, "lot", null, null, null, null, null);
    }

    /** V182 test helper — {@code q} with {@code depositPercent} replaced, everything else kept. */
    private DealQuotationDto withDepositPercent(DealQuotationDto q, int depositPercent) {
        return new DealQuotationDto(q.id(), q.number(), q.ticketId(), q.docStatus(), q.revisionNo(),
            q.parentQuotationId(), q.createdById(), q.createdByName(), q.createdByNameEn(),
            q.salesRepId(), q.salesRepName(), q.salesRepNameEn(), q.salesRepPhone(),
            q.submittedAt(), q.approvedById(), q.approvedByName(), q.approvedByNameEn(), q.approvedAt(),
            q.approvalNote(), q.quotationDate(), q.customerName(), q.customerAddress(),
            q.customerTaxId(), q.customerPhone(), q.contactId(), q.contactName(), q.contactPhone(),
            q.contactEmail(), q.projectName(), q.deptCode(), q.unitCode(), q.offerDate(),
            depositPercent, q.remainderMode(), q.creditDays(), q.validityDays(),
            q.validityDate(), q.customerNotes(), q.priceMode(), q.documentLanguage(),
            q.subtotalAmount(), q.vatAmount(), q.grandTotal(), q.currency(),
            q.approverHasSignature(), q.items(), q.createdAt(), q.updatedAt());
    }

    /** The three employee names blanked in English, to exercise the Thai fallback. */
    private DealQuotationDto withNoEnglishNames(DealQuotationDto q) {
        return new DealQuotationDto(q.id(), q.number(), q.ticketId(), q.docStatus(), q.revisionNo(),
            q.parentQuotationId(), q.createdById(), q.createdByName(), null,
            q.salesRepId(), q.salesRepName(), null, q.salesRepPhone(),
            q.submittedAt(), q.approvedById(), q.approvedByName(), null, q.approvedAt(),
            q.approvalNote(), q.quotationDate(), q.customerName(), q.customerAddress(),
            q.customerTaxId(), q.customerPhone(), q.contactId(), q.contactName(), q.contactPhone(),
            q.contactEmail(), q.projectName(), q.deptCode(), q.unitCode(), q.offerDate(),
            q.depositPercent(), q.remainderMode(), q.creditDays(), q.validityDays(),
            q.validityDate(), q.customerNotes(), q.priceMode(), q.documentLanguage(),
            q.subtotalAmount(), q.vatAmount(), q.grandTotal(), q.currency(),
            q.approverHasSignature(), q.items(), q.createdAt(), q.updatedAt());
    }

    /**
     * One APPROVED tile-row quotation created on 8 September 2026 (2569 BE), the date the spec's
     * own example prints. Item: 10 pieces at 100.00, lead time 30-45 days, so the computed remark
     * lines and the 1,000.00 total are both predictable.
     */
    private DealQuotationDto quotation(String documentLanguage, String currency) {
        BigDecimal subtotal = new BigDecimal("1000.00");
        boolean english = WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(documentLanguage);
        // Literals, NOT WastageCalculator.vat(...): a fixture that computes its expectation with
        // the code under test is CLAUDE.md's "mock MIRRORS a backend computation" failure shape —
        // it would stay green through any shared error. (These two DTO fields are not read by the
        // renderer at all; the printed totals come from QuotationRenderer's own arithmetic. They
        // are pinned here so the fixture still describes a document that could really exist.)
        BigDecimal vat = english ? new BigDecimal("0.00") : new BigDecimal("70.00");
        BigDecimal grand = english ? new BigDecimal("1000.00") : new BigDecimal("1070.00");
        Instant createdAt = Instant.parse("2026-09-08T02:00:00Z");   // 09:00 Bangkok, 8 Sep 2026
        return new DealQuotationDto(1L, "QT-2026-0001", 1L, "APPROVED", 1, null,
            47L, "จินตนา หาญมนตรี", "Jintana Hanmontree",
            67L, "เจนเนตร หลงสกุล", "Jennet Longsakul", "080-7767707",
            createdAt, 136L, "ราม อิฐรัตน์", "Rarm Itarat", createdAt, "อนุมัติ",
            LocalDate.of(2026, 9, 8),
            english ? "Blue Lagoon Resort Pvt Ltd" : "บริษัท บลูลากูน จำกัด",
            english ? "12 Boduthakurufaanu Magu, Male, Maldives" : "12 ถนนทดสอบ กรุงเทพฯ",
            "0100000000099",
            english ? "+960 330 1234" : "02-999-9999",
            9L,
            english ? "Ms. Aisha Rahman" : "สมหญิง ใจดี",
            "081-111-2222", "aisha@bluelagoon.mv",
            english ? "Blue Lagoon Villas" : "โครงการทดสอบ",
            "P003", "D002",
            LocalDate.of(2026, 9, 1), 30, "CREDIT", 30, 30, LocalDate.of(2026, 10, 8), null,
            WastageCalculator.PRICE_MODE_NET, documentLanguage,
            subtotal, vat, grand, currency,
            false, List.of(tile()), createdAt, createdAt);
    }

    private DealQuotationItemDto tile() {
        return new DealQuotationItemDto(1L, 1, null, null, null, null, "A", null, null, "60x60",
            new BigDecimal("2"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 1, new BigDecimal("100.00"), null, null, 30, 45, null,
            new BigDecimal("2.78"), 10, 10, 10, 10, new BigDecimal("100.00"), new BigDecimal("1000.00"),
            "Tile Model A", "Size 60x60x2 cm.", "(10 pcs.)",
            // Trailing args are lineType, quantity, unit, specialPriceSqm, adjustmentPct,
            // adjustmentDeadline, specialPriceLine, adjustmentAmount. The last one arrived with
            // #925's F2 fix (a flat adjustment had no DTO field, so a GET→PUT round-trip of one
            // was a hard 400) and is null on a TILE row.
            WastageCalculator.LINE_TYPE_TILE, BigDecimal.TEN, "pcs.", null, null, null, null, null);
    }

    /** V178 fixture — a DIRECT_NET tile whose net (85.00) genuinely differs from its list price
     * (100.00): {@link DealQuotationRenderAdapter#hasSpecialPricing} rule (b). */
    private DealQuotationItemDto discountedDirectNetTile() {
        return new DealQuotationItemDto(1L, 1, null, null, null, null, "A", null, null, "60x60",
            new BigDecimal("2"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 1, new BigDecimal("100.00"), null, null, 30, 45, null,
            new BigDecimal("2.78"), 10, 10, 10, 10, new BigDecimal("85.00"), new BigDecimal("850.00"),
            "Tile Model A", "Size 60x60x2 cm.", "(10 pcs.)",
            WastageCalculator.LINE_TYPE_TILE, BigDecimal.TEN, "pcs.", null, null, null, null, null);
    }

    /** V178 fixture — a NET tile with {@code discountPct} EXPLICITLY zero (never null), so no
     * rule counts it as special pricing on its own. */
    private DealQuotationItemDto netZeroDiscountTile(int seq) {
        return new DealQuotationItemDto((long) seq, seq, null, null, null, null, "A", null, null, "60x60",
            new BigDecimal("2"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 1, new BigDecimal("100.00"), BigDecimal.ZERO, null, 30, 45,
            null, new BigDecimal("2.78"), 10, 10, 10, 10, new BigDecimal("100.00"), new BigDecimal("1000.00"),
            "Tile Model A", "Size 60x60x2 cm.", "(10 pcs.)",
            WastageCalculator.LINE_TYPE_TILE, BigDecimal.TEN, "pcs.", null, null, null, null, null);
    }

    /** V182 fixture — a plain TILE row (no discount, no special pricing of its own) with NO lead
     * time set, used alongside {@link #adjustmentRow} so a "DATE-mode validity + no lead time"
     * test keeps exercising the TILE remark set (a document needs at least one TILE line for that
     * — see {@link DealQuotationRenderAdapter#hasAnyTileLine}) rather than becoming a non-tile
     * document, whose remark set has no validity/DATE line at all. */
    private DealQuotationItemDto tileNoLeadTime(int seq) {
        return new DealQuotationItemDto((long) seq, seq, null, null, null, null, "A", null, null, "60x60",
            new BigDecimal("2"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 1, new BigDecimal("100.00"), null, null, null, null,
            null, new BigDecimal("2.78"), 10, 10, 10, 10, new BigDecimal("100.00"), new BigDecimal("1000.00"),
            "Tile Model A", "Size 60x60x2 cm.", "(10 pcs.)",
            WastageCalculator.LINE_TYPE_TILE, BigDecimal.TEN, "pcs.", null, null, null, null, null);
    }

    /** V178 fixture — a bare ADJUSTMENT (ส่วนลดพิเศษ) row: rule (e) on its own, independent of
     * every other row's price mode or discount. */
    private DealQuotationItemDto adjustmentRow(int seq) {
        BigDecimal adj = new BigDecimal("30.00");
        LocalDate deadline = LocalDate.of(2026, 9, 20);
        return new DealQuotationItemDto((long) seq, seq,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            adj, null, null, null, null, null,
            null, 0, 0, 0, null,
            adj, adj.negate(),
            "ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 20/09/2026", null, null,
            WastageCalculator.LINE_TYPE_ADJUSTMENT, BigDecimal.valueOf(-1), null, null, new BigDecimal("3"),
            deadline, null, null);
    }
}
