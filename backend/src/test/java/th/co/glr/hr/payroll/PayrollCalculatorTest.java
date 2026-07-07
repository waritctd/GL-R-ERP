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
}
