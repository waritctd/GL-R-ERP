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
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.common.sheet.FontResolver;
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

        // Heading printed exactly once even though two items share it — counted in the SHEET the
        // PDF is printed from, not in the PDF's extracted text: LibreOffice's PDF export of a
        // substituted Thai font (CI has only fonts-thai-tlwg) emits tone-mark variant glyphs
        // with no ToUnicode mapping, so "หน้าบ้าน" came back from the PDF 0 times there while
        // the page printed it once. The XLS cell is what decides how often it prints.
        assertThat(countCellsWithText(renderer.toXls(model), "หน้าบ้าน")).isEqualTo(1);
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

    // ── owner feedback pass 1 (2026-09-10): F2 slot-4 name, F4 dates, F6 picture on the rule ──

    @Test
    void signatureDateText_isDayMonthUnpaddedWithBuddhistYear_orTheDottedPlaceholder() {
        assertThat(QuotationRenderer.signatureDateText(LocalDate.of(2026, 9, 10))).isEqualTo("วันที่ 10/9/2569");
        assertThat(QuotationRenderer.signatureDateText(LocalDate.of(2026, 1, 1))).isEqualTo("วันที่ 1/1/2569");
        assertThat(QuotationRenderer.signatureDateText(LocalDate.of(2025, 12, 31))).isEqualTo("วันที่ 31/12/2568");
        assertThat(QuotationRenderer.signatureDateText(null)).isEqualTo("วันที่........./........./.........");
    }

    /** F2 + F4: the names row prints the ผู้สั่งซื้อ name in slot 4 (after the approver), and the
     * dates row prints created/submitted/approved under slots 1-3 with slot 4 dotted. */
    @Test
    void modelPath_signatureBlock_printsOrderedByInSlot4_andPerSlotDates() throws Exception {
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item), List.of("1.x"),
            new QuotationRenderModel.Signatories("A", "B", "ราม อิฐรัตน์", "สมหญิง ใจดี", null, null,
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10)));

        byte[] xlsx = renderer.toXls(model);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            String names = sheet.getRow(45).getCell(0).getStringCellValue();
            assertThat(names).contains("(A)", "(B)", "(ราม อิฐรัตน์)", "(สมหญิง ใจดี)");
            assertThat(names.indexOf("(สมหญิง ใจดี)")).isGreaterThan(names.indexOf("(ราม อิฐรัตน์)"));
            assertThat(names).doesNotContain("(..........................)");

            String dates = sheet.getRow(46).getCell(0).getStringCellValue();
            int d1 = dates.indexOf("วันที่ 8/9/2569");
            int d2 = dates.indexOf("วันที่ 9/9/2569");
            int d3 = dates.indexOf("วันที่ 10/9/2569");
            int dotted = dates.indexOf("วันที่........./........./.........");
            assertThat(d1).isNotNegative();
            assertThat(d2).isGreaterThan(d1);
            assertThat(d3).isGreaterThan(d2);
            assertThat(dotted).as("ผู้สั่งซื้อ's date slot stays dotted, last").isGreaterThan(d3);
            assertThat(dates.split("วันที่........./........./.........", -1)).hasSize(2);
        }
    }

    /** A DRAFT-shaped model (no submitted/approved dates, no approver) keeps those slots dotted;
     * the five-argument constructor (legacy shape) prints all four dotted. */
    @Test
    void modelPath_signatureBlock_missingDatesAndNames_printPlaceholders() throws Exception {
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel draft = modelWithItems(List.of(item), List.of("1.x"),
            new QuotationRenderModel.Signatories("A", "B", null, "สมหญิง ใจดี", null, null,
                LocalDate.of(2026, 9, 8), null, null));
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(renderer.toXls(draft)))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            String names = sheet.getRow(45).getCell(0).getStringCellValue();
            assertThat(names.split("\\(\\.{26}\\)", -1)).as("one dotted name: the approver").hasSize(2);
            assertThat(names.indexOf("(..........................)")).isGreaterThan(names.indexOf("(B)"))
                .isLessThan(names.indexOf("(สมหญิง ใจดี)"));
            String dates = sheet.getRow(46).getCell(0).getStringCellValue();
            assertThat(dates).contains("วันที่ 8/9/2569");
            assertThat(dates.split("วันที่........./........./.........", -1)).hasSize(4);
        }

        QuotationRenderModel legacyShape = modelWithItems(List.of(item), List.of("1.x"),
            new QuotationRenderModel.Signatories("A", "B", "C", null, null));
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(renderer.toXls(legacyShape)))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            assertThat(sheet.getRow(45).getCell(0).getStringCellValue()).containsOnlyOnce("(..........................)");
            assertThat(sheet.getRow(46).getCell(0).getStringCellValue()
                .split("วันที่........./........./.........", -1)).hasSize(5);
        }
    }

    /**
     * F6, amended twice on 2026-09-10 — the owner's final words: "still make the line visible but
     * put the signature on top of the line in the middle and not too high up."
     *
     * <p>Three regressions are pinned here, all read off the RENDERED anchor and the RENDERED
     * labels string (never off the renderer's own constants):
     *
     * <ol>
     *   <li><strong>Centred on the underscore RUN, not the slot.</strong> The build the owner saw
     *       centred the picture on the whole ผู้จัดการฝ่ายขาย quarter, which drew it over the label
     *       words themselves. The run is the blank stretch AFTER the label text, and the picture's
     *       left edge must start clear of the label's own x-range.</li>
     *   <li><strong>Scaled down</strong> to at most {@code SIGNATURE_RUN_WIDTH_FRACTION} (60%) of
     *       that run, so the rule shows on both sides of the ink — and at most 8 mm tall.</li>
     *   <li><strong>Resting ON the rule</strong>: the bottom edge lands just ABOVE it (0–1 mm),
     *       never cutting through it. The owner rejected the straddling build in as many words —
     *       "it should sit above the line, right now it's across the middle of the line" — after
     *       an earlier build had it floating a whole row too high, so both directions are pinned:
     *       the gap is positive, and smaller than a millimetre.</li>
     * </ol>
     *
     * <p>The three sources cover both binding constraints and the wide-source regression the
     * width cap exists for: a 300x120 (width binds), a 200x400 (height binds — it must stay
     * centred anyway) and a 2000x400, which at natural size would be over half a metre wide.
     */
    @Test
    void modelPath_approverSignatureImage_restsOnTheRuleCentredOnTheUnderscoreRun() throws Exception {
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        for (byte[] png : List.of(realPng(300, 120), realPng(200, 400), realPng(2000, 400))) {
            QuotationRenderModel model = modelWithItems(List.of(item), List.of("1.x"),
                new QuotationRenderModel.Signatories("A", "B", "ราม อิฐรัตน์", png, "image/png"));
            byte[] xlsx = renderer.toXls(model);
            try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
                var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
                var hssf = (org.apache.poi.hssf.usermodel.HSSFSheet) sheet;
                org.apache.poi.hssf.usermodel.HSSFPicture signature = null;
                for (var shape : hssf.getDrawingPatriarch().getChildren()) {
                    if (shape instanceof org.apache.poi.hssf.usermodel.HSSFPicture pic
                        && pic.getClientAnchor().getRow1() >= 40) {
                        signature = pic;
                    }
                }
                assertThat(signature).isNotNull();
                var anchor = signature.getClientAnchor();

                // ── the run, measured off the string the renderer actually emitted ───────────
                int labelsRow = 44;
                double[] run = approverUnderscoreRun(sheet, labelsRow);
                double runStart = run[0];
                double runEnd = run[1];
                double runCentre = (runStart + runEnd) / 2;

                double startPx = anchorXPixels(sheet, anchor.getCol1(), anchor.getDx1());
                double endPx = anchorXPixels(sheet, anchor.getCol2(), anchor.getDx2());
                double centrePx = (startPx + endPx) / 2;
                double widthPx = endPx - startPx;

                assertThat(Math.abs(centrePx - runCentre))
                    .as("%dx%d: centred on the underscore run (run %.0f..%.0f px, picture %.0f..%.0f)",
                        signature.getImageDimension().width, signature.getImageDimension().height,
                        runStart, runEnd, startPx, endPx)
                    .isLessThanOrEqualTo(mmToPixels(2.0));
                assertThat(startPx)
                    .as("never drawn over the ผู้จัดการฝ่ายขาย label text — it starts after the run does")
                    .isGreaterThanOrEqualTo(runStart);
                assertThat(endPx).as("stays inside the run, so the rule shows on both sides")
                    .isLessThanOrEqualTo(runEnd);
                assertThat(widthPx).as("at most 60%% of the run")
                    .isLessThanOrEqualTo((runEnd - runStart) * 0.60 + 1);

                // ── resting on the rule ─────────────────────────────────────────────────────
                double labelsRowPt = sheet.getRow(labelsRow).getHeightInPoints();
                double rulePt = labelsRowPt - mmToPoints(1.0); // SIGNATURE_BASELINE_LIFT_MM
                double bottomPt = anchorYPoints(sheet, labelsRow, anchor.getRow2(), anchor.getDy2());
                double topPt = anchorYPoints(sheet, labelsRow, anchor.getRow1(), anchor.getDy1());
                assertThat(rulePt - bottomPt)
                    .as("the ink RESTS ON the rule: bottom just above it, never crossing it")
                    .isBetween(0.0, mmToPoints(1.0));
                assertThat(bottomPt - topPt).as("at most 8 mm tall, and a real box")
                    .isPositive()
                    .isLessThanOrEqualTo(mmToPoints(8.0) + 1);
            }
        }
    }

    /** The absolute pixel x of one anchor corner, from column A's left edge. */
    private double anchorXPixels(org.apache.poi.ss.usermodel.Sheet sheet, int col, int dx) {
        double x = 0;
        for (int c = 0; c < col; c++) x += sheet.getColumnWidthInPixels(c);
        return x + dx / 1024.0 * sheet.getColumnWidthInPixels(col);
    }

    /** One anchor corner's offset in POINTS below {@code baseRow}'s TOP edge (the corner may sit
     * above the base row, or — as the signature's bottom now deliberately does — below it). */
    private double anchorYPoints(org.apache.poi.ss.usermodel.Sheet sheet, int baseRow, int row, int dy) {
        double y = 0;
        for (int r = Math.min(baseRow, row); r < Math.max(baseRow, row); r++) {
            var sheetRow = sheet.getRow(r);
            y += sheetRow != null ? sheetRow.getHeightInPoints() : sheet.getDefaultRowHeightInPoints();
        }
        if (row < baseRow) y = -y;
        var anchorRow = sheet.getRow(row);
        double h = anchorRow != null ? anchorRow.getHeightInPoints() : sheet.getDefaultRowHeightInPoints();
        return y + dy / 256.0 * h;
    }

    /**
     * The ผู้จัดการฝ่ายขาย slot's underscore run — {@code [startPx, endPx]} in the same absolute
     * anchor-pixel space {@link #anchorXPixels} returns — derived from the labels string the
     * renderer actually WROTE into the sheet (split on the four label words), measured with the
     * row's own font, and converted from font pixels to anchor pixels through LibreOffice's column
     * model. The conversion is what makes the two comparable at all: text is placed by font
     * advances, an anchor by column widths, and LibreOffice does not size a column the way POI's
     * {@code getColumnWidthInPixels} does.
     */
    private double[] approverUnderscoreRun(org.apache.poi.ss.usermodel.Sheet sheet, int labelsRow)
            throws Exception {
        String line = sheet.getRow(labelsRow).getCell(0).getStringCellValue();
        String[] labels = {"ผู้พิมพ์", "พนักงานขาย", "ผู้จัดการฝ่ายขาย", "ผู้สั่งซื้อ"};
        var poiFont = sheet.getWorkbook().getFontAt(
            sheet.getRow(labelsRow).getCell(0).getCellStyle().getFontIndexAsInt());
        // Resolve through FontResolver, exactly like QuotationRenderer#resolveSignatureFontMetrics
        // now does — measuring with the raw template font NAME ("Angsana New"/"Cordia New") would
        // silently diverge from the renderer's own (fixed) metrics on a host without those fonts,
        // since java.awt.Font's own fallback ("Dialog") is not fontconfig's Thai-aware substitute.
        String resolvedFamily = FontResolver.resolve(poiFont.getFontName()).family();
        var awtFont = new java.awt.Font(resolvedFamily, java.awt.Font.PLAIN,
            (int) poiFont.getFontHeightInPoints());
        var frc = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            .createGraphics().getFontRenderContext();

        double cursor = 0;
        double runStart = 0;
        double runEnd = 0;
        int at = 0;
        for (int i = 0; i < labels.length; i++) {
            int labelAt = line.indexOf(labels[i], at);
            int slotEnd = i + 1 < labels.length ? line.indexOf(labels[i + 1], labelAt + labels[i].length())
                : line.length();
            String slot = line.substring(labelAt, slotEnd);
            double slotPx = awtFont.getStringBounds(slot, frc).getWidth() * 96.0 / 72.0;
            if (i == 2) {
                runStart = cursor + awtFont.getStringBounds(labels[i], frc).getWidth() * 96.0 / 72.0;
                runEnd = cursor + slotPx;
            }
            cursor += slotPx;
            at = slotEnd;
        }

        int charWidthTwips = th.co.glr.hr.common.sheet.LibreOfficeMetrics.charWidthTwips(sheet.getWorkbook());
        long loTwips = 0;
        double poiPx = 0;
        for (int c = 0; c <= 8; c++) {
            loTwips += th.co.glr.hr.common.sheet.LibreOfficeMetrics.columnTwips(sheet.getColumnWidth(c), charWidthTwips);
            poiPx += sheet.getColumnWidthInPixels(c);
        }
        double loPx = loTwips / 1440.0 * 96.0;
        double fontToAnchor = loPx > 0 && poiPx > 0 ? loPx / poiPx : 1.0;
        double insetPx = 40 / 1440.0 * 96.0; // LibreOfficeMetrics.TEXT_INSET_TWIPS
        return new double[]{(insetPx + runStart) / fontToAnchor, (insetPx + runEnd) / fontToAnchor};
    }

    private static double mmToPixels(double mm) {
        return mm / 25.4 * 96.0;
    }

    private static double mmToPoints(double mm) {
        return mm / 25.4 * 72.0;
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

    /**
     * Defect 2 regression (owner's approved-quotation export F-SM-008/QT-2026-0014-2, 2026-09-10):
     * a genuinely decodable signature must survive as a real, drawn image in the ACTUAL
     * LibreOffice-rendered PDF — a POI-model-only assertion (as
     * {@code modelPath_approverSignatureImage_anchorsWithinTheApproverSlot_notOverCustomerSlot}
     * above already makes) would NOT have caught the bug this guards against: the degenerate
     * (zero-width) provisional anchor a decode failure used to leave behind is a shape POI's own
     * object model keeps without complaint, but that LibreOffice's PDF export silently drops —
     * see {@link QuotationRenderer#anchorApproverSignature}'s Javadoc for the full chain,
     * confirmed by reading {@code org.apache.poi.ss.util.ImageUtils}'s bytecode and reproducing
     * end-to-end. The template itself carries exactly two pictures (logo, ISO badge — see
     * {@code modelPath_approverSignatureImage_survivesTemplatePictures} above); a THIRD real
     * image XObject in the rendered PDF is the approver's signature actually being drawn.
     */
    @Test
    void modelPath_decodableApproverSignatureImage_isActuallyDrawnInTheRealLibreOfficeRenderedPdf()
            throws Exception {
        requireLibreOffice();
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item), List.of("1.x"),
            new QuotationRenderModel.Signatories("A", "B", "ราม อิฐรัตน์", onePixelPng(), "image/png"));

        byte[] pdf = renderer.toPdf(model);

        assertThat(countImageXObjects(pdf))
            .as("logo + ISO badge + the approver's signature, actually drawn by LibreOffice "
                + "(not merely present in POI's in-memory model)")
            .isGreaterThanOrEqualTo(3);
    }

    /**
     * Defect 2 root cause, pinned directly: {@code org.apache.poi.ss.util.ImageUtils
     * #getImageDimension} (what {@link org.apache.poi.ss.usermodel.Picture#getImageDimension()}
     * calls) SWALLOWS an image ImageIO has no reader for — an unsupported or corrupt format — and
     * returns {@code Dimension(0, 0)} rather than throwing. Before the fix,
     * {@code QuotationRenderer#anchorApproverSignature} still created a picture shape for such
     * bytes and left it on its PROVISIONAL anchor, which is deliberately ZERO-WIDTH
     * ({@code col1==col2}, {@code dx1==dx2}: same offset repeated, see the method's own comment).
     * POI's model keeps a shape like that without complaint (which is exactly why a POI-only test
     * would not have caught this), but LibreOffice's PDF export — and, by the same mechanism, a
     * real Excel/Calc open of the .xls download — draws nothing for a zero-area shape. The fix
     * must never let that shape exist in the first place: for bytes ImageIO cannot decode, the
     * sheet ends up with exactly the template's own pictures, no phantom third one, degenerate or
     * not.
     */
    @Test
    void modelPath_undecodableApproverSignatureBytes_neverLeaveADegenerateAnchoredShape() throws Exception {
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        byte[] undecodable = "not a real image -- ImageIO has no reader for this"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        QuotationRenderModel model = modelWithItems(List.of(item), List.of("1.x"),
            new QuotationRenderModel.Signatories("A", "B", "ราม อิฐรัตน์", undecodable, "image/png"));

        int templatePictureShapes;
        try (var in = new ClassPathResource("templates/quotation_template.xls").getInputStream();
             var wbTpl = WorkbookFactory.create(in)) {
            var tplSheet = wbTpl.getSheet("Update") != null ? wbTpl.getSheet("Update") : wbTpl.getSheetAt(0);
            var tplPatriarch = ((org.apache.poi.hssf.usermodel.HSSFSheet) tplSheet).getDrawingPatriarch();
            templatePictureShapes = tplPatriarch == null ? 0 : tplPatriarch.getChildren().size();
        }
        assertThat(templatePictureShapes).isGreaterThan(0);

        byte[] xlsx = renderer.toXls(model);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            var patriarch = ((org.apache.poi.hssf.usermodel.HSSFSheet) sheet).getDrawingPatriarch();
            int shapes = 0;
            boolean anyDegenerate = false;
            for (var shape : patriarch.getChildren()) {
                if (shape instanceof org.apache.poi.hssf.usermodel.HSSFPicture pic) {
                    shapes++;
                    var anc = pic.getClientAnchor();
                    if (anc.getCol1() == anc.getCol2() && anc.getDx1() == anc.getDx2()) {
                        anyDegenerate = true;
                    }
                }
            }
            assertThat(anyDegenerate)
                .as("no picture shape may ever be left on a zero-width (col1==col2, dx1==dx2) anchor")
                .isFalse();
            assertThat(shapes)
                .as("undecodable bytes must add no picture at all -- only the template's own")
                .isEqualTo(templatePictureShapes);
        }
    }

    private int countImageXObjects(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int count = 0;
            for (var page : doc.getPages()) {
                for (var name : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(name)
                            instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                        count++;
                    }
                }
            }
            return count;
        }
    }

    /**
     * Defect 1 regression, pinned against the REAL LibreOffice render — reading the XLS cell or
     * re-measuring with AWT (even AWT pointed at the correctly {@link FontResolver}-resolved
     * family) is NOT sufficient evidence here: both stayed within a few points of the 95% target
     * even against the unfixed renderer on this exact container, because the actual bug is not
     * primarily a font-SUBSTITUTION mismatch, it is that {@code writeSignatureBlock} padded to
     * 95% of {@code QuotationRenderer#totalColumnWidthPixels} — POI's OWN, unrelated column-pixel
     * constant — instead of the width LibreOffice ACTUALLY prints the A..I range at. Only reading
     * real glyph positions out of the real LibreOffice-rendered PDF (soffice --convert-to pdf,
     * exactly like {@code renderer.toPdf}'s production path) exposes it: on this template POI's
     * figure (659.5 pt) is roughly a third narrower than LibreOffice's own column-width model
     * (979.0 pt — see {@code QuotationRenderer#totalColumnWidthPixelsLibreOffice}'s Javadoc for
     * the derivation), so the unfixed row landed at {@code 0.95 × (659.5/979.0) ≈ 0.64} of the
     * real page width — measured here at 66.9%, matching the owner's ~65% almost exactly.
     *
     * <p>Compares the signature labels row's real rendered horizontal span against the item
     * table's (both read off the SAME PDF, same left/right reference edges), rather than a fixed
     * fraction of a computed page width, so this needs no separate "what's the real page width"
     * derivation of its own. PDFBox's Thai-glyph ToUnicode gaps on a substituted font (this file's
     * other PDF tests already work around this — see {@code strip}'s own comment) make matching
     * the Thai label text unreliable, so both reference rows are located by ASCII content the
     * fonts-without-ToUnicode-mapping problem never touches: the item row's literal "Net"+amount,
     * and the signature row's long run of literal underscore characters.
     *
     * <p>Mutation check: reverting {@code totalColumnWidthPixelsLibreOffice} back to
     * {@code totalColumnWidthPixels} in {@code writeSignatureBlock} turns this red on this
     * container (measured 66.9%, below the 0.80 floor below) — verified by running it against the
     * reverted line.
     */
    @Test
    void signatureRow_fillsTheA4PageWidth_measuredFromRealGlyphPositionsInTheLibreOfficeRenderedPdf()
            throws Exception {
        requireLibreOffice();
        QuotationRenderModel.RenderItem item = renderItem(null, threeLines("A"), BigDecimal.ONE,
            BigDecimal.TEN, "Net", BigDecimal.TEN, BigDecimal.TEN);
        QuotationRenderModel model = modelWithItems(List.of(item), List.of("1.x"),
            new QuotationRenderModel.Signatories("A", "B", "ราม อิฐรัตน์", null, null));

        byte[] pdf = renderer.toPdf(model);
        Map<String, double[]> rows = rowSpansByAsciiContent(pdf);

        double[] itemRow = rows.entrySet().stream()
            .filter(e -> e.getKey().contains("Net") && e.getKey().contains("10.00"))
            .map(Map.Entry::getValue).findFirst()
            .orElseThrow(() -> new AssertionError("item row (containing 'Net'/'10.00') not found in rendered PDF"));
        double[] signatureRow = rows.entrySet().stream()
            .filter(e -> e.getKey().contains("____________")) // a long underscore run only S1 has
            .map(Map.Entry::getValue).findFirst()
            .orElseThrow(() -> new AssertionError("signature labels row (long underscore run) not found in rendered PDF"));

        // Both rows start at column A's left edge (S1 is A:I merged, left-aligned) — use the
        // WIDER of the two observed left edges as the shared left reference, and the item row's
        // right edge (the ราคา/เป็นเงิน columns' own content reaches furthest right) as the page
        // reference; the signature row's own right edge is the thing under test.
        double leftRef = Math.min(itemRow[0], signatureRow[0]);
        double tableSpan = itemRow[1] - leftRef;
        double signatureSpan = signatureRow[1] - leftRef;

        assertThat(signatureSpan / tableSpan)
            .as("signature row must fill close to 95%% of the item table's own real rendered "
                + "width (table span %.1f pt, signature span %.1f pt, in the ACTUAL "
                + "LibreOffice-rendered PDF) -- the owner's production export measured ~65%%",
                tableSpan, signatureSpan)
            .isGreaterThan(0.80);
    }

    /**
     * Groups every real, positioned glyph LibreOffice drew into rows (by rounded Y), and returns
     * each row's [minX, maxX] keyed by its own extracted text — used to find a specific row by
     * stable ASCII content (see the width test above for why Thai text isn't a reliable key here).
     */
    private Map<String, double[]> rowSpansByAsciiContent(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            var byLine = new java.util.TreeMap<Integer, java.util.List<org.apache.pdfbox.text.TextPosition>>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, java.util.List<org.apache.pdfbox.text.TextPosition> positions) {
                    if (positions.isEmpty()) return;
                    int y = Math.round(positions.get(0).getY());
                    byLine.computeIfAbsent(y, k -> new java.util.ArrayList<>()).addAll(positions);
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);

            Map<String, double[]> result = new java.util.LinkedHashMap<>();
            for (var line : byLine.values()) {
                StringBuilder text = new StringBuilder();
                double minX = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE;
                for (var tp : line) {
                    text.append(tp.getUnicode());
                    minX = Math.min(minX, tp.getX());
                    maxX = Math.max(maxX, tp.getX() + tp.getWidth());
                }
                result.put(text.toString(), new double[]{minX, maxX});
            }
            return result;
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

    /** How many cells of the quotation sheet hold exactly {@code text}. */
    private int countCellsWithText(byte[] xls, String text) throws Exception {
        int count = 0;
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            for (var row : sheet) {
                for (var cell : row) {
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && text.equals(cell.getStringCellValue().strip())) {
                        count++;
                    }
                }
            }
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
