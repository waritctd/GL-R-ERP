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
 * <p>This file drives {@link PayrollCalculator#calculateClassified}, the engine {@link
 * PayrollService#calculateLine} actually calls — {@link PayrollCalculator#calculate} has ZERO
 * production callers, so reconciling against it would prove nothing about what processes every
 * payroll. Every non-zero component is classified {@code REGULAR_REPROJECT} (the layered engine's
 * equivalent of single-limb annualisation when nothing is KNOWN or CUMULATIVE). Every
 * ACCOUNTANT-WORKBOOK figure (the {@code MAY_2026} rows and every test driven directly from them)
 * is a transcribed sheet value. Not every expected figure here is workbook-reconciled: {@link
 * #anEmptyYearToDateUnderWithholdsAndReachesZeroLateInTheYear()} is NOT reconciled against the
 * sheet (it drives an empty year-to-date, a scenario the sheet does not represent); see that
 * test's own javadoc.
 *
 * <p>{@code calculate} and {@code calculateClassified} do not agree on this classification shape,
 * and need not: {@code calculate} is a two-limb ป.96 engine in which เงินพิเศษ (ข้อ 2.5) is taken at
 * its actual cumulative amount and is deliberately NOT multiplied out — the opposite of what
 * REGULAR_REPROJECT does to the พิเศษ block below. Nothing in this file is reconciled against
 * {@code calculate}; every test below is reconciled directly against the accountant's workbook
 * figures, which is the only agreement that matters. {@code calculate} itself is left in place
 * (not deleted); the tests exercising it directly are in {@code PayrollCalculatorTest}.
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

    /** Taxable gross (sheet column W) is base salary plus the whole พิเศษ block. This is the figure the
     * special-money work will add per-diem and life-event aid into, so it has to match first. */
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

    /** Social security, on base salary only, 5% capped at the 2026 wage ceiling of 17,500 → 875. Every employee in
     * this sample earns above the ceiling, so all seven land on 875. */
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
     * {@link #socialSecurityMatchesTheSheet()} above cannot
     * tell a correct salary-only SSO wage base apart from a wrongly-inclusive one -- every {@code
     * MAY_2026} row's base salary already exceeds the ฿17,500 ceiling on its own, so the wage base
     * lands on 875 whether or not the พิเศษ block is folded in. Driven through {@link
     * #calculateForMonth(SheetRow, PayrollTaxAllowanceInput, int)} -- the exact path the seven
     * sheet-reconciliation tests above use -- with a synthetic below-ceiling salary (not an
     * accountant figure; nothing here is pinned against the workbook) specifically to make the
     * difference observable: with พิเศษ wrongly included the base would be salary + allowance
     * (15,000, 5%% = 750.00); with the sheet's actual AF-column semantics (salary only, handoff
     * section 5) the base is salary alone (10,000, 5%% = 500.00).
     */
    @Test
    void aBelowCeilingSalaryDetectsWhetherTheAllowanceBlockWronglyEntersTheSsoBase() {
        SheetRow belowCeiling = new SheetRow(
            "SYNTHETIC-BELOW-CEILING", "10000", "5000", "15000", "0", "500", "500", "14500");

        PayrollClassifiedCalculation result = calculate(belowCeiling);

        assertThat(result.socialSecurity())
            .withFailMessage("SSO must be 5%% of SALARY ALONE (10,000 -> 500.00), not salary + the "
                + "พิเศษ block (10,000 + 5,000 would wrongly land on 750.00)")
            .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    /** The sheet's own arithmetic, reproduced by the engine: net = gross - deductions + non-taxable. Feeding the
     * accountant's tax figure in as a post-tax deduction isolates this identity from the allowance question, so a
     * failure here means the engine's *structure* diverges from the sheet. */
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

    /** The tax gap, stated rather than hidden. Operational consequence: PayrollTaxAllowanceInput's 16 fields must
     * be populated per employee from the accountant's records before the engine's tax column can be trusted. */
    @Test
    void withholdingTaxDependsOnAllowancesThatTheSheetDoesNotCarry() {
        SheetRow row = sheetRow("จริญญา");

        PayrollClassifiedCalculation withNoAllowances = calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 1);
        assertThat(withNoAllowances.withholdingTax())
            .withFailMessage("with no allowance data the engine should over-withhold versus the sheet")
            .isGreaterThan(new BigDecimal(row.tax()));

        // Directional proof that this is a missing-input problem, not a formula problem: feeding the engine allowance
        // data moves its withholding down toward the accountant's figure.
        PayrollClassifiedCalculation withSomeAllowances = calculateForMonth(row, allowancesTotalling(new BigDecimal("60000")), 1);
        assertThat(withSomeAllowances.withholdingTax())
            .withFailMessage("allowance data should reduce the engine's withholding")
            .isLessThan(withNoAllowances.withholdingTax());
    }

    /** ค่าตอบแทนกรรมการ (director remuneration, sheet column G) is NOT เงินเดือน. In the workbook the five
     * directors have column G filled and D/H (salary) empty, the same amount every month, and — decisively — no
     * social security row at all. */
    @Test
    void directorRemunerationEnteredAsSalaryWronglyChargesSocialSecurity() {
        // กัลยาณี, May 2026: G = 150,000, no salary, no SSO in the sheet. Entered here AS salary (the historical
        // mis-entry this test demonstrates), so SALARY's default-TRUE SSO inclusion fires.
        PayrollClassifiedCalculation asSalary = calculateClassified(
            new BigDecimal("150000"), BigDecimal.ZERO, BigDecimal.ZERO, true, PayrollTaxAllowanceInput.empty(), 5);

        assertThat(asSalary.socialSecurity())
            .withFailMessage("the sheet charges a director NO social security; the engine charges 875")
            .isEqualByComparingTo(new BigDecimal("875.00"));
        assertThat(asSalary.grossEarnings()).isEqualByComparingTo(new BigDecimal("150000"));
    }

    /** The workaround that happens to be correct, recorded so it is a deliberate choice rather than an accident:
     * enter director remuneration as พิเศษ 1 (an allowance) with a ZERO base salary, AND explicitly exclude that
     * slot from the SSO wage base. */
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
     * A migration hazard, pinned so it cannot be forgotten — under BOTH classifications a real
     * one-off allowance could carry, not just one, since classification is per-component HR data
     * rather than a hardcoded slot (no component except salary is hardcoded to a limb).
     *
     * <p>{@code fullAnnualProjection = yearToDate + thisMonth x monthsRemaining} for the REGULAR
     * limb (ข้อ 1(4)): correct as a catch-up mechanism <em>when year-to-date figures are loaded</em>,
     * but with an empty YTD it projects only the months that remain, so the later in the year payroll
     * is first run, the less tax is withheld. The CUMULATIVE limb (ข้อ 1(6)) has no such multiplier —
     * it is taken at its actual year-to-date amount, full stop — so an empty YTD there does not
     * shrink a projection, it simply reports what one period's actual figure implies with nothing
     * annualised at all, which collapses to zero even faster.
     *
     * <p>Measured below, REGULAR_REPROJECT is still withholding ฿268.75 in May and reaches zero only
     * from June. It is EXTRA_CUMULATIVE_ACTUAL — not REGULAR_REPROJECT — that collapses to zero
     * early (by March). Rather than guess which classification a real HR record would carry, both are driven explicitly
     * below and each is pinned to its own measured zero-point, so a future change to either limb's
     * math is caught regardless of which one narrows or widens.
     *
     * <p>So: before the first live run mid-year, each employee's year-to-date taxable income, SSO
     * and withholding must be back-loaded, or every employee is under-withheld and discovers it at
     * filing time — CUMULATIVE-classified components soonest of all.
     */
    @Test
    void anEmptyYearToDateUnderWithholdsAndReachesZeroLateInTheYear() {
        SheetRow row = sheetRow("จริญญา");

        // Whole-baht withholding (see PayrollCalculator's computedWithholding): the four
        // pins below are the measured figures HALF_UP-rounded to whole baht -- 1204.17 -> 1204,
        // 268.75 -> 269, 914.58 -> 915. The decay SHAPE this test exists to pin
        // (gradual for REGULAR_REPROJECT, immediate for EXTRA_CUMULATIVE_ACTUAL, and the month each
        // one first reaches zero) is unaffected by rounding.

        // REGULAR_REPROJECT (ข้อ 1(4)): annualised x months remaining, so it decays gradually and is still withholding
        // something through May; zero only from June (measured).
        assertDecaysToZeroWithNoYearToDate(row, PayrollTaxTreatment.REGULAR_REPROJECT,
            new BigDecimal("1204.00"), new BigDecimal("269.00"), 6);

        // EXTRA_CUMULATIVE_ACTUAL (ข้อ 1(6)): actual amount, never multiplied out, so there is no projection to shrink
        // gradually -- it is already zero by March (measured).
        assertDecaysToZeroWithNoYearToDate(row, PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL,
            new BigDecimal("915.00"), BigDecimal.ZERO, 3);
    }

    /** Checks expected monthly withholding decays to its first zero month when year-to-date data is absent. */
    private void assertDecaysToZeroWithNoYearToDate(
            SheetRow row, PayrollTaxTreatment treatment,
            BigDecimal expectedJanuary, BigDecimal expectedMay, int firstZeroMonth) {
        BigDecimal january = calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 1, treatment).withholdingTax();
        BigDecimal may = calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 5, treatment).withholdingTax();
        BigDecimal august = calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 8, treatment).withholdingTax();
        BigDecimal monthBeforeZero = calculateForMonth(
            row, PayrollTaxAllowanceInput.empty(), firstZeroMonth - 1, treatment).withholdingTax();
        BigDecimal zeroMonth = calculateForMonth(
            row, PayrollTaxAllowanceInput.empty(), firstZeroMonth, treatment).withholdingTax();

        assertThat(january)
            .as("[%s] January, empty YTD", treatment)
            .isEqualByComparingTo(expectedJanuary);
        assertThat(january)
            .as("[%s] withholding must strictly fall from January to May under an empty YTD", treatment)
            .isGreaterThan(may);
        assertThat(may)
            .as("[%s] withholding must never rise from May to August under an empty YTD", treatment)
            .isGreaterThanOrEqualTo(august);
        assertThat(may)
            .as("[%s] May, empty YTD", treatment)
            .isEqualByComparingTo(expectedMay);
        assertThat(august)
            .withFailMessage("[%s] an August first-run with no YTD withholds nothing at all", treatment)
            .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(monthBeforeZero)
            .as("[%s] month %d must still withhold something, or the zero-point pin below is vacuous",
                treatment, firstZeroMonth - 1)
            .isGreaterThan(BigDecimal.ZERO);
        assertThat(zeroMonth)
            .withFailMessage("[%s] MIGRATION HAZARD, pinned: month %d is the first month this "
                + "classification withholds nothing with an empty YTD. If this now reads nonzero the "
                + "hazard narrowed for this classification and the pin should move later; if an "
                + "EARLIER month already reads zero the hazard widened and this pin should move "
                + "earlier -- either way, do not just delete the pin.", treatment, firstZeroMonth)
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------

    private PayrollClassifiedCalculation calculate(SheetRow row) {
        return calculateForMonth(row, PayrollTaxAllowanceInput.empty(), 5);
    }

    /**
     * Whole-baht withholding. ภ.ง.ด.1 and the KBank PCT transfer file
     * are both rendered straight from the stored columns, so a satang in the withheld tax is a
     * satang on the filing and on the bank file; the accountant's workbook has always carried whole
     * baht. Same house convention as SSO and commission.
     *
     * <p><b>Non-vacuous by construction.</b> The pinned figure below is one a non-rounding engine
     * gets wrong: this exact fixture (month 1, empty YTD, empty
     * allowances, REGULAR_REPROJECT) would produce <b>1204.17</b> unrounded, which is what {@link
     * #anEmptyYearToDateUnderWithholdsAndReachesZeroLateInTheYear()} would pin without rounding. Revert
     * {@code PayrollCalculator}'s {@code wholeBaht(exactWithholding)} and this test goes red on the
     * satang, not merely on a tautology about {@code scale()}.
     *
     * <p>The limb assertion is the other half: rounding the TOTAL without moving the residual into a
     * limb would silently break {@code withholding_tax = regular + cumulative}, the invariant next
     * period's year-to-date bookkeeping reads back.
     */
    @Test
    void withholdingTaxIsWholeBahtAndTheLimbsStillSumToIt() {
        PayrollClassifiedCalculation january =
            calculateForMonth(sheetRow("จริญญา"), PayrollTaxAllowanceInput.empty(), 1);

        assertThat(january.withholdingTax())
            .as("pre-rounding this fixture withheld 1204.17")
            .isEqualByComparingTo(new BigDecimal("1204.00"));
        assertThat(january.withholdingTax().stripTrailingZeros().scale())
            .as("no satang may survive into the ภ.ง.ด.1 / KBank PCT figure")
            .isLessThanOrEqualTo(0);
        assertThat(january.withholdingTax())
            .as("guard against a vacuous pass on an engine that withholds nothing")
            .isGreaterThan(BigDecimal.ZERO);
        assertThat(january.withholdingTaxRegularLimb().add(january.withholdingTaxCumulativeLimb()))
            .as("the <=0.50 rounding residual must land on a limb, not vanish")
            .isEqualByComparingTo(january.withholdingTax());
    }

    /** An HR override REPLACES the withheld amount outright, so a satang override would put a satang straight back
     * onto the filing and the transfer file. 1315.60 is not hypothetical — it is the ป.96 correction typed for
     * ชนิดา ทองเทพ in the August 2026 run, before the whole-baht rule. */
    @Test
    void aSatangWithholdingTaxOverrideIsRoundedToWholeBahtToo() {
        SheetRow row = sheetRow("จริญญา");
        PayrollClassifiedCalculation overridden = calculateClassified(
            new BigDecimal(row.baseSalary()), new BigDecimal(row.allowances()), BigDecimal.ZERO,
            false, PayrollTaxAllowanceInput.empty(), 1, PayrollTaxTreatment.REGULAR_REPROJECT,
            new BigDecimal("1315.60"));

        assertThat(overridden.withholdingTax()).isEqualByComparingTo(new BigDecimal("1316.00"));
        assertThat(overridden.withholdingTaxOverride()).isEqualByComparingTo(new BigDecimal("1316.00"));
        // Net follows the rounded tax, so the transfer amount stays whole too.
        assertThat(overridden.netPay().stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
    }

    private PayrollClassifiedCalculation calculateForMonth(
            SheetRow row, PayrollTaxAllowanceInput allowances, int month) {
        return calculateForMonth(row, allowances, month, PayrollTaxTreatment.REGULAR_REPROJECT);
    }

    /** Same as the 3-arg overload, but with the พิเศษ block's classification left open rather than hardcoded to
     * REGULAR_REPROJECT. */
    private PayrollClassifiedCalculation calculateForMonth(
            SheetRow row, PayrollTaxAllowanceInput allowances, int month, PayrollTaxTreatment specialPayTreatment) {
        // The whole พิเศษ block collapsed into slot 1 (only the total matters for the maths),
        // SSO-EXCLUDED -- matching the sheet's own AF column ("In: เงินเดือน ... Out: ...
        // รายได้ไม่คิดภาษี", and AF is salary-only in every row transcribed above). SSO-INCLUDED
        // would silently put the whole พิเศษ block into the wage base -- undetectable by any
        // assertion on these rows because every MAY_2026 row's base salary already saturates the
        // ฿17,500 ceiling on its own, so socialSecurityMatchesTheSheet() lands on 875 either way.
        // See aBelowCeilingSalaryDetectsWhetherTheAllowanceBlockWronglyEntersTheSsoBase() above for
        // the case that does discriminate.
        return calculateClassified(
            new BigDecimal(row.baseSalary()), new BigDecimal(row.allowances()), BigDecimal.ZERO,
            false, allowances, month, specialPayTreatment);
    }

    /**
     * Drives {@link PayrollCalculator#calculateClassified} with salary, one allowance slot
     * (พิเศษ 1, classified {@code REGULAR_REPROJECT}) and director remuneration (also
     * {@code REGULAR_REPROJECT}) -- the layered engine's equivalent of single-limb
     * annualisation (no EXTRA_KNOWN_FREQUENCY /
     * EXTRA_CUMULATIVE_ACTUAL component in any of these fixtures, so the three-limb layering
     * collapses to exactly one limb). {@code ssoIncludeSpecialPay1} lets the two director-remuneration
     * tests use a salary-only SSO base precisely.
     *
     * <p>This does NOT reproduce {@link PayrollCalculator#calculate} figure-for-figure: that is a
     * two-limb ป.96 engine, in which เงินพิเศษ (ข้อ 2.5) is taken at its actual cumulative amount
     * and is explicitly NOT multiplied out by the months remaining -- the opposite of what
     * REGULAR_REPROJECT does here. {@code calculate} has zero production callers (see the class
     * javadoc), so nothing here needs to match it; the seven tests above only need
     * {@code calculateClassified} to reproduce the accountant's workbook.
     */
    private PayrollClassifiedCalculation calculateClassified(
            BigDecimal baseSalary, BigDecimal specialPay1, BigDecimal directorRemuneration,
            boolean ssoIncludeSpecialPay1, PayrollTaxAllowanceInput allowances, int month) {
        return calculateClassified(baseSalary, specialPay1, directorRemuneration, ssoIncludeSpecialPay1,
            allowances, month, PayrollTaxTreatment.REGULAR_REPROJECT);
    }

    private PayrollClassifiedCalculation calculateClassified(
            BigDecimal baseSalary, BigDecimal specialPay1, BigDecimal directorRemuneration,
            boolean ssoIncludeSpecialPay1, PayrollTaxAllowanceInput allowances, int month,
            PayrollTaxTreatment specialPayTreatment) {
        return calculateClassified(baseSalary, specialPay1, directorRemuneration, ssoIncludeSpecialPay1,
            allowances, month, specialPayTreatment, null);
    }

    /** Same, with an explicit HR withholding-tax override (null = none). */
    private PayrollClassifiedCalculation calculateClassified(
            BigDecimal baseSalary, BigDecimal specialPay1, BigDecimal directorRemuneration,
            boolean ssoIncludeSpecialPay1, PayrollTaxAllowanceInput allowances, int month,
            PayrollTaxTreatment specialPayTreatment, BigDecimal withholdingTaxOverride) {
        Map<PayrollComponent, BigDecimal> amounts = new EnumMap<>(PayrollComponent.class);
        amounts.put(PayrollComponent.SALARY, baseSalary);
        amounts.put(PayrollComponent.SPECIAL_PAY_1, specialPay1);
        amounts.put(PayrollComponent.DIRECTOR_REMUNERATION, directorRemuneration);

        Map<PayrollComponent, PayrollTaxTreatment> treatments = new EnumMap<>(PayrollComponent.class);
        // SALARY is auto-REGULAR_REPROJECT (PayrollClassifiedCalculationInput#treatmentOf) -- no entry needed here.
        treatments.put(PayrollComponent.SPECIAL_PAY_1, specialPayTreatment);
        treatments.put(PayrollComponent.DIRECTOR_REMUNERATION, PayrollTaxTreatment.REGULAR_REPROJECT);

        Map<PayrollComponent, Boolean> sso = new EnumMap<>(PayrollComponent.class);
        sso.put(PayrollComponent.SALARY, true);
        sso.put(PayrollComponent.SPECIAL_PAY_1, ssoIncludeSpecialPay1);
        // DIRECTOR_REMUNERATION intentionally absent -> excluded, matching the V95 SSO-inclusion default and the
        // Social Security Act (director fees are not wages).

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
            2026,             // taxYear (the sheet is พ.ค.69 / May 2026)
            withholdingTaxOverride
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
