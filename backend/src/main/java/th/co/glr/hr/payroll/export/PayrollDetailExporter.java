package th.co.glr.hr.payroll.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollSpecialPayDto;

/**
 * Builds the detailed monthly payroll workbook (xlsx) for HR: every employee's full breakdown for
 * a period, reproducing the layout of the accountant's original spreadsheet (with the SYSTEM's
 * พิเศษ numbering — see {@code PayrollService#specialPayDtos}'s javadoc for why that differs from
 * the accountant's Jan/Feb sheets) plus a handful of additions the workbook never had: employee
 * code (the workbook matched rows by first name only), the days-worked/daily-rate pair for
 * {@code pay_type='D'} employees (V103), and the มาตรา 42 per-diem exempt/taxable split.
 *
 * <p>Read-only report: every monetary cell is copied verbatim from an already-computed {@link
 * PayrollLineDto} (or, for the two identity/salary-history columns, {@link
 * PayrollDetailIdentityDto}) — no payroll/tax math is recomputed here. The two exceptions are the
 * "รวมรายหักที่ต้องคิดภาษี (B)"/"คงเหลือ (A-B)"/"รวมรายหักอื่นๆ (C)" subtotal columns, which are a
 * plain display sum of already-final leaf figures (not a re-derivation of any tax/SSO formula),
 * and the totals row, which sums each numeric column down the sheet in plain {@link BigDecimal}
 * arithmetic. "คงเหลือจ่ายจริง (A-B-C+D)" is NOT recomputed from A-B-C+D — it is {@link
 * PayrollLineDto#netPay()} verbatim, the one figure the payroll engine actually produced, so this
 * report can never silently disagree with the real net pay over some engine subtlety (garnishment
 * caps, leave refunds, the ป.96 3-limb split, a typed withholding override, ...) this class does
 * not otherwise reproduce.
 *
 * <p>Known, stated gap: the accountant's legacy "หัก 3 (ทำทรัพย์สินเสียหาย สูญหาย)" and "หัก 5
 * (หักค่าโทรศัพท์ส่วนที่โทรเกิน)" deduction categories have no dedicated {@code payroll_line}
 * column in this system (only {@code studentLoanDeduction}/{@code legalExecutionDeduction}/{@code
 * otherPostTaxDeductions} exist as "other deduction" slots) — both columns are still emitted, per
 * the requested layout, but are always blank/zero rather than fabricated. Any real damage/phone
 * deduction the system tracks today lands in {@code otherPostTaxDeductions} ("หัก 8 (อื่นๆ)")
 * undifferentiated, exactly as it already does everywhere else in this codebase.
 */
@Component
public class PayrollDetailExporter {
    private static final String SHEET_NAME = "รายละเอียดเงินเดือน";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** One column: a header label plus how to pull its cell value out of a row's context. */
    private record Column(String header, Function<Ctx, Object> extract) {}

    /** Per-row context handed to every column extractor. */
    private record Ctx(PayrollLineDto line, PayrollDetailIdentityDto identity, PayrollPeriodDto period) {
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
    }

    public byte[] export(List<PayrollLineDto> lines, Map<Long, PayrollDetailIdentityDto> identities,
                         PayrollPeriodDto period) {
        List<Column> columns = buildColumns();
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle moneyStyle = moneyStyle(workbook);
            CellStyle dateStyle = dateStyle(workbook);
            CellStyle totalsLabelStyle = totalsLabelStyle(workbook);
            CellStyle totalsMoneyStyle = totalsMoneyStyle(workbook);

            writeHeaderRow(sheet, columns, headerStyle);

            BigDecimal[] totals = new BigDecimal[columns.size()];
            int rowIdx = 1;
            for (PayrollLineDto line : lines) {
                PayrollDetailIdentityDto identity = identities.getOrDefault(line.employeeId(), PayrollDetailIdentityDto.EMPTY);
                Ctx ctx = new Ctx(line, identity, period);
                Row row = sheet.createRow(rowIdx++);
                for (int c = 0; c < columns.size(); c++) {
                    Object value = columns.get(c).extract().apply(ctx);
                    writeCell(row, c, value, moneyStyle, dateStyle);
                    if (value instanceof BigDecimal bd) {
                        totals[c] = (totals[c] == null ? ZERO : totals[c]).add(bd);
                    }
                }
            }

            Row totalsRow = sheet.createRow(rowIdx);
            Cell label = totalsRow.createCell(0);
            label.setCellValue("รวมทั้งหมด (" + lines.size() + " คน)");
            label.setCellStyle(totalsLabelStyle);
            for (int c = 1; c < columns.size(); c++) {
                if (totals[c] != null) {
                    Cell cell = totalsRow.createCell(c);
                    cell.setCellValue(totals[c].doubleValue());
                    cell.setCellStyle(totalsMoneyStyle);
                }
            }

            sheet.createFreezePane(1, 1);
            for (int c = 0; c < columns.size(); c++) {
                int width = Math.min(Math.max(columns.get(c).header().length() * 2, 10), 42) * 256;
                sheet.setColumnWidth(c, width);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeHeaderRow(Sheet sheet, List<Column> columns, CellStyle headerStyle) {
        Row header = sheet.createRow(0);
        for (int c = 0; c < columns.size(); c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(columns.get(c).header());
            cell.setCellStyle(headerStyle);
        }
    }

    private void writeCell(Row row, int col, Object value, CellStyle moneyStyle, CellStyle dateStyle) {
        Cell cell = row.createCell(col);
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof BigDecimal bd) {
            cell.setCellValue(bd.doubleValue());
            cell.setCellStyle(moneyStyle);
        } else if (value instanceof LocalDate date) {
            cell.setCellValue(date);
            cell.setCellStyle(dateStyle);
        } else if (value instanceof Boolean b) {
            cell.setCellValue(b ? "ใช่" : "ไม่ใช่");
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    // ---- styles ----

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setWrapText(true);
        return style;
    }

    private CellStyle moneyStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
        return style;
    }

    private CellStyle totalsMoneyStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle totalsLabelStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle dateStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
        return style;
    }

    // ---- column layout ----

    private List<Column> buildColumns() {
        List<Column> columns = new ArrayList<>();

        // Identity.
        columns.add(new Column("รหัสพนักงาน", ctx -> ctx.line().employeeCode()));
        columns.add(new Column("ชื่อ", ctx -> ctx.line().employeeName()));
        columns.add(new Column("แผนก", ctx -> ctx.line().departmentName()));
        columns.add(new Column("ประเภทพนักงาน", ctx -> payTypeLabel(ctx.line().payType())));
        columns.add(new Column("วันที่เริ่มงาน", ctx -> ctx.identity().hireDate()));
        columns.add(new Column("วันที่ปรับล่าสุด", ctx -> ctx.identity().lastSalaryAdjustedDate()));

        // Base.
        columns.add(new Column("เงินเดือน", ctx -> ctx.nz(ctx.line().baseSalary())));
        columns.add(new Column("เงินเดือนใหม่", ctx -> newSalaryThisMonth(ctx)));
        columns.add(new Column("ค่าตอบแทนกรรมการ", ctx -> ctx.nz(ctx.line().directorRemuneration())));

        // Daily-rate support (V103, addition -- absent from the accountant's original workbook).
        columns.add(new Column("อัตรารายวัน", ctx -> ctx.nz(ctx.line().dailyRate())));
        columns.add(new Column("จำนวนวันทำงาน", ctx -> ctx.nz(ctx.line().daysWorked())));
        columns.add(new Column("อัตรารายชั่วโมง", ctx -> ctx.nz(ctx.line().hourlyRate())));

        // Income -- each its own column, SYSTEM พิเศษ numbering (see class javadoc).
        columns.add(new Column("พิเศษ 1 (ค่าครองชีพ)", ctx -> ctx.special(0)));
        columns.add(new Column("พิเศษ 2 (ค่าเช่าบ้าน)", ctx -> ctx.special(1)));
        columns.add(new Column("ค่าอาหาร", ctx -> ctx.nz(ctx.line().mealAllowance())));
        columns.add(new Column("พิเศษ 3 (เบี้ยเลี้ยงประจำ)", ctx -> ctx.special(2)));
        columns.add(new Column("พิเศษ 4 (ค่าตำแหน่ง)", ctx -> ctx.special(3)));
        columns.add(new Column("พิเศษ 5 (เบี้ยขยันประจำ)", ctx -> ctx.special(4)));
        columns.add(new Column("พิเศษ 6 (ค่า GPRS)", ctx -> ctx.special(5)));
        // มาตรา 42 per-diem split (V97 addition) -- kept as three columns: the two halves plus a
        // combined figure for reconciliation against the old single เบี้ยเลี้ยง (ตจว/ตปท) column.
        columns.add(new Column("เบี้ยเลี้ยง (ตจว/ตปท) รวม",
            ctx -> ctx.nz(ctx.line().perDiemExempt()).add(ctx.nz(ctx.line().perDiemTaxable()))));
        columns.add(new Column("เบี้ยเลี้ยง - ส่วนยกเว้นภาษี (ม.42)", ctx -> ctx.nz(ctx.line().perDiemExempt())));
        columns.add(new Column("เบี้ยเลี้ยง - ส่วนต้องเสียภาษี", ctx -> ctx.nz(ctx.line().perDiemTaxable())));
        columns.add(new Column("ล่วงเวลา", ctx -> ctx.nz(ctx.line().overtimePay())));
        columns.add(new Column("พิเศษ 7 (คอมมิชชั่น)", ctx -> ctx.special(6)));
        // commissionPay has no natural home in the requested layout -- it is the AUTOMATED
        // commission fed by CommissionService, distinct from the HR-typed "พิเศษ 7" slot above.
        // Added as its own column per the "add a column rather than dropping it" instruction.
        columns.add(new Column("ค่าคอมมิชชั่น (ระบบขาย)", ctx -> ctx.nz(ctx.line().commissionPay())));
        columns.add(new Column("พิเศษ 8 (ทำได้ตาม KPI)", ctx -> ctx.special(7)));
        columns.add(new Column("พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)", ctx -> ctx.special(8)));
        columns.add(new Column("รวมพิเศษ 1-9", ctx -> ctx.nz(ctx.line().specialPayTotal())));
        columns.add(new Column("โบนัสสิ้นปี", ctx -> ctx.nz(ctx.line().bonusPay())));
        columns.add(new Column("อื่นๆ (รายได้)", ctx -> ctx.nz(ctx.line().otherOneOffPay())));
        columns.add(new Column("รวมรายได้ทั้งหมด", ctx -> ctx.nz(ctx.line().grossEarnings())));

        // Taxable total.
        columns.add(new Column("รวมรายได้ที่ต้องคิดภาษี (A)", ctx -> ctx.nz(ctx.line().grossTaxableIncome())));

        // Pre-tax deductions.
        columns.add(new Column("หักขาดงาน", ctx -> ctx.nz(ctx.line().unpaidLeaveDeduction())));
        columns.add(new Column("จำนวนวันขาดงาน", ctx -> ctx.nz(ctx.line().unpaidLeaveDays())));
        columns.add(new Column("หักตามใบเตือน", ctx -> ctx.nz(ctx.line().warningLetterDeduction())));
        columns.add(new Column("หัก 6 (ลูกค้าคืนสินค้า)", ctx -> ctx.nz(ctx.line().customerReturnDeduction())));
        columns.add(new Column("ลูกค้าคืนสินค้า - ยอดที่ขอหัก", ctx -> ctx.nz(ctx.line().customerReturnRequested())));
        columns.add(new Column("ลูกค้าคืนสินค้า - รับรู้รายได้แล้ว", ctx -> ctx.line().customerReturnAlreadyEarned()));
        columns.add(new Column("อื่นๆ (หักก่อนภาษี)", ctx -> ctx.nz(ctx.line().otherPretaxDeduction())));

        // Subtotals B / (A-B) -- plain display sums of already-final leaf figures, not recomputed
        // tax math.
        columns.add(new Column("รวมรายหักที่ต้องคิดภาษี (B)", this::preTaxDeductionSubtotal));
        columns.add(new Column("คงเหลือ (A-B)",
            ctx -> ctx.nz(ctx.line().grossTaxableIncome()).subtract(preTaxDeductionSubtotal(ctx))));

        // Statutory.
        columns.add(new Column("ภาษี", ctx -> ctx.nz(ctx.line().withholdingTax())));
        columns.add(new Column("ประกันสังคม", ctx -> ctx.nz(ctx.line().socialSecurity())));
        columns.add(new Column("ฐานเงินเดือนประกันสังคม", ctx -> ctx.nz(ctx.line().ssoWageBase())));

        // Other (post-tax) deductions. หัก 3 / หัก 5 have no dedicated payroll_line column in this
        // system -- always blank, per this class's javadoc "known, stated gap".
        columns.add(new Column("หัก 1 (คืนเงินกู้จากบริษัท)", ctx -> ctx.nz(ctx.line().studentLoanDeduction())));
        columns.add(new Column("หัก 3 (ทำทรัพย์สินเสียหาย สูญหาย)", ctx -> null));
        columns.add(new Column("หัก 5 (หักค่าโทรศัพท์ส่วนที่โทรเกิน)", ctx -> null));
        columns.add(new Column("หักนำส่งกรมบังคับคดี", ctx -> ctx.nz(ctx.line().legalExecutionDeduction())));
        columns.add(new Column("ประเภทการบังคับคดี", ctx -> ctx.line().garnishmentType()));
        columns.add(new Column("หัก 8 (อื่นๆ)", ctx -> ctx.nz(ctx.line().otherPostTaxDeductions())));
        columns.add(new Column("รวมรายหักอื่นๆ (C)", this::otherDeductionSubtotal));

        // Non-taxable / net.
        columns.add(new Column("รายได้อื่นๆที่ไม่คำนวนภาษี (D)", ctx -> ctx.nz(ctx.line().nonTaxableIncome())));
        // NOT recomputed from A-B-C+D -- see class javadoc. This is PayrollLineDto#netPay verbatim,
        // the engine's own figure.
        columns.add(new Column("คงเหลือจ่ายจริง (A-B-C+D)", ctx -> ctx.nz(ctx.line().netPay())));
        columns.add(new Column("รวมรายหักทั้งหมด", ctx -> ctx.nz(ctx.line().totalDeductions())));

        // ---- Extras: every remaining PayrollLineDto field with no natural home above. ----
        columns.add(new Column("เงินได้สุทธิรายปี (โครงการ)", ctx -> ctx.nz(ctx.line().projectedAnnualIncome())));
        columns.add(new Column("ค่าใช้จ่ายหักลดหย่อน", ctx -> ctx.nz(ctx.line().taxExpenseDeduction())));
        columns.add(new Column("ค่าลดหย่อนภาษีรวม", ctx -> ctx.nz(ctx.line().taxAllowanceTotal())));
        columns.add(new Column("เงินได้สุทธิที่ต้องเสียภาษีรายปี", ctx -> ctx.nz(ctx.line().taxableAnnualIncome())));
        columns.add(new Column("ภาษีรายปี (โครงการ)", ctx -> ctx.nz(ctx.line().annualTax())));
        columns.add(new Column("ภาษีที่ HR ระบุเพิ่มเติม", ctx -> ctx.line().withholdingTaxOverride()));
        columns.add(new Column("ภาษีหัก ณ ที่จ่ายเกิน สะสมยกมา", ctx -> ctx.nz(ctx.line().excessWithheldToDate())));
        columns.add(new Column("เงินได้ที่ต้องเสียภาษี - เงินได้ประจำ", ctx -> ctx.nz(ctx.line().taxableIncomeRegularLimb())));
        columns.add(new Column("เงินได้ที่ต้องเสียภาษี - เงินได้ที่ทราบล่วงหน้า", ctx -> ctx.nz(ctx.line().taxableIncomeKnownLimb())));
        columns.add(new Column("เงินได้ที่ต้องเสียภาษี - เงินได้สะสม", ctx -> ctx.nz(ctx.line().taxableIncomeCumulativeLimb())));
        columns.add(new Column("ภาษีหัก ณ ที่จ่าย - เงินได้ประจำ", ctx -> ctx.nz(ctx.line().withholdingTaxRegularLimb())));
        columns.add(new Column("ภาษีหัก ณ ที่จ่าย - เงินได้สะสม", ctx -> ctx.nz(ctx.line().withholdingTaxCumulativeLimb())));
        columns.add(new Column("วันลาที่ขอคืนเงิน", ctx -> ctx.nz(ctx.line().leaveRefundDays())));
        columns.add(new Column("เงินคืนจากการหักวันลา", ctx -> ctx.nz(ctx.line().leaveDeductionRefund())));
        columns.add(new Column("ธนาคาร", ctx -> ctx.line().bankName()));
        columns.add(new Column("เลขที่บัญชี", ctx -> ctx.line().bankAccount()));
        columns.add(new Column("หมายเหตุ", ctx -> ctx.line().calculationNote()));

        return columns;
    }

    private BigDecimal preTaxDeductionSubtotal(Ctx ctx) {
        return ctx.nz(ctx.line().unpaidLeaveDeduction())
            .add(ctx.nz(ctx.line().warningLetterDeduction()))
            .add(ctx.nz(ctx.line().customerReturnDeduction()))
            .add(ctx.nz(ctx.line().otherPretaxDeduction()));
    }

    private BigDecimal otherDeductionSubtotal(Ctx ctx) {
        return ctx.nz(ctx.line().studentLoanDeduction())
            .add(ctx.nz(ctx.line().legalExecutionDeduction()))
            .add(ctx.nz(ctx.line().otherPostTaxDeductions()));
    }

    /**
     * "เงินเดือนใหม่" -- only populated when the employee's most recent salary_history adjustment
     * took effect DURING this reported payroll month (periodStart..periodEnd inclusive); otherwise
     * blank, so a raise from a prior month does not read as "new" on every run after it.
     */
    private BigDecimal newSalaryThisMonth(Ctx ctx) {
        LocalDate adjustedDate = ctx.identity().lastSalaryAdjustedDate();
        BigDecimal newAmount = ctx.identity().lastSalaryAdjustedNewAmount();
        if (adjustedDate == null || newAmount == null) {
            return null;
        }
        LocalDate start = ctx.period().periodStart();
        LocalDate end = ctx.period().periodEnd();
        if (start != null && adjustedDate.isBefore(start)) {
            return null;
        }
        if (end != null && adjustedDate.isAfter(end)) {
            return null;
        }
        return newAmount;
    }

    private String payTypeLabel(String payType) {
        if ("D".equals(payType)) {
            return "รายวัน";
        }
        if ("M".equals(payType)) {
            return "รายเดือน";
        }
        return "-";
    }
}
