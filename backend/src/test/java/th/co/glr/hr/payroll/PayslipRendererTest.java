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
    void rendersTheTwoColumnEmployerLayout() throws Exception {
        PayrollLineDto line = line();
        byte[] pdf = renderer.toPdf(line, periodFor(line));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = extractText(pdf);

        // Header + meta
        assertThat(text).contains("บริษัท จีแอลแอนด์อาร์แทปส์แอนด์ไทลส์ จำกัด");
        assertThat(text).contains("สลิปเงินเดือน");
        assertThat(text).contains("สมชาย ทดสอบ");
        assertThat(text).contains("GLR-42");
        assertThat(text).contains("30/มิ.ย./2569");

        // Two-column grid headers + rows
        assertThat(text).contains("รายการได้");
        assertThat(text).contains("รายการหัก");
        assertThat(text).contains("เงินเดือน");
        assertThat(text).contains("ค่าล่วงเวลา");     // overtime surfaced when non-zero
        assertThat(text).contains("ค่าคอมมิชชั่น");   // commission surfaced when non-zero
        assertThat(text).contains("ประกันสังคม");
        assertThat(text).contains("ภาษี");

        // Totals foot: รวมรายได้ − รวมรายหัก == เงินรับสุทธิ == netPay
        assertThat(text).contains("รวมรายได้");
        assertThat(text).contains("42,000.00");
        assertThat(text).contains("รวมรายหัก");
        assertThat(text).contains("10,000.00");
        assertThat(text).contains("เงินรับสุทธิ");
        assertThat(text).contains("32,000.00");
        BigDecimal earningsTotal = nz(line.grossEarnings()).add(nz(line.nonTaxableIncome()));
        assertThat(earningsTotal.subtract(line.totalDeductions())).isEqualByComparingTo(line.netPay());

        // Signature line
        assertThat(text).contains("ผู้รับเงิน");
        assertThat(text).contains("วันที่");

        // The old HR-worksheet intermediates are gone
        assertThat(text).doesNotContain("รายได้ทั้งปีประมาณการ");
        assertThat(text).doesNotContain("เงินได้สุทธิทั้งปี");
        assertThat(text).doesNotContain("รหัสรอบเงินเดือน");
        assertThat(text).doesNotContain("?????");
    }

    @Test
    void footsWithNonTaxableIncomeAndLeaveRefundCredit() throws Exception {
        // gross 30,000 + non-taxable 2,000 = earnings total 32,000
        // deductions: unpaidLeave 1,500 − refund 500 + sso 875 + กยศ 1,000 = 2,875
        // net = 30,000 − 2,875 + 2,000 = 29,125
        PayrollLineDto line = new PayrollLineDto(
            1L, 7L, "GLR-07", "ทดสอบ สอง", "AC-บัญชี", "ธ.", "1",
            money("30000.00"), money("1000.00"), money("125.00"),
            List.of(), money("0.00"), money("0.00"), money("0.00"),
            money("30000.00"), money("2000.00"), money("1.50"), money("1500.00"), money("28000.00"),
            money("17500.00"), money("875.00"), money("0.00"), money("0.00"), money("0.00"),
            money("0.00"), money("0.00"), money("0.00"), money("1000.00"), money("0.00"),
            money("0.00"), money("2875.00"), money("29125.00"), null,
            money("0.00"), money("0.00"), money("0.00"), money("0.00"),
            money("0.50"), money("500.00")
        );
        String text = extractText(renderer.toPdf(line, periodFor(line)));

        assertThat(text).contains("รายได้ไม่คิดภาษี");
        assertThat(text).contains("2,000.00");
        assertThat(text).contains("คืนหักลาย้อนหลัง");
        assertThat(text).contains("-500.00");
        assertThat(text).contains("32,000.00");   // รวมรายได้
        assertThat(text).contains("2,875.00");     // รวมรายหัก
        assertThat(text).contains("29,125.00");    // เงินรับสุทธิ

        BigDecimal earningsTotal = nz(line.grossEarnings()).add(nz(line.nonTaxableIncome()));
        assertThat(earningsTotal.subtract(line.totalDeductions())).isEqualByComparingTo(line.netPay());
    }

    private String extractText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private PayrollPeriodDto periodFor(PayrollLineDto line) {
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
            List.of(line)
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
            "calculated",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
