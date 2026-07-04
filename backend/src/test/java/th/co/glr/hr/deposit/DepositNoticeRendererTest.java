package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DepositNoticeRendererTest {
    private final DepositNoticeRenderer renderer = new DepositNoticeRenderer();

    @Test
    void rendersPdfBytesWithPdfHeader() {
        byte[] pdf = renderer.toPdf(document());

        assertThat(new String(pdf, 0, 8)).startsWith("%PDF-1.4");
        assertThat(pdf).containsSequence("%%EOF".getBytes());
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
            "ACME Co., Ltd.",
            "0100000000000",
            "Bangkok",
            "Showroom",
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
