package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REVIEWER-authored, THIRD pass. The over-withholding is PERMANENT: withholdingTax is floored at zero, so
 * later periods can only stop withholding, never hand anything back, and December's true-up (ป.96 ข้อ 2 /
 * คำชี้แจง ข้อ 2.9) clamps at zero too. */
class PayrollCalculatorRegularLimbSpikeReviewTest {
    private final PayrollCalculator calculator = new PayrollCalculator();

    private record YearRun(BigDecimal totalWithheld, PayrollCalculation december) {}

    /** Runs a whole tax year, carrying both limbs forward exactly as PayrollRepository's year-to-date query does,
     * and checks the two structural invariants in every month. */
    private YearRun year(
        IntFunction<BigDecimal> baseSalary,
        IntFunction<BigDecimal> commission,
        IntFunction<BigDecimal> bonusInSpecial1,
        IntFunction<BigDecimal> directorRemuneration
    ) {
        BigDecimal regularIncome = BigDecimal.ZERO;
        BigDecimal variableIncome = BigDecimal.ZERO;
        BigDecimal sso = BigDecimal.ZERO;
        BigDecimal regularWh = BigDecimal.ZERO;
        BigDecimal variableWh = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        PayrollCalculation last = null;
        for (int month = 1; month <= 12; month += 1) {
            List<BigDecimal> specials = List.of(
                bonusInSpecial1.apply(month), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
                baseSalary.apply(month), specials, BigDecimal.ZERO, commission.apply(month),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                PayrollTaxAllowanceInput.empty(),
                new PayrollYearToDate(regularIncome, variableIncome, sso, regularWh, variableWh),
                month,
                directorRemuneration.apply(month),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, 2026, 13 - month, 0));

            assertThat(result.regularTaxableIncome().add(result.variableTaxableIncome()))
                .as("month %s: income limbs must sum to grossTaxableIncome", month)
                .isEqualByComparingTo(result.grossTaxableIncome());
            assertThat(result.regularWithholdingTax().add(result.variableWithholdingTax()))
                .as("month %s: withholding limbs must sum to withholdingTax", month)
                .isEqualByComparingTo(result.withholdingTax());
            assertThat(result.withholdingTax())
                .as("month %s: the remitted figure may never be negative", month)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);

            regularIncome = regularIncome.add(result.regularTaxableIncome());
            variableIncome = variableIncome.add(result.variableTaxableIncome());
            sso = sso.add(result.socialSecurity());
            regularWh = regularWh.add(result.regularWithholdingTax());
            variableWh = variableWh.add(result.variableWithholdingTax());
            total = total.add(result.withholdingTax());
            last = result;
        }
        return new YearRun(total, last);
    }

    private static final IntFunction<BigDecimal> NONE = month -> BigDecimal.ZERO;

    /** Documents over-withholding when a commission spike is projected as recurring income. */
    @Test
    @DisplayName("DEFECT: a spiky commission month over-withholds ฿17,527.51 and never gives it back")
    void aSpikyCommissionMonthPermanentlyOverWithholds() {
        YearRun run = year(
            month -> new BigDecimal("30000.00"),
            month -> month == 3 ? new BigDecimal("300000.00") : new BigDecimal("20000.00"),
            NONE, NONE);

        assertThat(run.december().annualTax())
            .as("the year's actual liability, unchanged by when the commission arrived")
            .isEqualByComparingTo("58925.00");
        assertThat(run.totalWithheld())
            .withFailMessage("DEFECT PINNED: expected the by-the-book spike over-withholding of "
                + "76,452.51; got %s. If this now equals 58,925.00 the defect is fixed.",
                run.totalWithheld())
            .isEqualByComparingTo("76452.51");
        assertThat(run.totalWithheld().subtract(run.december().annualTax()))
            .as("29.7%% more tax than the employee owes, recoverable only via ภ.ง.ด.91")
            .isEqualByComparingTo("17527.51");
        assertThat(run.december().excessWithheldToDate())
            .as("the engine KNOWS — it is now surfaced -- persisted to payroll_line and printed on the payslip")
            .isEqualByComparingTo("17527.51");
        assertThat(run.december().withholdingTax())
            .as("December cannot refund: ป.96 ข้อ 2's true-up is one-directional here")
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

}
