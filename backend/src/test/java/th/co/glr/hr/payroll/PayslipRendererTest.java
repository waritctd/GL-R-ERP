package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PayslipRendererTest {
    private final PayslipRenderer renderer = new PayslipRenderer();

    @Test
    void rendersThaiTextAndExactPayrollFigures() throws Exception {
        byte[] pdf = renderer.toPdf(line(), period());

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }

        assertThat(text).contains("สลิปเงินเดือน มิถุนายน 2569");
        assertThat(text).contains("สมชาย ทดสอบ");
        assertThat(text).contains("รายได้รวม: 42,000.00 บาท");
        assertThat(text).contains("ประกันสังคม: 750.00 บาท");
        assertThat(text).contains("ภาษีหัก ณ ที่จ่ายงวดนี้: 500.00 บาท");
        assertThat(text).contains("เงินหักรวม: 10,000.00 บาท");
        assertThat(text).contains("เงินโอนสุทธิ: 32,000.00 บาท");
        assertThat(text).doesNotContain("?????");
    }

    private PayrollPeriodDto period() {
        return new PayrollPeriodDto(
            99L,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            LocalDate.of(2026, 6, 30),
            "PROCESSED",
            OffsetDateTime.now(),
            7L,
            1,
            money("42000.00"),
            money("10000.00"),
            money("32000.00"),
            money("750.00"),
            money("500.00"),
            List.of(line())
        );
    }

    private PayrollLineDto line() {
        return new PayrollLineDto(
            123L,
            42L,
            "GLR-42",
            "สมชาย ทดสอบ",
            "บุคคล",
            "ธนาคาร",
            "001-234-5678",
            money("40000.00"),
            money("1333.33"),
            money("166.67"),
            List.of(new PayrollSpecialPayDto("specialPay1", "พิเศษ 1 (ค่าครองชีพ)", money("1000.00"))),
            money("1000.00"),
            money("500.00"),
            money("500.00"),
            money("42000.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            money("42000.00"),
            money("15000.00"),
            money("750.00"),
            money("504000.00"),
            money("100000.00"),
            BigDecimal.ZERO,
            money("404000.00"),
            money("9500.00"),
            money("500.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            money("8750.00"),
            money("10000.00"),
            money("32000.00"),
            "calculated"
        );
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
