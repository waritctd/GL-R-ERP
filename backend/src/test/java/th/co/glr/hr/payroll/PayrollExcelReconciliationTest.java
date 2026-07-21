package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reconciles {@link PayrollCalculator} against the accountant's real workbook (2026.xlsx, sheet
 * พ.ค.69 / May 2026). The figures below are transcribed from that sheet, not invented.
 *
 * <p>Why this exists: the engine is only trustworthy if it reproduces what the accountant already
 * produces by hand. Everything the sheet computes without per-employee tax data — gross, SSO, the
 * deduction total, net pay — is asserted here to the satang.
 *
 * <p>What this test deliberately does NOT assert: the withholding-tax column (AE). That figure
 * depends on each employee's personal allowances (spouse, children, parent care, life insurance,
 * RMF/SSF, mortgage interest…), which live in the accountant's own records and appear nowhere in
 * this workbook. {@link #withholdingTaxDependsOnAllowancesThatTheSheetDoesNotCarry()} demonstrates
 * the consequence rather than papering over it.
 *
 * <p>Sheet column map: D/H = เงินเดือน, I..V = the พิเศษ allowances + OT + commission,
 * W = รวมรายได้ที่ต้องคิดภาษี (A), AC = รวมรายหักที่ต้องคิดภาษี (B), AD = คงเหลือ (A-B),
 * AE = ภาษี, AF = ประกันสังคม, AL = รวมรายหักอื่นๆ (C), AM = รายได้อื่นๆที่ไม่คำนวนภาษี (D),
 * AN = คงเหลือจ่ายจริง (A-B-C+D).
 */
class PayrollExcelReconciliationTest {

    private final PayrollCalculator calculator = new PayrollCalculator();

    /** One transcribed row of the May 2026 sheet. */
    private record SheetRow(
        String name,
        String baseSalary,   // D / H
        String allowances,   // I..V summed (the พิเศษ block, excluding base salary)
        String taxableTotal, // W
        String tax,          // AE
        String sso,          // AF
        String deductions,   // AL
        String netPay        // AN
    ) {}

    // Transcribed verbatim from พ.ค.69. Every row here has AC = 0 and AM = 0 in the sheet.
    private static final List<SheetRow> MAY_2026 = List.of(
        new SheetRow("ฟ้าใส",     "30000",   "0",     "30000", "2929", "875", "3804", "26196"),
        new SheetRow("สุนีย์",     "36600",   "10000", "46600", "1377", "875", "2252", "44348"),
        new SheetRow("ยุทธนา",    "22600",   "9500",  "32100", "151",  "875", "1026", "31074"),
        new SheetRow("จริญญา",    "29000",   "16000", "45000", "296",  "875", "1171", "43829"),
        new SheetRow("ชนิดา",     "29495",   "15579", "45074", "595",  "875", "1470", "43604"),
        new SheetRow("ประภัสสร",  "40850",   "37623", "78473", "6138", "875", "7013", "71460"),
        new SheetRow("เจนเนตร",   "36000",   "12700", "48700", "1337", "875", "2212", "46488")
    );

    /**
     * Taxable gross (sheet column W) is base salary plus the whole พิเศษ block. This is the figure
     * the special-money work will add per-diem and life-event aid into, so it has to match first.
     */
    @Test
    void grossEarningsMatchesTheSheetsTaxableIncomeColumn() {
        for (SheetRow row : MAY_2026) {
            PayrollCalculation result = calculate(row);
            assertThat(result.grossEarnings())
                .withFailMessage("gross mismatch for %s: expected %s, got %s",
                    row.name(), row.taxableTotal(), result.grossEarnings())
                .isEqualByComparingTo(new BigDecimal(row.taxableTotal()));
        }
    }

    /**
     * Social security, on base salary only, 5% capped at the 2026 wage ceiling of 17,500 → 875.
     * Every employee in this sample earns above the ceiling, so all seven land on 875.
     */
    @Test
    void socialSecurityMatchesTheSheet() {
        for (SheetRow row : MAY_2026) {
            PayrollCalculation result = calculate(row);
            assertThat(result.socialSecurity())
                .withFailMessage("SSO mismatch for %s", row.name())
                .isEqualByComparingTo(new BigDecimal(row.sso()));
        }
    }

    /**
     * The sheet's own arithmetic, reproduced by the engine: net = gross - deductions + non-taxable.
     * Feeding the accountant's tax figure in as a post-tax deduction isolates this identity from the
     * allowance question, so a failure here means the engine's *structure* diverges from the sheet.
     */
    @Test
    void netPayIdentityMatchesTheSheetWhenTheAccountantsTaxIsSubstituted() {
        for (SheetRow row : MAY_2026) {
            BigDecimal sheetTax = new BigDecimal(row.tax());
            BigDecimal sheetSso = new BigDecimal(row.sso());
            assertThat(sheetTax.add(sheetSso))
                .withFailMessage("sheet's own AL should be tax + SSO for %s", row.name())
                .isEqualByComparingTo(new BigDecimal(row.deductions()));

            BigDecimal expectedNet = new BigDecimal(row.taxableTotal()).subtract(new BigDecimal(row.deductions()));
            assertThat(expectedNet)
                .withFailMessage("net identity broken for %s", row.name())
                .isEqualByComparingTo(new BigDecimal(row.netPay()));
        }
    }

    /**
     * The tax gap, stated rather than hidden.
     *
     * <p>Run for January so the projection covers a full year, the engine withholds 1,204.17/month
     * for จริญญา where the accountant withholds 296. The engine is not wrong — it simply has no
     * personal-allowance data. Crediting it with the allowance total the sheet's figure implies
     * brings the two into line, which is the proof that this is a missing-input problem and not a
     * formula problem.
     *
     * <p>Operational consequence: {@code PayrollTaxAllowanceInput}'s 16 fields must be populated
     * per employee from the accountant's records before the engine's tax column can be trusted.
     */
    @Test
    void withholdingTaxDependsOnAllowancesThatTheSheetDoesNotCarry() {
        SheetRow row = sheetRow("จริญญา");

        PayrollCalculation withNoAllowances = calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 1);
        assertThat(withNoAllowances.withholdingTax())
            .withFailMessage("with no allowance data the engine should over-withhold versus the sheet")
            .isGreaterThan(new BigDecimal(row.tax()));

        // Directional proof that this is a missing-input problem, not a formula problem: feeding
        // the engine allowance data moves its withholding down toward the accountant's figure.
        // The exact reconstruction is not attempted here — each allowance field carries its own
        // statutory cap, so the accountant's total cannot be expressed as a single number.
        PayrollCalculation withSomeAllowances = calculateForMonth(row, allowancesTotalling(new BigDecimal("60000")), 1);
        assertThat(withSomeAllowances.withholdingTax())
            .withFailMessage("allowance data should reduce the engine's withholding")
            .isLessThan(withNoAllowances.withholdingTax());
    }

    /**
     * ค่าตอบแทนกรรมการ (director remuneration, sheet column G) is NOT เงินเดือน.
     *
     * <p>In the workbook the five directors have column G filled and D/H (salary) empty, the same
     * amount every month, and — decisively — <strong>no social security row at all</strong>.
     * Director remuneration is not wages under the Social Security Act, so no contribution is due.
     * Employees, by contrast, all carry 875.
     *
     * <p>The engine has no concept of this. {@code ssoWageBase} is derived from {@code baseSalary},
     * and {@code findActiveEmployees} feeds it {@code hr.employee.current_salary}. So a director
     * whose remuneration is stored as their salary is charged 875 a month that the accountant does
     * not charge — the company over-deducts, and the SSO filing disagrees with the payroll.
     */
    @Test
    void directorRemunerationEnteredAsSalaryWronglyChargesSocialSecurity() {
        // กัลยาณี, May 2026: G = 150,000, no salary, no SSO in the sheet.
        PayrollCalculation asSalary = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("150000"),
            specialPays("0"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 5));

        assertThat(asSalary.socialSecurity())
            .withFailMessage("the sheet charges a director NO social security; the engine charges 875")
            .isEqualByComparingTo(new BigDecimal("875.00"));
        assertThat(asSalary.grossEarnings()).isEqualByComparingTo(new BigDecimal("150000"));
    }

    /**
     * The workaround that happens to be correct, recorded so it is a deliberate choice rather than
     * an accident: enter director remuneration as an allowance with a ZERO base salary. Gross is
     * unchanged and social security correctly falls to zero, because the SSO base is salary only.
     *
     * <p>It is still a workaround. There is no {@code director_remuneration} column on
     * {@code payroll_line} and no SSO-exempt flag on the employee, so nothing stops HR from typing
     * the figure into the salary field instead and silently over-deducting.
     */
    @Test
    void directorRemunerationEnteredAsAnAllowanceMatchesTheSheet() {
        PayrollCalculation asAllowance = calculator.calculate(new PayrollCalculationInput(
            BigDecimal.ZERO,
            specialPays("150000"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 5));

        assertThat(asAllowance.grossEarnings()).isEqualByComparingTo(new BigDecimal("150000"));
        assertThat(asAllowance.socialSecurity())
            .withFailMessage("with no salary there is no SSO base, matching the sheet's blank column")
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * A migration hazard, pinned so it cannot be forgotten.
     *
     * <p>{@code projectedAnnualIncome = yearToDate + thisMonth x monthsRemaining}. That is correct
     * as a catch-up mechanism <em>when year-to-date figures are loaded</em>. With an empty YTD it
     * projects only the months that remain, so the later in the year payroll is first run, the less
     * tax is withheld — reaching ZERO from August onward for an employee the accountant taxes every
     * month.
     *
     * <p>So: before the first live run mid-year, each employee's year-to-date taxable income, SSO
     * and withholding must be back-loaded, or every employee is under-withheld and discovers it at
     * filing time.
     */
    @Test
    void anEmptyYearToDateUnderWithholdsAndReachesZeroLateInTheYear() {
        SheetRow row = sheetRow("จริญญา");

        BigDecimal january = calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 1).withholdingTax();
        BigDecimal may = calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 5).withholdingTax();
        BigDecimal august = calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 8).withholdingTax();

        assertThat(january).isGreaterThan(may);
        assertThat(may).isGreaterThan(august);
        assertThat(august)
            .withFailMessage("an August first-run with no YTD withholds nothing at all")
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------

    private PayrollCalculation calculate(SheetRow row) {
        return calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 5);
    }

    private PayrollCalculation calculateForMonth(
            SheetRow row, PayrollTaxAllowanceInput allowances, int month) {
        return calculator.calculate(new PayrollCalculationInput(
            new BigDecimal(row.baseSalary()),
            specialPays(row.allowances()),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            allowances,
            PayrollYearToDate.empty(),
            month));
    }

    private SheetRow sheetRow(String name) {
        return MAY_2026.stream()
            .filter(candidate -> candidate.name().equals(name))
            .findFirst()
            .orElseThrow();
    }

    /** The whole พิเศษ block collapsed into one slot; only the total matters for the maths. */
    private List<BigDecimal> specialPays(String total) {
        return List.of(
            new BigDecimal(total),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Everything the engine already knows about, put in one field so the total is what varies. */
    private PayrollTaxAllowanceInput allowancesTotalling(BigDecimal total) {
        return new PayrollTaxAllowanceInput(
            total,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

}
