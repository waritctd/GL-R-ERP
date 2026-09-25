package th.co.glr.hr.payroll.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollSpecialPayDto;
import th.co.glr.hr.payroll.PayrollYearToDate;

/**
 * Unit test for {@link PayrollDetailExporter}'s 3-tab workbook (สรุปเงินเดือน / การคำนวณภาษี /
 * อัตราภาษีและหมายเหตุ), driven directly (no DB, no {@code PayrollService}) with three hand-built
 * {@link PayrollLineDto} fixtures -- an ordinary employee, a director (a non-null {@code
 * withholdingTaxOverride}), and a commission earner -- plus YTD carry-forward for the two
 * non-director lines.
 *
 * <p>The commission-earner fixture is the pin for BOTH correctness fixes (a payroll export must
 * always agree with what was actually withheld/paid):
 * <ul>
 *   <li><b>WHT fidelity</b>: its {@link PayrollLineDto#withholdingTax()} (฿3,200) is deliberately far
 *       from what the naive (annualTax − ytdWithheld) ÷ remainingPeriods estimate on this same data
 *       would produce (฿125 -- see the fixture's own comment for the arithmetic) -- exactly the
 *       shape a real commission spike produces under the actual regular/known/cumulative three-limb
 *       engine.</li>
 *   <li><b>Net-pay fidelity</b>: it ALSO carries a ฿245 unpaid-leave deduction this month (the real
 *       สนั่น เปรมานุพันธ์ case), a ฿500 garnishment, and a ฿150 warning-letter deduction, all at
 *       once alongside the commission -- so รวมรายได้ (R), ประกันสังคม (S), หักกรมบังคับคดี (V), and
 *       คงเหลือจ่ายสุทธิ (Y) are asserted directly against {@link PayrollLineDto#grossTaxableIncome()}
 *       / {@link PayrollLineDto#socialSecurity()} / {@link PayrollLineDto#legalExecutionDeduction()}
 *       / {@link PayrollLineDto#netPay()}, not hand-copied numbers, so the test would catch a
 *       regression in either the D-column unpaid-leave adjustment or the S/V/W literal wiring.</li>
 * </ul>
 *
 * <p>All three fixtures are additionally constructed so the ป.96 recon block on
 * {@code อัตราภาษีและหมายเหตุ} ties to zero on every row (see {@code
 * reconciliationShowsNoDifferenceOnAnyRow}) to demonstrate the mechanism holds exactly whenever no
 * untracked income category (bonus/other-one-off/welfare/per-diem-taxable/non-taxable) applies (see
 * the class javadoc's "residual gap" note).
 *
 * <p>Formula cells are read back via a {@link FormulaEvaluator} rather than {@code
 * getNumericCellValue()} (which throws on a formula cell) -- this is a real check of what Excel
 * would display, not just that a formula string was written.
 */
class PayrollDetailExporterTest {
    private final PayrollDetailExporter exporter = new PayrollDetailExporter();

    @Test
    void producesThreeSheetsWithHeaderRowsEmployeeRowsAndCorrectTotals() throws Exception {
        byte[] bytes = exportThreeFixtures();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            assertThat(workbook.getSheetName(0)).isEqualTo("สรุปเงินเดือน");
            assertThat(workbook.getSheetName(1)).isEqualTo("การคำนวณภาษี");
            assertThat(workbook.getSheetName(2)).isEqualTo("อัตราภาษีและหมายเหตุ");

            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            // ---- Sheet 1: สรุปเงินเดือน ----
            Sheet summary = workbook.getSheetAt(0);
            Row summaryHeader = summary.getRow(3); // row 4 (0-indexed 3)
            Map<String, Integer> summaryCols = headerIndex(summaryHeader);
            assertThat(summaryCols).containsKeys(
                "รหัส", "ชื่อ - สกุล", "ฝ่าย", "เงินเดือน", "ค่าตอบแทน\nกรรมการ",
                "พิเศษ 1\nค่าครองชีพ", "พิเศษ 2\nค่าเช่าบ้าน", "พิเศษ 3\nเบี้ยเลี้ยงประจำ",
                "พิเศษ 4\nค่าตำแหน่ง", "พิเศษ 5\nเบี้ยขยัน", "พิเศษ 6\nค่า GPRS",
                "พิเศษ 7\nคอมมิชชั่นพิเศษ", "พิเศษ 8\nKPI", "พิเศษ 9\nเงินรางวัล",
                "ค่าอาหาร", "ล่วงเวลา", "คอมมิชชั่น",
                "รวมรายได้\nที่ต้องเสียภาษี", "ประกันสังคม", "ภาษีหัก ณ\nที่จ่าย", "หัก กยศ.",
                "หักกรม\nบังคับคดี", "หักอื่น ๆ", "รวมรายการหัก", "คงเหลือ\nจ่ายสุทธิ");

            Row employeeRow = summary.getRow(4); // row 5, first employee
            assertThat(employeeRow.getCell(summaryCols.get("รหัส")).getStringCellValue()).isEqualTo("10012");
            assertThat(employeeRow.getCell(summaryCols.get("ฝ่าย")).getStringCellValue()).isEqualTo("ฝ่ายขาย");
            assertThat(employeeRow.getCell(summaryCols.get("เงินเดือน")).getNumericCellValue()).isEqualTo(15000.00);

            Cell grossCell = employeeRow.getCell(summaryCols.get("รวมรายได้\nที่ต้องเสียภาษี"));
            assertThat(grossCell.getCellType()).isEqualTo(CellType.FORMULA);
            double gross = evaluator.evaluate(grossCell).getNumberValue();
            // เงินเดือน 15000 + พิเศษ2 500 + ค่าอาหาร 1000 + ล่วงเวลา 300 = 16800.
            assertThat(gross).isEqualTo(16800.00);

            Cell whtCell = employeeRow.getCell(summaryCols.get("ภาษีหัก ณ\nที่จ่าย"));
            assertThat(whtCell.getCellType()).isEqualTo(CellType.FORMULA);
            // Cross-referenced from การคำนวณภาษี -- just confirm it evaluates without error and is >= 0.
            assertThat(evaluator.evaluate(whtCell).getNumberValue()).isGreaterThanOrEqualTo(0.0);

            Row directorRow = summary.getRow(5); // row 6, second employee (director)
            assertThat(directorRow.getCell(summaryCols.get("รหัส")).getStringCellValue()).isEqualTo("10001");
            assertThat(directorRow.getCell(summaryCols.get("ค่าตอบแทน\nกรรมการ")).getNumericCellValue())
                .isEqualTo(150000.00);

            // Commission earner: THE pin for BOTH correctness fixes. WHT/net must track the DTO
            // exactly, not a recompute -- see commissionEarnerLine()'s own comment for the arithmetic.
            PayrollLineDto commission = commissionEarnerLine();
            Row commissionRow = summary.getRow(6); // row 7, third employee
            assertThat(commissionRow.getCell(summaryCols.get("รหัส")).getStringCellValue()).isEqualTo("10099");

            // D (เงินเดือน): baseSalary NET of this month's unpaid-leave deduction (สนั่น-style), with
            // a cell comment explaining the adjustment.
            Cell commissionSalaryCell = commissionRow.getCell(summaryCols.get("เงินเดือน"));
            assertThat(commissionSalaryCell.getNumericCellValue())
                .as("baseSalary(20000) - unpaidLeaveDeduction(245)")
                .isEqualTo(19755.00);
            assertThat(commissionSalaryCell.getCellComment()).as("adjustment must be explained").isNotNull();
            assertThat(commissionSalaryCell.getCellComment().getString().getString())
                .contains("20000").contains("245");

            // R (รวมรายได้ที่ต้องเสียภาษี) must equal grossTaxableIncome() -- the D-column adjustment
            // above is what makes SUM(D:Q) foot to it despite the unpaid-leave deduction.
            Cell commissionGrossCell = commissionRow.getCell(summaryCols.get("รวมรายได้\nที่ต้องเสียภาษี"));
            assertThat(evaluator.evaluate(commissionGrossCell).getNumberValue())
                .isEqualTo(commission.grossTaxableIncome().doubleValue());

            // S (ประกันสังคม) must be the ACTUAL value, not a recomputed formula.
            Cell commissionSsoCell = commissionRow.getCell(summaryCols.get("ประกันสังคม"));
            assertThat(commissionSsoCell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(commissionSsoCell.getNumericCellValue()).isEqualTo(commission.socialSecurity().doubleValue());

            // V (หักกรมบังคับคดี) must be the ACTUAL applied/capped garnishment.
            Cell commissionLegalCell = commissionRow.getCell(summaryCols.get("หักกรม\nบังคับคดี"));
            assertThat(commissionLegalCell.getNumericCellValue())
                .isEqualTo(commission.legalExecutionDeduction().doubleValue());

            Cell commissionWhtCell = commissionRow.getCell(summaryCols.get("ภาษีหัก ณ\nที่จ่าย"));
            double commissionWht = evaluator.evaluate(commissionWhtCell).getNumberValue();
            assertThat(commissionWht)
                .as("must equal PayrollLineDto#withholdingTax() verbatim, the actual engine figure")
                .isEqualTo(3200.00);
            assertThat(commissionWht)
                .as("must NOT equal the naive (annualTax-ytdWithheld)/remainingPeriods estimate this "
                    + "same data would produce (K=MAX(0,4500-4000)=500, remainingPeriods=4 in "
                    + "September -> ROUND(500/4,0)=125) -- that estimate is the bug this fixture pins")
                .isNotEqualTo(125.00);

            // Y (คงเหลือจ่ายสุทธิ) must foot to netPay() now that D/S/V/W are all wired to the actual
            // figures -- this line carries an unpaid-leave deduction, a garnishment, AND a
            // warning-letter deduction on top of the commission spike, so this is not a coincidence.
            Cell commissionNetCell = commissionRow.getCell(summaryCols.get("คงเหลือ\nจ่ายสุทธิ"));
            assertThat(evaluator.evaluate(commissionNetCell).getNumberValue())
                .isEqualTo(commission.netPay().doubleValue());

            int summaryTotalsRowIdx = 7; // row 8 (0-indexed 7): header(4) + 3 employees -> totals on row 8
            Row summaryTotals = summary.getRow(summaryTotalsRowIdx);
            assertThat(summaryTotals.getCell(0).getStringCellValue()).contains("รวม");
            assertThat(summaryTotals.getCell(1).getStringCellValue()).contains("3");
            Cell totalSalaryCell = summaryTotals.getCell(summaryCols.get("เงินเดือน"));
            assertThat(evaluator.evaluate(totalSalaryCell).getNumberValue()).isEqualTo(15000.00 + 0.00 + 19755.00);

            // ---- Sheet 2: การคำนวณภาษี ----
            Sheet tax = workbook.getSheetAt(1);
            Row taxHeader = tax.getRow(2); // row 3
            Map<String, Integer> taxCols = headerIndex(taxHeader);
            assertThat(taxCols).containsKeys(
                "รหัส", "ชื่อ - สกุล", "เงินได้ทั้งปี\n(ประมาณการ)", "หักค่าใช้จ่าย\n50%≤100,000",
                "ลดหย่อนรวม", "เงินได้สุทธิ\nทั้งปี", "ภาษีทั้งปี\n(ขั้นบันได)", "ภาษีคงเหลือ\nทั้งปี",
                "ภาษีหัก ณ ที่จ่าย\nเดือนนี้ (ระบบ)", "ประเภท");
            int taxWhtCol = taxCols.get("ภาษีหัก ณ ที่จ่าย\nเดือนนี้ (ระบบ)");

            Row taxEmployeeRow = tax.getRow(3); // row 4
            assertThat(taxEmployeeRow.getCell(taxCols.get("รหัส")).getStringCellValue()).isEqualTo("10012");
            assertThat(taxEmployeeRow.getCell(taxCols.get("ประเภท")).getStringCellValue()).isEqualTo("คำนวณ");
            Cell empWhtCell = taxEmployeeRow.getCell(taxWhtCol);
            // Non-director: still a LITERAL value now (the fix), never a projection formula.
            assertThat(empWhtCell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(empWhtCell.getNumericCellValue()).isEqualTo(0.00);

            Row taxDirectorRow = tax.getRow(4); // row 5
            assertThat(taxDirectorRow.getCell(taxCols.get("รหัส")).getStringCellValue()).isEqualTo("10001");
            assertThat(taxDirectorRow.getCell(taxCols.get("ประเภท")).getStringCellValue())
                .isEqualTo("กรรมการ (คงที่)");
            Cell dirWhtCell = taxDirectorRow.getCell(taxWhtCol);
            // Director: literal value (the DTO's own withholdingTax).
            assertThat(dirWhtCell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(dirWhtCell.getNumericCellValue()).isEqualTo(5000.00);

            Row taxCommissionRow = tax.getRow(5); // row 6, third employee
            assertThat(taxCommissionRow.getCell(taxCols.get("รหัส")).getStringCellValue()).isEqualTo("10099");
            assertThat(taxCommissionRow.getCell(taxCols.get("ประเภท")).getStringCellValue()).isEqualTo("คำนวณ");
            Cell commissionTaxWhtCell = taxCommissionRow.getCell(taxWhtCol);
            assertThat(commissionTaxWhtCell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(commissionTaxWhtCell.getNumericCellValue())
                .as("literal DTO value, not MAX(0,ROUND(K/remainingPeriods,0))")
                .isEqualTo(3200.00);
            // The projection columns stay live formulas (context only) -- confirm K (ภาษีคงเหลือทั้งปี)
            // is still a formula cell over the annual-projection chain, to prove it was deliberately
            // decoupled from the money cell above rather than accidentally removed. (Its EVALUATED
            // value is not asserted here: this SUMPRODUCT-based progressive-tax formula, taken
            // verbatim from the accountant's reference workbook, is valid Excel and computes correctly
            // there, but POI's own formula evaluator does not correctly evaluate this particular
            // SUMPRODUCT/array-comparison shape (returns 0) -- a POI limitation unrelated to this
            // fix, and irrelevant to it: the money cells asserted below never read J/K back.)
            Cell remainingTaxCell = taxCommissionRow.getCell(taxCols.get("ภาษีคงเหลือ\nทั้งปี"));
            assertThat(remainingTaxCell.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(remainingTaxCell.getCellFormula()).contains("MAX(0,J", "-E");

            int taxTotalsRowIdx = 6; // row 7 (0-indexed 6): header(3) + 3 employees -> totals row 7
            Row taxTotals = tax.getRow(taxTotalsRowIdx);
            assertThat(taxTotals.getCell(0).getStringCellValue()).contains("รวม");

            // ---- Sheet 3: อัตราภาษีและหมายเหตุ ----
            Sheet rate = workbook.getSheetAt(2);
            Row bracketHeader = rate.getRow(2); // row 3
            assertThat(bracketHeader.getCell(0).getStringCellValue()).isEqualTo("เงินได้สุทธิตั้งแต่");
            Row firstBracket = rate.getRow(3); // row 4
            assertThat(firstBracket.getCell(0).getNumericCellValue()).isEqualTo(0.0);
            assertThat(firstBracket.getCell(1).getNumericCellValue()).isEqualTo(150000.0);
            assertThat(firstBracket.getCell(2).getNumericCellValue()).isEqualTo(0.0);
            Row lastBracket = rate.getRow(10); // row 11
            assertThat(lastBracket.getCell(1).getStringCellValue()).isEqualTo("ขึ้นไป");
            assertThat(lastBracket.getCell(2).getNumericCellValue()).isEqualTo(0.35);
        }
    }

    @Test
    void emptyLineListProducesThreeSheetsWithNoDataRows() throws Exception {
        PayrollPeriodDto period = new PayrollPeriodDto(
            9L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 3, 31), "VOID", OffsetDateTime.now(), 7L, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        byte[] bytes = exporter.export(List.of(), Map.of(), period, Map.of());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            Sheet summary = workbook.getSheetAt(0);
            Row totals = summary.getRow(4); // header(4) + 0 employees -> totals on row 5 (0-indexed 4)
            assertThat(totals.getCell(1).getStringCellValue()).contains("0");
        }
    }

    /**
     * The recon block on อัตราภาษีและหมายเหตุ must show ZERO difference on every row now that
     * เงินเดือน/ประกันสังคม/หักกรมบังคับคดี/หักอื่นๆ/ภาษีหัก ณ ที่จ่าย on สรุปเงินเดือน are all wired
     * to the actual {@link PayrollLineDto} figures (WHT and SSO reconcile to zero STRUCTURALLY now,
     * since both are literal copies of the DTO on both sides of the recon; รวมรายได้/คงเหลือ
     * reconcile to zero for this fixture set because none of the three lines carries an untracked
     * income category -- see the class javadoc's "residual gap" note for when they would not).
     */
    @Test
    void reconciliationShowsNoDifferenceOnAnyRow() throws Exception {
        byte[] bytes = exportThreeFixtures();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet rate = workbook.getSheetAt(2);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            int headerRowIdx = -1;
            for (Row row : rate) {
                Cell first = row.getCell(0);
                if (first != null && first.getCellType() == CellType.STRING
                        && "รายการ".equals(first.getStringCellValue())) {
                    headerRowIdx = row.getRowNum();
                    break;
                }
            }
            assertThat(headerRowIdx).as("reconciliation header row must exist").isGreaterThan(0);

            Map<String, Row> reconByLabel = new HashMap<>();
            for (int r = headerRowIdx + 1; r <= rate.getLastRowNum(); r++) {
                Row row = rate.getRow(r);
                if (row == null || row.getCell(0) == null) {
                    continue;
                }
                Cell label = row.getCell(0);
                if (label.getCellType() == CellType.STRING) {
                    reconByLabel.put(label.getStringCellValue(), row);
                }
            }
            assertThat(reconByLabel).containsKeys(
                "รวมรายได้ที่ต้องเสียภาษี", "รวมประกันสังคม", "รวมภาษีหัก ณ ที่จ่าย", "รวมคงเหลือจ่ายสุทธิ");

            for (String label : List.of(
                    "รวมรายได้ที่ต้องเสียภาษี", "รวมประกันสังคม", "รวมภาษีหัก ณ ที่จ่าย", "รวมคงเหลือจ่ายสุทธิ")) {
                Row row = reconByLabel.get(label);
                Cell diffCell = row.getCell(3); // ผลต่าง column
                assertThat(evaluator.evaluate(diffCell).getNumberValue())
                    .as(label + " must reconcile to zero")
                    .isEqualTo(0.00);
            }
        }
    }

    private byte[] exportThreeFixtures() {
        PayrollPeriodDto period = new PayrollPeriodDto(
            8L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
            LocalDate.of(2026, 9, 30), "PROCESSED", OffsetDateTime.now(), 7L, 3,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        PayrollLineDto employee = employeeLine();
        PayrollLineDto director = directorLine();
        PayrollLineDto commissionEarner = commissionEarnerLine();

        Map<Long, PayrollDetailIdentityDto> identities = new HashMap<>();
        Map<Long, PayrollYearToDate> yearToDate = new HashMap<>();
        // Employee 1: some YTD income/withholding already accumulated Jan-Aug.
        yearToDate.put(1L, new PayrollYearToDate(
            money("1000000.00"), BigDecimal.ZERO, money("6125.00"),
            money("40000.00"), BigDecimal.ZERO,
            money("40000.00"),
            money("1000000.00"), money("40000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO));
        // Employee 2 (director): no YTD stub -> must default to PayrollYearToDate.empty(), not NPE.
        // Employee 3 (commission earner): ฿4,000 already withheld Jan-Aug -- see
        // commissionEarnerLine()'s own comment for how this feeds the naive-vs-actual divergence.
        yearToDate.put(3L, new PayrollYearToDate(
            money("300000.00"), BigDecimal.ZERO, money("4375.00"),
            money("4000.00"), BigDecimal.ZERO,
            money("4000.00"),
            money("300000.00"), money("4000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO));

        return exporter.export(List.of(employee, director, commissionEarner), identities, period, yearToDate);
    }

    private Map<String, Integer> headerIndex(Row headerRow) {
        Map<String, Integer> col = new HashMap<>();
        for (Cell cell : headerRow) {
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            col.put(cell.getStringCellValue(), cell.getColumnIndex());
        }
        return col;
    }

    /**
     * Built via the 58-arg legacy {@link PayrollLineDto} constructor (fields id..perDiemBasis) --
     * this test needs {@code withholdingTaxOverride} (field 40, the director marker) and {@code
     * mealAllowance} (field 55, so it feeds the sheet1 รวมรายได้ formula) explicit, which no shorter
     * legacy arity exposes together.
     */
    private PayrollLineDto employeeLine() {
        List<PayrollSpecialPayDto> specialPays = List.of(
            special("specialPay1", BigDecimal.ZERO),
            special("specialPay2", money("500.00")),
            special("specialPay3", BigDecimal.ZERO),
            special("specialPay4", BigDecimal.ZERO),
            special("specialPay5", BigDecimal.ZERO),
            special("specialPay6", BigDecimal.ZERO),
            special("specialPay7", BigDecimal.ZERO),
            special("specialPay8", BigDecimal.ZERO),
            special("specialPay9", BigDecimal.ZERO));
        return new PayrollLineDto(
            1L, 1L, "10012", "มณฑ์ชญา ศรีสุมล", "ฝ่ายขาย", "กสิกรไทย", "1112223334",     // id..bankAccount
            money("15000.00"), BigDecimal.ZERO, BigDecimal.ZERO,                          // baseSalary/daily/hourly
            specialPays, money("500.00"), money("300.00"), BigDecimal.ZERO,               // specialPays.. commissionPay
            money("16800.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,         // grossEarnings..unpaidLeaveDeduction
            money("16800.00"), money("16800.00"), money("825.00"),                        // grossTaxableIncome/ssoWageBase/socialSecurity (=formula: (16800-0-300)*5%=825)
            money("265067.00"), money("100000.00"), money("70500.00"), money("94567.00"), // proj/expense/allowance/taxableAnnual
            money("0.00"), money("0.00"),                                                 // annualTax/withholdingTax (0: not a director, and this fixture's bracket lands at 0%)
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,                             // studentLoan/legal/otherPostTax
            money("825.00"), money("15975.00"), null,                                     // totalDeductions/netPay (=R-SSO-WHT=16800-825-0)/note
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,            // directorRemun/warning/customerReturn/otherPretax
            BigDecimal.ZERO, BigDecimal.ZERO, null,                                       // leaveRefundDays/leaveDeductionRefund/withholdingTaxOverride (null = not director)
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,            // regular/variable taxable+withholding
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,                            // bonusPay/otherOneOffPay/excessWithheld
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,                            // taxableIncome regular/known/cumulative limb
            BigDecimal.ZERO, BigDecimal.ZERO,                                             // withholdingTax regular/cumulative limb
            false, "SALARY",                                                              // customerReturnAlreadyEarned/garnishmentType
            money("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, null);                    // mealAllowance/perDiemExempt/perDiemTaxable/perDiemBasis
    }

    /** Director: a non-null {@code withholdingTaxOverride} (field 40) is what makes this a director. */
    private PayrollLineDto directorLine() {
        return new PayrollLineDto(
            2L, 2L, "10001", "กัลยาณี อิฐรัตน์", "ผู้บริหารระดับสูง", "กรุงเทพ", "9998887776",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            money("150000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            money("150000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            money("1800000.00"), money("100000.00"), money("220000.00"), money("1480000.00"),
            money("235000.00"), money("5000.00"),                                         // annualTax/withholdingTax (used verbatim)
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            money("5000.00"), money("145000.00"), null,
            money("150000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, money("5000.00"),                            // withholdingTaxOverride (director marker)
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            false, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
    }

    /**
     * Commission earner: the pin for BOTH correctness fixes. A big one-off commission this period
     * (฿50,000, {@link PayrollLineDto#commissionPay()}) makes the ACTUAL withholding this month
     * (฿3,200, produced by the real regular/known/cumulative three-limb engine reacting to the
     * spike) very different from what the naive (annualTax-ytdWithheld)/remainingPeriods estimate on
     * this same annual-projection data would produce:
     * <pre>
     *   I (เงินได้สุทธิทั้งปี)  = MAX(0, 400000 - MIN(400000*0.5,100000) - 60000) = 240000
     *   J (ภาษีทั้งปี ขั้นบันได) = 0% x 150000 + 5% x (240000-150000) = 4500
     *   K (ภาษีคงเหลือทั้งปี)   = MAX(0, J - ytdWithheld(4000)) = 500
     *   naive monthly (dropped) = MAX(0, ROUND(K / remainingPeriods(4 in Sept), 0)) = 125
     * </pre>
     * The exported ภาษีหัก ณ ที่จ่าย must be ฿3,200 (the DTO value), not ฿125.
     *
     * <p>This fixture ALSO carries a ฿245 unpaid-leave deduction this month (the same figure as the
     * real สนั่น เปรมานุพันธ์ case the reference workbook documents), a ฿500 garnishment
     * (legalExecutionDeduction), and a ฿150 warning-letter deduction (one of the "other post-tax"
     * categories folded into หักอื่น ๆ) -- together with the commission spike, this exercises every
     * money column the net-pay-fidelity fix touches at once:
     * <pre>
     *   D (เงินเดือน, adjusted)  = 20000 - 245 (unpaidLeaveDeduction) - 0 + 0 = 19755
     *   R (รวมรายได้)            = D(19755) + Q(commissionPay 50000) = 69755 = grossTaxableIncome()
     *   S (ประกันสังคม)          = socialSecurity() verbatim = 875
     *   V (หักกรมบังคับคดี)      = legalExecutionDeduction() verbatim = 500
     *   W (หักอื่น ๆ)            = otherPostTaxDeductions(0) + warningLetterDeduction(150)
     *                              + customerReturnDeduction(0) = 150
     *   X (รวมรายการหัก)         = S(875) + T(WHT 3200) + U(0) + V(500) + W(150) = 4725
     *   Y (คงเหลือจ่ายสุทธิ)     = R(69755) - X(4725) = 65030 = netPay()
     * </pre>
     */
    private PayrollLineDto commissionEarnerLine() {
        List<PayrollSpecialPayDto> zeros = List.of(
            special("specialPay1", BigDecimal.ZERO), special("specialPay2", BigDecimal.ZERO),
            special("specialPay3", BigDecimal.ZERO), special("specialPay4", BigDecimal.ZERO),
            special("specialPay5", BigDecimal.ZERO), special("specialPay6", BigDecimal.ZERO),
            special("specialPay7", BigDecimal.ZERO), special("specialPay8", BigDecimal.ZERO),
            special("specialPay9", BigDecimal.ZERO));
        return new PayrollLineDto(
            3L, 3L, "10099", "ประภัสสร คำเกิด", "ฝ่ายขาย", "ไทยพาณิชย์", "2223334445",       // id..bankAccount
            money("20000.00"), BigDecimal.ZERO, BigDecimal.ZERO,                            // baseSalary/daily/hourly
            zeros, BigDecimal.ZERO, BigDecimal.ZERO, money("50000.00"),                     // specialPays..commissionPay (the spike)
            money("69755.00"), BigDecimal.ZERO, BigDecimal.ZERO, money("245.00"),           // grossEarnings/nonTaxable/unpaidLeaveDays/unpaidLeaveDeduction (สนั่น-style)
            money("69755.00"), money("17500.00"), money("875.00"),                          // grossTaxableIncome (ACTUAL, asserted)/ssoWageBase/socialSecurity (ACTUAL, asserted)
            money("400000.00"), money("100000.00"), money("60000.00"), money("240000.00"),  // proj/expense/allowance/taxableAnnual
            money("4500.00"), money("3200.00"),                                             // annualTax/withholdingTax (ACTUAL, asserted)
            BigDecimal.ZERO, money("500.00"), BigDecimal.ZERO,                              // studentLoan/legalExecution (ACTUAL, asserted)/otherPostTax
            money("4970.00"), money("65030.00"), null,                                      // totalDeductions/netPay (ACTUAL, asserted)/note
            BigDecimal.ZERO, money("150.00"), BigDecimal.ZERO, BigDecimal.ZERO,             // directorRemun/warningLetter(the other-post-tax case)/customerReturn/otherPretax
            BigDecimal.ZERO, BigDecimal.ZERO, null,                                         // leaveRefundDays/leaveDeductionRefund/withholdingTaxOverride (null = not director)
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            false, "SALARY",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
    }

    private PayrollSpecialPayDto special(String key, BigDecimal amount) {
        return new PayrollSpecialPayDto(key, key, amount);
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
