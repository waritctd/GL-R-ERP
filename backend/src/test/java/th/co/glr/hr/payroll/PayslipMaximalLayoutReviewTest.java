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
 * REVIEWER-authored, FOURTH pass. Geometry, not strings.
 *
 * <p>{@code PdfDocumentWriter#textAt} neither paginates nor calls {@code ensureRoom}, and PDFBox
 * draws outside the media box without error. Every previous "it is on the payslip" claim on this
 * branch was asserted with {@link PDFTextStripper#getText}, which reports a glyph whether or not it
 * landed on the page — that is exactly how a note drawn ~900pt past the right edge passed review.
 *
 * <p>These tests therefore assert POSITIONS. The third pass verified vertical headroom WITHOUT the
 * over-withholding notice; the notice adds an unbounded block of prose below the signature line, so
 * the headroom has to be re-verified WITH it, on the biggest payslip the engine can actually produce.
 */
class PayslipMaximalLayoutReviewTest {
    private final PayslipRenderer renderer = new PayslipRenderer();

    /** Exactly what {@code PayrollCalculator} emits for a clamped, overridden, over-withheld run. */
    private static final String MAXIMAL_NOTE =
        "ป.96/2543 withholding; SSO 5% cap; legal execution floor."
        + " WARNING: ค่าลดหย่อนบุตร 300000.00 > cap 30000.00 (per ล.ย.01 head count)."
        + " WARNING: ค่าอุปการะคนพิการ 600000.00 > cap 60000.00 (per ล.ย.01 head count)."
        + " Withholding tax overridden by HR to 1234.56 (computed projection retained for transparency).";

    @Test
    @DisplayName("a maximal payslip keeps every glyph inside the page, notice and note included")
    void aMaximalPayslipDoesNotRunOffThePage() throws Exception {
        PayrollLineDto line = maximalLine(new BigDecimal("17527.51"), MAXIMAL_NOTE);
        byte[] pdf = renderer.toPdf(line, periodFor(line));

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float pageHeight = doc.getPage(0).getMediaBox().getHeight();
            float pageWidth = doc.getPage(0).getMediaBox().getWidth();
            List<TextPosition> glyphs = glyphs(doc);

            assertThat(glyphs).as("the payslip rendered no text at all").isNotEmpty();

            // PDFTextStripper reports y in top-down page space. A glyph below the page bottom has
            // y > pageHeight; one past the right edge has endX > pageWidth. Both are invisible.
            List<String> offPage = new ArrayList<>();
            for (TextPosition glyph : glyphs) {
                if (glyph.getY() > pageHeight || glyph.getY() < 0
                    || glyph.getEndX() > pageWidth || glyph.getX() < 0) {
                    offPage.add(glyph.getUnicode() + "@(" + glyph.getX() + "," + glyph.getY() + ")");
                }
            }
            assertThat(offPage)
                .as("glyphs drawn outside the A4 media box are invisible to the employee, and "
                    + "PDFBox reports no error — this is the failure mode review caught before")
                .isEmpty();

            // Measured 2026-07-29: the lowest glyph on this maximal payslip sits at y=551 of 841.89,
            // i.e. 240pt of slack below the bottom margin. Pinned so a future addition below the
            // signature line cannot quietly eat it.
            float lowest = 0f;
            for (TextPosition glyph : glyphs) {
                lowest = Math.max(lowest, glyph.getY());
            }
            assertThat(lowest)
                .as("vertical headroom on the maximal payslip has been consumed")
                .isLessThan(pageHeight - 50f);

            assertThat(doc.getNumberOfPages())
                .as("PayslipRenderer uses textAt, which never paginates; a second page here would "
                    + "mean content was pushed off page 1 rather than continued onto page 2")
                .isEqualTo(1);
        }
    }

    /**
     * The notice and the note must both be READABLE, not merely present in the text layer: every
     * wrapped line has to end inside the right margin.
     */
    @Test
    @DisplayName("the over-withholding notice and the note both wrap inside the printable width")
    void theNoticeAndNoteWrapInsideThePrintableWidth() throws Exception {
        PayrollLineDto line = maximalLine(new BigDecimal("17527.51"), MAXIMAL_NOTE);
        byte[] pdf = renderer.toPdf(line, periodFor(line));

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float printableRight = doc.getPage(0).getMediaBox().getWidth() - 50f; // PAGE_MARGIN
            float worstEndX = 0f;
            for (TextPosition glyph : glyphs(doc)) {
                worstEndX = Math.max(worstEndX, glyph.getEndX());
            }
            assertThat(worstEndX)
                .as("something is drawn past the printable right edge (%s)", printableRight)
                .isLessThanOrEqualTo(printableRight + 1f);
        }

        String text = new PDFTextStripper().getText(Loader.loadPDF(pdf));
        assertThat(text).contains("ภ.ง.ด.91");
        assertThat(text).contains("ค่าลดหย่อนบุตร");
        assertThat(text).contains("ค่าอุปการะคนพิการ");
    }

    // --- helpers ------------------------------------------------------------

    private List<TextPosition> glyphs(PDDocument doc) throws Exception {
        List<TextPosition> collected = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                collected.addAll(positions);
            }
        };
        stripper.getText(doc);
        return collected;
    }

    /**
     * The biggest payslip the engine can produce: all 8 พิเศษ slots populated, plus OT, commission,
     * the two V94 one-off fields, ค่าตอบแทนกรรมการ and non-taxable income (14 earnings rows), against
     * all 10 deduction rows.
     */
    private PayrollLineDto maximalLine(BigDecimal excessWithheld, String note) {
        List<PayrollSpecialPayDto> specials = new ArrayList<>();
        for (int slot = 1; slot <= 8; slot += 1) {
            specials.add(new PayrollSpecialPayDto("specialPay" + slot,
                "พิเศษ " + slot + " (ค่าครองชีพประจำเดือน)", money("1000.00")));
        }
        return new PayrollLineDto(
            1L, 7L, "GLR-07", "ทดสอบ ยาวมากเป็นพิเศษ", "AC-บัญชีและการเงิน", "ธ.กสิกรไทย", "1234567890",
            money("50000.00"), money("1666.67"), money("208.33"),
            specials, money("8000.00"),
            money("3000.00"),    // overtime
            money("9000.00"),    // commission
            money("125000.00"),  // grossEarnings
            money("2000.00"),    // nonTaxableIncome
            money("1.50"), money("2500.00"),
            money("122500.00"), money("17500.00"), money("875.00"),
            money("1500000.00"), money("100000.00"), money("100500.00"), money("1299500.00"),
            money("250000.00"), money("1234.56"),
            money("1000.00"),    // studentLoan
            money("3000.00"),    // legalExecution
            money("1500.00"),    // otherPostTax
            money("14109.56"), money("112890.44"),
            note,
            money("20000.00"),   // directorRemuneration
            money("800.00"),     // warningLetterDeduction
            money("900.00"),     // customerReturnDeduction
            money("700.00"),     // otherPretaxDeduction
            money("0.50"), money("500.00"),
            money("1234.56"),    // withholdingTaxOverride
            money("70000.00"), money("52500.00"), money("1234.56"), money("0.00"),
            money("15000.00"),   // bonusPay
            money("5000.00"),    // otherOneOffPay
            excessWithheld);
    }

    private PayrollPeriodDto periodFor(PayrollLineDto line) {
        return new PayrollPeriodDto(
            // DECEMBER (changed 2026-07-29): the over-withholding notice is gated on the final period
            // of the year, because mid-year the figure is provisional and later periods can still
            // absorb it. December is the only month that prints the FINAL wording, which is what the
            // ภ.ง.ด.91 assertion below needs.
            //
            // It is NOT the widest wording -- measured with the real font at 9pt, final = 579.78pt and
            // provisional = 644.03pt, so the eleven provisional months are the harder geometry case.
            // An earlier comment here claimed the opposite and used it to justify testing December
            // only. PayslipProvisionalNoticeGeometryReviewTest covers all eleven.
            99L, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31), LocalDate.of(2026, 12, 31),
            "PROCESSED", OffsetDateTime.now(), 7L, 1,
            money("127000.00"), money("14109.56"), money("112890.44"),
            money("875.00"), money("1234.56"), List.of(line));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
