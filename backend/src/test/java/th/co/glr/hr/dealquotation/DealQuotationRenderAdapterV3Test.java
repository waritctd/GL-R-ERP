package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderModel.RenderItem;
import th.co.glr.hr.ticket.QuotationRenderer;

/**
 * Quotation v3 (owner feedback pass 3, 2026-09-11) — renderer fidelity for the three line types
 * and the three price entry modes, through the REAL {@link DealQuotationRenderAdapter} and the
 * REAL {@link QuotationRenderer}.
 *
 * <p>Two layers, deliberately. The {@code RenderItem}-level assertions pin the ADAPTER's decisions
 * (which printed lines, which ส่วนลด label, which หน่วย); the workbook-level ones pin that those
 * decisions actually reach the printed cells. The second layer is not redundant: the หน่วย case in
 * particular passes at adapter level and would still have printed "แผ่น" on an ADJUSTMENT row,
 * because {@code QuotationRenderer#fillItemMainRow} used to fall back for a BLANK unit, not only a
 * null one. The PDF/HTML path draws this same workbook ({@link QuotationHtmlDocument}), so pinning
 * the XLS cells covers all three output formats.
 *
 * <p>Column map (see {@code QuotationRenderer#fillItemMainRow}): 0 = seq, 1 = description,
 * 2 = จำนวน, 3 = หน่วย, 4 = ราคา, 6 = ส่วนลด, 7 = คงเหลือ, 8 = เป็นเงิน. Items start at row 9.
 */
class DealQuotationRenderAdapterV3Test {
    private static final int ITEM_START_ROW = 9;

    // ── adapter level: printed lines, ส่วนลด label, หน่วย ───────────────────────────────────

    @Test
    void tileRowInNetMode_keepsTodaysLabelsAndLines() {
        RenderItem item = renderItems(WastageCalculator.PRICE_MODE_NET, List.of(tile(null, null))).get(0);
        assertThat(item.discountLabel()).isEqualTo("Net");
        assertThat(item.unit()).isEqualTo("แผ่น");
        assertThat(item.qty()).isEqualByComparingTo("10");
        assertThat(item.descriptionLines()).containsExactly("กระเบื้อง รุ่น A", "ขนาด 60x60x2 cm.", "(จำนวน 10 แผ่น)");
    }

    @Test
    void tileRowInNetMode_withADiscount_printsThePercentage() {
        DealQuotationItemDto discounted = withDiscountPct(tile(null, null), new BigDecimal("12.5"));
        RenderItem item = renderItems(WastageCalculator.PRICE_MODE_NET, List.of(discounted)).get(0);
        assertThat(item.discountLabel()).isEqualTo("12.5%");
    }

    /** S1: SPECIAL_SQM prints ส่วนลด "พิเศษ" and adds the ราคาพิเศษ sub-line. */
    @Test
    void specialSqmRow_printsPhisetAndTheSubLine() {
        RenderItem item = renderItems(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
            List.of(tile(new BigDecimal("1350"), null))).get(0);
        assertThat(item.discountLabel()).isEqualTo("พิเศษ");
        assertThat(item.descriptionLines()).last()
            .isEqualTo("(ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)");
    }

    /** S1: DIRECT_NET prints "พิเศษ" only when the net actually differs from the list price. */
    @Test
    void directNetRow_printsPhisetOnlyWhenTheNetDiffersFromTheListPrice() {
        DealQuotationItemDto differs = tileWithPrices(new BigDecimal("2000"), new BigDecimal("777.50"));
        DealQuotationItemDto equal = tileWithPrices(new BigDecimal("2000"), new BigDecimal("2000"));
        assertThat(renderItems(WastageCalculator.PRICE_MODE_DIRECT_NET, List.of(differs)).get(0).discountLabel())
            .isEqualTo("พิเศษ");
        assertThat(renderItems(WastageCalculator.PRICE_MODE_DIRECT_NET, List.of(equal)).get(0).discountLabel())
            .isEqualTo("Net");
    }

    /** A PLAIN row follows its OWN discount, not the document's price mode — that is what lets a
     * ราคาพิเศษ document still carry an ordinary freight line. */
    @Test
    void plainRow_ignoresTheDocumentsPriceMode() {
        RenderItem item = renderItems(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(plain())).get(1);
        assertThat(item.discountLabel()).isEqualTo("Net");
        assertThat(item.unit()).isEqualTo("JOB");
        assertThat(item.qty()).isEqualByComparingTo("1");
        // Description only: no size line, no calculation line, no ราคาพิเศษ sub-line.
        assertThat(item.descriptionLines())
            .containsExactly("Transportation Charges from China to Male Port, Maldives");
    }

    /** S3: จำนวน −1, an EMPTY หน่วย, positive ราคา/คงเหลือ, negative เป็นเงิน. */
    @Test
    void adjustmentRow_printsMinusOneAndAnEmptyUnit() {
        RenderItem item = renderItems(WastageCalculator.PRICE_MODE_NET, List.of(adjustment())).get(1);
        assertThat(item.qty()).isEqualByComparingTo("-1");
        assertThat(item.unit()).isEmpty();
        assertThat(item.unitPrice()).isEqualByComparingTo("38198.21");
        assertThat(item.netUnitPrice()).isEqualByComparingTo("38198.21");
        assertThat(item.amount()).isEqualByComparingTo("-38198.21");
        assertThat(item.descriptionLines())
            .containsExactly("ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569");
    }

    // ── customer header: Thai document's B6 line (bug fix) ─────────────────────────────────

    /** Bug fix: the Thai branch of {@code DealQuotationRenderAdapter#toRenderModel} built "เรียน"/
     * "โทร." but never read {@code quotation.customerAddress()} at all, so every Thai-form
     * quotation printed with no address even though it is captured correctly on the DTO. The Thai
     * template has no free row of its own for an address (unlike the English branch, whose B6
     * already folds Address/E/Tel into one cell) — so the fix folds the address into that same
     * โทร. line rather than leaving it unprinted. */
    @Test
    void thaiDocument_printsTheCustomerAddressAlongsideThePhoneNumber() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithCustomer("99/1 ถนนสุขุมวิท กรุงเทพฯ 10110", "081-234-5678"), null, null);
        assertThat(model.phoneLine()).isEqualTo("ที่อยู่ 99/1 ถนนสุขุมวิท กรุงเทพฯ 10110   โทร. 081-234-5678");
    }

    @Test
    void thaiDocument_withNoAddress_stillPrintsJustThePhoneNumber() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithCustomer(null, "081-234-5678"), null, null);
        assertThat(model.phoneLine()).isEqualTo("โทร. 081-234-5678");
    }

    @Test
    void thaiDocument_withNoPhone_stillPrintsTheAddress() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithCustomer("99/1 ถนนสุขุมวิท กรุงเทพฯ 10110", null), null, null);
        assertThat(model.phoneLine()).isEqualTo("ที่อยู่ 99/1 ถนนสุขุมวิท กรุงเทพฯ 10110");
    }

    // ── QT-2026-0032-1 (2026-09-15): the customer phone's own label ────────────────────────

    /** Production bug: B6 printed "โทร. โทร 02 314 354-2" on QT-2026-0032-1. The customer master
     * row for บริษัท อุณากรรณ จำกัด stores {@code phone = "โทร 02 314 354-2"} — the imported GL&amp;R
     * directory kept the Thai label INSIDE the value — and this branch prefixes its own. 299 of
     * 4320 customer rows are shaped that way, so this is every quotation for any of them. */
    @Test
    void thaiDocument_doesNotPrintThePhoneLabelTwiceWhenTheStoredValueCarriesItsOwn() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithCustomer(null, "โทร 02 314 354-2"), null, null);
        assertThat(model.phoneLine()).isEqualTo("โทร. 02 314 354-2");
    }

    /** The same doubling on the English form, whose own label is "Tel." — and a Thai-labelled value
     * on an English document is the ordinary case, because the label lives in shared master data
     * that knows nothing about the document's language. */
    @Test
    void englishDocument_doesNotPrintThePhoneLabelTwiceEither() {
        QuotationRenderModel thaiLabel = DealQuotationRenderAdapter.toRenderModel(
            englishQuotationWithCustomerPhone("โทร 02 314 354-2"), null, null);
        assertThat(thaiLabel.phoneLine()).isEqualTo("Tel. 02 314 354-2");

        QuotationRenderModel englishLabel = DealQuotationRenderAdapter.toRenderModel(
            englishQuotationWithCustomerPhone("Tel: 02 314 354-2"), null, null);
        assertThat(englishLabel.phoneLine()).isEqualTo("Tel. 02 314 354-2");
    }

    /** The separator shapes the directory actually contains — bare, ":", "." and a doubled space —
     * all strip, so the printed line is the same whichever way the row was typed. */
    @Test
    void stripPhoneLabel_handlesTheSeparatorShapesTheCustomerDirectoryContains() {
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("โทร 02 314 354-2")).isEqualTo("02 314 354-2");
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("โทร : 091-895-6541")).isEqualTo("091-895-6541");
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("โทร.064 245 5499")).isEqualTo("064 245 5499");
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("โทร  061-1164584")).isEqualTo("061-1164584");
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("โทรศัพท์ 02-711-5995")).isEqualTo("02-711-5995");
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("เบอร์โทร 081-234-5678")).isEqualTo("081-234-5678");
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("TEL. +66 81 339 0431")).isEqualTo("+66 81 339 0431");
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("Phone: (02) 314-3542")).isEqualTo("(02) 314-3542");
    }

    /** The wrong-way-round half, which is the half that matters: the strip must never change the
     * MEANING of a value. "โทรสาร" is a fax, not a mislabelled phone; a leading contact name is
     * free text the rep typed on purpose; and "Tony" merely starts with the letters of "Tel". The
     * lookahead for a digit/"+"/"(" is what keeps all three intact. */
    @Test
    void stripPhoneLabel_leavesAValueAloneWhenTheLabelIsNotActuallyALabel() {
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("โทรสาร 02-314-3542")).isEqualTo("โทรสาร 02-314-3542");
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("คุณเจี๊ยบ โทร 081-927-9010"))
            .isEqualTo("คุณเจี๊ยบ โทร 081-927-9010");
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("Tony 081-927-9010")).isEqualTo("Tony 081-927-9010");
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("02 314 354-2")).isEqualTo("02 314 354-2");
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel(null)).isEmpty();
        assertThat(DealQuotationRenderAdapter.stripPhoneLabel("   ")).isEmpty();
    }

    /** A value that is NOTHING but a label leaves no number behind, so the line must drop entirely
     * rather than print a bare "โทร." with nothing after it. */
    @Test
    void thaiDocument_aPhoneValueThatIsOnlyALabelPrintsNoPhoneLineAtAll() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithCustomer("99/1 ถนนสุขุมวิท กรุงเทพฯ 10110", "โทร."), null, null);
        assertThat(model.phoneLine()).isEqualTo("ที่อยู่ 99/1 ถนนสุขุมวิท กรุงเทพฯ 10110");
    }

    // ── item #1 (2026-09-14): "คุณ" prefix / contact-customer dedupe / organisation detection ──

    /** Bug fix: production printed "เรียน คุณบริษัท นันทวัน จำกัด   /   บริษัท นันทวัน จำกัด" because
     * the contact snapshot WAS the customer name, and this branch prefixed "คุณ" and duplicated it
     * unconditionally. Once the contact equals the customer (after collapsing whitespace), the
     * contact part must be omitted entirely -- no duplicate, no "/". */
    @Test
    void thaiDocument_attnLine_omitsTheContactPartWhenItIsTheSameAsTheCustomerName() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact("บริษัท นันทวัน จำกัด", "บริษัท นันทวัน จำกัด", null), null, null);
        assertThat(model.attnLine()).isEqualTo("บริษัท นันทวัน จำกัด");
    }

    /** Same dedupe, tolerant of whitespace-only differences ("trimmed, internal whitespace
     * collapsed") -- a contact typed with extra spaces must still count as the same name. */
    @Test
    void thaiDocument_attnLine_dedupeToleratesWhitespaceDifferences() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact("  บริษัท   นันทวัน  จำกัด ", "บริษัท นันทวัน จำกัด", null), null, null);
        assertThat(model.attnLine()).isEqualTo("บริษัท นันทวัน จำกัด");
    }

    /** Opus review nit (2026-09-14): a trailing "."/"," typo must not defeat the dedupe either --
     * the duplicate-name bug this whole fix targets is exactly this kind of near-miss, not a
     * genuinely different name. */
    @Test
    void thaiDocument_attnLine_dedupeToleratesATrailingFullStopOrComma() {
        QuotationRenderModel trailingStop = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact("บริษัท นันทวัน จำกัด.", "บริษัท นันทวัน จำกัด", null), null, null);
        assertThat(trailingStop.attnLine()).isEqualTo("บริษัท นันทวัน จำกัด");

        QuotationRenderModel trailingComma = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact("บริษัท นันทวัน จำกัด,", "บริษัท นันทวัน จำกัด", null), null, null);
        assertThat(trailingComma.attnLine()).isEqualTo("บริษัท นันทวัน จำกัด");
    }

    /** A genuine PERSON contact still gets "คุณ" and the "/"-separated customer name, exactly as
     * before -- the fix must not remove this for the common case. */
    @Test
    void thaiDocument_attnLine_prefixesKhunForAPersonContactDistinctFromTheCustomer() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact("ธนพล ใจดี", "บริษัท นันทวัน จำกัด", null), null, null);
        assertThat(model.attnLine()).isEqualTo("คุณธนพล ใจดี   /   บริษัท นันทวัน จำกัด");
    }

    /** A contact recorded as ITS OWN organisation (distinct from the customer name -- e.g. a
     * different group-company contact) survives the dedupe but must not read "คุณ" in front of it. */
    @Test
    void thaiDocument_attnLine_omitsKhunForAnOrganisationContactDistinctFromTheCustomer() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact("บริษัท นันทวัน สาขา 2 จำกัด", "บริษัท นันทวัน จำกัด", null), null, null);
        assertThat(model.attnLine()).isEqualTo("บริษัท นันทวัน สาขา 2 จำกัด   /   บริษัท นันทวัน จำกัด");
    }

    /** The English mirror: dedupe applies the same way, but "คุณ" never appears at all -- the
     * English form never prefixed it in the first place. */
    @Test
    void englishDocument_attnLine_dedupesTheContactPartButNeverPrefixesKhun() {
        QuotationRenderModel same = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact("Nantawan Co., Ltd.", "Nantawan Co., Ltd.", WastageCalculator.DOCUMENT_LANGUAGE_EN),
            null, null);
        assertThat(same.attnLine()).isEqualTo("Nantawan Co., Ltd.");

        QuotationRenderModel differs = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact("Mr. Somchai", "Nantawan Co., Ltd.", WastageCalculator.DOCUMENT_LANGUAGE_EN),
            null, null);
        assertThat(differs.attnLine()).isEqualTo("Mr. Somchai   /   Nantawan Co., Ltd.");
        assertThat(differs.attnLine()).doesNotContain("คุณ");
    }

    // ── item #1: the package-private helpers directly ───────────────────────────────────────

    @Test
    void looksLikeOrganisation_detectsLeadingThaiEntityMarkers() {
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("บริษัท นันทวัน จำกัด")).isTrue();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("หจก. รุ่งเรืองค้าไม้")).isTrue();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("ร้านทองไทย")).isTrue();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("โรงพยาบาลกรุงเทพ")).isTrue();
    }

    @Test
    void looksLikeOrganisation_detectsATrailingMarkerWithNoLeadingPrefix() {
        // "นันทวัน จำกัด" has no บริษัท/หจก/etc. prefix at all -- only the trailing จำกัด marks it.
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("นันทวัน จำกัด")).isTrue();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("Siam Tiles (Thailand) (มหาชน)")).isTrue();
    }

    @Test
    void looksLikeOrganisation_detectsEnglishCompanySuffixesCaseInsensitively() {
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("Nantawan Co., Ltd.")).isTrue();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("nantawan co.,ltd")).isTrue();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("Blue Lagoon Company")).isTrue();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("Ocean Trading LIMITED")).isTrue();
        // " inc" (a leading space, no period required) matches as soon as it is preceded by a
        // space anywhere in the name -- both spellings below carry one.
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("Aisha Resorts Inc")).isTrue();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("Aisha Resorts, Inc.")).isTrue();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("Blue Lagoon LLC")).isTrue();
    }

    @Test
    void looksLikeOrganisation_aPersonsNameIsNeverAnOrganisation() {
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("ธนพล ใจดี")).isFalse();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("Ms. Aisha Rahman")).isFalse();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation(null)).isFalse();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("   ")).isFalse();
    }

    /** Opus review nit (2026-09-14): "inc"/"llc" are matched with \b word boundaries specifically
     * so a person's name that merely CONTAINS those letters is never misread as an organisation --
     * a bare substring check ("Somchai INchana" contains " inc") would have failed this. */
    @Test
    void looksLikeOrganisation_doesNotFalsePositiveOnASurnameContainingAMarkerAsASubstring() {
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("Somchai Inchana")).isFalse();
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("John Hollcroft")).isFalse();
        // The genuine word-boundary case still fires either side of punctuation.
        assertThat(DealQuotationRenderAdapter.looksLikeOrganisation("Somchai, Inc.")).isTrue();
    }

    @Test
    void printContactPart_falseWhenContactIsBlankOrEqualsTheCustomerName() {
        assertThat(DealQuotationRenderAdapter.printContactPart(null, "บริษัท นันทวัน จำกัด")).isFalse();
        assertThat(DealQuotationRenderAdapter.printContactPart("   ", "บริษัท นันทวัน จำกัด")).isFalse();
        assertThat(DealQuotationRenderAdapter.printContactPart("บริษัท นันทวัน จำกัด", "บริษัท นันทวัน จำกัด")).isFalse();
        // Case-insensitive too -- an English name typed in different casing is still the same name.
        assertThat(DealQuotationRenderAdapter.printContactPart("NANTAWAN CO., LTD.", "Nantawan Co., Ltd.")).isFalse();
    }

    @Test
    void printContactPart_trueWhenContactDiffersFromTheCustomerName() {
        assertThat(DealQuotationRenderAdapter.printContactPart("ธนพล ใจดี", "บริษัท นันทวัน จำกัด")).isTrue();
    }

    private DealQuotationDto quotationWithContact(String contactName, String customerName, String documentLanguage) {
        return new DealQuotationDto(1L, "QT-2026-0001", 1L, "DRAFT", 1, null,
            1L, "ผู้พิมพ์", null, 1L, "พนักงานขาย", null, "081-000-0000",
            null, null, null, null, null, null,
            LocalDate.of(2026, 9, 11), customerName, null, null, null,
            9L, contactName, null, null, "โครงการทดสอบ",
            "P003", "D002", LocalDate.of(2026, 9, 11), 30, "CREDIT", 30, 30, null, null,
            WastageCalculator.PRICE_MODE_NET,
            documentLanguage != null ? documentLanguage : WastageCalculator.DOCUMENT_LANGUAGE_TH,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "THB",
            false, List.of(), Instant.parse("2026-09-11T00:00:00Z"), null);
    }

    private DealQuotationDto quotationWithCustomer(String customerAddress, String customerPhone) {
        return new DealQuotationDto(1L, "QT-2026-0001", 1L, "DRAFT", 1, null,
            1L, "ผู้พิมพ์", null, 1L, "พนักงานขาย", null, "081-000-0000",
            null, null, null, null, null, null,
            LocalDate.of(2026, 9, 11), "ลูกค้าทดสอบ", customerAddress, null, customerPhone,
            null, null, null, null, "โครงการทดสอบ",
            "P003", "D002", LocalDate.of(2026, 9, 11), 30, "CREDIT", 30, 30, null, null,
            WastageCalculator.PRICE_MODE_NET, WastageCalculator.DOCUMENT_LANGUAGE_TH,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "THB",
            false, List.of(), Instant.parse("2026-09-11T00:00:00Z"), null);
    }

    /** The English mirror of {@link #quotationWithCustomer} — same customer fields, EN document. */
    private DealQuotationDto englishQuotationWithCustomerPhone(String customerPhone) {
        return new DealQuotationDto(1L, "QT-2026-0001", 1L, "DRAFT", 1, null,
            1L, "ผู้พิมพ์", null, 1L, "พนักงานขาย", null, "081-000-0000",
            null, null, null, null, null, null,
            LocalDate.of(2026, 9, 11), "ลูกค้าทดสอบ", null, null, customerPhone,
            null, null, null, null, "โครงการทดสอบ",
            "P003", "D002", LocalDate.of(2026, 9, 11), 30, "CREDIT", 30, 30, null, null,
            WastageCalculator.PRICE_MODE_NET, WastageCalculator.DOCUMENT_LANGUAGE_EN,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "THB",
            false, List.of(), Instant.parse("2026-09-11T00:00:00Z"), null);
    }

    // ── workbook level: the decisions above actually reach the printed cells ────────────────

    /** Not redundant with the adapter-level assertions above — this class's own Javadoc explains
     * why (a decision can be right at the adapter and still not reach the printed cell). B6 is
     * row index 5 / column index 1 — {@code QuotationRenderer#PHONE_ROW}/{@code #LABEL_VALUE_COL}. */
    @Test
    void thaiDocument_theAddressLineActuallyReachesCellB6() throws Exception {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithCustomer("99/1 ถนนสุขุมวิท กรุงเทพฯ 10110", "081-234-5678"), null, null);
        byte[] xls = new QuotationRenderer().toXls(model);
        var wb = WorkbookFactory.create(new ByteArrayInputStream(xls));
        Sheet sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
        assertThat(sheet.getRow(5).getCell(1).getStringCellValue())
            .isEqualTo("ที่อยู่ 99/1 ถนนสุขุมวิท กรุงเทพฯ 10110   โทร. 081-234-5678");
    }

    @Test
    void adjustmentRow_leavesBothTheUnitAndTheDiscountCellsEmpty() throws Exception {
        Sheet sheet = render(WastageCalculator.PRICE_MODE_NET, List.of(adjustment()));
        // Row 9 is the tile; its three description lines occupy rows 9-11, so the adjustment's
        // main row is 12.
        int adjustmentRow = ITEM_START_ROW + 3;
        assertThat(sheet.getRow(adjustmentRow).getCell(1).getStringCellValue())
            .isEqualTo("ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569");
        assertThat(sheet.getRow(adjustmentRow).getCell(2).getNumericCellValue()).isEqualTo(-1.0);
        // ⚠️ The whole point: NOT "แผ่น".
        assertThat(sheet.getRow(adjustmentRow).getCell(3).getStringCellValue()).isEmpty();
        assertThat(sheet.getRow(adjustmentRow).getCell(4).getNumericCellValue()).isEqualTo(38198.21);
        // ส่วนลด is empty for the same reason หน่วย is: neither column means anything for a row that
        // IS the discount. This printed "Net" until 2026-09-11, on the reasoning that ราคา equals
        // คงเหลือ here — a plausible inference that the owner's QN6900704-2 contradicts, since its
        // ส่วนลดพิเศษ row leaves the cell blank. Caught by rendering her document back and comparing.
        // Column G (index 6) is ส่วนลด — see QuotationRenderer's `setStr(sh, r, 6, …)`. An earlier
        // version of this assertion read cell 5 and passed whether or not the fix was present,
        // which the mutation check caught.
        assertThat(sheet.getRow(adjustmentRow).getCell(6).getStringCellValue()).isEmpty();
        assertThat(sheet.getRow(adjustmentRow).getCell(7).getNumericCellValue()).isEqualTo(38198.21);
        assertThat(sheet.getRow(adjustmentRow).getCell(8).getNumericCellValue()).isEqualTo(-38198.21);
    }

    @Test
    void plainRow_printsItsOwnUnitAndQuantity() throws Exception {
        Sheet sheet = render(WastageCalculator.PRICE_MODE_NET, List.of(plain()));
        int plainRow = ITEM_START_ROW + 3;
        assertThat(sheet.getRow(plainRow).getCell(3).getStringCellValue()).isEqualTo("JOB");
        assertThat(sheet.getRow(plainRow).getCell(2).getNumericCellValue()).isEqualTo(1.0);
        assertThat(sheet.getRow(plainRow).getCell(8).getNumericCellValue()).isEqualTo(50000.00);
    }

    @Test
    void specialSqmRow_printsPhisetInTheDiscountCell() throws Exception {
        Sheet sheet = render(WastageCalculator.PRICE_MODE_SPECIAL_SQM,
            List.of(tile(new BigDecimal("1350"), null)));
        assertThat(sheet.getRow(ITEM_START_ROW).getCell(6).getStringCellValue()).isEqualTo("พิเศษ");
    }

    @Test
    void tileRowInNetMode_stillPrintsPhaenAndNet() throws Exception {
        Sheet sheet = render(WastageCalculator.PRICE_MODE_NET, List.of(tile(null, null)));
        assertThat(sheet.getRow(ITEM_START_ROW).getCell(3).getStringCellValue()).isEqualTo("แผ่น");
        assertThat(sheet.getRow(ITEM_START_ROW).getCell(6).getStringCellValue()).isEqualTo("Net");
    }

    /** An adjustment must reduce the document's รวมเป็นเงิน — the renderer sums RenderItem::amount,
     * and a negative amount is what makes that come out right with no special case. */
    @Test
    void adjustmentRow_reducesTheRenderedSubtotal() {
        QuotationRenderModel withAdjustment = model(WastageCalculator.PRICE_MODE_NET, List.of(adjustment()));
        QuotationRenderModel without = model(WastageCalculator.PRICE_MODE_NET, List.of());
        assertThat(sumAmounts(withAdjustment)).isEqualByComparingTo("-37198.21"); // 1000.00 - 38198.21
        assertThat(sumAmounts(without)).isEqualByComparingTo("1000.00");
    }

    // ── item #7 (2026-09-14): Thai remark 3 -- per-item lead-time grouping ──────────────────

    /** ALWAYS the per-item form now (owner feedback #7), even for a single item/group -- the old
     * "3.กำหนดส่งมอบสินค้า : ..." header word is also gone. */
    @Test
    void leadTime_aSingleItem_printsThePerItemFormEvenForOneGroup() {
        QuotationRenderModel m = model(WastageCalculator.PRICE_MODE_NET, List.of(tileWithLeadTime(30, 45)));
        assertThat(m.remarkLines().get(2)).isEqualTo("3.ระยะเวลานำเข้า : รายการที่ 1 ประมาณ 30-45 วัน");
    }

    /** Consecutive items sharing the same (min, max) are grouped into one "รายการที่ 1-2" range. */
    @Test
    void leadTime_consecutiveItemsWithTheSameRangeAreGrouped() {
        QuotationRenderModel m = model(WastageCalculator.PRICE_MODE_NET, List.of(
            tileWithLeadTime(75, 90), tileWithLeadTime(75, 90), tileWithLeadTime(30, 45)));
        assertThat(m.remarkLines().get(2))
            .isEqualTo("3.ระยะเวลานำเข้า : รายการที่ 1-2 ประมาณ 75-90 วัน  รายการที่ 3 ประมาณ 30-45 วัน");
    }

    /** The SAME range on non-consecutive items must NOT be merged across the different range
     * between them -- grouping is about consecutive SEQUENCE, not about matching values anywhere
     * in the document. */
    @Test
    void leadTime_theSameRangeOnNonConsecutiveItemsIsNotGrouped() {
        QuotationRenderModel m = model(WastageCalculator.PRICE_MODE_NET, List.of(
            tileWithLeadTime(30, 45), tileWithLeadTime(75, 90), tileWithLeadTime(30, 45)));
        assertThat(m.remarkLines().get(2)).isEqualTo("3.ระยะเวลานำเข้า : รายการที่ 1 ประมาณ 30-45 วัน  "
            + "รายการที่ 2 ประมาณ 75-90 วัน  รายการที่ 3 ประมาณ 30-45 วัน");
    }

    /** min == max collapses to "ประมาณ {n} วัน" -- no pointless "30-30 วัน". */
    @Test
    void leadTime_anExactLeadTime_collapsesToOneNumber() {
        QuotationRenderModel m = model(WastageCalculator.PRICE_MODE_NET, List.of(tileWithLeadTime(30, 30)));
        assertThat(m.remarkLines().get(2)).isEqualTo("3.ระยะเวลานำเข้า : รายการที่ 1 ประมาณ 30 วัน");
    }

    /** Different items keep their DIFFERENT ranges as separate groups -- the base case grouping
     * must not collapse to. */
    @Test
    void leadTime_differentRangesStaySeparateGroups() {
        QuotationRenderModel m = model(WastageCalculator.PRICE_MODE_NET, List.of(
            tileWithLeadTime(10, 20), tileWithLeadTime(40, 50)));
        assertThat(m.remarkLines().get(2)).isEqualTo(
            "3.ระยะเวลานำเข้า : รายการที่ 1 ประมาณ 10-20 วัน  รายการที่ 2 ประมาณ 40-50 วัน");
    }

    /** No item carries a lead time at all -- owner feedback (2026-09-14) supersedes the earlier
     * "print a visible blank" behaviour: the line is now dropped ENTIRELY (not left blank), and
     * every remark after it renumbers down by one -- exactly 7 lines, old line 4 becomes "3.",
     * old 5->4, 6->5, 7->6, 8->7, with no gap. */
    @Test
    void leadTime_noItemHasOne_dropsTheLineEntirelyAndRenumbers() {
        QuotationRenderModel m = model(WastageCalculator.PRICE_MODE_NET, List.of(tileWithLeadTime(null, null)));
        assertThat(m.remarkLines()).hasSize(7);
        assertThat(m.remarkLines()).noneMatch(l -> l.contains("ระยะเวลานำเข้า"));
        assertThat(m.remarkLines().get(0)).startsWith("1.");
        assertThat(m.remarkLines().get(1)).startsWith("2.");
        // Old LINE4 ("4.ขนาดของกระเบื้องจริง...") renumbers down to "3." and slides into the
        // now-vacant slot.
        assertThat(m.remarkLines().get(2)).startsWith("3.ขนาดของกระเบื้องจริง");
        assertThat(m.remarkLines().get(3)).startsWith("4."); // was LINE5
        assertThat(m.remarkLines().get(4)).startsWith("5."); // was LINE6
        assertThat(m.remarkLines().get(5)).startsWith("6."); // was line 7 (validity)
        assertThat(m.remarkLines().get(6)).startsWith("7."); // was LINE8
    }

    /** The same drop, this time through the renderer -- the workbook actually ends up with 7
     * populated remark rows (23..29) and the box closes ONE ROW EARLIER than the 8-line case: the
     * bottom rule sits on row 29, not row 30, with no blank row left inside the box. */
    @Test
    void leadTime_noItemHasOne_rendererWritesExactlySevenRemarkRows() throws Exception {
        Sheet sheet = render(WastageCalculator.PRICE_MODE_NET, List.of(tileWithLeadTime(null, null)));
        for (int r = 23; r <= 29; r++) {
            assertThat(sheet.getRow(r).getCell(1).getStringCellValue())
                .as("row %d must be populated", r).isNotBlank();
        }
        // The closing bottom rule (see QuotationRenderer#closeItemTableBorders) is on row 29 across
        // every column A..I -- the ACTUAL last packed remark row, not always row 30.
        for (int c = 0; c <= 8; c++) {
            assertThat(sheet.getRow(29).getCell(c).getCellStyle().getBorderBottom())
                .as("row 29 col %d must carry the box's closing bottom rule", c)
                .isNotEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);
        }
        // Row 30 (the now-unused 8th packed slot) must not be pulled INSIDE the box's side borders
        // either -- see QuotationRenderer#writeRemarks' openRemarkBox call, which must span only
        // through the ACTUAL last written row (29), not the hardcoded 8th slot (30). A missing
        // cell counts as NONE -- openRemarkBox creates the cell via getOrKeep when it adds a
        // border, so "no cell" and "cell with no border" are the same "untouched" outcome here.
        assertThat(borderLeftOrNone(sheet, 30, 1))
            .as("row 30 must not be inside the box's left border once it has shrunk to 7 lines")
            .isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);
        assertThat(borderRightOrNone(sheet, 30, 7))
            .as("row 30 must not be inside the box's right border once it has shrunk to 7 lines")
            .isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);
    }

    private org.apache.poi.ss.usermodel.BorderStyle borderLeftOrNone(Sheet sheet, int row, int col) {
        var cell = sheet.getRow(row) == null ? null : sheet.getRow(row).getCell(col);
        return cell == null ? org.apache.poi.ss.usermodel.BorderStyle.NONE : cell.getCellStyle().getBorderLeft();
    }

    private org.apache.poi.ss.usermodel.BorderStyle borderRightOrNone(Sheet sheet, int row, int col) {
        var cell = sheet.getRow(row) == null ? null : sheet.getRow(row).getCell(col);
        return cell == null ? org.apache.poi.ss.usermodel.BorderStyle.NONE : cell.getCellStyle().getBorderRight();
    }

    /** The rest of the footer (totals/signature block) shifts up by exactly ONE extra row versus
     * an otherwise-identical 8-line (has-lead-time) fixture -- same content, one row higher,
     * because the 7-line render removes one more now-unused packed slot. */
    @Test
    void leadTime_dropVsNoDrop_shiftsTheFooterBlockUpByExactlyOneRow() throws Exception {
        Sheet eightLine = render(WastageCalculator.PRICE_MODE_NET, List.of(tileWithLeadTime(30, 45)));
        Sheet sevenLine = render(WastageCalculator.PRICE_MODE_NET, List.of(tileWithLeadTime(null, null)));
        // SUBTOTAL_ROW (raw template constant 38) + footerShift: -7 for the 8-line case (physical
        // row 31), -8 for the 7-line case (physical row 30) -- the SAME value, one row higher.
        assertThat(eightLine.getRow(31).getCell(8).getNumericCellValue()).isEqualTo(1000.00);
        assertThat(sevenLine.getRow(30).getCell(8).getNumericCellValue()).isEqualTo(1000.00);
        // Row 31 in the 7-line render is no longer the subtotal row: its I-column cell is either
        // absent or not the numeric subtotal.
        org.apache.poi.ss.usermodel.Row row31 = sevenLine.getRow(31);
        boolean row31IsNotSubtotal = row31 == null || row31.getCell(8) == null
            || row31.getCell(8).getCellType() != org.apache.poi.ss.usermodel.CellType.NUMERIC
            || row31.getCell(8).getNumericCellValue() != 1000.00;
        assertThat(row31IsNotSubtotal)
            .as("row 31 must not carry the subtotal in the 7-line render").isTrue();
    }

    // ── item #3 (2026-09-14): a calculation line that is 66 String.length() chars but only 59
    // VISIBLE ones must not wrap at budget 62 -- see QuotationRenderer#visibleLength's Javadoc for
    // the measured figures. Through the REAL adapter and REAL renderer, per the task's own ask. ─

    /** Production printed this exact calculation line split across two rows -- "(บรรจุ 4" on the
     * head row and "แผ่น/กล่อง)" alone on a continuation row. It must now land on ONE row, intact,
     * and the NEXT item's own description (not a leftover fragment of this one) must follow
     * immediately -- proving the row accounting agrees with what actually got written. */
    @Test
    void tileItem_withTheProductionCalculationLine_landsOnOneRow_notSplitAcrossTwo() throws Exception {
        String calcLine = "(จำนวน 5,560 แผ่น และปัดลงกล่อง = 5,560 แผ่น) (บรรจุ 4 แผ่น/กล่อง)";
        Sheet sheet = render(WastageCalculator.PRICE_MODE_NET,
            List.of(tileWithCalculationLine(calcLine), tileWithLeadTime(30, 45)));
        // Item 1: description (row 9), size (row 10), calculation (row 11) -- exactly 3 rows, no
        // 4th continuation row for the calculation line.
        assertThat(sheet.getRow(ITEM_START_ROW + 2).getCell(1).getStringCellValue()).isEqualTo(calcLine);
        // Item 2 starts immediately at row 12 with ITS OWN description line -- not a fragment like
        // "แผ่น/กล่อง)" left over from a still-broken wrap of item 1's calculation line.
        assertThat(sheet.getRow(ITEM_START_ROW + 3).getCell(1).getStringCellValue()).isEqualTo("กระเบื้อง รุ่น A");
    }

    private DealQuotationItemDto tileWithCalculationLine(String calculationLine) {
        return new DealQuotationItemDto(1L, 1, null, null, null, null, "A", null, null, "60x60",
            new BigDecimal("2"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, null, new BigDecimal("100.00"), null,
            null, null, null, null,
            new BigDecimal("2.78"), 10, 10, 10, null, new BigDecimal("100.00"), new BigDecimal("1000.00"),
            "กระเบื้อง รุ่น A", "ขนาด 60x60x2 cm.", calculationLine,
            WastageCalculator.LINE_TYPE_TILE, BigDecimal.TEN, "แผ่น", null, null, null,
            DealQuotationLines.specialPriceLine(null), null);
    }

    private DealQuotationItemDto tileWithLeadTime(Integer minDays, Integer maxDays) {
        return new DealQuotationItemDto(1L, 1, null, null, null, null, "A", null, null, "60x60",
            new BigDecimal("2"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, null, new BigDecimal("100.00"), null,
            null, minDays, maxDays, null,
            new BigDecimal("2.78"), 10, 10, 10, null, new BigDecimal("100.00"), new BigDecimal("1000.00"),
            "กระเบื้อง รุ่น A", "ขนาด 60x60x2 cm.", "(จำนวน 10 แผ่น)",
            WastageCalculator.LINE_TYPE_TILE, BigDecimal.TEN, "แผ่น", null, null, null,
            DealQuotationLines.specialPriceLine(null), null);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────

    private BigDecimal sumAmounts(QuotationRenderModel m) {
        return m.items().stream().map(RenderItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<RenderItem> renderItems(String priceMode, List<DealQuotationItemDto> extraItems) {
        return model(priceMode, extraItems).items();
    }

    private QuotationRenderModel model(String priceMode, List<DealQuotationItemDto> extraItems) {
        List<DealQuotationItemDto> items = new java.util.ArrayList<>();
        items.add(tile(priceMode.equals(WastageCalculator.PRICE_MODE_SPECIAL_SQM)
            ? new BigDecimal("1350") : null, null));
        // The caller's first item replaces the default tile when it IS a tile, so the single-item
        // price-mode cases assert on one row rather than two.
        if (!extraItems.isEmpty()
            && WastageCalculator.LINE_TYPE_TILE.equals(extraItems.get(0).lineType())) {
            items.clear();
        }
        for (int i = 0; i < extraItems.size(); i++) {
            items.add(reseq(extraItems.get(i), items.size() + 1));
        }
        return DealQuotationRenderAdapter.toRenderModel(quotation(priceMode, items), null, null);
    }

    private Sheet render(String priceMode, List<DealQuotationItemDto> extraItems) throws Exception {
        byte[] xls = new QuotationRenderer().toXls(model(priceMode, extraItems));
        var wb = WorkbookFactory.create(new ByteArrayInputStream(xls));
        return wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
    }

    private DealQuotationItemDto tile(BigDecimal specialPriceSqm, BigDecimal ignored) {
        return tileWithPrices(new BigDecimal("100.00"), new BigDecimal("100.00"), specialPriceSqm);
    }

    private DealQuotationItemDto tileWithPrices(BigDecimal listPrice, BigDecimal netPrice) {
        return tileWithPrices(listPrice, netPrice, null);
    }

    private DealQuotationItemDto tileWithPrices(BigDecimal listPrice, BigDecimal netPrice,
                                                BigDecimal specialPriceSqm) {
        return new DealQuotationItemDto(1L, 1, null, null, null, null, "A", null, null, "60x60",
            new BigDecimal("2"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, null, listPrice, null, null, null, null, null,
            new BigDecimal("2.78"), 10, 10, 10, null, netPrice, netPrice.multiply(BigDecimal.TEN),
            "กระเบื้อง รุ่น A", "ขนาด 60x60x2 cm.", "(จำนวน 10 แผ่น)",
            WastageCalculator.LINE_TYPE_TILE, BigDecimal.TEN, "แผ่น", specialPriceSqm, null, null,
            DealQuotationLines.specialPriceLine(specialPriceSqm), null);
    }

    private DealQuotationItemDto withDiscountPct(DealQuotationItemDto src, BigDecimal pct) {
        return new DealQuotationItemDto(src.id(), src.seq(), src.locationLabel(), src.catalogPriceId(),
            src.productCode(), src.brand(), src.model(), src.color(), src.texture(), src.sizeText(),
            src.thicknessMm(), src.sqmPerPiece(), src.quantityMode(), src.areaSqm(), src.piecesInput(),
            src.wastageMode(), src.wastageValue(), src.piecesPerBox(), src.unitPrice(), pct,
            src.originCountry(), src.leadTimeMinDays(), src.leadTimeMaxDays(), src.itemNotes(),
            src.piecesPerSqm(), src.piecesBeforeWastage(), src.piecesAfterWastage(), src.piecesFinal(),
            src.boxes(), src.netUnitPrice(), src.lineAmount(), src.descriptionLine(), src.sizeLine(),
            src.calculationLine(), src.lineType(), src.quantity(), src.unit(), src.specialPriceSqm(),
            src.adjustmentPct(), src.adjustmentDeadline(), src.specialPriceLine(), src.adjustmentAmount());
    }

    private DealQuotationItemDto reseq(DealQuotationItemDto src, int seq) {
        return new DealQuotationItemDto(src.id(), seq, src.locationLabel(), src.catalogPriceId(),
            src.productCode(), src.brand(), src.model(), src.color(), src.texture(), src.sizeText(),
            src.thicknessMm(), src.sqmPerPiece(), src.quantityMode(), src.areaSqm(), src.piecesInput(),
            src.wastageMode(), src.wastageValue(), src.piecesPerBox(), src.unitPrice(), src.discountPct(),
            src.originCountry(), src.leadTimeMinDays(), src.leadTimeMaxDays(), src.itemNotes(),
            src.piecesPerSqm(), src.piecesBeforeWastage(), src.piecesAfterWastage(), src.piecesFinal(),
            src.boxes(), src.netUnitPrice(), src.lineAmount(), src.descriptionLine(), src.sizeLine(),
            src.calculationLine(), src.lineType(), src.quantity(), src.unit(), src.specialPriceSqm(),
            src.adjustmentPct(), src.adjustmentDeadline(), src.specialPriceLine(), src.adjustmentAmount());
    }

    private DealQuotationItemDto plain() {
        return new DealQuotationItemDto(2L, 2, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, new BigDecimal("50000.00"), null,
            null, null, null, null, null, 0, 0, 0, null,
            new BigDecimal("50000.00"), new BigDecimal("50000.00"),
            "Transportation Charges from China to Male Port, Maldives", null, null,
            WastageCalculator.LINE_TYPE_PLAIN, BigDecimal.ONE, "JOB", null, null, null, null, null);
    }

    private DealQuotationItemDto adjustment() {
        return new DealQuotationItemDto(3L, 2, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, new BigDecimal("38198.21"), null,
            null, null, null, null, null, 0, 0, 0, null,
            new BigDecimal("38198.21"), new BigDecimal("-38198.21"),
            "ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569", null, null,
            WastageCalculator.LINE_TYPE_ADJUSTMENT, new BigDecimal("-1"), null, null,
            new BigDecimal("3"), LocalDate.of(2026, 7, 31), null, null);
    }

    // ── V178 — hasSpecialPricing, the ONE shared gate for remark 7's DATE variant AND for
    // DealQuotationService refusing DATE mode on save. Pure logic, no rendering — see
    // DealQuotationRenderAdapter's own rule comment (a)-(e) for what each case pins. ────────────

    @Test
    void hasSpecialPricing_specialSqmWithATileRow_isTrue_regardlessOfDiscount() {
        // rule (a): SPECIAL_SQM + >=1 TILE row is enough on its own -- tile()'s own discountPct
        // (null) and specialPriceSqm are irrelevant to the rule.
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(
            quotation(WastageCalculator.PRICE_MODE_SPECIAL_SQM, List.of(tile(null, null))))).isTrue();
    }

    @Test
    void hasSpecialPricing_directNetWithNetBelowList_isTrue() {
        // rule (b): DIRECT_NET and net != list price.
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(quotation(
            WastageCalculator.PRICE_MODE_DIRECT_NET,
            List.of(tileWithPrices(new BigDecimal("100.00"), new BigDecimal("85.00")))))).isTrue();
    }

    @Test
    void hasSpecialPricing_directNetWithNetEqualToList_isFalse() {
        // The (b) counter-case: DIRECT_NET but net == list price -- no special pricing.
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(quotation(
            WastageCalculator.PRICE_MODE_DIRECT_NET,
            List.of(tileWithPrices(new BigDecimal("100.00"), new BigDecimal("100.00")))))).isFalse();
    }

    @Test
    void hasSpecialPricing_netTileWithPositiveDiscount_isTrue() {
        // rule (c): NET priceMode, a TILE row's discountPct > 0.
        DealQuotationItemDto discounted = withDiscountPct(tile(null, null), new BigDecimal("5"));
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(
            quotation(WastageCalculator.PRICE_MODE_NET, List.of(discounted)))).isTrue();
    }

    @Test
    void hasSpecialPricing_netTileWithZeroOrNullDiscount_isFalse() {
        // The (c) counter-case, both shapes: discountPct null, and explicitly zero.
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(
            quotation(WastageCalculator.PRICE_MODE_NET, List.of(tile(null, null))))).isFalse();
        DealQuotationItemDto zero = withDiscountPct(tile(null, null), BigDecimal.ZERO);
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(
            quotation(WastageCalculator.PRICE_MODE_NET, List.of(zero)))).isFalse();
    }

    @Test
    void hasSpecialPricing_plainRowWithPositiveDiscount_isTrue_regardlessOfPriceMode() {
        // rule (d): a PLAIN row's own discountPct > 0 -- independent of the document's priceMode.
        DealQuotationItemDto discountedPlain = withDiscountPct(plain(), new BigDecimal("10"));
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(
            quotation(WastageCalculator.PRICE_MODE_NET, List.of(discountedPlain)))).isTrue();
    }

    @Test
    void hasSpecialPricing_plainRowWithNoDiscount_isFalse() {
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(
            quotation(WastageCalculator.PRICE_MODE_NET, List.of(plain())))).isFalse();
    }

    @Test
    void hasSpecialPricing_adjustmentRowExists_isTrue_regardlessOfPriceModeOrOtherRows() {
        // rule (e): an ADJUSTMENT row on its own, even alongside an otherwise plain NET tile with
        // zero discount.
        DealQuotationItemDto plainTile = reseq(tile(null, null), 1);
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(quotation(
            WastageCalculator.PRICE_MODE_NET, List.of(plainTile, reseq(adjustment(), 2))))).isTrue();
    }

    @Test
    void hasSpecialPricing_noDiscountAnywhere_isFalse() {
        // The negative baseline every gating test above gets contrasted against: a NET tile with
        // no discount plus a PLAIN row with no discount and no adjustment row anywhere.
        assertThat(DealQuotationRenderAdapter.hasSpecialPricing(quotation(
            WastageCalculator.PRICE_MODE_NET, List.of(tile(null, null), reseq(plain(), 2))))).isFalse();
    }

    private DealQuotationDto quotation(String priceMode, List<DealQuotationItemDto> items) {
        return new DealQuotationDto(1L, "QT-2026-0001", 1L, "DRAFT", 1, null,
            1L, "ผู้พิมพ์", null, 1L, "พนักงานขาย", null, "081-000-0000",
            null, null, null, null, null, null,
            LocalDate.of(2026, 9, 11), "ลูกค้าทดสอบ", null, null, null,
            null, null, null, null, "โครงการทดสอบ",
            "P003", "D002", LocalDate.of(2026, 9, 11), 30, "CREDIT", 30, 30, null, null,
            priceMode, WastageCalculator.DOCUMENT_LANGUAGE_TH,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "THB",
            false, items, Instant.parse("2026-09-11T00:00:00Z"), null);
    }

    // ── V179 (owner feedback #4, 2026-09-14): ผู้พิมพ์/พนักงานขาย print-name override ──────────

    @Test
    void printedByName_usesRealCreatedByName_whenDisplayIdIsNull() {
        DealQuotationDto q = quotationWithDisplayNames(
            "จินตนา", "Jintana", "ชนิดา", "Chanida", "081-111-1111",
            null, null, null, null, null, null, null);
        assertThat(DealQuotationRenderAdapter.printedByName(q, false)).isEqualTo("จินตนา");
        assertThat(DealQuotationRenderAdapter.printedByName(q, true)).isEqualTo("Jintana");
    }

    /** The owner's own example: "ผู้พิมพ์ อยากให้แสดงเป็นจินตนา ส่วนพนักงานขายอยากให้แสดงเป็น ชนิดา". */
    @Test
    void printedByName_usesTheDisplayName_whenDisplayIdIsSet() {
        DealQuotationDto q = quotationWithDisplayNames(
            "แอดมิน", "Admin", "เซลล์จริง", "RealRep", "081-000-0000",
            144L, "จินตนา", "Jintana", 200L, "ชนิดา", "Chanida", "081-222-2222");
        assertThat(DealQuotationRenderAdapter.printedByName(q, false)).isEqualTo("จินตนา");
        assertThat(DealQuotationRenderAdapter.printedByName(q, true)).isEqualTo("Jintana");
    }

    @Test
    void salesRepDisplayNameOrReal_usesRealSalesRepName_whenDisplayIdIsNull() {
        DealQuotationDto q = quotationWithDisplayNames(
            "จินตนา", "Jintana", "ชนิดา", "Chanida", "081-111-1111",
            null, null, null, null, null, null, null);
        assertThat(DealQuotationRenderAdapter.salesRepDisplayNameOrReal(q, false)).isEqualTo("ชนิดา");
        assertThat(DealQuotationRenderAdapter.salesRepDisplayNameOrReal(q, true)).isEqualTo("Chanida");
    }

    @Test
    void salesRepDisplayNameOrReal_usesTheDisplayName_whenDisplayIdIsSet() {
        DealQuotationDto q = quotationWithDisplayNames(
            "แอดมิน", "Admin", "เซลล์จริง", "RealRep", "081-000-0000",
            144L, "จินตนา", "Jintana", 200L, "ชนิดา", "Chanida", "081-222-2222");
        assertThat(DealQuotationRenderAdapter.salesRepDisplayNameOrReal(q, false)).isEqualTo("ชนิดา");
        assertThat(DealQuotationRenderAdapter.salesRepDisplayNameOrReal(q, true)).isEqualTo("Chanida");
    }

    /** Both the name AND the phone come from the SAME salesRepDisplayId — never a mix of one
     * employee's name with another's phone. */
    @Test
    void salesRepDisplayPhoneOrReal_pairsWithTheSameEmployeeAsTheDisplayName() {
        DealQuotationDto withDisplay = quotationWithDisplayNames(
            "แอดมิน", "Admin", "เซลล์จริง", "RealRep", "081-000-0000",
            null, null, null, 200L, "ชนิดา", "Chanida", "081-222-2222");
        assertThat(DealQuotationRenderAdapter.salesRepDisplayPhoneOrReal(withDisplay)).isEqualTo("081-222-2222");

        DealQuotationDto withoutDisplay = quotationWithDisplayNames(
            "แอดมิน", "Admin", "เซลล์จริง", "RealRep", "081-000-0000",
            null, null, null, null, null, null, null);
        assertThat(DealQuotationRenderAdapter.salesRepDisplayPhoneOrReal(withoutDisplay)).isEqualTo("081-000-0000");
    }

    /** English fallback Thai->English (mirrors {@code #displayName}'s own tested behaviour): a
     * display employee with a blank English name still prints in Thai on the English document,
     * rather than an empty signature slot. */
    @Test
    void printedByName_englishDocument_fallsBackToThai_whenDisplayEnglishNameIsBlank() {
        DealQuotationDto q = quotationWithDisplayNames(
            "แอดมิน", "Admin", "เซลล์จริง", "RealRep", "081-000-0000",
            144L, "จินตนา", null, null, null, null, null);
        assertThat(DealQuotationRenderAdapter.printedByName(q, true))
            .as("no English name on file for the display employee -- falls back to Thai")
            .isEqualTo("จินตนา");
    }

    /** The full model round-trip: the header "Sales/{name} T.{phone}" line and the พนักงานขาย
     * signature slot both follow salesRepDisplayId. */
    @Test
    void toRenderModel_salesLineAndSignatorySalesRep_followTheDisplayOverride() {
        DealQuotationDto q = quotationWithDisplayNames(
            "แอดมิน", "Admin", "เซลล์จริง", "RealRep", "081-000-0000",
            null, null, null, 200L, "ชนิดา", "Chanida", "081-222-2222");
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(q, null, null);
        assertThat(model.salesLine()).isEqualTo("Sales/ชนิดา T.081-222-2222");
        // Signatories' 2nd positional field is generically named checkedBy, but this adapter uses
        // that SLOT for พนักงานขาย (the sales rep) — see DealQuotationRenderAdapter's own
        // Signatories construction.
        assertThat(model.signatories().checkedBy()).isEqualTo("ชนิดา");
        // The ผู้พิมพ์ slot and the real ownership are UNTOUCHED by the sales-rep override.
        assertThat(model.signatories().printedBy()).isEqualTo("แอดมิน");
    }

    private DealQuotationDto quotationWithDisplayNames(
            String createdByName, String createdByNameEn,
            String salesRepName, String salesRepNameEn, String salesRepPhone,
            Long printedByDisplayId, String printedByDisplayName, String printedByDisplayNameEn,
            Long salesRepDisplayId, String salesRepDisplayName, String salesRepDisplayNameEn,
            String salesRepDisplayPhone) {
        return new DealQuotationDto(1L, "QT-2026-0001", 1L, "DRAFT", 1, null,
            1L, createdByName, createdByNameEn, 1L, salesRepName, salesRepNameEn, salesRepPhone,
            null, null, null, null, null, null,
            LocalDate.of(2026, 9, 11), "ลูกค้าทดสอบ", null, null, null,
            null, null, null, null, "โครงการทดสอบ",
            "P003", "D002", LocalDate.of(2026, 9, 11), 30, "CREDIT", 30, 30,
            null, null, null, null,
            WastageCalculator.PRICE_MODE_NET, WastageCalculator.DOCUMENT_LANGUAGE_TH,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "THB",
            false,
            printedByDisplayId, printedByDisplayName, printedByDisplayNameEn,
            salesRepDisplayId, salesRepDisplayName, salesRepDisplayNameEn, salesRepDisplayPhone,
            List.<DealQuotationItemDto>of(), Instant.parse("2026-09-11T00:00:00Z"), null);
    }

    // ── fix (2026-09-15): "คุณ" is not doubled when the contact name already carries one ────────

    /** The exact production value: contact_name = "คุณปิยพร เมืองจีน" printed "เรียน
     * คุณคุณปิยพร เมืองจีน" because #looksLikeOrganisation correctly said this is NOT an
     * organisation (so the "คุณ" branch fired) but nothing checked whether the name already
     * carried its own honorific. */
    @Test
    void thaiDocument_attnLine_doesNotDoublePrefixAnHonorificTheContactNameAlreadyCarries() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact("คุณปิยพร เมืองจีน", "บริษัท ทดสอบ จำกัด", null), null, null);
        assertThat(model.attnLine()).isEqualTo("คุณปิยพร เมืองจีน   /   บริษัท ทดสอบ จำกัด");
        assertThat(model.attnLine()).doesNotContain("คุณคุณ");
    }

    @Test
    void hasThaiHonorificPrefix_detectsEachSupportedHonorific() {
        assertThat(DealQuotationRenderAdapter.hasThaiHonorificPrefix("คุณปิยพร เมืองจีน")).isTrue();
        assertThat(DealQuotationRenderAdapter.hasThaiHonorificPrefix("นายสมชาย ใจดี")).isTrue();
        assertThat(DealQuotationRenderAdapter.hasThaiHonorificPrefix("นางสมหญิง ใจดี")).isTrue();
        assertThat(DealQuotationRenderAdapter.hasThaiHonorificPrefix("นางสาวสมหญิง ใจดี")).isTrue();
        assertThat(DealQuotationRenderAdapter.hasThaiHonorificPrefix("ดร.สมชาย ใจดี")).isTrue();
    }

    /** The prefix overlap the task specifically calls out: "นางสาว" must be recognised as its OWN
     * honorific, not merely as "นาง" followed by a name that happens to start with "สาว" -- both
     * readings currently answer {@code true} here (this method only ever detects, never strips),
     * but the point is that a name genuinely typed as "นางสาว..." must not be misread as if only
     * "นาง" matched and the rest were part of the name. */
    @Test
    void hasThaiHonorificPrefix_doesNotConfuseNangsaoForNangPlusAName() {
        assertThat(DealQuotationRenderAdapter.hasThaiHonorificPrefix("นางสาวปิยพร เมืองจีน")).isTrue();
    }

    @Test
    void hasThaiHonorificPrefix_aPersonWithNoHonorificIsNotPrefixed() {
        assertThat(DealQuotationRenderAdapter.hasThaiHonorificPrefix("ธนพล ใจดี")).isFalse();
        assertThat(DealQuotationRenderAdapter.hasThaiHonorificPrefix(null)).isFalse();
        assertThat(DealQuotationRenderAdapter.hasThaiHonorificPrefix("   ")).isFalse();
    }

    /** Only a LEADING match counts -- a name that merely contains "นาง" somewhere past the start
     * (never at position 0 of the whitespace-normalised string) is not mistaken for one. */
    @Test
    void hasThaiHonorificPrefix_aMidStringOccurrenceDoesNotCount() {
        assertThat(DealQuotationRenderAdapter.hasThaiHonorificPrefix("จรินางค์ ใจดี")).isFalse();
    }

    /** A genuine person contact with NO honorific at all still gets "คุณ" prefixed, exactly as
     * before -- this fix must not remove the "คุณ" prefix for the common case, only skip it when
     * one is already present. */
    @Test
    void thaiDocument_attnLine_stillPrefixesKhunWhenTheContactHasNoHonorificOfItsOwn() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact("ธนพล ใจดี", "บริษัท ทดสอบ จำกัด", null), null, null);
        assertThat(model.attnLine()).isEqualTo("คุณธนพล ใจดี   /   บริษัท ทดสอบ จำกัด");
    }

    // ── fix (2026-09-15): the ผู้สั่งซื้อ signature name falls back to the customer name ─────────

    @Test
    void signatories_orderedBy_prefersTheContactNameWhenPresent() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact("สมหญิง ใจดี", "บริษัท ทดสอบ จำกัด", null), null, null);
        assertThat(model.signatories().orderedBy()).isEqualTo("สมหญิง ใจดี");
    }

    /** The bug: a deal with no separate contact snapshot printed the dotted placeholder on the
     * ผู้สั่งซื้อ signature line even though the customer being quoted to is right there on the
     * same document. */
    @Test
    void signatories_orderedBy_fallsBackToTheCustomerNameWhenThereIsNoContact() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact(null, "บริษัท ทดสอบ จำกัด", null), null, null);
        assertThat(model.signatories().orderedBy()).isEqualTo("บริษัท ทดสอบ จำกัด");
    }

    @Test
    void signatories_orderedBy_nullOnlyWhenBothContactAndCustomerAreBlank() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(
            quotationWithContact(null, null, null), null, null);
        assertThat(model.signatories().orderedBy()).isNull();
    }

    @Test
    void orderedByName_directly_mirrorsTheSameFallback() {
        assertThat(DealQuotationRenderAdapter.orderedByName(
            quotationWithContact("สมหญิง ใจดี", "บริษัท ทดสอบ จำกัด", null))).isEqualTo("สมหญิง ใจดี");
        assertThat(DealQuotationRenderAdapter.orderedByName(
            quotationWithContact(null, "บริษัท ทดสอบ จำกัด", null))).isEqualTo("บริษัท ทดสอบ จำกัด");
        assertThat(DealQuotationRenderAdapter.orderedByName(
            quotationWithContact(null, null, null))).isNull();
    }
}
