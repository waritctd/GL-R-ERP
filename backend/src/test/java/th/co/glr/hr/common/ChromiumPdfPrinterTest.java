package th.co.glr.hr.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.QuotationHtmlDocument;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderModel.RenderItem;
import th.co.glr.hr.ticket.QuotationRenderModel.Signatories;

/** Guarded by ChromiumTestGate: skips locally when no Chromium can be launched (the LibreOffice-gated idiom),
 * FAILS under REQUIRE_CHROMIUM=1 (CI) so absence can never turn the gate green. */
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

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private static final List<String> EIGHT_REMARKS = List.of(
        "1.line one", "2.line two", "3.line three", "4.line four",
        "5.line five", "6.line six", "7.line seven", "8.line eight");

    private QuotationRenderModel model(int itemCount) {
        return model(itemCount, "QT-TEST-0001");
    }

    /** A small itemCount-item quotation numbered number. */
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
            // The number is repeated in the project name on purpose: I4 is a narrow cell whose left neighbour carries the
            // label, so both engines CLIP it at the cell edge — and how much survives depends on the digit width of
            // whatever font the host substitutes.
            "คุณทดสอบ", "โทร. 000", "Test Project " + number,
            items, EIGHT_REMARKS, sig, true);
    }
}
