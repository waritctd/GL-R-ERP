package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REVIEWER-authored adversarial coverage for the ป.96/2543 withholding split (branch
 * fix/payroll-wht-po96-compliance). These are deliberately NOT the author's scenarios. */
class PayrollCalculatorPo96ReviewTest {
    private final PayrollCalculator calculator = new PayrollCalculator();

    private static final List<BigDecimal> NO_SPECIAL = List.of(
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    private PayrollCalculation run(
        BigDecimal baseSalary,
        List<BigDecimal> specialPays,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        PayrollTaxAllowanceInput allowances,
        PayrollYearToDate ytd,
        int month,
        int remainingPayPeriods
    ) {
        return calculator.calculate(new PayrollCalculationInput(
            baseSalary, specialPays, overtimePay, commissionPay,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            allowances, ytd, month,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, 2026, remainingPayPeriods, 0));
    }

    // Checks commission clawbacks do not reduce the month's taxable income.
    @Test
    @DisplayName("REVIEW: a commission clawback no longer reduces the month's taxable income")
    void aNegativeCommissionIsSwallowedByTheVariableLimbFloor() {
        PayrollCalculation clawback = run(
            new BigDecimal("50000.00"), NO_SPECIAL, BigDecimal.ZERO, new BigDecimal("-10000.00"),
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 6, 7);

        // The pre-split engine computed max(0, 50000 + (-10000)) = 40,000.00.
        assertThat(clawback.grossTaxableIncome())
            .as("a -10,000 clawback must still reduce assessable income to 40,000")
            .isEqualByComparingTo(new BigDecimal("40000.00"));
    }

}
