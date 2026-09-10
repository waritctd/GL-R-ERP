package th.co.glr.hr.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.QuotationHtmlDocument;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderModel.RenderItem;
import th.co.glr.hr.ticket.QuotationRenderModel.Signatories;

/**
 * Guarded by {@link ChromiumTestGate}: skips locally when no Chromium can be launched (the
 * LibreOffice-gated idiom), FAILS under {@code REQUIRE_CHROMIUM=1} (CI) so absence can never turn
 * the gate green.
 */
class ChromiumPdfPrinterTest {

    @BeforeEach
    void checkAvailable() {
        ChromiumTestGate.requireOrSkip();
    }

    @Test
    void printsNonEmptyPdf() {
        byte[] pdf = ChromiumPdfPrinter.print(QuotationHtmlDocument.render(model(3)));
        assertThat(pdf).isNotEmpty();
        assertThat(pdf[0]).isEqualTo((byte) '%');
        assertThat(pdf[1]).isEqualTo((byte) 'P');
        assertThat(pdf[2]).isEqualTo((byte) 'D');
        assertThat(pdf[3]).isEqualTo((byte) 'F');
    }

    @Test
    void singlePageDocumentContainsLabelsAndRemarksAndNoPageNumber() throws Exception {
        byte[] pdf = ChromiumPdfPrinter.print(QuotationHtmlDocument.render(model(3)));
        String text = new PDFTextStripper().getText(Loader.loadPDF(pdf));
        String flat = text.replaceAll("\\s+", "");

        assertThat(flat).contains("ผู้พิมพ์");
        assertThat(flat).contains("พนักงานขาย");
        assertThat(flat).contains("ผู้จัดการฝ่ายขาย");
        assertThat(flat).contains("ผู้สั่งซื้อ");
        for (String line : EIGHT_REMARKS) {
            assertThat(flat).contains(line.replaceAll("\\s+", ""));
        }
        // The XLS path sets the "หน้า &P/&N" footer only when it paginates; a single-page
        // LibreOffice render carries no page number, so neither does this one.
        assertThat(flat).doesNotContain("หน้า1/1");
        assertThat(Loader.loadPDF(pdf).getNumberOfPages()).isEqualTo(1);
    }

    @Test
    void thirtyItemDocumentPaginatesAndRepeatsHeaderRow() throws Exception {
        byte[] pdf = ChromiumPdfPrinter.print(QuotationHtmlDocument.render(model(30)));
        var doc = Loader.loadPDF(pdf);
        int pageCount = doc.getNumberOfPages();
        assertThat(pageCount).isGreaterThan(1);

        PDFTextStripper stripper = new PDFTextStripper();
        for (int p = 1; p <= pageCount; p++) {
            stripper.setStartPage(p);
            stripper.setEndPage(p);
            String pageText = stripper.getText(doc).replaceAll("\\s+", "");
            // The sheet's repeating rows A1:I7 (letterhead + column titles) print on every page.
            assertThat(pageText).contains("ลำดับ").contains("จำนวน").contains("หน่วย")
                .contains("ราคา").contains("ส่วนลด").contains("คงเหลือ");
            assertThat(pageText).contains("หน้า" + p + "/" + pageCount);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private static final List<String> EIGHT_REMARKS = List.of(
        "1.line one", "2.line two", "3.line three", "4.line four",
        "5.line five", "6.line six", "7.line seven", "8.line eight");

    private QuotationRenderModel model(int itemCount) {
        return model(itemCount, "QT-TEST-0001");
    }

    /** A small {@code itemCount}-item quotation numbered {@code number} — shared with
     * {@link ChromiumPdfPrinterConcurrencyTest}, whose renders must be told apart by number. */
    static QuotationRenderModel model(int itemCount, String number) {
        List<RenderItem> items = new ArrayList<>();
        for (int i = 1; i <= itemCount; i++) {
            items.add(new RenderItem("โซน " + i,
                List.of("กระเบื้อง รุ่น Test " + i, "ขนาด 60x60x0.9 cm. (ขนาดโดยประมาณ)",
                    "(จำนวน 10 แผ่น = 10 แผ่น)"),
                BigDecimal.TEN, "แผ่น", BigDecimal.ONE, "Net", BigDecimal.ONE, BigDecimal.TEN));
        }
        Signatories sig = new Signatories("Printed Name", "Checked Name", "Approved Name", null, null);
        return new QuotationRenderModel(
            LocalDate.of(2026, 9, 10), number, "P003", "D002", "Sales/Test T.000",
            "คุณทดสอบ", "โทร. 000", "Test Project",
            items, EIGHT_REMARKS, sig, true);
    }
}
