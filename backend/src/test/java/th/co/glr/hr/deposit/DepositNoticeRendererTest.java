package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
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

class DepositNoticeRendererTest {
    private final DepositNoticeRenderer renderer = new DepositNoticeRenderer();

    @BeforeEach
    void requireLibreOffice() {
        // toPdf() shells out to LibreOffice; skip (don't fail) where it isn't installed —
        // same policy as the repo's Testcontainers-on-Docker gating. CI installs libreoffice-calc.
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(),
            "LibreOffice (soffice) not installed — skipping PDF render test");
    }

    @Test
    void rendersValidPdfBytes() {
        byte[] pdf = renderer.toPdf(document());

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(pdf).containsSequence("%%EOF".getBytes());
    }

    @Test
    void rendersThaiTextCorrectly() throws Exception {
        byte[] pdf = renderer.toPdf(document());

        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }

        // The previous ASCII-only renderer turned every Thai character into "?" — this
        // asserts the actual Thai customer name and project come through unmangled.
        assertThat(text).contains("บริษัท เอซีเอ็มอี จำกัด");
        assertThat(text).contains("โครงการโชว์รูม");
        assertThat(text).doesNotContain("?????");
    }

    @Test
    void rendersWithoutFormulaErrorsAsASinglePageWithTheCorrectTotal() throws Exception {
        // Regression test for the two bugs this branch fixed: the address cell used to
        // evaluate to #N/A (broken VLOOKUP) and every amount cell evaluated to #VALUE!,
        // and the render leaked extra blank pages. Assert none of that survives, and that
        // the fixture's real customer address + computed total are present in the output.
        byte[] pdf = renderer.toPdf(document());

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
        // customerAddress fixture value ("Bangkok") — plain Latin text, no flattening needed.
        assertThat(text).contains("Bangkok");
        // totalPayable fixture value (depositAmount 500.00 + vatAmount 35.00 = 535.00).
        assertThat(text).contains("535.00");
    }

    private DepositNoticeDto document() {
        return new DepositNoticeDto(
            99L,
            10L,
            "DEPOSIT_NOTICE",
            1,
            "GLRD69001",
            LocalDate.of(2026, 7, 5),
            "ISSUED",
            "บริษัท เอซีเอ็มอี จำกัด",
            "0100000000000",
            "Bangkok",
            "โครงการโชว์รูม",
            "REF-1",
            "THB",
            new BigDecimal("0.50"),
            new BigDecimal("1000.00"),
            new BigDecimal("500.00"),
            new BigDecimal("0.07"),
            new BigDecimal("35.00"),
            new BigDecimal("535.00"),
            List.of("Payment by transfer"),
            true,
            true,
            "Sales",
            "Preparer",
            null,
            null,
            List.of(new DepositNoticeItemDto(
                1L,
                1,
                "Tile 60x60",
                new BigDecimal("10"),
                "pcs",
                new BigDecimal("100.00"),
                null,
                new BigDecimal("100.00"),
                new BigDecimal("1000.00")
            ))
        );
    }
}
