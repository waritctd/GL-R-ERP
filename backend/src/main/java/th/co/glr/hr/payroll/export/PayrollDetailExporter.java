package th.co.glr.hr.payroll.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollSpecialPayDto;
import th.co.glr.hr.payroll.PayrollYearToDate;

/**
 * Builds the "รายละเอียดเงินเดือน" payroll workbook as a THREE-tab accountant-facing report --
 * {@code สรุปเงินเดือน} (per-employee pay/deduction summary), {@code การคำนวณภาษี} (the ป.96/2543
 * annual-projection withholding-tax detail behind each employee's WHT figure), and
 * {@code อัตราภาษีและหมายเหตุ} (the progressive tax-bracket table the SUMPRODUCT formula above reads,
 * plus notes and a reconciliation block). This replaced the prior single ~70-column
 * {@code รายละเอียดเงินเดือน} sheet (kept only as this class's historical name) because that layout
 * buried the withholding-tax derivation entirely off-sheet; this one puts it in view.
 *
 * <p><b>Every money column is the ACTUAL persisted figure; only the ป.96 projection columns on
 * {@code การคำนวณภาษี} are live formulas, and they are context, never read back into a money
 * column.</b> A payroll export must always agree with what was actually withheld and paid (the real
 * payslip and bank file) -- so:
 * <ul>
 *   <li>{@code การคำนวณภาษี}'s "ภาษีหัก ณ ที่จ่ายเดือนนี้ (ระบบ)" column is {@link
 *       PayrollLineDto#withholdingTax()} VERBATIM for every employee, director or not -- never the
 *       {@code (annualTax-ytdWithheld)/remainingPeriods} estimate the projection columns imply.
 *       {@link th.co.glr.hr.payroll.PayrollCalculator#calculateClassified}'s real
 *       regular/known/cumulative three-limb engine does not equal that simple estimate for an
 *       employee whose income varies month to month (commission earners especially).</li>
 *   <li>{@code สรุปเงินเดือน}'s เงินเดือน (col D) is {@link PayrollLineDto#baseSalary()} NET of
 *       {@code unpaidLeaveDeduction + otherPretaxDeduction - leaveDeductionRefund} -- the same
 *       adjustment {@link th.co.glr.hr.payroll.PayrollCalculator#calculateClassified}'s
 *       {@code regularSumAfterLeave} applies before the three limbs are summed into {@link
 *       PayrollLineDto#grossTaxableIncome()}. A cell comment states the full/adjustment breakdown
 *       whenever it is non-zero (e.g. a mid-month unpaid-leave day). Folding the adjustment into this
 *       one column (rather than adding a column this layout has no room for) is what makes
 *       {@code รวมรายได้ที่ต้องเสียภาษี} ({@code =SUM(D:Q)}) equal {@link
 *       PayrollLineDto#grossTaxableIncome()} for standard data.</li>
 *   <li>{@code สรุปเงินเดือน}'s ประกันสังคม (col S) is {@link PayrollLineDto#socialSecurity()}
 *       VERBATIM, not the standard 5%/1,650-17,500 formula the notes tab still documents for
 *       reference -- the real engine derives SSO from a per-component inclusion matrix a flat formula
 *       cannot always reproduce, so a formula here could silently disagree with what was actually
 *       withheld.</li>
 *   <li>{@code สรุปเงินเดือน}'s หักอื่น ๆ (col W) sums {@link PayrollLineDto#otherPostTaxDeductions()}
 *       + {@link PayrollLineDto#warningLetterDeduction()} + {@link
 *       PayrollLineDto#customerReturnDeduction()} -- every POST-TAX "other" category {@link
 *       th.co.glr.hr.payroll.PayrollCalculator}'s {@code totalDeductions} includes, combined because
 *       this layout has only one column for them (the previous version silently dropped two of the
 *       three).</li>
 * </ul>
 * With all four money columns above wired to the real figures, {@code รวมรายการหัก} ({@code
 * =SUM(S:W)}) and คงเหลือจ่ายสุทธิ ({@code =R-X}) foot to {@link PayrollLineDto#netPay()} for
 * STANDARD data. <b>The one remaining, deliberate, and VISIBLE residual gap:</b> a line whose income
 * includes {@link PayrollLineDto#bonusPay()}, {@link PayrollLineDto#otherOneOffPay()}, {@link
 * PayrollLineDto#welfarePay()}, {@link PayrollLineDto#perDiemTaxable()}, or {@link
 * PayrollLineDto#nonTaxableIncome()} is NOT itemized in R (this layout has no column for any of
 * them, by instruction -- do not add one), so คงเหลือจ่ายสุทธิ under- or over-states {@code
 * netPay()} by exactly that amount for such a line. This is not silent: {@code
 * อัตราภาษีและหมายเหตุ}'s reconciliation block sums every line's real {@link PayrollLineDto} totals
 * against the Sheet-1 SUM totals and surfaces any such difference directly, rather than hiding it.
 *
 * <p>The remaining projection columns on {@code การคำนวณภาษี} (เงินได้ทั้งปี, หักค่าใช้จ่าย,
 * ลดหย่อนรวม, เงินได้สุทธิทั้งปี, ภาษีทั้งปี ขั้นบันได, ภาษีคงเหลือทั้งปี) stay LIVE FORMULAS, because
 * they are legitimately informational: they show the ป.96/2543 annual basis the withholding figure
 * sits on top of, for HR/accountant review, and are never read back into a money column.
 */
@Component
public class PayrollDetailExporter {
    private static final String SUMMARY_SHEET = "สรุปเงินเดือน";
    private static final String TAX_SHEET = "การคำนวณภาษี";
    private static final String RATE_SHEET = "อัตราภาษีและหมายเหตุ";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static final String M2 = "#,##0.00;(#,##0.00);-";
    private static final String M0 = "#,##0;(#,##0);-";

    private static final String[] THAI_MONTH_FULL = {
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม"
    };
    private static final String[] THAI_MONTH_ABBR = {
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
        "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."
    };

    // ---- sheet1 (สรุปเงินเดือน) row layout: title(1), subtitle(2), [gap 3], header(4), data(5..). --
    private static final int SUMMARY_HEADER_ROW = 4;
    private static final int SUMMARY_DATA_START_ROW = 5;

    // ---- sheet2 (การคำนวณภาษี) row layout: title(1), subtitle(2), header(3), data(4..). ----
    private static final int TAX_HEADER_ROW = 3;
    private static final int TAX_DATA_START_ROW = 4;

    /** Per-row context handed to every cell writer -- one employee line plus its YTD carry-forward. */
    private record Ctx(PayrollLineDto line, PayrollYearToDate ytd) {
        BigDecimal special(int index) {
            List<PayrollSpecialPayDto> pays = line.specialPays();
            if (pays == null || index >= pays.size()) {
                return ZERO;
            }
            BigDecimal amount = pays.get(index).amount();
            return amount == null ? ZERO : amount;
        }

        BigDecimal nz(BigDecimal value) {
            return value == null ? ZERO : value;
        }

        boolean isDirector() {
            return line.withholdingTaxOverride() != null;
        }

        /**
         * Total ACTUAL (not annualised) taxable income already paid this tax year before the current
         * period -- the sum of the three ป.96 limbs' own persisted cumulative figures. See
         * PayrollYearToDate's javadoc: regularLimbTaxableIncome/cumulativeLimbTaxableIncome are each
         * already a running total of what was actually paid on that limb in every prior period this
         * year, and knownLimbTaxableIncome is the same running total for settled known-frequency
         * payments. None of the three is itself annualised/projected, so their sum is exactly
         * "เงินได้สะสม ม.ค.-ก่อนงวดนี้" -- distinct from projectedAnnualIncome, which reprojects the
         * regular limb forward for the REST of the year.
         */
        BigDecimal cumulativeIncomeBeforeThisPeriod() {
            return nz(ytd.regularLimbTaxableIncome())
                .add(nz(ytd.cumulativeLimbTaxableIncome()))
                .add(nz(ytd.knownLimbTaxableIncome()));
        }

        /** Total withholding already remitted this tax year before the current period, all limbs. */
        BigDecimal cumulativeWithholdingBeforeThisPeriod() {
            return nz(ytd.withholdingTax());
        }
    }

    public byte[] export(List<PayrollLineDto> lines, Map<Long, PayrollDetailIdentityDto> identities,
                         PayrollPeriodDto period, Map<Long, PayrollYearToDate> yearToDateByEmployee) {
        // `identities` (hire date / last salary adjustment) has no column in this 3-tab format -- the
        // accountant's reference workbook this replaces the old sheet with never showed it either.
        // Kept as a parameter (unused here) rather than removed, so PayrollService's existing
        // findDetailIdentity plumbing does not need to be torn out for a display concern that may
        // return in a future tab.
        int n = lines.size();
        int summaryTotalsRow = SUMMARY_DATA_START_ROW + n;
        int taxTotalsRow = TAX_DATA_START_ROW + n;
        int monthOfYear = period.payrollMonth() != null ? period.payrollMonth().getMonthValue() : 1;
        String prevMonthLabel = monthOfYear > 1 ? "ม.ค.–" + THAI_MONTH_ABBR[monthOfYear - 2] : "(เดือนแรกของปี)";

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // Create every sheet up-front (empty) so cross-sheet formulas written below can resolve
            // their target sheet immediately -- POI validates formula syntax (incl. sheet existence)
            // at setCellFormula() time, unlike a plain-text writer.
            XSSFSheet summarySheet = workbook.createSheet(SUMMARY_SHEET);
            XSSFSheet taxSheet = workbook.createSheet(TAX_SHEET);
            XSSFSheet rateSheet = workbook.createSheet(RATE_SHEET);
            Styles styles = new Styles(workbook);

            List<Ctx> rows = new ArrayList<>(n);
            for (PayrollLineDto line : lines) {
                PayrollYearToDate ytd = yearToDateByEmployee.getOrDefault(line.employeeId(), PayrollYearToDate.empty());
                rows.add(new Ctx(line, ytd));
            }

            buildSummarySheet(summarySheet, styles, rows, period, monthOfYear, summaryTotalsRow);
            buildTaxSheet(taxSheet, styles, rows, prevMonthLabel, taxTotalsRow);
            buildRateSheet(rateSheet, styles, summaryTotalsRow, rows, prevMonthLabel);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // =====================================================================================
    // Sheet 1 -- สรุปเงินเดือน
    // =====================================================================================

    private static final String[] SUMMARY_HEADERS = {
        "รหัส", "ชื่อ - สกุล", "ฝ่าย", "เงินเดือน", "ค่าตอบแทน\nกรรมการ",
        "พิเศษ 1\nค่าครองชีพ", "พิเศษ 2\nค่าเช่าบ้าน", "พิเศษ 3\nเบี้ยเลี้ยงประจำ", "พิเศษ 4\nค่าตำแหน่ง",
        "พิเศษ 5\nเบี้ยขยัน", "พิเศษ 6\nค่า GPRS", "พิเศษ 7\nคอมมิชชั่นพิเศษ", "พิเศษ 8\nKPI",
        "พิเศษ 9\nเงินรางวัล", "ค่าอาหาร", "ล่วงเวลา", "คอมมิชชั่น",
        "รวมรายได้\nที่ต้องเสียภาษี", "ประกันสังคม", "ภาษีหัก ณ\nที่จ่าย", "หัก กยศ.", "หักกรม\nบังคับคดี",
        "หักอื่น ๆ", "รวมรายการหัก", "คงเหลือ\nจ่ายสุทธิ"
    };

    private void buildSummarySheet(XSSFSheet sheet, Styles styles, List<Ctx> rows, PayrollPeriodDto period,
                                    int monthOfYear, int totalsRow) {
        sheet.setDisplayGridlines(false);
        writeTitle(sheet, styles, "สรุปเงินเดือน ประจำงวดเดือน " + THAI_MONTH_FULL[monthOfYear - 1] + " "
            + (period.payrollMonth() != null ? period.payrollMonth().getYear() + 543 : ""));
        writeSubtitle(sheet, styles, "GL&R ERP" + (period.id() != null ? " · period_id " + period.id() : "")
            + " · เงินเดือน/ประกันสังคม/หักกรมบังคับคดี/หักอื่นๆ/ภาษีหัก ณ ที่จ่าย/คงเหลือจ่ายสุทธิ "
            + "คือยอดจริงที่ระบบบันทึก (ดูหมายเหตุในชีต " + RATE_SHEET + ")");

        for (int c = 0; c < SUMMARY_HEADERS.length; c++) {
            writeHeaderCell(sheet, SUMMARY_HEADER_ROW, c, SUMMARY_HEADERS[c], styles);
        }

        // Lazily created on first cell comment needed (สนั่น-style unpaid-leave adjustment) --
        // Sheet#createDrawingPatriarch() replaces any existing drawing if called more than once per
        // sheet, so it must be created at most once and reused for every comment on this sheet.
        Drawing<?> drawing = null;

        for (int i = 0; i < rows.size(); i++) {
            Ctx ctx = rows.get(i);
            int rr = SUMMARY_DATA_START_ROW + i;
            int taxRow = TAX_DATA_START_ROW + i;
            Row row = sheet.createRow(rr - 1);
            PayrollLineDto line = ctx.line();

            setText(row, styles, 0, line.employeeCode());
            setText(row, styles, 1, line.employeeName());
            setText(row, styles, 2, line.departmentName());
            // Correctness requirement (net-pay fidelity): the real engine's grossTaxableIncome nets
            // unpaidLeaveDeduction and otherPretaxDeduction OUT of (and leaveDeductionRefund back
            // INTO) the regular limb -- see PayrollCalculator#calculateClassified's
            // regularSumAfterLeave. Folding that same adjustment into เงินเดือน here (rather than a
            // separate column this layout has no room for) is what makes SUM(D:Q) below equal
            // grossTaxableIncome() for standard data, instead of silently overstating it by exactly
            // this period's unpaid-leave/other-pretax deduction (net of any refund) -- e.g. สนั่น's
            // ฿245 unpaid-leave case.
            BigDecimal baseSalary = ctx.nz(line.baseSalary());
            BigDecimal unpaidLeaveDeduction = ctx.nz(line.unpaidLeaveDeduction());
            BigDecimal otherPretaxDeduction = ctx.nz(line.otherPretaxDeduction());
            BigDecimal leaveDeductionRefund = ctx.nz(line.leaveDeductionRefund());
            BigDecimal netAdjustment = unpaidLeaveDeduction.add(otherPretaxDeduction).subtract(leaveDeductionRefund);
            BigDecimal adjustedSalary = baseSalary.subtract(netAdjustment);
            setBlue(row, styles, 3, adjustedSalary, M2);
            if (netAdjustment.signum() != 0) {
                if (drawing == null) {
                    drawing = sheet.createDrawingPatriarch();
                }
                addComment(sheet, drawing, rr - 1, 3,
                    "เงินเดือนเต็ม " + baseSalary.toPlainString() + " หัก ลาไม่รับค่าจ้าง/อื่นๆ "
                        + netAdjustment.toPlainString());
            }
            setBlue(row, styles, 4, ctx.nz(line.directorRemuneration()), M2);
            for (int s = 0; s < 9; s++) {
                setBlue(row, styles, 5 + s, ctx.special(s), M2);
            }
            setBlue(row, styles, 14, ctx.nz(line.mealAllowance()), M2);
            setBlue(row, styles, 15, ctx.nz(line.overtimePay()), M2);
            setBlue(row, styles, 16, ctx.nz(line.commissionPay()), M2);
            setFormula(row, styles, 17, "SUM(D" + rr + ":Q" + rr + ")", M2);
            // ประกันสังคม: the ACTUAL persisted value, not a recomputed formula -- the real engine
            // derives SSO from a per-component inclusion matrix (PayrollCalculator's SSO treatment
            // table) that a flat "5% of gross-minus-director-minus-OT" formula cannot always
            // reproduce (e.g. a component excluded from the SSO wage base for a reason this simple
            // rule does not know). A formula here could silently disagree with what was actually
            // withheld; the literal value cannot.
            setBlue(row, styles, 18, ctx.nz(line.socialSecurity()), M2);
            setGreenFormula(row, styles, 19, "'" + TAX_SHEET + "'!L" + taxRow, M0);
            setBlue(row, styles, 20, ctx.nz(line.studentLoanDeduction()), M2);
            setBlue(row, styles, 21, ctx.nz(line.legalExecutionDeduction()), M2);
            // หักอื่น ๆ: sums every POST-TAX "other" deduction category that has no column of its own
            // (otherPostTaxDeductions, warningLetterDeduction, customerReturnDeduction) -- all three
            // are added into PayrollCalculator's totalDeductions the same way, post-tax, so combining
            // them here (rather than dropping two of the three, as the previous column did) is what
            // makes รวมรายการหัก/คงเหลือจ่ายสุทธิ below foot to totalDeductions()/netPay().
            BigDecimal otherPostTax = ctx.nz(line.otherPostTaxDeductions())
                .add(ctx.nz(line.warningLetterDeduction()))
                .add(ctx.nz(line.customerReturnDeduction()));
            setBlue(row, styles, 22, otherPostTax, M2);
            setFormula(row, styles, 23, "SUM(S" + rr + ":W" + rr + ")", M2);
            setFormula(row, styles, 24, "R" + rr + "-X" + rr, M0);
        }

        Row totals = sheet.createRow(totalsRow - 1);
        setCell(totals, styles.totalsLabel, 0, "รวม");
        setCell(totals, styles.totalsLabel, 1, "พนักงาน " + rows.size() + " คน");
        setCell(totals, styles.totalsLabel, 2, "");
        int firstDataRow = SUMMARY_DATA_START_ROW;
        int lastDataRow = SUMMARY_DATA_START_ROW + rows.size() - 1;
        for (int c = 3; c < SUMMARY_HEADERS.length; c++) {
            String colLetter = colLetter(c);
            boolean intFormat = c == 19 || c == 24; // T (WHT) / Y (net) columns are whole-baht.
            Cell cell = totals.createCell(c);
            if (rows.isEmpty()) {
                cell.setCellValue(0d);
            } else {
                cell.setCellFormula("SUM(" + colLetter + firstDataRow + ":" + colLetter + lastDataRow + ")");
            }
            cell.setCellStyle(intFormat ? styles.totalsM0 : styles.totalsM2);
        }

        applySummaryLayout(sheet);
    }

    private void applySummaryLayout(XSSFSheet sheet) {
        int[] widths = {9, 26, 18, 13, 12, 12, 11, 13, 11, 11, 12, 13, 11, 12, 11, 11, 13, 15, 12, 13, 11, 12, 11, 13, 15};
        for (int c = 0; c < widths.length; c++) {
            sheet.setColumnWidth(c, widths[c] * 256);
        }
        if (sheet.getRow(0) != null) {
            sheet.getRow(0).setHeightInPoints(16.5f);
        }
        if (sheet.getRow(1) != null) {
            sheet.getRow(1).setHeightInPoints(15f);
        }
        if (sheet.getRow(SUMMARY_HEADER_ROW - 1) != null) {
            sheet.getRow(SUMMARY_HEADER_ROW - 1).setHeightInPoints(42f);
        }
        sheet.createFreezePane(3, SUMMARY_DATA_START_ROW - 1);
    }

    // =====================================================================================
    // Sheet 2 -- การคำนวณภาษี
    // =====================================================================================

    private void buildTaxSheet(XSSFSheet sheet, Styles styles, List<Ctx> rows, String prevMonthLabel,
                                int totalsRow) {
        sheet.setDisplayGridlines(false);
        writeTitle(sheet, styles, "รายละเอียดการคำนวณภาษีหัก ณ ที่จ่าย");
        writeSubtitle(sheet, styles,
            "ภาษีหัก ณ ที่จ่ายเดือนนี้ (ระบบ) คือยอดที่ระบบหักจริงตามเอนจินภาษี (PayrollCalculator) เสมอ -- "
                + "ไม่ใช่สูตรประมาณการ | คอลัมน์เงินได้ทั้งปี/หักค่าใช้จ่าย/ลดหย่อน/เงินได้สุทธิ/ภาษีทั้งปี/"
                + "ภาษีคงเหลือ เป็นข้อมูลประกอบแสดงฐานการคำนวณตาม ป.96/2543 เท่านั้น "
                + "ยอดสะสม " + prevMonthLabel + " มาจากงวดก่อนหน้าในปีภาษีเดียวกัน");

        String[] headers = {
            "รหัส", "ชื่อ - สกุล", "รวมเงินได้\nเดือนนี้", "เงินได้สะสม\n" + prevMonthLabel,
            "ภาษีหักสะสม\n" + prevMonthLabel, "เงินได้ทั้งปี\n(ประมาณการ)", "หักค่าใช้จ่าย\n50%≤100,000",
            "ลดหย่อนรวม", "เงินได้สุทธิ\nทั้งปี", "ภาษีทั้งปี\n(ขั้นบันได)", "ภาษีคงเหลือ\nทั้งปี",
            "ภาษีหัก ณ ที่จ่าย\nเดือนนี้ (ระบบ)", "ประเภท"
        };
        for (int c = 0; c < headers.length; c++) {
            writeHeaderCell(sheet, TAX_HEADER_ROW, c, headers[c], styles);
        }

        for (int i = 0; i < rows.size(); i++) {
            Ctx ctx = rows.get(i);
            PayrollLineDto line = ctx.line();
            int rr = TAX_DATA_START_ROW + i;
            int summaryRow = SUMMARY_DATA_START_ROW + i;
            Row row = sheet.createRow(rr - 1);

            setText(row, styles, 0, line.employeeCode());
            setText(row, styles, 1, line.employeeName());
            setGreenFormula(row, styles, 2, "'" + SUMMARY_SHEET + "'!R" + summaryRow, M2);
            setBlue(row, styles, 3, ctx.cumulativeIncomeBeforeThisPeriod(), M2);
            setBlue(row, styles, 4, ctx.cumulativeWithholdingBeforeThisPeriod(), M2);
            setBlue(row, styles, 5, ctx.nz(line.projectedAnnualIncome()), M2);
            setFormula(row, styles, 6, "MIN(F" + rr + "*0.5,100000)", M2);
            setBlue(row, styles, 7, ctx.nz(line.taxAllowanceTotal()), M2);
            setFormula(row, styles, 8, "MAX(0,F" + rr + "-G" + rr + "-H" + rr + ")", M2);
            setFormula(row, styles, 9,
                "SUMPRODUCT((MAX(0,$I" + rr + ")>'" + RATE_SHEET + "'!$A$4:$A$11)*(MAX(0,$I" + rr + ")-'"
                    + RATE_SHEET + "'!$A$4:$A$11)*'" + RATE_SHEET + "'!$D$4:$D$11)",
                M2);
            setFormula(row, styles, 10, "MAX(0,J" + rr + "-E" + rr + ")", M2);
            // Correctness requirement, not a style choice: a payroll export must ALWAYS agree with
            // what was actually withheld (the real payslip/bank file). The K column above is
            // informational context (the ป.96 annual-projection basis); the money cell here is
            // PayrollLineDto#withholdingTax() verbatim for EVERY employee, director or not -- never a
            // recomputed (annualTax-ytdWithheld)/remainingPeriods estimate, which the real
            // regular/known/cumulative three-limb engine does not equal in general (commission earners
            // especially). "ประเภท" still distinguishes an HR-typed fixed override from the engine's
            // own figure, purely as metadata -- both are literal values now.
            setBlue(row, styles, 11, ctx.nz(line.withholdingTax()), M0);
            setText(row, styles, 12, ctx.isDirector() ? "กรรมการ (คงที่)" : "คำนวณ");
        }

        Row totals = sheet.createRow(totalsRow - 1);
        setCell(totals, styles.totalsLabel, 0, "รวม");
        setCell(totals, styles.totalsLabel, 1, "พนักงาน " + rows.size() + " คน");
        int firstDataRow = TAX_DATA_START_ROW;
        int lastDataRow = TAX_DATA_START_ROW + rows.size() - 1;
        for (int c = 2; c <= 11; c++) {
            String colLetter = colLetter(c);
            Cell cell = totals.createCell(c);
            if (rows.isEmpty()) {
                cell.setCellValue(0d);
            } else {
                cell.setCellFormula("SUM(" + colLetter + firstDataRow + ":" + colLetter + lastDataRow + ")");
            }
            cell.setCellStyle(c == 11 ? styles.totalsM0 : styles.totalsM2);
        }
        setCell(totals, styles.totalsLabel, 12, "");

        applyTaxLayout(sheet);
    }

    private void applyTaxLayout(XSSFSheet sheet) {
        int[] widths = {9, 26, 13, 15, 14, 14, 14, 12, 13, 13, 13, 15, 15};
        for (int c = 0; c < widths.length; c++) {
            sheet.setColumnWidth(c, widths[c] * 256);
        }
        if (sheet.getRow(TAX_HEADER_ROW - 1) != null) {
            sheet.getRow(TAX_HEADER_ROW - 1).setHeightInPoints(42f);
        }
        sheet.createFreezePane(2, TAX_DATA_START_ROW - 1);
    }

    // =====================================================================================
    // Sheet 3 -- อัตราภาษีและหมายเหตุ
    // =====================================================================================

    private static final Object[][] BRACKETS = {
        {0, 150000, 0.0}, {150000, 300000, 0.05}, {300000, 500000, 0.10}, {500000, 750000, 0.15},
        {750000, 1000000, 0.20}, {1000000, 2000000, 0.25}, {2000000, 5000000, 0.30}, {5000000, null, 0.35}
    };

    private void buildRateSheet(XSSFSheet sheet, Styles styles, int summaryTotalsRow, List<Ctx> rows,
                                 String prevMonthLabel) {
        sheet.setDisplayGridlines(false);
        writeTitle(sheet, styles, "อัตราภาษีเงินได้บุคคลธรรมดา (ขั้นบันได)");

        String[] bracketHeaders = {"เงินได้สุทธิตั้งแต่", "ถึง", "อัตราภาษี (%)", "ส่วนต่างอัตรา (%)"};
        for (int c = 0; c < bracketHeaders.length; c++) {
            writeHeaderCell(sheet, 3, c, bracketHeaders[c], styles);
        }
        for (int i = 0; i < BRACKETS.length; i++) {
            int r = 4 + i;
            Row row = sheet.createRow(r - 1);
            setBlueRaw(row, styles, 0, ((Number) BRACKETS[i][0]).doubleValue(), "#,##0");
            Object upper = BRACKETS[i][1];
            if (upper == null) {
                Cell cell = row.createCell(1);
                cell.setCellValue("ขึ้นไป");
                cell.setCellStyle(styles.blueTextRight);
            } else {
                setBlueRaw(row, styles, 1, ((Number) upper).doubleValue(), "#,##0");
            }
            setBlueRaw(row, styles, 2, (Double) BRACKETS[i][2], "0%");
            setFormula(row, styles, 3, i == 0 ? "C4" : "C" + r + "-C" + (r - 1), "0.0%");
        }

        int r = 13;
        writeNoteBold(sheet, styles, r, "หมายเหตุ");
        r += 1;
        writeNotePlain(sheet, styles, r,
            "สีน้ำเงิน = ค่าจากระบบ/รับมาจากงวดก่อนหน้า · สีดำ = สูตรคำนวณในไฟล์นี้ · สีเขียว = อ้างอิงข้ามชีต");
        r += 1;
        writeNotePlain(sheet, styles, r,
            "ที่มา: ระบบ GL&R ERP · ยอดสะสม " + prevMonthLabel + " มาจาก payroll_line ของงวดก่อนหน้าในปีภาษีเดียวกัน "
                + "รวมกับยอดยกมาก่อนใช้ระบบ (หากมี)");
        r += 2;
        writeNoteBold(sheet, styles, r, "ภาษีหัก ณ ที่จ่ายเดือนนี้ (ชีตการคำนวณภาษี คอลัมน์ \"...(ระบบ)\"):");
        r += 1;
        writeNotePlain(sheet, styles, r,
            "คือยอดที่ระบบหักจริงตามเอนจินภาษี (PayrollCalculator) เสมอ ทั้งพนักงานทั่วไปและกรรมการ -- "
                + "ไม่ใช่สูตรประมาณการ จึงตรงกับสลิปเงินเดือนและไฟล์โอนเงินจริงทุกกรณี");
        r += 1;
        writeNotePlain(sheet, styles, r,
            "คอลัมน์เงินได้ทั้งปี/หักค่าใช้จ่าย/ลดหย่อน/เงินได้สุทธิ/ภาษีทั้งปี ขั้นบันได/ภาษีคงเหลือทั้งปี "
                + "เป็นข้อมูลประกอบแสดงฐานการคำนวณตาม ป.96/2543 เท่านั้น ไม่ใช่ที่มาของยอดหักจริง "
                + "(เอนจินจริงคำนวณแบบ 3 ฐาน ปกติ/ทราบล่วงหน้า/สะสม ซึ่งไม่เท่ากับการเฉลี่ยแบบง่ายในคอลัมน์เหล่านี้)");
        r += 2;
        writeNoteBold(sheet, styles, r, "คอลัมน์เงินจริงในชีตสรุปเงินเดือน:");
        r += 1;
        writeNotePlain(sheet, styles, r,
            "เงินเดือน (D): เงินเดือนเต็มหักลาไม่รับค่าจ้าง/รายการหักก่อนภาษีอื่น ๆ ของงวดนี้ บวกเงินคืนจากการหักวันลา "
                + "(ถ้ามี) -- มีความคิดเห็น (comment) กำกับที่เซลล์เมื่อมีการปรับ ชี้แจงยอดเต็มและยอดที่หัก");
        r += 1;
        writeNotePlain(sheet, styles, r,
            "ประกันสังคม (S) และหักกรมบังคับคดี (V): ยอดที่ระบบหักจริงเสมอ ไม่ใช่สูตรประมาณ 5% มาตรฐาน "
                + "-- เอนจินจริงคำนวณ SSO จากตารางองค์ประกอบที่รวม/ไม่รวมในฐาน ซึ่งสูตรอย่างง่ายอาจสะท้อนไม่ครบ");
        r += 1;
        writeNotePlain(sheet, styles, r,
            "หักอื่น ๆ (W) = หักอื่นๆ + หักตามใบเตือน + หักลูกค้าคืนสินค้า รวมกัน (ทั้งสามรายการหักหลังภาษีเหมือนกัน "
                + "แต่ไฟล์นี้มีคอลัมน์เดียว)");
        r += 2;
        writeNotePlain(sheet, styles, r,
            "ด้วยคอลัมน์เงินจริงข้างต้น รวมรายได้ (R)/รวมรายการหัก (X)/คงเหลือจ่ายสุทธิ (Y) จะตรงกับ "
                + "grossTaxableIncome/รายการหักที่หักจริง/netPay ของระบบสำหรับข้อมูลปกติ");
        r += 1;
        writeNotePlain(sheet, styles, r,
            "⚠ ข้อยกเว้นเดียวที่เหลืออยู่ (ตั้งใจ ไม่ปิดบัง): พนักงานที่มีโบนัส/รายได้ครั้งคราวอื่นๆ/สวัสดิการ/"
                + "เบี้ยเลี้ยงส่วนต้องเสียภาษี/รายได้ไม่เสียภาษี ไฟล์นี้ไม่มีคอลัมน์แสดงรายการเหล่านี้แยก "
                + "คงเหลือจ่ายสุทธิ (Y) จึงอาจต่างจาก netPay จริงเท่ากับยอดรายการนั้น -- ดูผลต่างในตาราง"
                + "ตรวจสอบยอดรวมด้านล่างหากมี");

        r += 2;
        writeNoteBold(sheet, styles, r, "ตรวจสอบยอดรวม — เทียบสูตรในไฟล์กับยอดจากระบบ ERP");
        int headerRow = r + 2;
        String[] reconHeaders = {"รายการ", "ยอดในไฟล์ (สูตร)", "ยอดจากระบบ ERP", "ผลต่าง"};
        for (int c = 0; c < reconHeaders.length; c++) {
            writeHeaderCell(sheet, headerRow, c, reconHeaders[c], styles);
        }

        BigDecimal grossTaxableSum = ZERO;
        BigDecimal ssoSum = ZERO;
        BigDecimal whtSum = ZERO;
        BigDecimal netPaySum = ZERO;
        for (Ctx ctx : rows) {
            grossTaxableSum = grossTaxableSum.add(ctx.nz(ctx.line().grossTaxableIncome()));
            ssoSum = ssoSum.add(ctx.nz(ctx.line().socialSecurity()));
            whtSum = whtSum.add(ctx.nz(ctx.line().withholdingTax()));
            netPaySum = netPaySum.add(ctx.nz(ctx.line().netPay()));
        }
        Object[][] recon = {
            {"รวมรายได้ที่ต้องเสียภาษี", "'" + SUMMARY_SHEET + "'!R" + summaryTotalsRow, grossTaxableSum},
            {"รวมประกันสังคม", "'" + SUMMARY_SHEET + "'!S" + summaryTotalsRow, ssoSum},
            {"รวมภาษีหัก ณ ที่จ่าย", "'" + SUMMARY_SHEET + "'!T" + summaryTotalsRow, whtSum},
            {"รวมคงเหลือจ่ายสุทธิ", "'" + SUMMARY_SHEET + "'!Y" + summaryTotalsRow, netPaySum},
        };
        for (int i = 0; i < recon.length; i++) {
            int rowIdx = headerRow + 1 + i;
            Row row = sheet.createRow(rowIdx - 1);
            setText(row, styles, 0, (String) recon[i][0]);
            setFormula(row, styles, 1, (String) recon[i][1], M2);
            setBlue(row, styles, 2, (BigDecimal) recon[i][2], M2);
            setFormula(row, styles, 3, "B" + rowIdx + "-C" + rowIdx, M2);
        }

        int[] widths = {60, 22, 20, 14};
        for (int c = 0; c < widths.length; c++) {
            sheet.setColumnWidth(c, widths[c] * 256);
        }
        if (sheet.getRow(3 - 1) != null) {
            sheet.getRow(3 - 1).setHeightInPoints(30f);
        }
    }

    // =====================================================================================
    // Cell-writing helpers
    // =====================================================================================

    private void writeTitle(XSSFSheet sheet, Styles styles, String text) {
        Row row = sheet.createRow(0);
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        cell.setCellStyle(styles.title);
    }

    private void writeSubtitle(XSSFSheet sheet, Styles styles, String text) {
        Row row = sheet.createRow(1);
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        cell.setCellStyle(styles.subtitle);
    }

    private void writeHeaderCell(XSSFSheet sheet, int rowNum1Based, int col, String text, Styles styles) {
        Row row = sheet.getRow(rowNum1Based - 1);
        if (row == null) {
            row = sheet.createRow(rowNum1Based - 1);
        }
        Cell cell = row.createCell(col);
        cell.setCellValue(text);
        cell.setCellStyle(styles.header);
    }

    private void writeNoteBold(XSSFSheet sheet, Styles styles, int rowNum1Based, String text) {
        Row row = sheet.createRow(rowNum1Based - 1);
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        cell.setCellStyle(styles.noteBold);
    }

    private void writeNotePlain(XSSFSheet sheet, Styles styles, int rowNum1Based, String text) {
        Row row = sheet.createRow(rowNum1Based - 1);
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        cell.setCellStyle(styles.notePlain);
    }

    /** Attaches a cell comment (e.g. the สนั่น-style "เงินเดือนเต็ม X หัก ลาไม่รับค่าจ้าง/อื่นๆ Y" note). */
    private void addComment(XSSFSheet sheet, Drawing<?> drawing, int rowIdx0, int col, String text) {
        CreationHelper factory = sheet.getWorkbook().getCreationHelper();
        ClientAnchor anchor = factory.createClientAnchor();
        anchor.setCol1(col);
        anchor.setCol2(col + 3);
        anchor.setRow1(rowIdx0);
        anchor.setRow2(rowIdx0 + 3);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(factory.createRichTextString(text));
        comment.setAuthor("GL&R ERP");
        Row row = sheet.getRow(rowIdx0);
        Cell cell = row.getCell(col);
        cell.setCellComment(comment);
    }

    private void setCell(Row row, XSSFCellStyle style, int col, String text) {
        Cell cell = row.createCell(col);
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    private void setText(Row row, Styles styles, int col, String value) {
        Cell cell = row.createCell(col);
        if (value == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(value);
        }
        cell.setCellStyle(styles.dataText);
    }

    private void setBlue(Row row, Styles styles, int col, BigDecimal value, String numFmt) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(M0.equals(numFmt) ? styles.blueM0 : styles.blueM2);
    }

    private void setBlueRaw(Row row, Styles styles, int col, double value, String numFmt) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(styles.styleFor(styles.blueFont, numFmt, true, null));
    }

    private void setFormula(Row row, Styles styles, int col, String formula, String numFmt) {
        Cell cell = row.createCell(col);
        cell.setCellFormula(formula);
        cell.setCellStyle(M0.equals(numFmt) ? styles.blackM0 : (M2.equals(numFmt) ? styles.blackM2 : styles.percentStyleFor(numFmt)));
    }

    private void setGreenFormula(Row row, Styles styles, int col, String formula, String numFmt) {
        Cell cell = row.createCell(col);
        cell.setCellFormula(formula);
        cell.setCellStyle(M0.equals(numFmt) ? styles.greenM0 : styles.greenM2);
    }

    private static String colLetter(int zeroBasedIndex) {
        StringBuilder sb = new StringBuilder();
        int n = zeroBasedIndex;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    // =====================================================================================
    // Styles
    // =====================================================================================

    /** All cell styles used across the three sheets, built once per workbook. */
    private static final class Styles {
        private static final byte[] HEADER_FILL = {(byte) 0x1F, (byte) 0x4E, (byte) 0x78};
        private static final byte[] TOTALS_FILL = {(byte) 0xDD, (byte) 0xEB, (byte) 0xF7};
        private static final byte[] BORDER_COLOR = {(byte) 0xBF, (byte) 0xBF, (byte) 0xBF};
        private static final byte[] BLUE = {0x00, 0x00, (byte) 0xFF};
        private static final byte[] GREEN = {0x00, (byte) 0x80, 0x00};
        private static final byte[] GREY = {0x55, 0x55, 0x55};

        private final XSSFWorkbook workbook;
        private final XSSFFont plainFont;
        private final XSSFFont boldFont;
        private final XSSFFont whiteBoldFont;
        private final XSSFFont blueFont;
        private final XSSFFont greenFont;
        private final XSSFFont greyFont;
        private final XSSFFont titleFont;

        final XSSFCellStyle title;
        final XSSFCellStyle subtitle;
        final XSSFCellStyle header;
        final XSSFCellStyle dataText;
        final XSSFCellStyle blueM2;
        final XSSFCellStyle blueM0;
        final XSSFCellStyle blueTextRight;
        final XSSFCellStyle blackM2;
        final XSSFCellStyle blackM0;
        final XSSFCellStyle greenM2;
        final XSSFCellStyle greenM0;
        final XSSFCellStyle totalsLabel;
        final XSSFCellStyle totalsM2;
        final XSSFCellStyle totalsM0;
        final XSSFCellStyle noteBold;
        final XSSFCellStyle notePlain;
        private final XSSFCellStyle percent0;
        private final XSSFCellStyle percent1;

        Styles(XSSFWorkbook workbook) {
            this.workbook = workbook;
            plainFont = font("Tahoma", (short) 10, false, null);
            boldFont = font("Tahoma", (short) 10, true, null);
            whiteBoldFont = font("Tahoma", (short) 9, true, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
            blueFont = font("Tahoma", (short) 10, false, BLUE);
            greenFont = font("Tahoma", (short) 10, false, GREEN);
            greyFont = font("Tahoma", (short) 9, false, GREY);
            titleFont = font("Tahoma", (short) 12, true, null);

            title = plainStyle(titleFont, false);
            subtitle = plainStyle(greyFont, false);
            header = headerStyle();
            dataText = bordered(plainFont, null);
            blueM2 = bordered(blueFont, M2);
            blueM0 = bordered(blueFont, M0);
            blueTextRight = borderedRight(blueFont);
            blackM2 = bordered(plainFont, M2);
            blackM0 = bordered(plainFont, M0);
            greenM2 = bordered(greenFont, M2);
            greenM0 = bordered(greenFont, M0);
            totalsLabel = totalsStyle(null);
            totalsM2 = totalsStyle(M2);
            totalsM0 = totalsStyle(M0);
            noteBold = plainStyle(boldFont, false);
            notePlain = plainStyle(greyFont, false);
            percent0 = bordered(plainFont, "0%");
            percent1 = bordered(plainFont, "0.0%");
        }

        XSSFCellStyle percentStyleFor(String numFmt) {
            return "0.0%".equals(numFmt) ? percent1 : percent0;
        }

        XSSFCellStyle styleFor(XSSFFont f, String numFmt, boolean withBorder, HorizontalAlignment align) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFont(f);
            if (numFmt != null) {
                style.setDataFormat(workbook.createDataFormat().getFormat(numFmt));
            }
            if (withBorder) {
                applyBorder(style);
            }
            if (align != null) {
                style.setAlignment(align);
            }
            return style;
        }

        private XSSFFont font(String name, short size, boolean bold, byte[] rgb) {
            XSSFFont font = workbook.createFont();
            font.setFontName(name);
            font.setFontHeightInPoints(size);
            font.setBold(bold);
            if (rgb != null) {
                font.setColor(new XSSFColor(rgb, null));
            }
            return font;
        }

        private XSSFCellStyle plainStyle(XSSFFont font, boolean border) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFont(font);
            if (border) {
                applyBorder(style);
            }
            return style;
        }

        private XSSFCellStyle bordered(XSSFFont font, String numFmt) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFont(font);
            if (numFmt != null) {
                style.setDataFormat(workbook.createDataFormat().getFormat(numFmt));
            }
            applyBorder(style);
            return style;
        }

        private XSSFCellStyle borderedRight(XSSFFont font) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFont(font);
            style.setAlignment(HorizontalAlignment.RIGHT);
            applyBorder(style);
            return style;
        }

        private XSSFCellStyle headerStyle() {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFont(whiteBoldFont);
            style.setFillForegroundColor(new XSSFColor(HEADER_FILL, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setWrapText(true);
            applyBorder(style);
            return style;
        }

        private XSSFCellStyle totalsStyle(String numFmt) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFont(boldFont);
            style.setFillForegroundColor(new XSSFColor(TOTALS_FILL, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            if (numFmt != null) {
                style.setDataFormat(workbook.createDataFormat().getFormat(numFmt));
            }
            applyBorder(style);
            return style;
        }

        private void applyBorder(XSSFCellStyle style) {
            XSSFColor color = new XSSFColor(BORDER_COLOR, null);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setTopBorderColor(color);
            style.setBottomBorderColor(color);
            style.setLeftBorderColor(color);
            style.setRightBorderColor(color);
        }
    }
}
