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
        assertThat(result.socialSecurity()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(result.projectedAnnualIncome()).isEqualByComparingTo(new BigDecimal("494000.04"));
        assertThat(result.taxAllowanceTotal()).isEqualByComparingTo(new BigDecimal("114000.00"));
        assertThat(result.annualTax()).isEqualByComparingTo(new BigDecimal("6500.00"));
        assertThat(result.withholdingTax()).isEqualByComparingTo(new BigDecimal("541.67"));
        assertThat(result.legalExecutionDeduction()).isEqualByComparingTo(new BigDecimal("12350.00"));
        assertThat(result.netPay()).isEqualByComparingTo(new BigDecimal("26025.00"));
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
            new BigDecimal("10000.00"),
            BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(),
            PayrollYearToDate.empty(),
            1
        ));

        assertThat(result.legalExecutionDeduction()).isEqualByComparingTo(new BigDecimal("4250.00"));
        assertThat(result.netPay()).isEqualByComparingTo(new BigDecimal("20000.00"));
    }
}
