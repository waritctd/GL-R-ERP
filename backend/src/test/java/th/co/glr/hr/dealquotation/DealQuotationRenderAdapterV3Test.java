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
}
