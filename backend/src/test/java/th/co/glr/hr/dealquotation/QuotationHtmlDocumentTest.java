package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderModel.RenderItem;
import th.co.glr.hr.ticket.QuotationRenderModel.Signatories;

/**
 * Pure HTML assertions on {@link QuotationHtmlDocument#render(QuotationRenderModel)} — no
 * browser involved (that's {@code ChromiumPdfPrinterTest}, guarded on Chromium being present;
 * the pixel-level agreement with the LibreOffice render is {@code HtmlXlsFidelityTest}).
 */
class QuotationHtmlDocumentTest {

    private static final List<String> EIGHT_REMARKS = List.of(
        "1.line one", "2.line two", "3.line three", "4.line four",
        "5.line five", "6.line six", "7.line seven", "8.line eight");

    @Test
    void rendersAllFourSignatureLabelsInOrder() {
        // Owner ruling 2026-09-10: the template's own original four labels, matching
        // QuotationRenderer's SIG_LABELS (the XLS path) exactly.
        String html = QuotationHtmlDocument.render(baseModel(null, null, null, null, null));
        int printed = html.indexOf("ผู้พิมพ์");
        int checked = html.indexOf("พนักงานขาย");
        int approved = html.indexOf("ผู้จัดการฝ่ายขาย");
        int orderer = html.indexOf("ผู้สั่งซื้อ");
        assertThat(printed).isPositive();
        assertThat(checked).isGreaterThan(printed);
        assertThat(approved).isGreaterThan(checked);
        assertThat(orderer).isGreaterThan(approved);
    }

    @Test
    void rendersSignatoryNames() {
        String html = QuotationHtmlDocument.render(
            baseModel("จินตนา หาญมนตรี", "ประภัสสร คำเกิด", "ราม อิฐรัตน์", null, null));
        assertThat(html).contains("(จินตนา หาญมนตรี)");
        assertThat(html).contains("(ประภัสสร คำเกิด)");
        assertThat(html).contains("(ราม อิฐรัตน์)");
        // ผู้สั่งซื้อ has no data field at all — always the dotted placeholder.
        assertThat(html).contains("(..........................)");
    }

    @Test
    void blankSignatoryPrintsDottedPlaceholder() {
        String html = QuotationHtmlDocument.render(baseModel(null, null, null, null, null));
        long dottedCount = countOccurrences(html, "(..........................)");
        // printedBy, checkedBy, approvedBy all null + ผู้สั่งซื้อ always blank = 4.
        assertThat(dottedCount).isEqualTo(4);
    }

    @Test
    void rendersAllEightRemarkLines() {
        String html = QuotationHtmlDocument.render(baseModel(null, null, null, null, null));
        for (String line : EIGHT_REMARKS) {
            assertThat(html).contains(line);
        }
        assertThat(html).contains("หมายเหตุ");
    }

    @Test
    void sizeLinePrintsApproximateSuffix() {
        RenderItem withSize = new RenderItem(null,
            List.of("กระเบื้อง รุ่น Test", "ขนาด 60x60x0.9 cm. (ขนาดโดยประมาณ)", "(จำนวน 10 แผ่น = 10 แผ่น)"),
            BigDecimal.TEN, "แผ่น", BigDecimal.ONE, "Net", BigDecimal.ONE, BigDecimal.TEN);
        String html = QuotationHtmlDocument.render(modelWithItems(List.of(withSize)));
        assertThat(html).contains("(ขนาดโดยประมาณ)");
    }

    @Test
    void calculationLinePrinted() {
        RenderItem item = new RenderItem(null,
            List.of("กระเบื้อง รุ่น Test", "(จำนวน 10 แผ่น = 10 แผ่น)"),
            BigDecimal.TEN, "แผ่น", BigDecimal.ONE, "Net", BigDecimal.ONE, BigDecimal.TEN);
        String html = QuotationHtmlDocument.render(modelWithItems(List.of(item)));
        assertThat(html).contains("(จำนวน 10 แผ่น = 10 แผ่น)");
    }

    @Test
    void escapesAngleBracketsAndAmpersand() {
        RenderItem item = new RenderItem("โซน A & B <injected>",
            List.of("กระเบื้อง <script>alert(1)</script> & co"),
            BigDecimal.ONE, "แผ่น", BigDecimal.ONE, "Net", BigDecimal.ONE, BigDecimal.ONE);
        String html = QuotationHtmlDocument.render(modelWithItems(List.of(item)));
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).doesNotContain("A & B <injected>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("A &amp; B &lt;injected&gt;");
    }

    @Test
    void noExternalUrls() {
        String html = QuotationHtmlDocument.render(baseModel("a", "b", "c", signaturePng(), "image/png"));
        assertThat(html).doesNotContain("http://").doesNotContain("https://");
        assertThat(html).doesNotContain("<link ").doesNotContain("<script ");
    }

    @Test
    void approverImageDataUriPresentOnlyWhenModelHasOne() {
        String withoutSignature = QuotationHtmlDocument.render(baseModel("a", "b", "c", null, null));
        assertThat(withoutSignature).doesNotContain("sig-image");

        String withSignature = QuotationHtmlDocument.render(
            baseModel("a", "b", "c", signaturePng(), "image/png"));
        assertThat(withSignature).contains("class=\"p sig-image\" src=\"data:image/png;base64,");
    }

    @Test
    void logoAlwaysEmbeddedAsDataUri() {
        // The template's own GL&R wordmark picture is a JPEG (TemplateMetricsDump dumps it as
        // src/test/resources/static/brand/template/pic-0.jpeg for humans to look at), not the
        // generic app-wide PNG brand asset; the renderer takes it from the live workbook
        // (SheetPlan#readPictures), which is why it matches the XLS render exactly.
        String html = QuotationHtmlDocument.render(baseModel(null, null, null, null, null));
        assertThat(html).contains("class=\"p logo\" src=\"data:image/jpeg;base64,");
    }

    @Test
    void letterheadRowsRepeatOnEveryPageOfAPaginatedDocument() {
        // 30 three-line items paginate through the XLS path (repeating rows A1:I7, explicit
        // no-split row breaks); the plan must put those seven rows at the top of every page
        // after the first, and the HTML must carry one page box per plan page.
        List<RenderItem> items = new java.util.ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            items.add(new RenderItem("โซน " + i,
                List.of("กระเบื้อง รุ่น Test " + i, "ขนาด 60x60x0.9 cm. (ขนาดโดยประมาณ)", "(จำนวน 10 แผ่น = 10 แผ่น)"),
                BigDecimal.TEN, "แผ่น", BigDecimal.ONE, "Net", BigDecimal.ONE, BigDecimal.TEN));
        }
        QuotationRenderModel model = modelWithItems(items);
        byte[] xls = new th.co.glr.hr.ticket.QuotationRenderer().toXls(model);
        var rendered = QuotationHtmlDocument.rendered(xls, model);
        assertThat(rendered.pageCount()).isGreaterThan(1);
        for (var page : rendered.plan().pages) {
            if (page.number() == 1) continue;
            assertThat(page.repeatedRowCount()).isEqualTo(7);
            assertThat(page.rowIndices().subList(0, 7)).containsExactly(0, 1, 2, 3, 4, 5, 6);
        }
        assertThat(countOccurrences(rendered.html(), "<div class=\"page")).isEqualTo(rendered.pageCount());
        assertThat(rendered.html()).contains("หน้า 1/" + rendered.pageCount());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private byte[] signaturePng() {
        // Minimal valid 1x1 PNG.
        return new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
            0x42, 0x60, (byte) 0x82
        };
    }

    private QuotationRenderModel baseModel(String printedBy, String checkedBy, String approvedBy,
                                            byte[] sigPng, String sigMime) {
        RenderItem item = new RenderItem(null,
            List.of("กระเบื้อง รุ่น Test"),
            BigDecimal.TEN, "แผ่น", BigDecimal.ONE, "Net", BigDecimal.ONE, BigDecimal.TEN);
        Signatories sig = new Signatories(printedBy, checkedBy, approvedBy, sigPng, sigMime);
        return new QuotationRenderModel(
            LocalDate.of(2026, 9, 10), "QT-TEST-0001", "P003", "D002", "Sales/Test T.000",
            "คุณทดสอบ", "โทร. 000", "Test Project",
            List.of(item), EIGHT_REMARKS, sig, true);
    }

    private QuotationRenderModel modelWithItems(List<RenderItem> items) {
        Signatories sig = new Signatories(null, null, null, null, null);
        return new QuotationRenderModel(
            LocalDate.of(2026, 9, 10), "QT-TEST-0001", "P003", "D002", "Sales/Test T.000",
            "คุณทดสอบ", "โทร. 000", "Test Project",
            items, EIGHT_REMARKS, sig, true);
    }

    private long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
