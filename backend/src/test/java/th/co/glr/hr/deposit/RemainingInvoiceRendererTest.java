package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.common.LibreOfficePdfConverter;

class RemainingInvoiceRendererTest {
    private final RemainingInvoiceRenderer renderer = new RemainingInvoiceRenderer();

    @BeforeEach
    void requireLibreOffice() {
        // toXlsx() output is converted to PDF via LibreOffice for this test; skip (don't fail)
        // where it isn't installed — same policy as the repo's Testcontainers-on-Docker gating.
        // CI installs libreoffice-calc.
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(),
            "LibreOffice (soffice) not installed — skipping PDF render test");
    }

    @Test
    void rendersWithoutFormulaErrorsAsASinglePageWithTheCorrectTotal() throws Exception {
        // Regression test for the bugs this branch fixed: the renderer used to leave #REF!/
        // #VALUE! in the amount cells. Item 66 × 979.40 = 64,640.40; deposit 32,320.20 is
        // deducted → remainder 32,320.20; VAT 7% = 2,262.41; total payable = 34,582.61.
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
        // customerAddress fixture value — plain Latin text, no whitespace-flattening needed.
        assertThat(text).contains("99/1 Sukhumvit Road");
        // Item amount, deducted deposit, and final total must all appear as numbers.
        assertThat(text).contains("64,640.40");
        assertThat(text).contains("32,320.20");
        assertThat(text).contains("2,262.41");
        assertThat(text).contains("34,582.61");
    }

    private RemainingInvoiceDto document() {
        return new RemainingInvoiceDto(
            "GLRI69001",
            LocalDate.of(2026, 7, 5),
            "REF-1",
            "บริษัท เอซีเอ็มอี จำกัด",
            "99/1 Sukhumvit Road",
            "0100000000000",
            "โครงการโชว์รูม",
            new BigDecimal("32320.20"),
            List.of(new RemainingInvoiceItemDto(
                1,
                "Tile 60x60",
                new BigDecimal("66"),
                "pcs",
                new BigDecimal("979.40")
            ))
        );
    }
}
