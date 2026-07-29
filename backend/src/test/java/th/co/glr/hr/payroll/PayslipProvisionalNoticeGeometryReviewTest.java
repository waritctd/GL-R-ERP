package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REVIEWER-authored, FIFTH pass. The gap the fourth pass's geometry test left open.
 *
 * <p>{@link PayslipMaximalLayoutReviewTest} renders its maximal payslip in DECEMBER only, on the
 * stated grounds that December "carries the longer of the two notice wordings". The over-withholding
 * notice was gated in the fourth-pass remediation, so there are now TWO wordings, and the one that is
 * printed for ELEVEN months of the year — the ประมาณการ variant — had no geometry coverage at all.
 *
 * <p>These tests therefore (a) measure which wording is actually wider, so the claim is a measured
 * fact rather than an assumption, and (b) re-run the fourth pass's off-page assertions on a maximal
 * payslip in a NON-December month. They also pin the gate's boundary at the month level, which is the
 * only thing that decides whether an employee is told a provisional figure is final.
 */
class PayslipProvisionalNoticeGeometryReviewTest {
    private final PayslipRenderer renderer = new PayslipRenderer();

    /** Exactly what {@code PayrollCalculator} emits for a clamped, overridden, over-withheld run. */
    private static final String MAXIMAL_NOTE =
        "ป.96/2543 withholding; SSO 5% cap; legal execution floor."
        + " WARNING: ค่าลดหย่อนบุตร 300000.00 > cap 30000.00 (per ล.ย.01 head count)."
        + " WARNING: ค่าอุปการะคนพิการ 600000.00 > cap 60000.00 (per ล.ย.01 head count)."
        + " Withholding tax overridden by HR to 1234.56 (computed projection retained for transparency).";

    @Test
    @DisplayName("the PROVISIONAL notice occupies at least as many lines as the final one")
    void theProvisionalNoticeIsTheWorstCaseNotTheFinalOne() throws Exception {
        PayrollLineDto line = maximalLine(new BigDecimal("17527.51"), MAXIMAL_NOTE);

        int decemberLines = renderedLineCount(renderer.toPdf(line, periodForMonth(line, 12)));
        int juneLines = renderedLineCount(renderer.toPdf(line, periodForMonth(line, 6)));

        assertThat(juneLines)
            .withFailMessage(
                "PayslipMaximalLayoutReviewTest justifies testing DECEMBER only by asserting it "
                + "carries 'the longer of the two notice wordings'. Measured here: December renders "
                + "%s text lines, June renders %s. If June is the longer, December is not the worst "
                + "case and the maximal-layout test is verifying the shorter branch.",
                decemberLines, juneLines)
            .isGreaterThanOrEqualTo(decemberLines);
    }

    @Test
    @DisplayName("a maximal payslip in a PROVISIONAL month keeps every glyph inside the page")
    void aMaximalProvisionalPayslipDoesNotRunOffThePage() throws Exception {
        PayrollLineDto line = maximalLine(new BigDecimal("17527.51"), MAXIMAL_NOTE);

        // Every month that prints the provisional wording, not just one sample.
        for (int month = 1; month <= 11; month += 1) {
            byte[] pdf = renderer.toPdf(line, periodForMonth(line, month));
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                float pageHeight = doc.getPage(0).getMediaBox().getHeight();
                float pageWidth = doc.getPage(0).getMediaBox().getWidth();
                float printableRight = pageWidth - 50f; // PdfDocumentWriter.PAGE_MARGIN

                List<String> offPage = new ArrayList<>();
                float lowest = 0f;
                float worstEndX = 0f;
                for (TextPosition glyph : glyphs(doc)) {
                    if (glyph.getY() > pageHeight || glyph.getY() < 0
                        || glyph.getEndX() > pageWidth || glyph.getX() < 0) {
                        offPage.add(glyph.getUnicode() + "@(" + glyph.getX() + "," + glyph.getY() + ")");
                    }
                    lowest = Math.max(lowest, glyph.getY());
                    worstEndX = Math.max(worstEndX, glyph.getEndX());
                }

                assertThat(offPage)
                    .as("month %s: glyphs drawn outside the A4 media box are invisible to the "
                        + "employee and PDFBox reports no error", month)
                    .isEmpty();
                assertThat(worstEndX)
                    .as("month %s: something is drawn past the printable right edge (%s)",
                        month, printableRight)
                    .isLessThanOrEqualTo(printableRight + 1f);
                assertThat(lowest)
                    .as("month %s: vertical headroom on the maximal payslip has been consumed", month)
                    .isLessThan(pageHeight - 50f);
                assertThat(doc.getNumberOfPages())
                    .as("month %s: PayslipRenderer never paginates, so a second page means content "
                        + "was pushed off page 1", month)
                    .isEqualTo(1);
            }
        }
    }

    /**
     * The gate boundary. {@code PayslipRenderer} derives "final period" from
     * {@code period.payrollMonth().getMonthValue() >= 12}, which must agree with
     * {@code PayrollService#remainingPayPeriods}'s {@code 13 - month}: December, and only December,
     * is the period after which no further withholding can absorb the excess.
     */
    @Test
    @DisplayName("only December prints the ภ.ง.ด.91 wording; November is still provisional")
    void onlyDecemberAssertsTheExcessIsUnrecoverable() throws Exception {
        PayrollLineDto line = maximalLine(new BigDecimal("17527.51"), MAXIMAL_NOTE);

        for (int month = 1; month <= 11; month += 1) {
            String text = text(renderer.toPdf(line, periodForMonth(line, month)));
            assertThat(text)
                .as("month %s is not the final period, so the payslip must not tell the employee a "
                    + "still-moving figure is unrecoverable", month)
                .doesNotContain("ภ.ง.ด.91")
                .doesNotContain("ไม่สามารถคืนผ่านระบบเงินเดือนได้");
            assertThat(text)
                .as("month %s must still disclose the excess, labelled as an estimate", month)
                .contains("ประมาณการ")
                .contains("17,527.51");
        }

        String december = text(renderer.toPdf(line, periodForMonth(line, 12)));
        assertThat(december).contains("ภ.ง.ด.91");
        assertThat(december).contains("ไม่สามารถคืนผ่านระบบเงินเดือนได้");
        assertThat(december)
            .as("the final wording must not also be hedged as an estimate")
            .doesNotContain("ประมาณการ:");
    }

    /**
     * A null {@code payrollMonth} must fall to the PROVISIONAL wording, never the final one. The
     * renderer's null guard decides this, and the safe direction is unambiguous: asserting an excess
     * is unrecoverable when the period is unknown is a false statement on a payroll document, while
     * labelling it an estimate is merely conservative.
     */
    @Test
    @DisplayName("an unknown payroll month degrades to the provisional wording")
    void anUnknownPayrollMonthIsTreatedAsProvisional() throws Exception {
        PayrollLineDto line = maximalLine(new BigDecimal("17527.51"), MAXIMAL_NOTE);
        PayrollPeriodDto base = periodForMonth(line, 12);
        PayrollPeriodDto noMonth = new PayrollPeriodDto(
            base.id(), null, base.periodStart(), base.periodEnd(), base.payDate(),
            base.status(), base.processedAt(), base.processedById(), base.lineCount(),
            base.totalGross(), base.totalDeductions(), base.totalNet(),
            base.totalSocialSecurity(), base.totalWithholdingTax(), base.lines());

        String text = text(renderer.toPdf(line, noMonth));
        assertThat(text).contains("ประมาณการ");
        assertThat(text).doesNotContain("ภ.ง.ด.91");
    }

    // --- helpers ------------------------------------------------------------

    private int renderedLineCount(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return (int) new PDFTextStripper().getText(doc).lines()
                .filter(candidate -> !candidate.isBlank())
                .count();
        }
    }

    private String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private List<TextPosition> glyphs(PDDocument doc) throws Exception {
        List<TextPosition> collected = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String value, List<TextPosition> positions) {
                collected.addAll(positions);
            }
        };
        stripper.getText(doc);
        return collected;
    }

    /**
     * The biggest payslip the engine can produce — mirrors {@link PayslipMaximalLayoutReviewTest}.
     * All 9 พิเศษ slots (F6 fix, Opus review 2026-07-30 -- see that class's own comment; this loop
     * had the identical one-slot-short defect).
     */
    private PayrollLineDto maximalLine(BigDecimal excessWithheld, String note) {
        List<PayrollSpecialPayDto> specials = new ArrayList<>();
        for (int slot = 1; slot <= 9; slot += 1) {
            specials.add(new PayrollSpecialPayDto("specialPay" + slot,
                "พิเศษ " + slot + " (ค่าครองชีพประจำเดือน)", money("1000.00")));
        }
        return new PayrollLineDto(
            1L, 7L, "GLR-07", "ทดสอบ ยาวมากเป็นพิเศษ", "AC-บัญชีและการเงิน", "ธ.กสิกรไทย", "1234567890",
            money("50000.00"), money("1666.67"), money("208.33"),
            specials, money("9000.00"),
            money("3000.00"), money("9000.00"), money("125000.00"), money("2000.00"),
            money("1.50"), money("2500.00"),
            money("122500.00"), money("17500.00"), money("875.00"),
            money("1500000.00"), money("100000.00"), money("100500.00"), money("1299500.00"),
            money("250000.00"), money("1234.56"),
            money("1000.00"), money("3000.00"), money("1500.00"),
            money("14109.56"), money("112890.44"),
            note,
            money("20000.00"), money("800.00"), money("900.00"), money("700.00"),
            money("0.50"), money("500.00"), money("1234.56"),
            money("70000.00"), money("52500.00"), money("1234.56"), money("0.00"),
            money("15000.00"), money("5000.00"),
            excessWithheld);
    }

    private PayrollPeriodDto periodForMonth(PayrollLineDto line, int month) {
        LocalDate payrollMonth = LocalDate.of(2026, month, 1);
        LocalDate end = payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth());
        return new PayrollPeriodDto(
            99L, payrollMonth, payrollMonth, end, end,
            "PROCESSED", OffsetDateTime.now(), 7L, 1,
            money("127000.00"), money("14109.56"), money("112890.44"),
            money("875.00"), money("1234.56"), List.of(line));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
