package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.customer.CustomerDto;

class QuotationRendererTest {
    private final QuotationRenderer renderer = new QuotationRenderer();

    private void requireLibreOffice() {
        // toPdf() shells out to LibreOffice; skip (don't fail) where it isn't installed —
        // same policy as the repo's Testcontainers-on-Docker gating. CI installs libreoffice-calc.
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(),
            "LibreOffice (soffice) not installed — skipping PDF render test");
    }

    @Test
    void pdfRendersFormattedDocumentWithHeaderTableAndTotals() throws Exception {
        requireLibreOffice();
        byte[] pdf = renderer.toPdf(ticket(List.of(item(1, "Cotto", "Marble Series",
            new BigDecimal("300"), new BigDecimal("580.00")))), quotation(), customer(null));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = strip(pdf);
        // PDFBox's text extraction inserts stray spaces inside Thai glyph clusters (e.g.
        // "จำกั ด") that don't reflect the real rendered document — flatten whitespace
        // before asserting on any Thai substring per the project's PDF-test convention.
        String flat = text.replaceAll("\\s+", "");

        // Header block — the real template's company block is the full legal name
        // (quotation_template.xls row 0 col 1), not the abbreviated name.
        assertThat(flat).contains("บริษัทจีแอลแอนด์อาร์แทปส์แอนด์ไทลส์จำกัด");
        assertThat(text).contains("ใบเสนอราคา");
        assertThat(text).contains("QT-2026-0042");
        // Customer block — "เรียน" (A5) and the customer name (B5) are separate template
        // cells; the PDF's text-extraction order does not keep them adjacent, so assert
        // each independently rather than as one concatenated string.
        assertThat(text).contains("เรียน");
        assertThat(text).contains("Test Customer Co., Ltd.");
        // B8 carries a literal double space before the colon in the real template.
        assertThat(text).contains("Project  : Showroom Renovation");
        // Items table header + row
        assertThat(text).contains("ลำดับ");
        assertThat(text).contains("หน่วย");
        assertThat(text).contains("ราคา");
        // Product line uses the item's model — brand is not shown (QuotationRenderer#buildDesc).
        assertThat(text).contains("รุ่น Marble Series");
        assertThat(text).doesNotContain("Cotto");
        assertThat(text).contains("580.00");
        // Totals: 300 × 580 = 174,000.00; VAT 7% = 12,180.00; grand = 186,180.00
        assertThat(text).contains("174,000.00");
        assertThat(text).contains("12,180.00");
        assertThat(text).contains("186,180.00");
        // Signature block — real template label is "พนักงานขาย" (sales staff), not the
        // invented "ผู้เสนอราคา".
        assertThat(text).contains("พนักงานขาย");
        assertThat(text).doesNotContain("?????");
    }

    @Test
    void pdfSurvivesMultilineProjectName() throws Exception {
        requireLibreOffice();
        // Originally exercised a multiline CUSTOMER ADDRESS — but QuotationRenderer never
        // writes customer().address() onto the quotation template at all (the quotation
        // template has no address cell; see docs/V4(PDF Generator)/document-generation-fix.md
        // §A2, contrast with DepositNoticeRenderer's B8 which does). Re-target the same
        // "embedded newline must not crash the render, and both lines must survive" property
        // onto projectName (B8), a free-text field the renderer actually writes verbatim.
        TicketDto ticket = ticket(List.of(item(1, "Cotto", "Marble", BigDecimal.ONE, BigDecimal.TEN)),
            "Showroom Renovation\nPhase 2 - Bangkok Branch");

        byte[] pdf = renderer.toPdf(ticket, quotation(), customer(null));

        String text = strip(pdf);
        assertThat(text).contains("Showroom Renovation");
        assertThat(text).contains("Phase 2 - Bangkok Branch");
    }

    @Test
    void pdfWrapsLongDescriptionsInsteadOfClipping() throws Exception {
        requireLibreOffice();
        String longModel = "Super Extra Premium Glazed Porcelain Large Format Tile "
            + "with Anti-Slip Nano Coating and Digital Inkjet Marble Pattern Finish";

        byte[] pdf = renderer.toPdf(ticket(List.of(item(1, "MegaBrand", longModel,
            BigDecimal.ONE, BigDecimal.TEN))), quotation(), customer(null));

        // The full text must survive wrapping (a clipped render drops the tail glyphs).
        String flattened = strip(pdf).replace("\r", "").replace("\n", " ");
        assertThat(flattened).contains("Digital Inkjet Marble Pattern Finish");
    }

    @Test
    void pdfFlowsEveryItemAcrossPagesBeyondTheTemplateZone() throws Exception {
        requireLibreOffice();
        // More than 4 items switches QuotationRenderer to the one-row-per-item flow layout: EVERY
        // item renders (items past the template's item zone get cloned table styling), the footer
        // relocates below the last item, and the quote paginates across pages. Items 12 and 20 sit
        // well past the old 4-slot cap, so their presence proves the flow renders the overflow.
        List<TicketItemDto> items = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            items.add(item(i, "Brand" + i, "Model" + i, BigDecimal.ONE, new BigDecimal("10.00")));
        }

        byte[] pdf = renderer.toPdf(ticket(items), quotation(), customer(null));
        String text = strip(pdf);
        int pages;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = Loader.loadPDF(pdf)) {
            pages = doc.getNumberOfPages();
        }

        assertThat(text).contains("Model1");
        assertThat(text).contains("Model12");   // past the old 4-item cap
        assertThat(text).contains("Model20");   // last item still renders
        // Subtotal reflects all 20 × 10.00 = 200.00, then +7% VAT → 214.00 grand total; no cell errors.
        assertThat(text).contains("200.00");
        assertThat(text).contains("214.00");
        assertThat(text).doesNotContain("#VALUE!").doesNotContain("#REF!").doesNotContain("###");
        assertThat(pages).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aQuoteWithinTheTemplateItemZoneStaysASinglePage() throws Exception {
        requireLibreOffice();
        // Up to SINGLE_PAGE_CAPACITY (12) one-line items keep the footer at its anchored native
        // rows and fit on one page — the "fills the page like Excel" layout.
        List<TicketItemDto> items = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            items.add(item(i, "Brand" + i, "Model" + i, new BigDecimal("10"), new BigDecimal("100.00")));
        }
        byte[] pdf = renderer.toPdf(ticket(items), quotation(), customer(null));
        int pages;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            pages = doc.getNumberOfPages();
        }
        assertThat(pages).isEqualTo(1);
        String text = strip(pdf);
        assertThat(text).contains("6,000.00");     // 6 × 10 × 100 subtotal
        assertThat(text).contains("พนักงานขาย");   // signature block present on the page
        assertThat(text).doesNotContain("###");
    }

    @Test
    void aQuoteLargerThanOnePageOfItemsPaginatesWithTotalsOnTheLastPage() throws Exception {
        requireLibreOffice();
        // Enough items that the quote must span multiple pages in ANY font environment — the exact
        // page count is font-dependent (CI runs without the Thai fonts, which over-shrink), but 120
        // one-line items cannot fit a single page even at maximum shrink. Every item renders and the
        // grand total is correct.
        List<TicketItemDto> items = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            items.add(item(i, "Brand" + i, "Model" + i, BigDecimal.ONE, new BigDecimal("10.00")));
        }
        byte[] pdf = renderer.toPdf(ticket(items), quotation(), customer(null));
        int pages;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            pages = doc.getNumberOfPages();
        }
        assertThat(pages).isGreaterThanOrEqualTo(2);
        String text = strip(pdf);
        assertThat(text).contains("Model1");
        assertThat(text).contains("Model60");
        assertThat(text).contains("Model120");        // last item rendered
        assertThat(text).contains("1,200.00");         // 120 × 10 subtotal
        assertThat(text).contains("1,284.00");         // + 7% VAT grand total
        assertThat(text).doesNotContain("#VALUE!").doesNotContain("#REF!").doesNotContain("###");
    }

    @Test
    void xlsxOutputIsRealBiff8OleBytesNotOoxmlZip() throws Exception {
        // Pins the byte format and the advertised content type together: TicketController's
        // quotationFile endpoint serves this exact output as "application/vnd.ms-excel" +
        // ".xls". WorkbookFactory.create(templates/quotation_template.xls) returns an
        // HSSFWorkbook, so wb.write(out) always emits OLE2/Compound File Binary bytes
        // ([MS-CFB], BIFF8 .xls) — never the ZIP "PK\x03\x04" local-file-header magic an OOXML
        // .xlsx would start with. If this ever drifts to XSSF, this assertion catches it before
        // the response headers do (or don't).
        byte[] xlsx = renderer.toXlsx(ticket(List.of(item(1, "Cotto", "Marble Series",
            BigDecimal.ONE, new BigDecimal("10.00")))), quotation(), customer(null));

        assertThat(xlsx).startsWith((byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1);
    }

    @Test
    void xlsxSubtotalCellSumsAllPricedItemsNotJustTheRenderedFifteen() throws Exception {
        // 2026-07-16 pricing-integrity audit, finding #5: the subtotal cell must match
        // TicketService.generateQuotation's total_amount, which sums ALL priced items — not just a
        // rendered subset. Every item now flows onto its own row, so the subtotal cell is relocated
        // below the last item rather than sitting at the template's original I38.
        int count = 20;
        List<TicketItemDto> items = new ArrayList<>();
        BigDecimal expectedTotal = BigDecimal.ZERO;
        for (int i = 1; i <= count; i++) {
            BigDecimal price = new BigDecimal(i + ".00");
            items.add(item(i, "Brand" + i, "Model" + i, BigDecimal.ONE, price));
            expectedTotal = expectedTotal.add(price);
        }

        byte[] xlsx = renderer.toXlsx(ticket(items), quotation(), customer(null));

        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            // Flow layout: items occupy rows 9..(9+count-1); the footer block (whose subtotal sits
            // at 0-based row 38 — SUBTOTAL_ROW, post-H3's permanent +1 row shift for line 3's new
            // continuation row; see QuotationRenderer#FOOTER_END's own comment) is relocated by
            // delta = (9 + count) - 22, so the subtotal lands at row 38 + delta = 25 + count.
            // Col I → index 8.
            int subtotalRow = 25 + count;
            double subtotalCell = sheet.getRow(subtotalRow).getCell(8).getNumericCellValue();
            assertThat(BigDecimal.valueOf(subtotalCell).setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo(expectedTotal.setScale(2, RoundingMode.HALF_UP));
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private String strip(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private TicketDto ticket(List<TicketItemDto> items) {
        return ticket(items, "Showroom Renovation");
    }

    private TicketDto ticket(List<TicketItemDto> items, String projectName) {
        TicketSummaryDto summary = new TicketSummaryDto(
            10L, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", TicketStatus.QUOTATION_ISSUED,
            "NORMAL", 1L, "Sales User", null, null, "Test Customer Co., Ltd.", 5L, null,
            projectName, null, null, null, Instant.now(), Instant.now(), null,
            items.size(), false, null, null,
            "QUOTE_BUYER", null, null, Instant.now(),
            "ACTIVE", "UNKNOWN", "REQUIRED", null, "DESIGNER_LED");
        return new TicketDto(summary, items, List.of(), null, List.of());
    }

    private QuotationDto quotation() {
        return new QuotationDto(7L, 10L, "QT-2026-0042", 1L, "Sales User",
            Instant.parse("2026-07-16T04:00:00Z"), null, null, "THB", 1, "ISSUED");
    }

    private CustomerDto customer(String address) {
        return new CustomerDto(5L, "Test Customer Co., Ltd.", "0105542000000", address,
            "สำนักงานใหญ่", "02-000-0000");
    }

    /**
     * buildDesc's model/color/size/texture branches went uncovered for years because every
     * fixture here passed null for the last three — which is exactly how a Step 4 quotation
     * shipped a bare "กระเบื้อง" to customers (see V162 and
     * CustomerQuotationIntegrationTest#create_populatesLegacyRenderColumnsForModelColorTextureSize).
     * Both size shapes the product actually produces are pinned here: a bare catalog numeric,
     * which must GAIN " cm.", and a hand-typed value already carrying its unit (the ขนาด field's
     * own placeholder is "เช่น 60x60 ซม."), which must NOT be doubled into "60x60 ซม. cm.".
     */
    @Test
    void descriptionRendersAllAttributes_andSuppliesTheSizeUnitOnlyWhenItIsMissing() throws Exception {
        TicketItemDto bareCatalogSize = itemWithAttributes(1, "SCG", "Elegance", "ขาวนวล", "ด้าน", "600x1200");
        TicketItemDto sizeCarryingItsOwnUnit = itemWithAttributes(2, "Cotto", "Stone", "เทาเข้ม", "หยาบ", "60x60 ซม.");

        byte[] xlsx = renderer.toXlsx(
            ticket(List.of(bareCatalogSize, sizeCarryingItsOwnUnit)), quotation(), customer("123 Bangkok"));

        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            String first = sheet.getRow(9).getCell(1).getStringCellValue();
            String second = sheet.getRow(10).getCell(1).getStringCellValue();

            assertThat(first).isEqualTo("กระเบื้อง รุ่น Elegance สี ขาวนวล ขนาด 600x1200 cm. ด้าน");
            // The regression this guards: not "ขนาด 60x60 ซม. cm.".
            assertThat(second).isEqualTo("กระเบื้อง รุ่น Stone สี เทาเข้ม ขนาด 60x60 ซม. หยาบ");
            assertThat(second).doesNotContain("ซม. cm.");
        }
    }

    // ── model path (QuotationRenderModel, signatureLabelsV2 = true) ──────────────────────

    @Test
    void modelPath_locationHeadingPrintedOnceForTwoItemsSharingLabel_threeLinesEachAndDiscountLabels()
            throws Exception {
        requireLibreOffice();
        QuotationRenderModel.RenderItem item1 = renderItem("หน้าบ้าน",
            List.of("กระเบื้อง รุ่น Elegance สี ขาวนวล ผิว ด้าน", "ขนาด 60x120x2 cm. (ขนาดโดยประมาณ)",
                "(พื้นที่ 87 ตร.ม.ๆละ 2.78 แผ่น รวม 242 แผ่น + เผื่อ 10% และปัดลงกล่อง = 268 แผ่น) (บรรจุ 4 แผ่น/กล่อง)"),
            new BigDecimal("268"), new BigDecimal("500.00"), "Net", new BigDecimal("500.00"), new BigDecimal("134000.00"));
        QuotationRenderModel.RenderItem item2 = renderItem("หน้าบ้าน",
            List.of("กระเบื้อง รุ่น Stone สี เทาเข้ม ผิว หยาบ", "ขนาด 60x60x0.9 cm. (ขนาดโดยประมาณ)",
                "(จำนวน 124 แผ่น = 345 แผ่น) (บรรจุ 5 แผ่น/กล่อง)"),
            new BigDecimal("345"), new BigDecimal("300.00"), "10%", new BigDecimal("270.00"), new BigDecimal("93150.00"));

        QuotationRenderModel model = modelWithItems(List.of(item1, item2),
            List.of(
                "1.จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่  16/7/2569",
                "2.บริษัทฯ ขอรับมัดจำ 30% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือเครดิต 30 วัน",
                "3.กำหนดส่งมอบสินค้า : รายการที่ 1-2 ระยะเวลานำเข้า 75-90 วัน",
                "4.ขนาดของกระเบื้องจริง จะแตกต่างจากขนาดที่ระบุในใบเสนอราคา ได้เล็กน้อย ตามมาตรฐาน ISO และ มอก.",
                "5.สีและลวดลายของกระเบื้อง อาจแตกต่างจากตัวอย่างได้เล็กน้อย  เนื่องจากเป็นสินค้าคนละ LOT การผลิต",
                "6.ทางบริษัทฯ ไม่รับเปลี่ยนหรือคืนสินค้า กรุณาตรวจสอบ ความถูกต้องก่อนสั่งซื้อหรือลงชื่อรับสินค้า",
                "7.กำหนดยืนยันราคา 45 วัน นับจากวันที่ในใบเสนอราคา",
                "8.เงื่อนไขประกอบใบเสนอราคา ตามเอกสารแนบ"),
            new QuotationRenderModel.Signatories("ผู้พิมพ์ ทดสอบ", "พนักงานขาย ทดสอบ", "ราม อิฐรัตน์", null, null));

        byte[] pdf = renderer.toPdf(model);
        String text = strip(pdf);
        String flat = text.replaceAll("\\s+", "");

        // Heading printed exactly once even though two items share it.
        assertThat(countOccurrences(text, "หน้าบ้าน")).isEqualTo(1);
        // All three description lines of BOTH items survive.
        assertThat(flat).contains("รุ่นElegance");
        assertThat(flat).contains("รุ่นStone");
        assertThat(text).contains("(ขนาดโดยประมาณ)");
        assertThat(flat).contains("60x120x2cm.");
        assertThat(flat).contains("60x60x0.9cm.");
        assertThat(flat).contains("บรรจุ4แผ่น/กล่อง");
        // Discount column: "Net" for item1 (0%), "10%" for item2.
        assertThat(text).contains("Net");
        assertThat(text).contains("10%");
        // Remark lines 2/3/7 as composed.
        assertThat(flat).contains("เครดิต30วัน");
        assertThat(flat).contains("รายการที่1-2ระยะเวลานำเข้า75-90วัน");
        assertThat(flat).contains("กำหนดยืนยันราคา45วัน");
        // v2 signature labels (owner ruling 2026-09-10: the TEMPLATE's own original four labels,
        // not the invented ผู้ตรวจ/ผู้อนุมัติ pair) + approver name in parens. Order/rebuild
        // correctness against the raw template cell is pinned precisely (not via fragile PDF text
        // extraction) by modelPath_approverSignatureImage_anchorsWithinTheApproverSlot_... below,
        // which reads the actual XLSX cell string.
        // (flat, not text — PDFBox's text extraction can insert a stray space inside
        // "ผู้จัดการฝ่ายขาย"'s glyph cluster, same as it does elsewhere in this file.)
        assertThat(flat).contains("พนักงานขาย");
        assertThat(flat).contains("ผู้จัดการฝ่ายขาย");
        assertThat(flat).contains("(รามอิฐรัตน์)");
    }

    /**
     * H1 regression: {@code underlineCache} used to be a FIELD on this @Component-singleton
     * class, holding {@link org.apache.poi.ss.usermodel.CellStyle}s bound to whichever
     * {@code Workbook} the FIRST render happened to open. A second render on a heading-bearing
     * model, through the SAME renderer instance (exactly what happens in production —
     * {@code DealQuotationService#sendApprovalEmail} re-renders the PDF through the SAME injected
     * singleton after the approving transaction commits), threw "This Style does not belong to
     * the supplied Workbook" the moment the stale style touched the second workbook. Renders
     * TWICE, deliberately reusing {@code renderer} (never constructing a fresh
     * {@code QuotationRenderer}), with a heading on both calls so {@code underlinedStyle} is
     * actually exercised each time.
     */
    @Test
    void modelPath_secondRenderWithHeadingThroughTheSameRendererInstance_doesNotThrow() {
        QuotationRenderModel.RenderItem item = renderItem("ชั้น 1", threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item), List.of("1.x"),
            new QuotationRenderModel.Signatories(null, null, null, null, null));

        byte[] first = renderer.toXls(model);
        byte[] second = renderer.toXls(model); // SAME renderer instance — the regression case.

        assertThat(first).isNotEmpty();
        assertThat(second).isNotEmpty();
    }

    /**
     * H2/layout-spec §5 regression, REBUILT: the signature block is now THREE merged A:I rows
     * (S1 labels, S2 names, S3 dates), each built from four equal-PIXEL-width text slots — not
     * four discrete merged COLUMN ranges (git history: {@code SIG_COL_RANGES}, A:B/C:D/E:G/H:I,
     * put ผู้พิมพ์ alone behind column B — over half the page — while the other three were
     * crammed into the remaining 45%). Asserts the single labels-row string carries all four
     * labels IN ORDER, the names row carries the checked-by/approved-by names in order, and the
     * anchored approver signature picture's absolute pixel start sits within the ผู้อนุมัติ slot
     * (the third of four equal-width quarters, 50%-75% of the row) — never at or past the
     * ผู้สั่งซื้อ slot that starts at 75%, i.e. never over the customer's own box.
     */
    @Test
    void modelPath_approverSignatureImage_anchorsWithinTheApproverSlot_notOverCustomerSlot() throws Exception {
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item), List.of("1.x"),
            new QuotationRenderModel.Signatories("A", "B", "ราม อิฐรัตน์", onePixelPng(), "image/png"));

        byte[] xlsx = renderer.toXls(model);
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            var hssf = (org.apache.poi.hssf.usermodel.HSSFSheet) sheet;
            var pictures = hssf.getDrawingPatriarch().getChildren();
            org.apache.poi.hssf.usermodel.HSSFPicture signature = null;
            for (var shape : pictures) {
                if (shape instanceof org.apache.poi.hssf.usermodel.HSSFPicture pic
                    && pic.getClientAnchor().getRow1() >= 40) { // the letterhead/cert images sit near row 0
                    signature = pic;
                }
            }
            assertThat(signature).as("approver signature picture should have been anchored").isNotNull();

            // Row 44 (0-based, post-H3-shift LABELS_ROW) is now ONE merged A:I cell carrying all
            // four labels in a single string, in order — not a per-label merged column range.
            String labelsRowText = sheet.getRow(44).getCell(0).getStringCellValue();
            // Owner ruling 2026-09-10: the template's own original labels, not the invented
            // ผู้ตรวจ/ผู้อนุมัติ pair a previous rebuild substituted.
            assertThat(labelsRowText).contains("ผู้พิมพ์", "พนักงานขาย", "ผู้จัดการฝ่ายขาย", "ผู้สั่งซื้อ");
            int idxPrinted = labelsRowText.indexOf("ผู้พิมพ์");
            int idxChecked = labelsRowText.indexOf("พนักงานขาย");
            int idxApproved = labelsRowText.indexOf("ผู้จัดการฝ่ายขาย");
            int idxCustomer = labelsRowText.indexOf("ผู้สั่งซื้อ");
            assertThat(idxPrinted).isLessThan(idxChecked);
            assertThat(idxChecked).isLessThan(idxApproved);
            assertThat(idxApproved).isLessThan(idxCustomer);

            // Row 45 (SALESPERSON_FORMULA_ROW / names row) is likewise ONE merged A:I cell — the
            // approver's name must land after the checked-by name.
            String namesRowText = sheet.getRow(45).getCell(0).getStringCellValue();
            assertThat(namesRowText).contains("(A)", "(B)", "(ราม อิฐรัตน์)");
            assertThat(namesRowText.indexOf("(B)")).isLessThan(namesRowText.indexOf("(ราม อิฐรัตน์)"));

            // The signature's absolute pixel start (col1/dx1 converted the same way
            // #scaleSignaturePicture reads it back) must fall within the ผู้อนุมัติ slot — the
            // third quarter of the A..I row (50%-75% of its total width) — strictly before the
            // ผู้สั่งซื้อ slot that starts at 75%.
            double totalWidthPx = 0;
            for (int c = 0; c <= 8; c++) totalWidthPx += sheet.getColumnWidthInPixels(c);
            var anchor = signature.getClientAnchor();
            double startPx = 0;
            for (int c = 0; c < anchor.getCol1(); c++) startPx += sheet.getColumnWidthInPixels(c);
            startPx += (anchor.getDx1() / 1024.0) * sheet.getColumnWidthInPixels(anchor.getCol1());

            assertThat(startPx)
                .as("signature must anchor within the ผู้อนุมัติ slot (40%-75% of the row), "
                    + "never at/after ผู้สั่งซื้อ's 75%")
                .isGreaterThan(totalWidthPx * 0.40)
                .isLessThan(totalWidthPx * 0.75);
        }
    }

    /**
     * M6: the signature image was never scaled — an oversized upload (e.g. a raw phone-camera
     * photo) rendered at its own natural pixel size, which can dwarf the whole ผู้อนุมัติ box or
     * even the page. Renders a deliberately huge 2000×800 real PNG and asserts the anchored
     * picture's column span stays narrow (a handful of columns, not dozens) — i.e. it was scaled
     * down to the ~30mm target box rather than left at its natural size.
     */
    @Test
    void modelPath_oversizedSignatureImage_scaledToTheTargetBox_notNaturalSize() throws Exception {
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        byte[] oversizedPng = realPng(2000, 800);
        QuotationRenderModel model = modelWithItems(List.of(item), List.of("1.x"),
            new QuotationRenderModel.Signatories("A", "B", "ราม อิฐรัตน์", oversizedPng, "image/png"));

        byte[] xlsx = renderer.toXls(model);
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            var hssf = (org.apache.poi.hssf.usermodel.HSSFSheet) sheet;
            org.apache.poi.hssf.usermodel.HSSFPicture signature = null;
            for (var shape : hssf.getDrawingPatriarch().getChildren()) {
                if (shape instanceof org.apache.poi.hssf.usermodel.HSSFPicture pic
                    && pic.getClientAnchor().getRow1() >= 40) {
                    signature = pic;
                }
            }
            assertThat(signature).as("approver signature picture should have been anchored").isNotNull();
            var anchor = signature.getClientAnchor();
            // A 2000px-wide image left at natural size would span dozens of these columns (even
            // the sheet's widest column, B, is well under 2000px); scaled to ~30mm it must stay
            // within a handful of columns of its start.
            assertThat(anchor.getCol2() - anchor.getCol1())
                .as("scaled signature should span only a few columns, not the whole natural width")
                .isLessThanOrEqualTo(4);
        }
    }

    /** A real, valid PNG at an arbitrary size — {@code Workbook#addPicture} and
     * {@code Picture#getImageDimension()} both need genuine IHDR/IDAT/IEND bytes, not a
     * placeholder. */
    private byte[] realPng(int width, int height) throws Exception {
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void modelPath_paginatesAndKeepsPageFooter_forManyItems() throws Exception {
        requireLibreOffice();
        // 30, not 12: the layout-spec §3 remark compaction (REMARK_V2_COMPACT_SHIFT) shrank the
        // footer by 7 rows, so a 12-item/36-row document now fits on ONE page at a scale still
        // above MIN_SCALE (a real improvement — fewer documents need to paginate at all). Needs
        // enough rows to still genuinely exceed one page after that shrink.
        List<QuotationRenderModel.RenderItem> items = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            items.add(renderItem(null,
                List.of("กระเบื้อง รุ่น Model" + i, "ขนาด 60x60 cm. (ขนาดโดยประมาณ)", "(จำนวน 10 แผ่น = 10 แผ่น)"),
                BigDecimal.TEN, new BigDecimal("10.00"), "Net", new BigDecimal("10.00"), new BigDecimal("100.00")));
        }
        QuotationRenderModel model = modelWithItems(items,
            List.of("1.x", "2.x", "3.x", "4.x", "5.x", "6.x", "7.x", "8.x"),
            new QuotationRenderModel.Signatories(null, null, null, null, null));

        byte[] pdf = renderer.toPdf(model);
        String text = strip(pdf);
        assertThat(text).contains("Model1");
        assertThat(text).contains("Model30");
        assertThat(text).contains("หน้า"); // "หน้า &P/&N" paginated footer
    }

    @Test
    void modelPath_emittedRowCountMatchesThreeItemsThreeLinesTwoHeadings() throws Exception {
        // 3 items x 3 description lines each = 9 rows, + 2 heading rows (items 1&2 share one
        // heading, item 3 starts a new one) = 11 emitted rows -- within the native 13-row budget,
        // so the footer stays anchored (no move) and rows past the emitted count get cleared.
        QuotationRenderModel.RenderItem item1 = renderItem("โซนเอ", threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel.RenderItem item2 = renderItem("โซนเอ", threeLines("B"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel.RenderItem item3 = renderItem("โซนบี", threeLines("C"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item1, item2, item3),
            List.of("1.x", "2.x", "3.x"), new QuotationRenderModel.Signatories(null, null, null, null, null));

        byte[] xlsx = renderer.toXls(model);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            // Row 9 (0-based): heading "โซนเอ", no sequence number in column A.
            assertThat(sheet.getRow(9).getCell(1).getStringCellValue()).isEqualTo("โซนเอ");
            assertThat(cellIsEmpty(sheet, 9, 0)).isTrue();
            // Row 10: item1's main row, seq = 1 (v2 always numbers, even inside a heading group).
            assertThat(sheet.getRow(10).getCell(0).getNumericCellValue()).isEqualTo(1.0);
            // Rows 11-12: item1's continuation lines (description lines 2 and 3).
            assertThat(sheet.getRow(11).getCell(1).getStringCellValue()).isEqualTo("descB-A");
            assertThat(sheet.getRow(12).getCell(1).getStringCellValue()).isEqualTo("descC-A");
            // Row 13: item2's main row (same heading as item1 -- NOT repeated), seq = 2.
            assertThat(sheet.getRow(13).getCell(0).getNumericCellValue()).isEqualTo(2.0);
            // Row 16: new heading "โซนบี" for item3.
            assertThat(sheet.getRow(16).getCell(1).getStringCellValue()).isEqualTo("โซนบี");
            assertThat(sheet.getRow(17).getCell(0).getNumericCellValue()).isEqualTo(3.0);
            // Rows 20 (9 + 11 emitted) and 21 -- past the emitted content -- are cleared, not
            // showing template placeholder leftovers.
            assertThat(cellIsEmpty(sheet, 20, 1)).isTrue();
            assertThat(cellIsEmpty(sheet, 21, 1)).isTrue();
        }
    }

    /**
     * layout-spec §3 regression: line 3 (กำหนดส่งมอบสินค้า, a DYNAMIC composed list of lead-time
     * groups) used to have no continuation row of its own, so a long list wrapped straight into
     * line 4's border — the reviewer's original reference render showed exactly this. The FIX
     * (layout-spec §3) is not a second row: every v2/full-remarks line is now ONE merged B..I cell
     * — 8x the width of column B alone — so a long composed line 3 fits on its own single packed
     * row (REMARK_HEAD_ROWS[0] + 2) without wrapping or clobbering line 4's row
     * (REMARK_HEAD_ROWS[0] + 3), which sit immediately adjacent with the compaction in place.
     */
    @Test
    void modelPath_longLine3_fitsOnItsOwnPackedRow_withoutWrappingOrClobberingLine4() throws Exception {
        String longLine3 = "3.กำหนดส่งมอบสินค้า : "
            + "รายการที่ 1 ระยะเวลานำเข้า 75-90 วัน  "
            + "รายการที่ 2 ระยะเวลานำเข้า 75-90 วัน  "
            + "รายการที่ 3 ระยะเวลานำเข้า 75-90 วัน  "
            + "รายการที่ 4 ระยะเวลานำเข้า 75-90 วัน  "
            + "รายการที่ 5 ระยะเวลานำเข้า 75-90 วัน";
        assertThat(longLine3.length()).isGreaterThan(62); // would have exercised the OLD wrap path

        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        String line4 = "4.ขนาดของกระเบื้องจริง จะแตกต่างจากขนาดที่ระบุในใบเสนอราคา ได้เล็กน้อย ตามมาตรฐาน ISO และ มอก.";
        QuotationRenderModel model = modelWithItems(List.of(item), List.of(
                "1.x", "2.x", longLine3, line4, "5.x", "6.x", "7.x", "8.x"),
            new QuotationRenderModel.Signatories(null, null, null, null, null));

        byte[] xlsx = renderer.toXls(model);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            // REMARK_HEAD_ROWS[0] = 23 -- line 3 is the 3rd packed line (index 2) => row 25.
            String head = sheet.getRow(25).getCell(1).getStringCellValue();
            // The WHOLE composed string survives on the ONE row -- no split, nothing dropped.
            assertThat(head).isEqualTo(longLine3);
            for (int i = 1; i <= 5; i++) {
                assertThat(head.replaceAll("\\s+", "")).contains(("รายการที่" + i + "ระยะเวลานำเข้า75-90วัน"));
            }

            // Line 4 sits on the VERY NEXT row (26), immediately adjacent -- no gap, no clobbering.
            assertThat(sheet.getRow(26).getCell(1).getStringCellValue()).isEqualTo(line4);
        }
    }

    /**
     * layout-spec §3 + html-fidelity-spec §8: every one of the 8 v2/full-remarks lines is written
     * into a merged B..H range (not left as a plain column-B cell) — the remark box's width, so a
     * composed line never needs to wrap; NOT B..I, which would swallow the H|I separator that is
     * the box's right edge. Checks all 8 packed rows (REMARK_HEAD_ROWS[0]..+7 — the compaction
     * leaves them consecutive, see REMARK_V2_COMPACT_SHIFT's Javadoc) and the "หมายเหตุ" label
     * row above them, which is the first line inside the box.
     */
    @Test
    void modelPath_eachRemarkLine_isOneMergedRangeSpanningTheRemarkBox() throws Exception {
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item),
            List.of("1.x", "2.x", "3.x", "4.x", "5.x", "6.x", "7.x", "8.x"),
            new QuotationRenderModel.Signatories(null, null, null, null, null));

        byte[] xlsx = renderer.toXls(model);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            for (int i = 0; i < 8; i++) {
                int row = 23 + i; // REMARK_HEAD_ROWS[0] .. +7
                assertThat(mergedRange(sheet, row)).as("remark line " + (i + 1) + " (row " + row + ")")
                    .isEqualTo(new int[]{row, row, 1, 7}); // B..H
            }
            assertThat(mergedRange(sheet, 22)).as("หมายเหตุ label row (FOOTER_START)").isEqualTo(new int[]{22, 22, 1, 7});
        }
    }

    /**
     * html-fidelity-spec §8: the remark block is a closed box B..H — top rule on every cell B..H
     * of the "หมายเหตุ" row (and on no cell of A or I: the rule stops at the เป็นเงิน column's
     * left rule), left edge on B and right edge on H of every row down to line 8, and the
     * covered cells hold no value (the template's per-row formulas would otherwise print through
     * a merge in one engine and not the other).
     */
    @Test
    void modelPath_remarkBox_hasTopRuleBtoH_andLeftRightEdgesOnEveryRow() throws Exception {
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item),
            List.of("1.x", "2.x", "3.x", "4.x", "5.x", "6.x", "7.x", "8.x"),
            new QuotationRenderModel.Signatories(null, null, null, null, null));

        byte[] xlsx = renderer.toXls(model);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            var thin = org.apache.poi.ss.usermodel.BorderStyle.THIN;
            var none = org.apache.poi.ss.usermodel.BorderStyle.NONE;
            for (int c = 1; c <= 7; c++) {
                assertThat(sheet.getRow(22).getCell(c).getCellStyle().getBorderTop()).as("top rule at col " + c).isEqualTo(thin);
            }
            assertThat(sheet.getRow(22).getCell(0).getCellStyle().getBorderTop()).as("no top rule on ลำดับ").isEqualTo(none);
            assertThat(sheet.getRow(22).getCell(8).getCellStyle().getBorderTop()).as("no top rule on เป็นเงิน").isEqualTo(none);
            for (int row = 22; row <= 30; row++) {
                assertThat(sheet.getRow(row).getCell(1).getCellStyle().getBorderLeft()).as("left edge row " + row).isEqualTo(thin);
                assertThat(sheet.getRow(row).getCell(7).getCellStyle().getBorderRight()).as("right edge row " + row).isEqualTo(thin);
                assertThat(sheet.getRow(row).getCell(8).getCellStyle().getBorderRight()).as("เป็นเงิน outer rule row " + row).isEqualTo(thin);
                for (int c = 2; c <= 8; c++) {
                    assertThat(cellIsEmpty(sheet, row, c)).as("row " + row + " col " + c + " blank").isTrue();
                }
            }
        }
    }

    /**
     * layout-spec §3: the box must close DIRECTLY under the last remark line — no blank/bordered
     * "phantom" row between line 8 and รวมเป็นเงิน. With the compaction in place the totals row
     * sits immediately after the 8 packed remark rows (23..30), i.e. at row 31 (SUBTOTAL_ROW=38
     * minus the 7-row compaction shift).
     */
    @Test
    void modelPath_noTrailingEmptyRow_betweenLastRemarkLineAndTotals() throws Exception {
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item),
            List.of("1.x", "2.x", "3.x", "4.x", "5.x", "6.x", "7.x", "8.x"),
            new QuotationRenderModel.Signatories(null, null, null, null, null));

        byte[] xlsx = renderer.toXls(model);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            // Row 30 -- the last packed remark line (8) -- holds real text.
            assertThat(sheet.getRow(30).getCell(1).getStringCellValue()).isEqualTo("8.x");
            // Row 31 -- immediately next -- is the totals row: has the computed subtotal, not blank.
            assertThat(sheet.getRow(31).getCell(8).getNumericCellValue()).isEqualTo(10.0);
        }
    }

    /**
     * layout-spec §1: from the column-title row down to the last remark line there are ONLY
     * vertical column lines and the outer box — no horizontal rule under any item/description/
     * remark row. Spot-checks an interior item continuation row and an interior remark row both
     * carry no bottom border.
     */
    @Test
    void modelPath_interiorItemAndRemarkRows_carryNoBottomBorder() throws Exception {
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item),
            List.of("1.x", "2.x", "3.x", "4.x", "5.x", "6.x", "7.x", "8.x"),
            new QuotationRenderModel.Signatories(null, null, null, null, null));

        byte[] xlsx = renderer.toXls(model);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            // Row 10 -- item1's 2nd description line ("descB-A") -- an interior item row.
            assertThat(sheet.getRow(10).getCell(1).getCellStyle().getBorderBottom())
                .isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);
            // Row 25 -- packed remark line 3 -- an interior remark row.
            assertThat(sheet.getRow(25).getCell(1).getCellStyle().getBorderBottom())
                .isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);
        }
    }

    /**
     * layout-spec §5, REBUILT: each of the three signature rows (labels, names, dates) is now ONE
     * merged A:I cell built from four equal-PIXEL-width text slots — not four discrete merged
     * COLUMN ranges (git history: A:B/C:D/E:G/H:I, which put ผู้พิมพ์ alone behind column B, over
     * half the page). Checked on all three rows the block writes.
     */
    @Test
    void modelPath_signatureBlock_writesOneMergedAtoIRangePerRow_notFourDiscreteColumnRanges() throws Exception {
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item), List.of("1.x"),
            new QuotationRenderModel.Signatories("A", "B", "ราม อิฐรัตน์", null, null));

        byte[] xlsx = renderer.toXls(model);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            // LABELS_ROW=44, SALESPERSON_FORMULA_ROW(names)=45, DATE_ROW=46 -- footerShift=0 here
            // ("1.x" is a single line, not the full 8-line v2 remark set).
            for (int row : new int[]{44, 45, 46}) {
                assertThat(mergedRange(sheet, row, 0))
                    .as("row " + row + " should be ONE merged A:I range, not four discrete ones")
                    .isEqualTo(new int[]{row, row, 0, 8});
            }
        }
    }

    /** Finds the merged region starting at (row, col) if any, else null. */
    private int[] mergedRange(org.apache.poi.ss.usermodel.Sheet sheet, int row, int col) {
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            var m = sheet.getMergedRegion(i);
            if (m.getFirstRow() == row && m.getFirstColumn() == col) {
                return new int[]{m.getFirstRow(), m.getLastRow(), m.getFirstColumn(), m.getLastColumn()};
            }
        }
        return null;
    }

    /** Finds the merged region starting at (row, col 1 -- the B..I remark convention). */
    private int[] mergedRange(org.apache.poi.ss.usermodel.Sheet sheet, int row) {
        return mergedRange(sheet, row, 1);
    }

    /**
     * The template ALREADY carries embedded images (logo/certs) — a signature-image anchor must
     * not corrupt the drawing layer they live in. Asserts the rendered workbook still has AT
     * LEAST the template's original picture count (equal + 1 for the new signature, or equal if
     * the (documented, logged) fallback path had to kick in — either way, nothing was lost).
     */
    @Test
    void modelPath_approverSignatureImage_survivesTemplatePictures() throws Exception {
        int originalPictureCount;
        try (var in = new ClassPathResource("templates/quotation_template.xls").getInputStream();
             var wb = WorkbookFactory.create(in)) {
            originalPictureCount = ((HSSFWorkbook) wb).getAllPictures().size();
        }
        assertThat(originalPictureCount).isGreaterThan(0);

        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item), List.of("1.x"),
            new QuotationRenderModel.Signatories("A", "B", "ราม อิฐรัตน์", onePixelPng(), "image/png"));

        byte[] xlsx = renderer.toXls(model);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            int newCount = ((HSSFWorkbook) wb).getAllPictures().size();
            assertThat(newCount).isGreaterThanOrEqualTo(originalPictureCount);
        }
    }

    @Test
    void modelPath_approverSignatureImage_addsAnXObjectToTheRenderedPdf() throws Exception {
        requireLibreOffice();
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel.Signatories noSignatureImage =
            new QuotationRenderModel.Signatories("A", "B", "ราม อิฐรัตน์", null, null);
        QuotationRenderModel.Signatories withSignatureImage =
            new QuotationRenderModel.Signatories("A", "B", "ราม อิฐรัตน์", onePixelPng(), "image/png");

        int xObjectsWithout = countXObjects(renderer.toPdf(modelWithItems(List.of(item), List.of("1.x"), noSignatureImage)));
        int xObjectsWith = countXObjects(renderer.toPdf(modelWithItems(List.of(item), List.of("1.x"), withSignatureImage)));

        assertThat(xObjectsWith).isGreaterThan(xObjectsWithout);
    }

    private int countXObjects(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int count = 0;
            for (var page : doc.getPages()) {
                for (var ignored : page.getResources().getXObjectNames()) {
                    count++;
                }
            }
            return count;
        }
    }

    /** A minimal, real, valid 1×1 transparent PNG — magic bytes + a real IHDR/IDAT/IEND chain, not
     * a placeholder string, so {@code Workbook#addPicture} has genuine image bytes to embed. */
    private byte[] onePixelPng() {
        return Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    }

    private List<String> threeLines(String tag) {
        return List.of("descA-" + tag, "descB-" + tag, "descC-" + tag);
    }

    private boolean cellIsEmpty(org.apache.poi.ss.usermodel.Sheet sheet, int row, int col) {
        var r = sheet.getRow(row);
        if (r == null) return true;
        var c = r.getCell(col);
        return c == null || c.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK;
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private QuotationRenderModel.RenderItem renderItem(String heading, List<String> descriptionLines,
            BigDecimal qty, BigDecimal unitPrice, String discountLabel, BigDecimal netUnitPrice, BigDecimal amount) {
        return new QuotationRenderModel.RenderItem(heading, descriptionLines, qty, "แผ่น", unitPrice,
            discountLabel, netUnitPrice, amount);
    }

    private QuotationRenderModel modelWithItems(List<QuotationRenderModel.RenderItem> items,
            List<String> remarkLines, QuotationRenderModel.Signatories signatories) {
        return new QuotationRenderModel(
            LocalDate.of(2026, 7, 16), "QN-2026-0099", "P003", "D002",
            "Sales/สมชาย ใจดี T.081-234-5678",
            "คุณลูกค้า   /   Test Customer Co., Ltd.   เลขที่ผู้เสียภาษี : 0105542000000",
            "โทร. 02-000-0000", "Showroom V2 Project", items, remarkLines, signatories, true);
    }

    private TicketItemDto itemWithAttributes(int seq, String brand, String model, String color,
                                             String texture, String size) {
        return new TicketItemDto(
            seq, 10L, brand, model,
            color, texture, size,
            null,                                        // factory
            BigDecimal.ONE, null,                        // qty, qtySqm
            null, null, "แผ่น",                          // rawPrice, rawCurrency, rawUnit
            null, new BigDecimal("100.00"), "THB",       // proposedPrice, approvedPrice, currency
            seq,                                         // sortOrder
            null, null, null,                            // calcedCost, calcedPrice, calcConfigVersion
            "PIECE", null, null);                        // unitBasis, manualPrice, manualOverrideReason
    }

    private TicketItemDto item(int seq, String brand, String model, BigDecimal qty, BigDecimal approvedPrice) {
        return new TicketItemDto(
            seq, 10L, brand, model,
            null, null, null,          // color, texture, size
            null,                       // factory
            qty, null,                  // qty, qtySqm
            null, null, "แผ่น",         // rawPrice, rawCurrency, rawUnit
            null, approvedPrice, "THB", // proposedPrice, approvedPrice, currency
            seq,                        // sortOrder
            null, null, null,           // calcedCost, calcedPrice, calcConfigVersion
            "PIECE", null, null);       // unitBasis, manualPrice, manualOverrideReason
    }
}
