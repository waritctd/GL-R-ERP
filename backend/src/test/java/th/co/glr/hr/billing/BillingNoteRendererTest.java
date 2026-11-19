package th.co.glr.hr.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.common.LibreOfficePdfConverter;

class BillingNoteRendererTest {
    private final BillingNoteRenderer renderer = new BillingNoteRenderer();

    private void requireLibreOffice() {
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(),
            "LibreOffice (soffice) not installed — skipping PDF render test");
    }

    private BillingNoteDto document() {
        return new BillingNoteDto(
            "GLR6900001-1", LocalDate.of(2026, 9, 20),
            "บริษัท ทดสอบ จำกัด", "สาขาทดสอบ", "99/1 Sukhumvit Road\nBangkok 10110",
            "0100000000019", "หมายเหตุทดสอบ", new BigDecimal("126977.20"),
            List.of(
                new BillingNoteLineRenderDto("GLR6900001-1", LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 9, 1), new BigDecimal("100000.00"), "งวดที่ 1"),
                new BillingNoteLineRenderDto("DEP6900002-1", LocalDate.of(2026, 8, 5),
                    LocalDate.of(2026, 9, 5), new BigDecimal("26977.20"), null)
            ));
    }

    @Test
    void rendersWithoutFormulaErrorsAsASinglePageWithTheCorrectTotalAndBahtText() throws Exception {
        requireLibreOffice();
        byte[] xlsx = renderer.toXlsx(document());
        byte[] pdf = LibreOfficePdfConverter.convert(xlsx);

        String text;
        int pageCount;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            pageCount = doc.getNumberOfPages();
            text = new PDFTextStripper().getText(doc);
        }

        assertThat(text).doesNotContain("#VALUE!");
        assertThat(text).doesNotContain("#N/A");
        assertThat(text).doesNotContain("#REF!");
        assertThat(pageCount).isEqualTo(1);
        assertThat(text).contains("บริษัท ทดสอบ จำกัด");
        assertThat(text).contains("0100000000019");
        assertThat(text).contains("GLR6900001-1");
        assertThat(text).contains("DEP6900002-1");
        // Line amounts and the auto-calculated SUM total (100,000.00 + 26,977.20 = 126,977.20).
        assertThat(text).contains("100,000.00");
        assertThat(text).contains("26,977.20");
        assertThat(text).contains("126,977.20");
        // The computed Thai baht-text literal — the exact figure/string ThaiTextTest pins against the owner's own
        // sample sheet.
        assertThat(text).contains("หนึ่งแสนสองหมื่นหกพันเก้าร้อยเจ็ดสิบเจ็ดบาทยี่สิบสตางค์");
        assertThat(text).contains("หมายเหตุทดสอบ");
    }
}
