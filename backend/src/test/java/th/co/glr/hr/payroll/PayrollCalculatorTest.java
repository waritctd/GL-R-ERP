package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PayrollCalculatorTest {
    private final PayrollCalculator calculator = new PayrollCalculator();

    // ---------------------------------------------------------------------------------------------
    // ป.96/2543 withholding compliance (2026-07-28, V92)
    //
    // Source: คำสั่งกรมสรรพากร ที่ ป.96/2543, as restated in the คำชี้แจง printed on แบบ ภ.ง.ด.1
    // (https://www.rd.go.th/fileadmin/tax_pdf/withhold/200360_WHT1.pdf):
    //
    //   ข้อ 2.1  regular pay x จำนวนคราวที่ต้องจ่าย (x12 monthly; for a mid-year joiner, the number of
    //            payments ACTUALLY remaining -- the RD's own example is a 1 April start = 9 ครั้ง)
    //   ข้อ 2.2  less ค่าใช้จ่าย and ค่าลดหย่อน, taxed under มาตรา 48(1)
    //   ข้อ 2.3  that annual tax / จำนวนคราว = each period's withholding
    //   ข้อ 2.5  เงินพิเศษที่จ่ายเป็นครั้งคราว (it names ค่าล่วงเวลา and เงินโบนัส): multiplied by the
    //            number of times THAT payment is made -- 1 for an annual bonus -- added to the
    //            annualised regular income, retaxed, and the DIFFERENCE withheld in the period paid
    //   ข้อ 2.9  December may be adjusted so the year's total withheld equals the actual liability
    //   ข้อ 2.10 same true-up in a leaver's final period
    //
    // The reference figures below are all derived from a ฿50,000 monthly salary with no allowance
    // data, for which the standing annual arithmetic is:
    //   regular only : 600,000 - 100,000 expenses - 60,000 personal - 10,500 SSO = 429,500 net
    //                  -> tax 20,450.00 -> 1,704.17/month under ข้อ 2.3
    //   + 100,000    : 700,000 -> 529,500 net -> tax 31,925.00 -> ข้อ 2.5 difference 11,475.00
    //   + 500,000    : 1,100,000 -> 929,500 net -> tax 100,900.00 -> ข้อ 2.5 difference 80,450.00
    // ---------------------------------------------------------------------------------------------

    /** ฿50,000 salary with an occasional payment in slot 1 (where HR types a bonus), no allowances. */
    private PayrollCalculation salaryWithBonus(
        String salary, String bonus, PayrollYearToDate yearToDate, int month, int remainingPayPeriods) {
        return calculator.calculate(new PayrollCalculationInput(
            new BigDecimal(salary),
            // Slot 1: where HR actually types a bonus (owner-confirmed 2026-07-29). Slots 1-5, 7 and 8
            // are all occasional, so this is the ข้อ 2.5 limb.
            List.of(new BigDecimal(bonus), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), yearToDate, month,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, 2026, remainingPayPeriods));
    }

    /** Runs a whole tax year, carrying both ป.96 limbs forward, and returns the total withheld. */
    private BigDecimal withheldOverFullYear(String salary, String bonus, int bonusMonth) {
        BigDecimal regularIncome = BigDecimal.ZERO;
        BigDecimal variableIncome = BigDecimal.ZERO;
        BigDecimal sso = BigDecimal.ZERO;
        BigDecimal regularTax = BigDecimal.ZERO;
        BigDecimal variableTax = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (int month = 1; month <= 12; month += 1) {
            PayrollCalculation result = salaryWithBonus(
                salary,
                month == bonusMonth ? bonus : "0",
                new PayrollYearToDate(regularIncome, variableIncome, sso, regularTax, variableTax),
                month,
                0);
            regularIncome = regularIncome.add(result.regularTaxableIncome());
            variableIncome = variableIncome.add(result.variableTaxableIncome());
            sso = sso.add(result.socialSecurity());
            regularTax = regularTax.add(result.regularWithholdingTax());
            variableTax = variableTax.add(result.variableWithholdingTax());
            total = total.add(result.withholdingTax());
        }
        return total;
    }

    // ---------------------------------------------------------------------------------------------
    // ค่าลดหย่อน correctness (2026-07-28, V93)
    // ---------------------------------------------------------------------------------------------

    /** A ฿100,000/month salary in January with a single allowance declaration under test. */
    private PayrollCalculation withAllowances(PayrollTaxAllowanceInput allowances, int taxYear, int age) {
        return calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("100000.00"), List.of(),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            allowances, PayrollYearToDate.empty(), 1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, taxYear, 0, age));
    }

    private PayrollTaxAllowanceInput declaration(
        String childAllowance, String disabledCareAllowance, String rmf, String ssf,
        String providentFund, int childCount, int childCountDouble, int disabledCareCount,
        boolean disabilityCardHolder) {
        return new PayrollTaxAllowanceInput(
            BigDecimal.ZERO, new BigDecimal(childAllowance), BigDecimal.ZERO,
            new BigDecimal(disabledCareAllowance), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, new BigDecimal(rmf), new BigDecimal(ssf), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal(providentFund), childCount, childCountDouble, disabledCareCount,
            disabilityCardHolder);
    }

    /**
     * กองทุนสำรองเลี้ยงชีพ was missing from the engine entirely, so every provident-fund member was
     * over-withheld. It is deductible up to 15% of ค่าจ้าง and 500,000, inside the same 500,000
     * retirement cluster as RMF and บำนาญ.
     */
    @Test
    void providentFundContributionsReduceTheTaxableAnnualIncome() {
        PayrollCalculation without = withAllowances(
            declaration("0", "0", "0", "0", "0", 0, 0, 0, false), 2026, 40);
        PayrollCalculation with = withAllowances(
            declaration("0", "0", "0", "0", "120000.00", 0, 0, 0, false), 2026, 40);

        // 15% of 1,200,000 is 180,000, so the whole 120,000 is allowed.
        assertThat(without.taxableAnnualIncome().subtract(with.taxableAnnualIncome()))
            .isEqualByComparingTo(new BigDecimal("120000.00"));
        assertThat(with.withholdingTax()).isLessThan(without.withholdingTax());
    }

    /** The 15%-of-ค่าจ้าง ceiling on the provident-fund deduction is enforced. */
    @Test
    void theProvidentFundDeductionIsCappedAtFifteenPercentOfPay() {
        PayrollCalculation baseline = withAllowances(
            declaration("0", "0", "0", "0", "0", 0, 0, 0, false), 2026, 40);
        // Declaring 400,000 against 1,200,000 of pay: 15% = 180,000 is the most that can be allowed.
        PayrollCalculation capped = withAllowances(
            declaration("0", "0", "0", "0", "400000.00", 0, 0, 0, false), 2026, 40);

        assertThat(baseline.taxableAnnualIncome().subtract(capped.taxableAnnualIncome()))
            .isEqualByComparingTo(new BigDecimal("180000.00"));
    }

    /**
     * กองทุนสำรองเลี้ยงชีพ takes the 500,000 cluster FIRST. It is deducted from pay at source and the
     * employee cannot choose to stop it, so when the cluster runs out it must run out against the
     * voluntary RMF purchase instead.
     */
    @Test
    void theProvidentFundTakesTheRetirementClusterAheadOfRmf() {
        PayrollCalculation baseline = withAllowances(
            declaration("0", "0", "0", "0", "0", 0, 0, 0, false), 2026, 40);
        // PVD 180,000 (its own 15% ceiling) + RMF 500,000: the cluster allows 500,000 in total.
        PayrollCalculation both = withAllowances(
            declaration("0", "0", "500000.00", "0", "400000.00", 0, 0, 0, false), 2026, 40);

        assertThat(baseline.taxableAnnualIncome().subtract(both.taxableAnnualIncome()))
            .isEqualByComparingTo(new BigDecimal("500000.00"));
    }

    /**
     * SSF (กองทุนรวมเพื่อการออม) was deductible for ปีภาษี 2563-2567 only. A declared SSF figure must
     * still be honoured for those years — past filings have to remain explicable — and ignored from
     * ปีภาษี 2568 (Gregorian 2025).
     */
    @Test
    void ssfIsDeductibleUpToTaxYear2024AndIgnoredFrom2025() {
        PayrollTaxAllowanceInput ssfOnly = declaration("0", "0", "0", "100000.00", "0", 0, 0, 0, false);
        PayrollCalculation lastEligibleYear = withAllowances(ssfOnly, 2024, 40);
        PayrollCalculation firstIneligibleYear = withAllowances(ssfOnly, 2025, 40);
        PayrollCalculation currentYear = withAllowances(ssfOnly, 2026, 40);
        PayrollCalculation noSsfAtAll = withAllowances(
            declaration("0", "0", "0", "0", "0", 0, 0, 0, false), 2026, 40);

        assertThat(lastEligibleYear.taxableAnnualIncome())
            .isEqualByComparingTo(firstIneligibleYear.taxableAnnualIncome().subtract(new BigDecimal("100000.00")));
        assertThat(currentYear.taxableAnnualIncome()).isEqualByComparingTo(noSsfAtAll.taxableAnnualIncome());
    }

    /**
     * ค่าลดหย่อนบุตร is per head: 30,000 each, 60,000 for the second and later child born from
     * พ.ศ. 2561. The declared baht amount used to be trusted without any limit at all.
     */
    @Test
    void theChildAllowanceIsCappedByTheDeclaredNumberOfChildren() {
        // Two children, the second born from 2561: 30,000 + 60,000 = 90,000 allowed.
        PayrollCalculation honest = withAllowances(
            declaration("90000.00", "0", "0", "0", "0", 2, 1, 0, false), 2026, 40);
        // Same household, but 500,000 typed into the box.
        PayrollCalculation overstated = withAllowances(
            declaration("500000.00", "0", "0", "0", "0", 2, 1, 0, false), 2026, 40);

        assertThat(overstated.taxableAnnualIncome()).isEqualByComparingTo(honest.taxableAnnualIncome());
        assertThat(overstated.taxAllowanceTotal())
            .isEqualByComparingTo(new BigDecimal("60000.00").add(new BigDecimal("90000.00")).add(new BigDecimal("10500.00")));
    }

    /** ค่าอุปการะคนพิการ is 60,000 per person cared for, and was likewise uncapped. */
    @Test
    void theDisabledCareAllowanceIsCappedByTheDeclaredNumberOfDependants() {
        PayrollCalculation honest = withAllowances(
            declaration("0", "60000.00", "0", "0", "0", 0, 0, 1, false), 2026, 40);
        PayrollCalculation overstated = withAllowances(
            declaration("0", "300000.00", "0", "0", "0", 0, 0, 1, false), 2026, 40);

        assertThat(overstated.taxableAnnualIncome()).isEqualByComparingTo(honest.taxableAnnualIncome());
    }

    /**
     * ยกเว้นเงินได้ 190,000 for a taxpayer aged 65+ (กฎกระทรวง ฉบับที่ 126), applied BEFORE ค่าใช้จ่าย.
     *
     * <p>The order is the RD's own: ใบแสดงสิทธิการได้รับยกเว้นเงินได้ฯ ข้อ 1 goes
     * "1. มาตรา 40(1) เงินเดือน" → "2. หัก เงินได้ที่ได้รับการยกเว้น" → "3. คงเหลือ (1.-2.) ยกไปกรอกใน
     * ภ.ง.ด.90", and the 50% expense deduction is taken on that remainder.
     *
     * <p>At ฿1,200,000 the 50% expense deduction is already at its 100,000 ceiling with or without the
     * exemption, so taxable income falls by the full 190,000 and the ordering is invisible here — the
     * low-income case below is what pins the order.
     */
    @Test
    void ataxpayerAged65OrOverGetsThe190000Exemption() {
        PayrollCalculation under65 = withAllowances(
            declaration("0", "0", "0", "0", "0", 0, 0, 0, false), 2026, 64);
        PayrollCalculation over65 = withAllowances(
            declaration("0", "0", "0", "0", "0", 0, 0, 0, false), 2026, 65);

        assertThat(under65.taxableAnnualIncome().subtract(over65.taxableAnnualIncome()))
            .isEqualByComparingTo(new BigDecimal("190000.00"));
        assertThat(over65.withholdingTax()).isLessThan(under65.withholdingTax());
    }

    /**
     * The exemption comes off BEFORE the 50% expense deduction. On ฿300,000 a year the expense
     * deduction is still percentage-bound, so the order is observable: exemption first leaves
     * 110,000, halved to 55,000 of expenses; the wrong order would leave 150,000 of expenses.
     */
    @Test
    void the190000ExemptionIsTakenBeforeTheFiftyPercentExpenseDeduction() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("25000.00"), List.of(),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            declaration("0", "0", "0", "0", "0", 0, 0, 0, false),
            PayrollYearToDate.empty(), 1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, 2026, 0, 70));

        assertThat(result.projectedAnnualIncome()).isEqualByComparingTo(new BigDecimal("300000.00"));
        // 300,000 - 190,000 exempt = 110,000 assessable; 50% of that = 55,000, under the 100,000 cap.
        assertThat(result.taxExpenseDeduction()).isEqualByComparingTo(new BigDecimal("55000.00"));
    }

    /** A บัตรประจำตัวคนพิการ holder gets the same 190,000 exemption at any age. */
    @Test
    void aDisabilityCardHolderUnder65GetsTheSameExemption() {
        PayrollCalculation withoutCard = withAllowances(
            declaration("0", "0", "0", "0", "0", 0, 0, 0, false), 2026, 40);
        PayrollCalculation withCard = withAllowances(
            declaration("0", "0", "0", "0", "0", 0, 0, 0, true), 2026, 40);

        assertThat(withoutCard.taxableAnnualIncome().subtract(withCard.taxableAnnualIncome()))
            .isEqualByComparingTo(new BigDecimal("190000.00"));
    }

    /** An unknown date of birth must NOT be read as eligibility — the exemption is not granted. */
    @Test
    void anUnknownDateOfBirthDoesNotGrantTheExemption() {
        PayrollCalculation unknownAge = withAllowances(
            declaration("0", "0", "0", "0", "0", 0, 0, 0, false), 2026, 0);
        PayrollCalculation knownYoung = withAllowances(
            declaration("0", "0", "0", "0", "0", 0, 0, 0, false), 2026, 30);

        assertThat(unknownAge.taxableAnnualIncome()).isEqualByComparingTo(knownYoung.taxableAnnualIncome());
    }

    /**
     * An HR withholding override must be reflected in the over-withholding figure the payslip prints.
     *
     * <p>Regression guard for a defect found in the fifth review. {@code excessWithheldToDate} was
     * computed BEFORE the override was substituted, so a December override that pushed the employee
     * further past the year's liability left the payslip printing the smaller pre-override number —
     * understated by exactly the override, while the calculation note on the same page disclosed that
     * an override had been applied. A payroll document that names a baht figure and tells the employee
     * to reclaim it on their ภ.ง.ด.91 has to name the right one.
     */
    @Test
    void aWithholdingOverrideIsReflectedInTheReportedOverWithholding() {
        // December, already over-withheld: 25,000 taken against a 20,450 annual liability.
        PayrollYearToDate overWithheld = new PayrollYearToDate(
            new BigDecimal("550000.00"), BigDecimal.ZERO, new BigDecimal("9625.00"),
            new BigDecimal("25000.00"), BigDecimal.ZERO);

        PayrollCalculation withoutOverride = salaryWithBonus("50000.00", "0", overWithheld, 12, 0);
        assertThat(withoutOverride.withholdingTax()).isEqualByComparingTo(BigDecimal.ZERO);
        BigDecimal baseExcess = withoutOverride.excessWithheldToDate();
        assertThat(baseExcess).isGreaterThan(BigDecimal.ZERO);

        PayrollCalculation withOverride = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("50000.00"), List.of(),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), overWithheld, 12,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("5000.00"), 2026, 0, 0));

        assertThat(withOverride.withholdingTax()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(withOverride.excessWithheldToDate())
            .withFailMessage("an override that withholds 5,000 more must raise the reported excess by 5,000")
            .isEqualByComparingTo(baseExcess.add(new BigDecimal("5000.00")));
    }

    /**
     * ข้อ 2.5, the clause the old single-limb projection violated.
     *
     * <p>A ฿100,000 bonus paid once, in June, typed into พิเศษ 1 where HR actually puts it. The old
     * code multiplied it by the seven months
     * remaining, projected ฿1,300,000 of annual income that will never exist, and withheld
     * ฿19,836.31. ข้อ 2.5 says to take the DIFFERENCE the bonus makes to the annual tax —
     * 31,925.00 - 20,450.00 = 11,475.00 — and add it to that period's ordinary withholding.
     */
    @Test
    void aOneOffBonusIsTaxedAsTheDifferenceItMakes_notAnnualisedOverTheRemainingMonths() {
        // Five months of ฿50,000 already withheld at 1,704.17 under ข้อ 2.3.
        PayrollYearToDate afterMay = new PayrollYearToDate(
            new BigDecimal("250000.00"), BigDecimal.ZERO, new BigDecimal("4375.00"),
            new BigDecimal("8520.85"), BigDecimal.ZERO);

        PayrollCalculation june = salaryWithBonus("50000.00", "100000.00", afterMay, 6, 0);

        // ข้อ 2.1 + ข้อ 2.5: the projection is the TRUE annual figure, not 1,300,000.
        assertThat(june.projectedAnnualIncome()).isEqualByComparingTo(new BigDecimal("700000.00"));
        assertThat(june.annualRegularIncome()).isEqualByComparingTo(new BigDecimal("600000.00"));
        assertThat(june.annualVariableIncome()).isEqualByComparingTo(new BigDecimal("100000.00"));

        // ข้อ 2.5: the bonus bears exactly the difference in annual tax, nothing more.
        assertThat(june.regularAnnualTax()).isEqualByComparingTo(new BigDecimal("20450.00"));
        assertThat(june.variableAnnualTax()).isEqualByComparingTo(new BigDecimal("11475.00"));
        assertThat(june.variableWithholdingTax()).isEqualByComparingTo(new BigDecimal("11475.00"));

        // The old engine withheld 19,836.31 here — roughly 50% too much in the month the employee
        // was being rewarded, and a monthly ภ.ง.ด.1 that did not match the RD's own method.
        assertThat(june.withholdingTax()).isLessThan(new BigDecimal("19836.31"));
        assertThat(june.withholdingTax()).isEqualByComparingTo(new BigDecimal("13179.16"));
    }

    /**
     * ข้อ 2.9, and the second half of the same bug.
     *
     * <p>A large bonus late in the year used to over-withhold PERMANENTLY: November projected income
     * that would never arrive, and December's {@code .max(ZERO)} clamp meant the excess could never
     * be handed back. ฿500,000 in November made the year withhold ฿119,708.34 against an actual
     * liability of ฿100,900.00 — ฿18,808.34 the employee could only reclaim on their ภ.ง.ด.91.
     */
    @Test
    void aLateYearBonusNoLongerOverWithholdsForTheWholeYear() {
        BigDecimal withheld = withheldOverFullYear("50000.00", "500000.00", 11);

        assertThat(withheld).isEqualByComparingTo(new BigDecimal("100900.00"));
        assertThat(withheld).isLessThan(new BigDecimal("119708.34"));
    }

    /** The mid-year bonus year also lands exactly on the true annual liability (ข้อ 2.9). */
    @Test
    void aYearWithAMidYearBonusWithholdsExactlyTheAnnualLiability() {
        assertThat(withheldOverFullYear("50000.00", "100000.00", 6))
            .isEqualByComparingTo(new BigDecimal("31925.00"));
    }

    /**
     * ข้อ 2.9 — December is the true-up. With deliberately under-withheld year-to-date, the final
     * period collects the whole shortfall rather than a twelfth of it.
     */
    @Test
    void decemberCollectsTheWholeShortfallAsTheYearEndTrueUp() {
        PayrollYearToDate underWithheld = new PayrollYearToDate(
            new BigDecimal("550000.00"), BigDecimal.ZERO, new BigDecimal("9625.00"),
            new BigDecimal("5000.00"), BigDecimal.ZERO);

        PayrollCalculation december = salaryWithBonus("50000.00", "0", underWithheld, 12, 0);

        assertThat(december.remainingPayPeriods()).isEqualTo(1);
        assertThat(december.annualTax()).isEqualByComparingTo(new BigDecimal("20450.00"));
        // 20,450.00 due for the year, 5,000.00 already withheld.
        assertThat(december.withholdingTax()).isEqualByComparingTo(new BigDecimal("15450.00"));
    }

    /**
     * ข้อ 2.9, the direction the old clamp made impossible: when year-to-date withholding already
     * EXCEEDS the year's liability, the final period withholds nothing rather than a further slice.
     */
    @Test
    void anAlreadyOverWithheldYearWithholdsNothingInTheFinalPeriod() {
        PayrollYearToDate overWithheld = new PayrollYearToDate(
            new BigDecimal("550000.00"), BigDecimal.ZERO, new BigDecimal("9625.00"),
            new BigDecimal("25000.00"), BigDecimal.ZERO);

        PayrollCalculation december = salaryWithBonus("50000.00", "0", overWithheld, 12, 0);

        assertThat(december.withholdingTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(december.regularWithholdingTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(december.variableWithholdingTax()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * ข้อ 2.10 — a leaver's final period trues up exactly as December does. An August resignation
     * means August is the last of the 8 pay periods, so this test passes remainingPayPeriods=1 DIRECTLY -- PayrollService cannot produce it (ข้อ 2.10 is a known gap; resignations are not recorded in this platform)
     * and the ordinary formula becomes the final-period adjustment.
     */
    @Test
    void aLeaversFinalPeriodTruesUpLikeDecember() {
        PayrollYearToDate afterJuly = new PayrollYearToDate(
            new BigDecimal("350000.00"), BigDecimal.ZERO, new BigDecimal("6125.00"),
            new BigDecimal("3000.00"), BigDecimal.ZERO);

        PayrollCalculation august = salaryWithBonus("50000.00", "0", afterJuly, 8, 1);

        assertThat(august.remainingPayPeriods()).isEqualTo(1);
        // Their whole year is 350,000 + 50,000 = 400,000, not a projection to December.
        assertThat(august.projectedAnnualIncome()).isEqualByComparingTo(new BigDecimal("400000.00"));
        // Without the ข้อ 2.10 cap this would have projected 350,000 + 50,000 x 5 = 600,000.
        assertThat(august.annualTax()).isLessThan(new BigDecimal("20450.00"));
    }

    /**
     * ข้อ 2.1's own worked example: someone starting 1 April on monthly pay has 9 ครั้ง left, so their
     * first payroll must project over 9 periods and not 12. This already worked before the ป.96 change
     * (an empty year-to-date plus {@code 13 - month} produces it); pinned so it cannot regress.
     */
    @Test
    void aMidYearJoinerProjectsOverTheirRemainingPayPeriodsOnly() {
        PayrollCalculation april = salaryWithBonus("50000.00", "0", PayrollYearToDate.empty(), 4, 0);

        assertThat(april.remainingPayPeriods()).isEqualTo(9);
        assertThat(april.projectedAnnualIncome()).isEqualByComparingTo(new BigDecimal("450000.00"));
    }

    /**
     * The income invariant the whole change rests on: the two limbs partition exactly the same total
     * the single-limb code produced. {@code PayrollExcelReconciliationTest}'s net-pay identity and
     * every downstream figure depend on it, including when pre-tax deductions exceed the regular limb
     * entirely and spill onto the variable one.
     */
    @Test
    void theTwoIncomeLimbsAlwaysSumToGrossTaxableIncome() {
        String[][] cases = {
            // salary, พิเศษ7 (occasional), พิเศษ1 (occasional/bonus), OT, commission (RECURRING), unpaid leave days
            {"50000.00", "5000.00", "100000.00", "3000.00", "8000.00", "0"},
            {"50000.00", "0", "0", "0", "0", "5"},
            {"3000.00", "0", "20000.00", "0", "0", "30"},     // deductions exceed the regular limb
            {"3000.00", "0", "0", "0", "0", "60"},            // deductions exceed everything
            {"20000.00", "2000.00", "0", "1500.00", "0", "2"},
        };
        for (String[] row : cases) {
            PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
                new BigDecimal(row[0]),
                List.of(new BigDecimal(row[2]), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(row[1]), BigDecimal.ZERO),
                new BigDecimal(row[3]), new BigDecimal(row[4]),
                BigDecimal.ZERO, new BigDecimal(row[5]),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 3,
                BigDecimal.ZERO, new BigDecimal("500.00"), new BigDecimal("250.00"),
                new BigDecimal("125.00"), BigDecimal.ZERO, null, 2026, 0));

            assertThat(result.regularTaxableIncome().add(result.variableTaxableIncome()))
                .withFailMessage("limbs must partition grossTaxableIncome for salary=%s", row[0])
                .isEqualByComparingTo(result.grossTaxableIncome());
            assertThat(result.regularWithholdingTax().add(result.variableWithholdingTax()))
                .withFailMessage("withholding split must sum to withholdingTax for salary=%s", row[0])
                .isEqualByComparingTo(result.withholdingTax());
        }
    }

    /**
     * With no occasional pay at all — no OT and nothing in the occasional slots — the ป.96 split is
     * a no-op and the engine must reproduce the pre-change figures exactly. This is the regression
     * guarantee for the great majority of employees, who are paid salary and recurring allowances
     * only. Modelled on {@code withEveryNewInputAtZeroTheCalculatorIsByteIdenticalToBeforeTheReconciliationChange}.
     */
    @Test
    void withNoOccasionalPayTheWithholdingIsUnchangedFromTheSingleLimbEngine() {
        // ฿40,000, month 6, YTD 200,000 taxable / 2,675.00 withheld: the exact scenario already
        // pinned by yearToDateWithholdingTaxIsSubtractedBeforeSpreadingOverRemainingMonths below,
        // whose expected values predate this change.
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("40000.00"), List.of(),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(),
            new PayrollYearToDate(new BigDecimal("200000.00"), BigDecimal.ZERO, new BigDecimal("2675.00")),
            6));

        assertThat(result.projectedAnnualIncome()).isEqualByComparingTo(new BigDecimal("480000.00"));
        assertThat(result.annualTax()).isEqualByComparingTo(new BigDecimal("8887.50"));
        assertThat(result.withholdingTax()).isEqualByComparingTo(new BigDecimal("887.50"));
        assertThat(result.variableTaxableIncome()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.variableWithholdingTax()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void calculatesPayrollAccordingToThaiPayrollExample() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("40000.00"),
            List.of(),
            new BigDecimal("2500.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("1.00"),
            new BigDecimal("1500.00"),
            new BigDecimal("12350.00"),
            BigDecimal.ZERO,
            new PayrollTaxAllowanceInput(
                BigDecimal.ZERO,
                new BigDecimal("30000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("15000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
            ),
            PayrollYearToDate.empty(),
            1
        ));

        assertThat(result.grossEarnings()).isEqualByComparingTo(new BigDecimal("42500.00"));
        assertThat(result.unpaidLeaveDeduction()).isEqualByComparingTo(new BigDecimal("1333.33"));
        assertThat(result.grossTaxableIncome()).isEqualByComparingTo(new BigDecimal("41166.67"));
        assertThat(result.socialSecurity()).isEqualByComparingTo(new BigDecimal("875.00"));
        // ป.96/2543 (2026-07-28): these two figures CHANGED with the ข้อ 2.5 fix, deliberately.
        // The ฿2,500 of ค่าล่วงเวลา in this scenario used to be annualised along with the salary --
        // 41,166.67 x 12 = 494,000.04, projecting a year of overtime the employee has not worked and
        // an annual tax of 6,425.00. ข้อ 2.5 names ค่าล่วงเวลา explicitly as เงินพิเศษที่จ่ายเป็น
        // ครั้งคราว: only the regular limb is multiplied out (38,666.67 x 12 = 464,000.04) and the OT
        // enters at its actual 2,500.00, giving 466,500.04 and 5,050.00.
        assertThat(result.projectedAnnualIncome()).isEqualByComparingTo(new BigDecimal("466500.04"));
        assertThat(result.annualRegularIncome()).isEqualByComparingTo(new BigDecimal("464000.04"));
        assertThat(result.annualVariableIncome()).isEqualByComparingTo(new BigDecimal("2500.00"));
        assertThat(result.taxAllowanceTotal()).isEqualByComparingTo(new BigDecimal("115500.00"));
        assertThat(result.annualTax()).isEqualByComparingTo(new BigDecimal("5050.00"));
        // Withheld and net are UNCHANGED here: in January, with all 12 periods still remaining and the
        // whole liability inside the 5% band, spreading 12 x 2,500 of OT over 12 periods and charging
        // one 2,500 payment in full happen to coincide. The methods diverge as soon as the year is
        // part-elapsed or a higher bracket is crossed -- which is what the bonus tests above cover.
        assertThat(result.withholdingTax()).isEqualByComparingTo(new BigDecimal("535.42"));
        assertThat(result.legalExecutionDeduction()).isEqualByComparingTo(new BigDecimal("12350.00"));
        assertThat(result.netPay()).isEqualByComparingTo(new BigDecimal("25906.25"));
    }

    @Test
    void includesAllEightSpecialPaySlotsAsTaxableEarnings() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("30000.00"),
            List.of(
                new BigDecimal("100.00"),
                new BigDecimal("200.00"),
                new BigDecimal("300.00"),
                new BigDecimal("400.00"),
                new BigDecimal("500.00"),
                new BigDecimal("600.00"),
                new BigDecimal("700.00"),
                new BigDecimal("800.00")
            ),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(),
            PayrollYearToDate.empty(),
            6
        ));

        assertThat(result.specialPayTotal()).isEqualByComparingTo(new BigDecimal("3600.00"));
        assertThat(result.grossEarnings()).isEqualByComparingTo(new BigDecimal("33600.00"));
        assertThat(result.grossTaxableIncome()).isEqualByComparingTo(new BigDecimal("33600.00"));
    }

    @Test
    void legalExecutionCannotBreakTwentyThousandBahtLivingFloor() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("25000.00"),
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("10000.00"),
            BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(),
            PayrollYearToDate.empty(),
            1
        ));

        assertThat(result.legalExecutionDeduction()).isEqualByComparingTo(new BigDecimal("4125.00"));
        assertThat(result.netPay()).isEqualByComparingTo(new BigDecimal("20000.00"));
    }

    @Test
    void nonTaxableIncomeIsExcludedFromTaxAndSsoButAddedBackToNet() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("30000.00"),
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("5000.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(),
            PayrollYearToDate.empty(),
            1
        ));

        // Non-taxable income (sheet column D) stays out of taxable earnings, tax base, and SSO.
        assertThat(result.grossEarnings()).isEqualByComparingTo(new BigDecimal("30000.00"));
        assertThat(result.grossTaxableIncome()).isEqualByComparingTo(new BigDecimal("30000.00"));
        assertThat(result.nonTaxableIncome()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(result.socialSecurity()).isEqualByComparingTo(new BigDecimal("875.00"));
        assertThat(result.withholdingTax()).isEqualByComparingTo(new BigDecimal("164.58"));
        // Net = 30,000 taxable - (875 SSO + 164.58 tax) + 5,000 non-taxable
        assertThat(result.netPay()).isEqualByComparingTo(new BigDecimal("33960.42"));
    }

    @Test
    void ssoWageBaseFloorsAtMinimumBaseForLowWages() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("1000.00"),
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(),
            PayrollYearToDate.empty(),
            12
        ));

        // Wage (1,000) is below the 1,650 SSO minimum base, so SSO is computed on the floor:
        // 1,650 x 5% = 82.50, not 1,000 x 5% = 50.00.
        assertThat(result.ssoWageBase()).isEqualByComparingTo(new BigDecimal("1650.00"));
        assertThat(result.socialSecurity()).isEqualByComparingTo(new BigDecimal("82.50"));
        assertThat(result.netPay()).isEqualByComparingTo(new BigDecimal("917.50"));
    }

    @Test
    void ssoStopsContributingOnceYearToDateCapIsNearlyReached() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("40000.00"),
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(),
            new PayrollYearToDate(BigDecimal.ZERO, new BigDecimal("10200.00"), BigDecimal.ZERO),
            12
        ));

        // Wage is well above the SSO ceiling (would normally contribute 17,500 x 5% = 875), but
        // only 300 remains of the 10,500 annual SSO cap (10,500 - 10,200 already withheld this year).
        assertThat(result.socialSecurity()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(result.netPay()).isEqualByComparingTo(new BigDecimal("39700.00"));
    }

    @Test
    void progressiveTaxIsZeroBelowTheFirstTaxableBracket() {
        assertThat(calculator.progressiveTax(new BigDecimal("100000.00"))).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void progressiveTaxAppliesFivePercentWithinFirstBracket() {
        // (200,000 - 150,000) x 5% = 2,500
        assertThat(calculator.progressiveTax(new BigDecimal("200000.00")))
            .isEqualByComparingTo(new BigDecimal("2500.00"));
    }

    @Test
    void progressiveTaxSumsAcrossMultipleBrackets() {
        // 150,000@5% + 200,000@10% + 100,000@15% = 7,500 + 20,000 + 15,000 = 42,500
        assertThat(calculator.progressiveTax(new BigDecimal("600000.00")))
            .isEqualByComparingTo(new BigDecimal("42500.00"));
    }

    @Test
    void progressiveTaxAtExactBracketBoundaryExcludesTheNextBracket() {
        // 150,000@5% + 200,000@10% + 250,000@15% + 250,000@20% = 7,500+20,000+37,500+50,000 = 115,000
        // Income sits exactly at the 1,000,000 boundary, so the 1,000,000-2,000,000 bracket contributes nothing.
        assertThat(calculator.progressiveTax(new BigDecimal("1000000.00")))
            .isEqualByComparingTo(new BigDecimal("115000.00"));
    }

    @Test
    void progressiveTaxAppliesTopMarginalRateAboveFiveMillion() {
        // Full brackets up to 5M: 7,500+20,000+37,500+50,000+250,000+900,000 = 1,265,000
        // Plus (6,000,000 - 5,000,000) x 35% = 350,000 -> total 1,615,000
        assertThat(calculator.progressiveTax(new BigDecimal("6000000.00")))
            .isEqualByComparingTo(new BigDecimal("1615000.00"));
    }

    @Test
    void retirementClusterSharesACombinedFiveHundredThousandCapAcrossRmfSsfAndPension() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("160000.00"),
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new PayrollTaxAllowanceInput(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("500000.00"), // RMF alone consumes the entire shared 500,000 cluster cap
                new BigDecimal("100000.00"), // SSF: well within its own 200,000 cap, but the cluster is empty
                new BigDecimal("50000.00"),  // Pension: well within its own 200,000 cap, but the cluster is empty
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
            ),
            PayrollYearToDate.empty(),
            1
        ));

        // RMF (500,000) alone exhausts the shared cluster cap, so SSF and pension contribute 0 to the
        // allowance total despite being within their own individual caps. Allowance total = personal
        // (60,000) + projected SSO allowance (10,500) + retirement cluster (500,000) = 570,500.
        assertThat(result.taxAllowanceTotal()).isEqualByComparingTo(new BigDecimal("570500.00"));
        assertThat(result.annualTax()).isEqualByComparingTo(new BigDecimal("177375.00"));
        assertThat(result.withholdingTax()).isEqualByComparingTo(new BigDecimal("14781.25"));
        assertThat(result.netPay()).isEqualByComparingTo(new BigDecimal("144343.75"));
    }

    @Test
    void familyAndInsuranceAllowancesClampToTheirRespectiveCaps() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("50000.00"),
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new PayrollTaxAllowanceInput(
                new BigDecimal("100000.00"), // spouse: capped at 60,000
                new BigDecimal("30000.00"),  // child: uncapped
                new BigDecimal("150000.00"), // parent care: capped at 120,000
                new BigDecimal("20000.00"),  // disabled care: uncapped
                new BigDecimal("80000.00"),  // maternity: capped at 60,000
                new BigDecimal("90000.00"),  // life insurance: within its own 100,000 cap
                new BigDecimal("20000.00"),  // health insurance: within its own 25,000 cap
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
            ),
            PayrollYearToDate.empty(),
            1
        ));

        // Family: 60,000 + 30,000 + 120,000 + 20,000 + 60,000 = 290,000 (each over-cap value clamped).
        // Life+health: 90,000 + 20,000 = 110,000, but the COMBINED cap is 100,000 even though each is
        // within its own individual sub-cap.
        // Total = personal (60,000) + SSO allowance (10,500) + family (290,000) + life/health (100,000) = 460,500.
        assertThat(result.taxAllowanceTotal()).isEqualByComparingTo(new BigDecimal("460500.00"));
        assertThat(result.annualTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.netPay()).isEqualByComparingTo(new BigDecimal("49125.00"));
    }

    @Test
    void yearToDateWithholdingTaxIsSubtractedBeforeSpreadingOverRemainingMonths() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("40000.00"),
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(),
            new PayrollYearToDate(new BigDecimal("200000.00"), BigDecimal.ZERO, new BigDecimal("2675.00")),
            6
        ));

        // Month 6 of the year (7 months remaining, including this one). Projected annual income =
        // 200,000 YTD + 40,000 x 7 = 480,000. Annual tax on that (after allowances) is 8,887.50; with
        // 2,675.00 already withheld YTD, only 6,212.50 remains, spread over the 7 remaining months.
        assertThat(result.projectedAnnualIncome()).isEqualByComparingTo(new BigDecimal("480000.00"));
        assertThat(result.annualTax()).isEqualByComparingTo(new BigDecimal("8887.50"));
        assertThat(result.withholdingTax()).isEqualByComparingTo(new BigDecimal("887.50"));
        assertThat(result.netPay()).isEqualByComparingTo(new BigDecimal("38237.50"));
    }

    @Test
    void multiDayUnpaidLeaveProratesDeductionAndFloorsTaxableIncomeAtZero() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("3000.00"),
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("31.00"), // more unpaid days than the daily-rate divisor (30) covers
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(),
            PayrollYearToDate.empty(),
            12
        ));

        // Daily rate = 3,000 / 30 = 100.00; 31 unpaid days -> deduction of 3,100.00, which exceeds
        // gross earnings (3,000). Taxable income floors at zero rather than going negative.
        assertThat(result.dailyRate()).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(result.unpaidLeaveDeduction()).isEqualByComparingTo(new BigDecimal("3100.00"));
        assertThat(result.grossTaxableIncome()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.netPay()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------------------------------
    // Reconciliation additions (2026-07-21, C3/C4) -- see PayrollExcelReconciliationTest for the
    // accountant's-workbook rationale. These are separate from that file (which must not be edited)
    // and cover the engine-level behaviour the reconciliation only demonstrates by example.
    // ------------------------------------------------------------------------------------------

    /**
     * Byte-identical regression: with every new (C3/C4) input at zero, the calculator must reproduce
     * exactly what it produced before the reconciliation change. This uses the legacy 12-arg
     * constructor (so it is exercised the same way the pre-existing tests exercise it) against a
     * fully-populated scenario that exercises allowances, unpaid leave, YTD and legal execution
     * together, and asserts every output field.
     */
    @Test
    void withEveryNewInputAtZeroTheCalculatorIsByteIdenticalToBeforeTheReconciliationChange() {
        PayrollCalculationInput legacyShapedInput = new PayrollCalculationInput(
            new BigDecimal("40000.00"),
            List.of(),
            new BigDecimal("2500.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("1.00"),
            new BigDecimal("1500.00"),
            new BigDecimal("12350.00"),
            BigDecimal.ZERO,
            new PayrollTaxAllowanceInput(
                BigDecimal.ZERO, new BigDecimal("30000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("15000.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
            ),
            PayrollYearToDate.empty(),
            1
        );
        PayrollCalculationInput explicitZeroInput = new PayrollCalculationInput(
            legacyShapedInput.baseSalary(), legacyShapedInput.specialPays(), legacyShapedInput.overtimePay(),
            legacyShapedInput.commissionPay(), legacyShapedInput.nonTaxableIncome(), legacyShapedInput.unpaidLeaveDays(),
            legacyShapedInput.studentLoanDeduction(), legacyShapedInput.legalExecutionRequested(),
            legacyShapedInput.otherPostTaxDeductions(), legacyShapedInput.taxAllowances(), legacyShapedInput.yearToDate(),
            legacyShapedInput.payrollMonthValue(),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollCalculation viaLegacyConstructor = calculator.calculate(legacyShapedInput);
        PayrollCalculation viaExplicitZeros = calculator.calculate(explicitZeroInput);

        assertThat(viaExplicitZeros.grossEarnings()).isEqualByComparingTo(viaLegacyConstructor.grossEarnings());
        assertThat(viaExplicitZeros.grossTaxableIncome()).isEqualByComparingTo(viaLegacyConstructor.grossTaxableIncome());
        assertThat(viaExplicitZeros.ssoWageBase()).isEqualByComparingTo(viaLegacyConstructor.ssoWageBase());
        assertThat(viaExplicitZeros.socialSecurity()).isEqualByComparingTo(viaLegacyConstructor.socialSecurity());
        assertThat(viaExplicitZeros.projectedAnnualIncome()).isEqualByComparingTo(viaLegacyConstructor.projectedAnnualIncome());
        assertThat(viaExplicitZeros.taxAllowanceTotal()).isEqualByComparingTo(viaLegacyConstructor.taxAllowanceTotal());
        assertThat(viaExplicitZeros.annualTax()).isEqualByComparingTo(viaLegacyConstructor.annualTax());
        assertThat(viaExplicitZeros.withholdingTax()).isEqualByComparingTo(viaLegacyConstructor.withholdingTax());
        assertThat(viaExplicitZeros.legalExecutionDeduction()).isEqualByComparingTo(viaLegacyConstructor.legalExecutionDeduction());
        assertThat(viaExplicitZeros.totalDeductions()).isEqualByComparingTo(viaLegacyConstructor.totalDeductions());
        assertThat(viaExplicitZeros.netPay()).isEqualByComparingTo(viaLegacyConstructor.netPay());

        // And the absolute figures still match what this exact scenario asserted before the change
        // (see the equivalent case earlier in this file / the original commit).
        assertThat(viaExplicitZeros.netPay()).isEqualByComparingTo(new BigDecimal("25906.25"));
    }

    @Test
    void directorRemunerationRaisesGrossAndTaxButNeverTouchesSocialSecurity() {
        PayrollCalculation withoutDirectorPay = calculator.calculate(director(BigDecimal.ZERO));
        PayrollCalculation withDirectorPay = calculator.calculate(director(new BigDecimal("150000.00")));

        // Director remuneration IS taxable (joins grossEarnings / grossTaxableIncome / annual tax)...
        assertThat(withDirectorPay.grossEarnings())
            .isEqualByComparingTo(withoutDirectorPay.grossEarnings().add(new BigDecimal("150000.00")));
        assertThat(withDirectorPay.grossTaxableIncome())
            .isEqualByComparingTo(withoutDirectorPay.grossTaxableIncome().add(new BigDecimal("150000.00")));
        assertThat(withDirectorPay.annualTax()).isGreaterThan(withoutDirectorPay.annualTax());
        // ...but it is NOT wages under the Social Security Act, so SSO is untouched either way.
        assertThat(withDirectorPay.socialSecurity()).isEqualByComparingTo(withoutDirectorPay.socialSecurity());
        assertThat(withDirectorPay.ssoWageBase()).isEqualByComparingTo(withoutDirectorPay.ssoWageBase());
    }

    /**
     * The real director profile, and the case that actually guards the SSO exclusion.
     *
     * <p>{@link #directorRemunerationRaisesGrossAndTaxButNeverTouchesSocialSecurity()} cannot catch a
     * regression here on its own: it uses a 30,000 base salary, which is already above the 17,500
     * wage ceiling, so social security is capped at 875 whether or not director remuneration is
     * folded into the base. Mutating {@code ssoWageBase} to include it leaves that test green.
     *
     * <p>In the accountant's workbook a director has column G filled and D/H (เงินเดือน) EMPTY —
     * there is no salary at all — and no social security row. With a zero base the cap no longer
     * hides anything: the contribution must be exactly zero, and folding 150,000 of director pay
     * into the SSO base would produce 875.
     */
    @Test
    void aDirectorWithNoSalaryPaysNoSocialSecurityAtAll() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            BigDecimal.ZERO, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 1,
            new BigDecimal("150000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(result.grossEarnings()).isEqualByComparingTo(new BigDecimal("150000.00"));
        assertThat(result.ssoWageBase()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.socialSecurity())
            .withFailMessage("a director has no wages, so no social security is due")
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private PayrollCalculationInput director(BigDecimal directorRemuneration) {
        return new PayrollCalculationInput(
            new BigDecimal("30000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 1,
            directorRemuneration, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
    }

    @Test
    void eachPreTaxDeductionReducesBothTaxableIncomeAndNetPay() {
        PayrollCalculation baseline = calculator.calculate(pretax(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        PayrollCalculation withWarningLetter = calculator.calculate(pretax(new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO));
        PayrollCalculation withCustomerReturn = calculator.calculate(pretax(BigDecimal.ZERO, new BigDecimal("1000.00"), BigDecimal.ZERO));
        PayrollCalculation withOtherPretax = calculator.calculate(pretax(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000.00")));

        for (PayrollCalculation withDeduction : List.of(withWarningLetter, withCustomerReturn, withOtherPretax)) {
            assertThat(withDeduction.grossTaxableIncome())
                .isEqualByComparingTo(baseline.grossTaxableIncome().subtract(new BigDecimal("1000.00")));
            assertThat(withDeduction.netPay()).isLessThan(baseline.netPay());
        }
    }

    /**
     * C4's whole point: a pre-tax deduction changes the tax base (and therefore withholding), while
     * an equal-sized post-tax deduction does not. Same 1,000 THB, same employee, different treatment.
     */
    @Test
    void aPreTaxDeductionIsTaxedDifferentlyFromTheSameAmountAsAPostTaxDeduction() {
        PayrollCalculationInput asPreTax = new PayrollCalculationInput(
            new BigDecimal("50000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000.00")
        );
        PayrollCalculationInput asPostTax = new PayrollCalculationInput(
            new BigDecimal("50000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000.00"),
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 1
        );

        PayrollCalculation preTaxResult = calculator.calculate(asPreTax);
        PayrollCalculation postTaxResult = calculator.calculate(asPostTax);

        assertThat(preTaxResult.grossTaxableIncome())
            .isEqualByComparingTo(postTaxResult.grossTaxableIncome().subtract(new BigDecimal("1000.00")));
        assertThat(preTaxResult.withholdingTax()).isLessThan(postTaxResult.withholdingTax());
        // Both deduct the same 1,000 from net pay, but the pre-tax version withholds less tax, so its
        // net pay ends up higher by exactly that tax difference -- the whole point of C4.
        assertThat(preTaxResult.netPay()).isGreaterThan(postTaxResult.netPay());
    }

    private PayrollCalculationInput pretax(BigDecimal warningLetter, BigDecimal customerReturn, BigDecimal otherPretax) {
        return new PayrollCalculationInput(
            new BigDecimal("40000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 1,
            BigDecimal.ZERO, warningLetter, customerReturn, otherPretax
        );
    }

    // ---- Cancel-after-close reversal, AUTO-REFUND (2026-07-23) ------------------------------

    @Test
    void legacySixteenArgConstructorDefaultsLeaveRefundDaysToZero() {
        PayrollCalculationInput legacy = new PayrollCalculationInput(
            new BigDecimal("40000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
        assertThat(legacy.leaveRefundDays()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * At a salary BELOW the SSO 17,500 ceiling, the refund visibly moves the SSO wage base --
     * proving it recomputes SSO, not just a flat add to net pay. Expected figures hand-derived
     * (dailyRate = 10000/30 = 333.3333; refund = 333.3333 x 2 = 666.67 after HALF_UP rounding).
     */
    @Test
    void leaveDeductionRefundAddsBackPreTaxIncomeAndRecomputesSso() {
        PayrollCalculation withoutRefund = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("10000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 8,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        ));
        PayrollCalculation withRefund = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("10000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 8,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2.00")
        ));

        assertThat(withoutRefund.ssoWageBase()).isEqualByComparingTo("10000.00");
        assertThat(withoutRefund.socialSecurity()).isEqualByComparingTo("500.00");
        assertThat(withoutRefund.netPay()).isEqualByComparingTo("9500.00");

        assertThat(withRefund.leaveRefundDays()).isEqualByComparingTo("2.00");
        assertThat(withRefund.leaveDeductionRefund()).isEqualByComparingTo("666.67");
        assertThat(withRefund.grossTaxableIncome()).isEqualByComparingTo("10666.67");
        assertThat(withRefund.ssoWageBase()).isEqualByComparingTo("10666.67");
        assertThat(withRefund.socialSecurity()).isEqualByComparingTo("533.33");
        assertThat(withRefund.netPay()).isEqualByComparingTo("10133.34");
        // Net pay rises by LESS than the raw 666.67 refund, because SSO also grew on the restored
        // income (500.00 -> 533.33, i.e. 33.33 more) -- proof the refund is genuinely taxed/SSO'd
        // like real income, not just handed back flat.
        assertThat(withRefund.netPay().subtract(withoutRefund.netPay()))
            .isEqualByComparingTo(new BigDecimal("633.34"));
    }

    /**
     * At a salary already at the SSO ceiling, the refund cannot move SSO further (proving the
     * existing [MIN,MAX] clamp protects against a refund pushing the base past 17,500), but DOES
     * visibly move withholding tax at this larger amount -- proving the refund recomputes tax too.
     */
    @Test
    void leaveDeductionRefundRecomputesWithholdingTaxWithoutBreachingTheSsoCeiling() {
        PayrollCalculation withoutRefund = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("80000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 6,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        ));
        PayrollCalculation withRefund = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("80000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 6,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5.00")
        ));

        assertThat(withoutRefund.annualTax()).isEqualByComparingTo("16887.50");
        assertThat(withoutRefund.withholdingTax()).isEqualByComparingTo("2412.50");

        assertThat(withRefund.leaveDeductionRefund()).isEqualByComparingTo("13333.33");
        assertThat(withRefund.grossTaxableIncome()).isEqualByComparingTo("93333.33");
        assertThat(withRefund.ssoWageBase()).isEqualByComparingTo("17500.00");
        assertThat(withRefund.socialSecurity()).isEqualByComparingTo(withoutRefund.socialSecurity());
        assertThat(withRefund.annualTax()).isEqualByComparingTo("26220.83");
        assertThat(withRefund.withholdingTax()).isEqualByComparingTo("3745.83");
        assertThat(withRefund.netPay()).isEqualByComparingTo("88712.50");
        assertThat(withRefund.netPay()).isGreaterThan(withoutRefund.netPay());
    }

    /**
     * A refund and this month's OWN new unpaid leave can coexist -- they are independent events
     * (a credit owed from a past cancelled leave vs. a fresh deduction this month) that combine
     * additively through the same pre-tax path, not one silently overriding the other.
     */
    @Test
    void leaveDeductionRefundNetsAgainstThisMonthsOwnUnpaidLeaveDeduction() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("80000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("3.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 6,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5.00")
        ));

        assertThat(result.unpaidLeaveDays()).isEqualByComparingTo("3.00");
        assertThat(result.unpaidLeaveDeduction()).isEqualByComparingTo("8000.00");
        assertThat(result.leaveRefundDays()).isEqualByComparingTo("5.00");
        assertThat(result.leaveDeductionRefund()).isEqualByComparingTo("13333.33");
        // Net pre-tax effect is +5,333.33 (13,333.33 refund - 8,000.00 new deduction).
        assertThat(result.grossTaxableIncome()).isEqualByComparingTo("85333.33");
        assertThat(result.netPay()).isEqualByComparingTo("81512.50");
    }

    // ---- Withholding-tax override (2026-07-24, V88) ----------------------------------------
    // GUARDRAIL under test: the override SUBSTITUTES only the final withheld amount; the entire
    // progressive-tax computation (annualTax, taxableAnnualIncome, projectedAnnualIncome) stays
    // EXACTLY as computed and is still reported, for transparency.

    /**
     * The substitution guard. With an override present, withheld tax equals the override and net pay
     * moves by exactly the difference from the computed withholding -- while annualTax /
     * taxableAnnualIncome / projectedAnnualIncome are byte-identical to the no-override run. This is
     * the test that goes red if the substitution line in {@link PayrollCalculator} is broken.
     */
    @Test
    void withholdingTaxOverrideSubstitutesTheWithheldAmountButLeavesTheComputationIntact() {
        PayrollCalculation computed = calculator.calculate(withWithholdingOverride(null));
        PayrollCalculation overridden = calculator.calculate(withWithholdingOverride(new BigDecimal("1000.00")));

        // The computed withholding for this scenario is a real, non-zero, non-1000 figure -- so a
        // substitution is genuinely observable rather than coincidentally equal.
        assertThat(computed.withholdingTax()).isGreaterThan(new BigDecimal("1000.00"));
        assertThat(computed.withholdingTaxOverride()).isNull();

        // Substituted: withheld tax is now exactly the override.
        assertThat(overridden.withholdingTax()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(overridden.withholdingTaxOverride()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(overridden.calculationNote()).contains("overridden by HR");

        // Computation intact: every progressive-tax figure is UNCHANGED from the computed run.
        assertThat(overridden.projectedAnnualIncome()).isEqualByComparingTo(computed.projectedAnnualIncome());
        assertThat(overridden.taxableAnnualIncome()).isEqualByComparingTo(computed.taxableAnnualIncome());
        assertThat(overridden.annualTax()).isEqualByComparingTo(computed.annualTax());
        assertThat(overridden.taxAllowanceTotal()).isEqualByComparingTo(computed.taxAllowanceTotal());
        assertThat(overridden.grossTaxableIncome()).isEqualByComparingTo(computed.grossTaxableIncome());
        assertThat(overridden.socialSecurity()).isEqualByComparingTo(computed.socialSecurity());

        // Net pay reflects the substituted withholding exactly: it rises by (computed - override),
        // because a smaller withheld tax leaves more take-home, and nothing else moved.
        BigDecimal expectedNet = computed.netPay().add(computed.withholdingTax()).subtract(new BigDecimal("1000.00"));
        assertThat(overridden.netPay()).isEqualByComparingTo(expectedNet);
        assertThat(overridden.totalDeductions())
            .isEqualByComparingTo(computed.totalDeductions().subtract(computed.withholdingTax()).add(new BigDecimal("1000.00")));
    }

    /**
     * Zero is a MEANINGFUL override (withhold nothing), distinct from "no override". A 0 override must
     * force withheld tax to 0 even though the computed withholding is positive -- proving the guard is
     * a null check, not a truthiness/sign test.
     */
    @Test
    void withholdingTaxOverrideOfZeroForcesZeroWithheldTax() {
        PayrollCalculation overridden = calculator.calculate(withWithholdingOverride(BigDecimal.ZERO));

        assertThat(overridden.withholdingTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(overridden.withholdingTaxOverride()).isEqualByComparingTo(BigDecimal.ZERO);
        // The projection is still computed and still positive -- only the withheld amount is zeroed.
        assertThat(overridden.annualTax()).isGreaterThan(BigDecimal.ZERO);
    }

    /**
     * Wrong-way / regression: a NULL override reproduces today's behaviour exactly. Every output field
     * of the null-override run equals the pre-override (17-arg, no withholding-override) run, and the
     * override marker is null (no note appended).
     */
    @Test
    void nullWithholdingTaxOverrideIsByteIdenticalToNoOverrideAtAll() {
        PayrollCalculation viaLegacyNoOverride = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("50000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        PayrollCalculation viaNullOverride = calculator.calculate(withWithholdingOverride(null));

        assertThat(viaNullOverride.withholdingTax()).isEqualByComparingTo(viaLegacyNoOverride.withholdingTax());
        assertThat(viaNullOverride.annualTax()).isEqualByComparingTo(viaLegacyNoOverride.annualTax());
        assertThat(viaNullOverride.projectedAnnualIncome()).isEqualByComparingTo(viaLegacyNoOverride.projectedAnnualIncome());
        assertThat(viaNullOverride.totalDeductions()).isEqualByComparingTo(viaLegacyNoOverride.totalDeductions());
        assertThat(viaNullOverride.netPay()).isEqualByComparingTo(viaLegacyNoOverride.netPay());
        assertThat(viaNullOverride.withholdingTaxOverride()).isNull();
        assertThat(viaNullOverride.calculationNote()).doesNotContain("overridden by HR");
    }

    /** Same fixed scenario (base 50,000, month 1, no allowances) with only the override varying. */
    private PayrollCalculationInput withWithholdingOverride(BigDecimal withholdingTaxOverride) {
        return new PayrollCalculationInput(
            new BigDecimal("50000.00"), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            withholdingTaxOverride
        );
    }
}
