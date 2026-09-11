package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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

    // ── fixtures ───────────────────────────────────────────────────────────────────────────

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
}
