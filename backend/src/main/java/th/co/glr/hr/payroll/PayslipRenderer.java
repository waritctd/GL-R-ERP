package th.co.glr.hr.payroll;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.PdfDocumentWriter;

/**
 * Employee-facing payslip PDF. The layout mirrors the company's HR payslip template (slip.xls): a
 * centered company header, employee-id/date and name/department rows, a borderless two-column
 * รายการได้ / รายการหัก layout with per-side totals, a bold เงินรับสุทธิ line, and a
 * ผู้รับเงิน / วันที่ signature line.
 *
 * <p>This class only <em>renders</em>. Every figure is read verbatim from the already-computed
 * {@link PayrollLineDto}; no payroll math happens here. The two printed totals are defined so the
 * columns always foot to net pay:
 * <ul>
 *   <li>รวมรายได้ = grossEarnings + nonTaxableIncome (every money-in component gets a row)</li>
 *   <li>รวมรายหัก = totalDeductions (every deduction gets a row; the leave-refund credit prints as a
 *       negative line so the column still sums to totalDeductions)</li>
 *   <li>เงินรับสุทธิ = รวมรายได้ − รวมรายหัก = netPay, exact by construction</li>
 * </ul>
 */
@Component
public class PayslipRenderer {
    private static final String COMPANY_NAME = "บริษัท จีแอลแอนด์อาร์แทปส์แอนด์ไทลส์ จำกัด";

    private static final String[] THAI_MONTHS_ABBR = {
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
        "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."
    };

    private static final float BODY = 10f;
    private static final float COL_GAP = 20f;
    private static final float ROW_H = 15f;

    public byte[] toPdf(PayrollLineDto line, PayrollPeriodDto period) {
        try (PdfDocumentWriter pdf = new PdfDocumentWriter()) {
            PDFont regular = pdf.loadFont(PdfDocumentWriter.FONT_REGULAR);
            PDFont bold = pdf.loadFont(PdfDocumentWriter.FONT_BOLD);

            float left = pdf.left();
            float right = pdf.right();
            float mid = (left + right) / 2f;

            // --- Header -------------------------------------------------------------------
            pdf.textCenter(bold, 15, COMPANY_NAME);
            pdf.textCenter(regular, 13, "สลิปเงินเดือน");
            pdf.gap(14);

            // --- Meta rows ----------------------------------------------------------------
            pdf.textAt(regular, BODY, left, "เลขประจำตัวพนักงาน   " + nullSafe(line.employeeCode(), "-"));
            pdf.textRight(regular, BODY, right, "วันที่   " + thaiDate(period.periodEnd()));
            pdf.newLine(BODY);
            pdf.textAt(regular, BODY, left, "ชื่อ - นามสกุล   " + nullSafe(line.employeeName(), "-"));
            pdf.textRight(regular, BODY, right, "แผนก   " + nullSafe(line.departmentName(), "-"));
            pdf.newLine(BODY);
            pdf.gap(10);

            // --- Earnings / deductions rows ----------------------------------------------
            List<String[]> earnings = new ArrayList<>();
            earnings.add(row("เงินเดือน", line.baseSalary()));
            if (line.specialPays() != null) {
                for (PayrollSpecialPayDto item : line.specialPays()) {
                    if (nonZero(item.amount())) {
                        earnings.add(new String[] {nullSafe(item.label(), "เงินพิเศษ"), fmt2(item.amount())});
                    }
                }
            }
            addIfNonZero(earnings, "ค่าล่วงเวลา", line.overtimePay());
            addIfNonZero(earnings, "ค่าคอมมิชชั่น", line.commissionPay());
            addIfNonZero(earnings, "เงินโบนัส", line.bonusPay());
            addIfNonZero(earnings, "เงินได้อื่นๆ (ครั้งคราว)", line.otherOneOffPay());
            addIfNonZero(earnings, "ค่าตอบแทนกรรมการ", line.directorRemuneration());
            addIfNonZero(earnings, "รายได้ไม่คิดภาษี", line.nonTaxableIncome());

            List<String[]> deductions = new ArrayList<>();
            addIfNonZero(deductions, "ประกันสังคม", line.socialSecurity());
            addIfNonZero(deductions, "ภาษี", line.withholdingTax());
            addIfNonZero(deductions, "หัก กยศ", line.studentLoanDeduction());
            addIfNonZero(deductions, "หักลาไม่รับค่าจ้าง", line.unpaidLeaveDeduction());
            if (nonZero(line.leaveDeductionRefund())) {
                deductions.add(new String[] {"คืนหักลาย้อนหลัง", "-" + fmt2(line.leaveDeductionRefund())});
            }
            addIfNonZero(deductions, "หักตามใบเตือน", line.warningLetterDeduction());
            addIfNonZero(deductions, "หักลูกค้าคืนสินค้า", line.customerReturnDeduction());
            addIfNonZero(deductions, "หักอื่นๆ ก่อนภาษี", line.otherPretaxDeduction());
            addIfNonZero(deductions, "หักอายัดกรมบังคับคดี", line.legalExecutionDeduction());
            addIfNonZero(deductions, "หักอื่นๆ หลังภาษี", line.otherPostTaxDeductions());

            // --- Column headers -------------------------------------------------------------
            float headerBaseline = pdf.cursorY();
            pdf.textAt(bold, 11, left, "รายการได้");
            pdf.textAt(bold, 11, mid + COL_GAP, "รายการหัก");
            pdf.moveTo(headerBaseline - 18f);

            // --- Earnings / deductions rows --------------------------------------------------
            int maxRows = Math.max(earnings.size(), deductions.size());
            float rowBaseline = pdf.cursorY();
            for (int i = 0; i < maxRows; i++) {
                pdf.moveTo(rowBaseline);
                if (i < earnings.size()) {
                    pdf.textAt(regular, BODY, left, earnings.get(i)[0]);
                    pdf.textRight(regular, BODY, mid - COL_GAP, earnings.get(i)[1]);
                }
                if (i < deductions.size()) {
                    pdf.textAt(regular, BODY, mid + COL_GAP, deductions.get(i)[0]);
                    pdf.textRight(regular, BODY, right, deductions.get(i)[1]);
                }
                rowBaseline -= ROW_H;
            }

            // --- Totals row -------------------------------------------------------------------
            BigDecimal earningsTotal = nz(line.grossEarnings()).add(nz(line.nonTaxableIncome()));
            pdf.moveTo(rowBaseline - 4f);
            pdf.textAt(bold, BODY, left, "รวมรายได้");
            pdf.textRight(bold, BODY, mid - COL_GAP, fmt2(earningsTotal));
            pdf.textAt(bold, BODY, mid + COL_GAP, "รวมรายหัก");
            pdf.textRight(bold, BODY, right, fmt2(line.totalDeductions()));
            pdf.moveTo(pdf.cursorY() - 30f);

            // --- Net -----------------------------------------------------------------------
            pdf.textAt(bold, 14, left, "เงินรับสุทธิ");
            pdf.textRight(bold, 14, right, fmt2(line.netPay()) + " บาท");
            pdf.moveTo(pdf.cursorY() - 55f);

            // --- Signature ----------------------------------------------------------------
            pdf.textAt(regular, BODY, left + 18, "……………………………………   ผู้รับเงิน");
            pdf.textAt(regular, BODY, mid + 18, "……………………………………   วันที่");
            pdf.moveTo(pdf.cursorY() - 25f);

            // --- Over-withholding notice ---------------------------------------------------
            // Printed only when it exists, and printed as prose rather than a deduction row, because it
            // is neither an earning nor a deduction: it is money already remitted to the RD in an
            // earlier period of this tax year that exceeds the year's assessed liability. Payroll
            // cannot return it -- the employee reclaims it on their ภ.ง.ด.91 -- so the one thing that
            // must not happen is for it to be invisible. Without this the condition shows only as a
            // 0.00 ภาษี line, which reads as "no tax was due this month".
            if (nonZero(line.excessWithheldToDate())) {
                float noticeWidth = pdf.right() - left;
                // FINAL vs PROVISIONAL (2026-07-29, after review). excessWithheldToDate is measured
                // against the CURRENT period's projected annual tax, so before the last period of the
                // year it is an estimate that later periods can still absorb by withholding less. An
                // unconditional "ไม่สามารถคืนผ่านระบบเงินเดือนได้" was therefore a FALSE statement on a
                // payroll document from as early as April, naming a baht figure an employee could carry
                // onto a ภ.ง.ด.91 -- review built a case where payroll then did return it. Only the
                // final period of the year can assert the excess is unrecoverable.
                // Derived from the payroll month rather than carried on the line: PayrollService
                // computes จำนวนคราวที่ต้องจ่าย as 13 - month, so December IS the final period, and
                // reading it off the period keeps the renderer from needing a field it would only use
                // here. (A leaver's final period is not detected -- ข้อ 2.10 is a known gap.)
                boolean finalPeriodOfYear = period.payrollMonth() != null
                    && period.payrollMonth().getMonthValue() >= 12;
                String notice = finalPeriodOfYear
                    ? "ภาษีหัก ณ ที่จ่ายสะสมปีนี้เกินภาษีที่ต้องเสียทั้งปี "
                        + fmt2(line.excessWithheldToDate()) + " บาท "
                        + "ส่วนเกินนี้ไม่สามารถคืนผ่านระบบเงินเดือนได้ "
                        + "ท่านสามารถขอคืนได้เมื่อยื่นแบบ ภ.ง.ด.91 ประจำปี"
                    : "ประมาณการ: ภาษีหัก ณ ที่จ่ายสะสมถึงงวดนี้สูงกว่าประมาณการภาษีทั้งปีอยู่ "
                        + fmt2(line.excessWithheldToDate()) + " บาท "
                        + "ส่วนต่างนี้อาจลดลงได้จากการหักภาษีในงวดที่เหลือ "
                        + "ยอดที่แน่นอนจะทราบเมื่อสิ้นปีภาษี";
                for (String noticeLine : wrapToWidth(pdf, regular, 9, noticeWidth, notice)) {
                    pdf.textAt(regular, 9, left, noticeLine);
                    pdf.moveTo(pdf.cursorY() - 11f);
                }
                pdf.moveTo(pdf.cursorY() - 4f);
            }

            // --- Optional note ------------------------------------------------------------
            // WRAPPED (2026-07-29). This drew one unwrapped line, which fitted only because the note
            // was short; PDFBox draws past the page edge without complaining, so nothing failed and no
            // test caught it. The ป.96 note is longer, and a withholding-override or clamp warning can
            // triple it -- text that runs off the right edge is not a note, it is a note that is gone.
            if (line.calculationNote() != null && !line.calculationNote().isBlank()) {
                float noteWidth = pdf.right() - left;
                for (String noteLine : wrapToWidth(pdf, regular, 9, noteWidth, "หมายเหตุ: " + line.calculationNote())) {
                    pdf.textAt(regular, 9, left, noteLine);
                    pdf.moveTo(pdf.cursorY() - 11f);
                }
            }

            return pdf.toBytes();
        } catch (IOException e) {
            throw new RuntimeException("Payslip PDF render failed: " + e.getMessage(), e);
        }
    }

    /**
     * Greedy word wrap to a pixel width, measured with the real font rather than guessed from a
     * character count — Sarabun is proportional and Thai has no inter-word spaces, so a character
     * budget would be wrong in both directions. A single token longer than the line (a very long
     * reference, say) is emitted on its own line rather than dropped: overflowing one line beats
     * losing the content.
     */
    List<String> wrapToWidth(PdfDocumentWriter pdf, PDFont font, float size, float maxWidth, String text)
        throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && pdf.width(font, size, candidate) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private void addIfNonZero(List<String[]> rows, String label, BigDecimal amount) {
        if (nonZero(amount)) {
            rows.add(row(label, amount));
        }
    }

    private String[] row(String label, BigDecimal amount) {
        return new String[] {label, fmt2(amount)};
    }

    private String thaiDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        return date.getDayOfMonth() + "/" + THAI_MONTHS_ABBR[date.getMonthValue() - 1] + "/" + (date.getYear() + 543);
    }

    private String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean nonZero(BigDecimal value) {
        return value != null && value.signum() != 0;
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String fmt2(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        return String.format(Locale.US, "%,.2f", value);
    }
}
