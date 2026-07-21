package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PayrollCalculatorTest {
    private final PayrollCalculator calculator = new PayrollCalculator();

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
        assertThat(result.projectedAnnualIncome()).isEqualByComparingTo(new BigDecimal("494000.04"));
        assertThat(result.taxAllowanceTotal()).isEqualByComparingTo(new BigDecimal("115500.00"));
        assertThat(result.annualTax()).isEqualByComparingTo(new BigDecimal("6425.00"));
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
}
