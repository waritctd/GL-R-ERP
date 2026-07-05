package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class DepositNoticeRendererTest {
    private final DepositNoticeRenderer renderer = new DepositNoticeRenderer();

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
