package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.customer.CustomerDto;

class QuotationRendererTest {
    private final QuotationRenderer renderer = new QuotationRenderer();

    @Test
    void pdfRendersFormattedDocumentWithHeaderTableAndTotals() throws Exception {
        byte[] pdf = renderer.toPdf(ticket(List.of(item(1, "Cotto", "Marble Series",
            new BigDecimal("300"), new BigDecimal("580.00")))), quotation(), customer(null));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = strip(pdf);

        // Header block
        assertThat(text).contains("บริษัท จี แอล แอนด์ อาร์ จำกัด");
        assertThat(text).contains("ใบเสนอราคา / QUOTATION");
        assertThat(text).contains("เลขที่ QT-2026-0042");
        // Customer block
        assertThat(text).contains("เรียน Test Customer Co., Ltd.");
        assertThat(text).contains("Project : Showroom Renovation");
        // Items table header + row
        assertThat(text).contains("ลำดับ");
        assertThat(text).contains("รายการ");
        assertThat(text).contains("ราคา/หน่วย");
        assertThat(text).contains("Cotto Marble Series");
        assertThat(text).contains("580.00");
        // Totals: 300 × 580 = 174,000.00; VAT 7% = 12,180.00; grand = 186,180.00
        assertThat(text).contains("174,000.00");
        assertThat(text).contains("12,180.00");
        assertThat(text).contains("186,180.00");
        // Signature block
        assertThat(text).contains("ผู้เสนอราคา");
        assertThat(text).doesNotContain("?????");
    }

    @Test
    void pdfSurvivesMultilineCustomerAddress() throws Exception {
        // PDFBox showText() throws on U+000A — the writer must split, not crash.
        CustomerDto customer = customer("99/1 ถนนสุขุมวิท\nแขวงคลองเตย เขตคลองเตย\nกรุงเทพมหานคร 10110");

        byte[] pdf = renderer.toPdf(ticket(List.of(item(1, "Cotto", "Marble",
            BigDecimal.ONE, BigDecimal.TEN))), quotation(), customer);

        String text = strip(pdf);
        assertThat(text).contains("99/1 ถนนสุขุมวิท");
        assertThat(text).contains("กรุงเทพมหานคร 10110");
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
    void pdfRendersAllItemsBeyondTheXlsxTemplateCap() throws Exception {
        List<TicketItemDto> items = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            items.add(item(i, "Brand" + i, "Model" + i, BigDecimal.ONE, new BigDecimal("10.00")));
        }

        String text = strip(renderer.toPdf(ticket(items), quotation(), customer(null)));

        // The xlsx template caps at 15 rows; the PDF paginates and must render all 20.
        assertThat(text).contains("Brand1 Model1");
        assertThat(text).contains("Brand16 Model16");
        assertThat(text).contains("Brand20 Model20");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private String strip(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private TicketDto ticket(List<TicketItemDto> items) {
        TicketSummaryDto summary = new TicketSummaryDto(
            10L, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", TicketStatus.QUOTATION_ISSUED,
            "NORMAL", 1L, "Sales User", null, null, "Test Customer Co., Ltd.", 5L, null,
            "Showroom Renovation", null, null, null, Instant.now(), Instant.now(), null,
            items.size(), false, null, null);
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
