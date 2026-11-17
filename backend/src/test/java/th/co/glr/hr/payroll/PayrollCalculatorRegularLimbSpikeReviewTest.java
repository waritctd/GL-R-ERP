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

    private static final List<BigDecimal> NO_SPECIAL = List.of(
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

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

    /** The control. ฿30,000 salary + ฿20,000 commission every month lands on the year's liability to the satang — a
     * STABLE commission in the regular limb is handled perfectly. */
    @Test
    @DisplayName("control: a steady monthly commission lands exactly on the year's liability")
    void aSteadyCommissionReconcilesExactly() {
        YearRun run = year(
            month -> new BigDecimal("30000.00"),
            month -> new BigDecimal("20000.00"),
            NONE, NONE);

        assertThat(run.december().annualTax()).isEqualByComparingTo("20450.00");
        assertThat(run.totalWithheld()).isEqualByComparingTo(run.december().annualTax());
    }

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

    /** The same money paid as a เงินโบนัส in พิเศษ 1 — the VARIABLE limb — instead of as commission. ป.96 ข้อ 1(5)
     * applies, nothing is annualised, and the year lands exactly on the liability. */
    @Test
    @DisplayName("DEFECT contrast: the identical money in the VARIABLE limb reconciles to the satang")
    void theSameSpikeInTheVariableLimbReconcilesExactly() {
        YearRun run = year(
            month -> new BigDecimal("30000.00"),
            month -> new BigDecimal("20000.00"),
            month -> month == 3 ? new BigDecimal("280000.00") : BigDecimal.ZERO,
            NONE);

        assertThat(run.december().annualTax()).isEqualByComparingTo("58925.00");
        assertThat(run.totalWithheld())
            .as("variable-limb treatment of the identical income is exact")
            .isEqualByComparingTo("58925.00");
    }

    /** Documents over-withholding when one-off director remuneration is projected as recurring income. */
    @Test
    @DisplayName("DEFECT: a one-off director remuneration over-withholds ฿107,117.87 permanently")
    void aOneOffDirectorRemunerationPermanentlyOverWithholds() {
        YearRun run = year(
            month -> new BigDecimal("50000.00"),
            NONE, NONE,
            month -> month == 6 ? new BigDecimal("1200000.00") : BigDecimal.ZERO);

        assertThat(run.december().annualTax())
            .as("actual liability on 600,000 salary + 1,200,000 director remuneration")
            .isEqualByComparingTo("272375.00");
        assertThat(run.totalWithheld())
            .withFailMessage("DEFECT PINNED: expected 379,492.87 withheld; got %s", run.totalWithheld())
            .isEqualByComparingTo("379492.87");
        assertThat(run.totalWithheld().subtract(run.december().annualTax()))
            .isEqualByComparingTo("107117.87");
    }

    /** A late spike is less damaging but not harmless — a November commission spike still strands ฿6,045.84. The
     * magnitude scales with the periods remaining, which is why January is the worst month to be paid a large
     * commission and December the safest. */
    @Test
    @DisplayName("DEFECT: the damage scales with periods remaining — January is the worst month to be paid")
    void theOverWithholdingScalesWithThePeriodsRemaining() {
        BigDecimal january = year(
            month -> new BigDecimal("30000.00"),
            month -> month == 1 ? new BigDecimal("300000.00") : new BigDecimal("20000.00"),
            NONE, NONE).totalWithheld().subtract(new BigDecimal("58925.00"));
        BigDecimal november = year(
            month -> new BigDecimal("30000.00"),
            month -> month == 11 ? new BigDecimal("300000.00") : new BigDecimal("20000.00"),
            NONE, NONE).totalWithheld().subtract(new BigDecimal("58925.00"));

        assertThat(january).isEqualByComparingTo("16229.17");
        assertThat(november).isEqualByComparingTo("6045.84");
        assertThat(january).isGreaterThan(november);
    }
}
