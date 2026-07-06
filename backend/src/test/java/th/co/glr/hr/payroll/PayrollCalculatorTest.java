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
            1,
            false
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
            6,
            false
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
            1,
            false
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
            1,
            false
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
    void directorCompensationIsExemptFromSocialSecurityButStillTaxed() {
        PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("150000.00"),
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
            1,
            true
        ));

        // Director's remuneration (ค่าตอบแทนกรรมการ) is not wages under the Social Security Act.
        assertThat(result.ssoWageBase()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.socialSecurity()).isEqualByComparingTo(BigDecimal.ZERO);
        // But it is still fully subject to normal progressive income tax, same as a salary.
        assertThat(result.withholdingTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.netPay()).isEqualByComparingTo(result.grossEarnings().subtract(result.withholdingTax()));
    }
}
