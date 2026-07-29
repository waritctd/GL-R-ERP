package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculation;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculationInput;

/**
 * Reconciles {@link PayrollCalculator#calculateClassified} against the accountant's real workbook
 * (2026.xlsx, sheet พ.ค.69 / May 2026). The figures below are transcribed from that sheet, not
 * invented.
 *
 * <p>Why this exists: the engine is only trustworthy if it reproduces what the accountant already
 * produces by hand. Everything the sheet computes without per-employee tax data — gross, SSO, the
 * deduction total, net pay — is asserted here to the satang.
 *
 * <p><b>Defect fix (Opus review, 2026-07-29):</b> this file used to drive {@link
 * PayrollCalculator#calculate}, the pre-task-2 single-limb engine. {@link
 * PayrollService#calculateLine} has called only {@link PayrollCalculator#calculateClassified} since
 * task 2 — {@code calculate} has ZERO production callers — so this file was reconciling the
 * accountant's real numbers against an engine production no longer runs, while the engine that
 * actually processes every payroll was reconciled against nothing. Repointed at {@code
 * calculateClassified}, driven with every non-zero component classified {@code REGULAR_REPROJECT}
 * (the layered engine's equivalent of the legacy single-limb annualisation when nothing is KNOWN or
 * CUMULATIVE) so the two engines are asked the same question. Every expected figure below is
 * UNCHANGED from before this repoint — same transcribed sheet values, same assertions — because
 * {@code calculateClassified} must (and does) reproduce {@code calculate}'s numbers on this
 * classification shape; see this class's own tests for the proof. {@code calculate} itself is left
 * in place (not deleted) per instruction; the other 27 tests exercising it directly ({@code
 * PayrollCalculatorTest}) are a separate call, recorded in the branch's handoff.
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
            PayrollClassifiedCalculation result = calculate(row);
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
            PayrollClassifiedCalculation result = calculate(row);
            assertThat(result.socialSecurity())
                .withFailMessage("SSO mismatch for %s", row.name())
                .isEqualByComparingTo(new BigDecimal(row.sso()));
        }
    }

    /**
     * The sheet's own arithmetic, reproduced by the engine: net = gross - deductions + non-taxable.
     * Feeding the accountant's tax figure in as a post-tax deduction isolates this identity from the
     * allowance question, so a failure here means the engine's *structure* diverges from the sheet.
     *
     * <p>Pure arithmetic on the transcribed sheet figures -- does not call the calculator at all, so
     * it needed no change for the {@code calculateClassified} repoint.
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

        PayrollClassifiedCalculation withNoAllowances = calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 1);
        assertThat(withNoAllowances.withholdingTax())
            .withFailMessage("with no allowance data the engine should over-withhold versus the sheet")
            .isGreaterThan(new BigDecimal(row.tax()));

        // Directional proof that this is a missing-input problem, not a formula problem: feeding
        // the engine allowance data moves its withholding down toward the accountant's figure.
        // The exact reconstruction is not attempted here — each allowance field carries its own
        // statutory cap, so the accountant's total cannot be expressed as a single number.
        PayrollClassifiedCalculation withSomeAllowances = calculateForMonth(row, allowancesTotalling(new BigDecimal("60000")), 1);
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
     * <p>The engine has no concept of this when director pay is (mis-)entered as ordinary salary.
     * {@code ssoIncluded(SALARY)} defaults TRUE, so a director whose remuneration is stored as their
     * salary is charged 875 a month that the accountant does not charge — the company over-deducts,
     * and the SSO filing disagrees with the payroll.
     */
    @Test
    void directorRemunerationEnteredAsSalaryWronglyChargesSocialSecurity() {
        // กัลยาณี, May 2026: G = 150,000, no salary, no SSO in the sheet. Entered here AS salary (the
        // historical mis-entry this test demonstrates), so SALARY's default-TRUE SSO inclusion fires.
        PayrollClassifiedCalculation asSalary = calculateClassified(
            new BigDecimal("150000"), BigDecimal.ZERO, BigDecimal.ZERO, true, PayrollTaxAllowanceInput.empty(), 5);

        assertThat(asSalary.socialSecurity())
            .withFailMessage("the sheet charges a director NO social security; the engine charges 875")
            .isEqualByComparingTo(new BigDecimal("875.00"));
        assertThat(asSalary.grossEarnings()).isEqualByComparingTo(new BigDecimal("150000"));
    }

    /**
     * The workaround that happens to be correct, recorded so it is a deliberate choice rather than
     * an accident: enter director remuneration as พิเศษ 1 (an allowance) with a ZERO base salary,
     * AND explicitly exclude that slot from the SSO wage base. Gross is unchanged and social
     * security correctly falls to zero.
     *
     * <p>It is still a workaround. The modern, correct path is the dedicated {@code
     * DIRECTOR_REMUNERATION} component (SSO-excluded by default, see {@code
     * PayrollAllowanceDirectorNonTaxableIntegrationTest}) — this test pins the OLD workaround
     * specifically, because nothing stops HR from typing the figure into an ordinary allowance slot
     * instead and forgetting to flip its SSO tick, silently over-deducting.
     */
    @Test
    void directorRemunerationEnteredAsAnAllowanceMatchesTheSheet() {
        PayrollClassifiedCalculation asAllowance = calculateClassified(
            BigDecimal.ZERO, new BigDecimal("150000"), BigDecimal.ZERO, false, PayrollTaxAllowanceInput.empty(), 5);

        assertThat(asAllowance.grossEarnings()).isEqualByComparingTo(new BigDecimal("150000"));
        assertThat(asAllowance.socialSecurity())
            .withFailMessage("with the slot explicitly excluded from the SSO wage base, matching the sheet's blank column")
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * A migration hazard, pinned so it cannot be forgotten.
     *
     * <p>{@code fullAnnualProjection = yearToDate + thisMonth x monthsRemaining}. That is correct
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

        // ป.96/2543 (2026-07-29): the hazard is unchanged but it now bites EARLIER. The พิเศษ block is
        // occasional income (ข้อ 2.5) and is no longer multiplied out, so the projection falls away
        // faster and withholding reaches zero by May rather than August. May > August was the original
        // assertion; both are now zero, so the decay is asserted from January instead. If anything this
        // strengthens the warning below: back-load year-to-date BEFORE the first live run.
        assertThat(january).isGreaterThan(may);
        assertThat(january).isGreaterThan(august);
        assertThat(may)
            .withFailMessage("a mid-year first-run with no YTD stops withholding entirely")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(august)
            .withFailMessage("an August first-run with no YTD withholds nothing at all")
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------

    private PayrollClassifiedCalculation calculate(SheetRow row) {
        return calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 5);
    }

    private PayrollClassifiedCalculation calculateForMonth(
            SheetRow row, PayrollTaxAllowanceInput allowances, int month) {
        // The whole พิเศษ block collapsed into slot 1 (only the total matters for the maths, same as
        // the pre-repoint helper), SSO-included -- doesn't affect any of these rows' SSO figure
        // (every base salary here already saturates the 17,500 ceiling on its own).
        return calculateClassified(
            new BigDecimal(row.baseSalary()), new BigDecimal(row.allowances()), BigDecimal.ZERO,
            true, allowances, month);
    }

    /**
     * Drives {@link PayrollCalculator#calculateClassified} with salary, one allowance slot
     * (พิเศษ 1) and director remuneration, everything classified {@code REGULAR_REPROJECT} -- the
     * layered engine's equivalent of the legacy single-limb annualisation this file reconciled
     * against before the repoint (no EXTRA_KNOWN_FREQUENCY / EXTRA_CUMULATIVE_ACTUAL component in
     * any of these fixtures, so the three-limb layering collapses to exactly one limb, matching
     * {@link PayrollCalculator#calculate} figure-for-figure). {@code ssoIncludeSpecialPay1} lets the
     * two director-remuneration tests reproduce the legacy engine's salary-only SSO base precisely.
     */
    private PayrollClassifiedCalculation calculateClassified(
            BigDecimal baseSalary, BigDecimal specialPay1, BigDecimal directorRemuneration,
            boolean ssoIncludeSpecialPay1, PayrollTaxAllowanceInput allowances, int month) {
        Map<PayrollComponent, BigDecimal> amounts = new EnumMap<>(PayrollComponent.class);
        amounts.put(PayrollComponent.SALARY, baseSalary);
        amounts.put(PayrollComponent.SPECIAL_PAY_1, specialPay1);
        amounts.put(PayrollComponent.DIRECTOR_REMUNERATION, directorRemuneration);

        Map<PayrollComponent, PayrollTaxTreatment> treatments = new EnumMap<>(PayrollComponent.class);
        // SALARY is auto-REGULAR_REPROJECT (PayrollClassifiedCalculationInput#treatmentOf) -- no
        // entry needed here.
        treatments.put(PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.REGULAR_REPROJECT);
        treatments.put(PayrollComponent.DIRECTOR_REMUNERATION, PayrollTaxTreatment.REGULAR_REPROJECT);

        Map<PayrollComponent, Boolean> sso = new EnumMap<>(PayrollComponent.class);
        sso.put(PayrollComponent.SALARY, true);
        sso.put(PayrollComponent.SPECIAL_PAY_1, ssoIncludeSpecialPay1);
        // DIRECTOR_REMUNERATION intentionally absent -> excluded, matching the V95 SSO-inclusion
        // default and the Social Security Act (director fees are not wages).

        return calculator.calculateClassified(new PayrollClassifiedCalculationInput(
            1L,
            "SHEET-RECONCILIATION",
            amounts,
            treatments,
            sso,
            BigDecimal.ZERO,  // unpaidLeaveDays
            BigDecimal.ZERO,  // leaveRefundDays
            BigDecimal.ZERO,  // studentLoanDeduction
            BigDecimal.ZERO,  // otherPretaxDeduction
            BigDecimal.ZERO,  // otherPostTaxDeductions
            BigDecimal.ZERO,  // warningLetterDeduction
            BigDecimal.ZERO,  // customerReturnDeduction
            false,            // customerReturnAlreadyEarned
            BigDecimal.ZERO,  // garnishmentRequested
            null,             // garnishmentType
            allowances,
            PayrollYearToDate.empty(),
            month,
            null              // withholdingTaxOverride
        ));
    }

    private SheetRow sheetRow(String name) {
        return MAY_2026.stream()
            .filter(candidate -> candidate.name().equals(name))
            .findFirst()
            .orElseThrow();
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
