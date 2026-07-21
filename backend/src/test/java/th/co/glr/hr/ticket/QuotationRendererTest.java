package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.customer.CustomerDto;

class QuotationRendererTest {
    private final QuotationRenderer renderer = new QuotationRenderer();

    @BeforeEach
    void requireLibreOffice() {
        // toPdf() shells out to LibreOffice; skip (don't fail) where it isn't installed —
        // same policy as the repo's Testcontainers-on-Docker gating. CI installs libreoffice-calc.
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(),
            "LibreOffice (soffice) not installed — skipping PDF render test");
    }

    @Test
    void pdfRendersFormattedDocumentWithHeaderTableAndTotals() throws Exception {
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
            // Flow layout: items occupy rows 9..(9+count-1); the footer block (whose subtotal is
            // originally at 0-based row 37) is relocated by delta = (9 + count) - 22, so the subtotal
            // lands at row 37 + delta = 24 + count. Col I → index 8.
            int subtotalRow = 24 + count;
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
