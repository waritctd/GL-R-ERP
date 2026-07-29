package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shape of mid-year over-withholding — written by review as a DEFECT PIN, RETRACTED once the
 * defect was fixed.
 *
 * <p>The defect: the ภ.ง.ด.91 notice printed unconditionally from the first period the excess went
 * positive, on a figure measured against that period's PROJECTED annual tax. Test 2 below is the case
 * that condemned it — a March commission spike whose mid-year peak exceeds December's actual excess,
 * so an employee was told a named sum was unrecoverable that payroll then returned by withholding less.
 *
 * <p>The notice is now gated on the final period of the year: December states the excess is final and
 * names ภ.ง.ด.91; earlier periods print a ประมาณการ that says later withholding may reduce it. These
 * tests still describe the underlying ARITHMETIC honestly — the excess really does appear in April and
 * really can shrink — which is why they were retracted rather than deleted. What they no longer claim
 * is that the payslip asserts it as final. That boundary is pinned by
 * {@code PayslipRendererTest} and {@code PayslipProvisionalNoticeGeometryReviewTest}, on the RENDERED
 * PDF; these assert on {@code PayrollCalculation} and therefore cannot see the gate at all.
 */
class PayrollExcessWithheldNoticeReviewTest {
    private final PayrollCalculator calculator = new PayrollCalculator();

    private record Month(int month, PayrollCalculation result) {}

    /** A whole tax year, carrying both limbs forward exactly as PayrollRepository's YTD query does. */
    private List<Month> year(IntFunction<BigDecimal> baseSalary, IntFunction<BigDecimal> commission) {
        BigDecimal regularIncome = BigDecimal.ZERO;
        BigDecimal variableIncome = BigDecimal.ZERO;
        BigDecimal sso = BigDecimal.ZERO;
        BigDecimal regularWh = BigDecimal.ZERO;
        BigDecimal variableWh = BigDecimal.ZERO;
        List<Month> months = new ArrayList<>();
        List<BigDecimal> noSpecial = List.of(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        for (int month = 1; month <= 12; month += 1) {
            PayrollCalculation result = calculator.calculate(new PayrollCalculationInput(
                baseSalary.apply(month), noSpecial, BigDecimal.ZERO, commission.apply(month),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                PayrollTaxAllowanceInput.empty(),
                new PayrollYearToDate(regularIncome, variableIncome, sso, regularWh, variableWh),
                month, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, 2026, 13 - month, 0));
            months.add(new Month(month, result));
            regularIncome = regularIncome.add(result.regularTaxableIncome());
            variableIncome = variableIncome.add(result.variableTaxableIncome());
            sso = sso.add(result.socialSecurity());
            regularWh = regularWh.add(result.regularWithholdingTax());
            variableWh = variableWh.add(result.variableWithholdingTax());
        }
        return months;
    }

    /**
     * DEFECT. ฿30,000 salary with a ฿300,000 commission in MARCH and NO commission afterwards. The
     * March projection annualises the spike, so March over-withholds; from April the projection
     * collapses and {@code excessWithheldToDate} turns positive.
     *
     * <p>The payslip then prints, in April, that the excess "ไม่สามารถคืนผ่านระบบเงินเดือนได้" and that
     * the employee should reclaim it on their ภ.ง.ด.91. Both statements are premature: the year has
     * not ended, the projection is still moving, and the figure the employee is told to claim is not
     * the figure they will actually be owed.
     */
    @Test
    @DisplayName("mid-year over-withholding is reported, and reported as provisional")
    void theNoticeFiresMidYearOnAProvisionalFigure() {
        List<Month> months = year(
            month -> new BigDecimal("30000.00"),
            month -> month == 3 ? new BigDecimal("300000.00") : BigDecimal.ZERO);

        List<Integer> noticeMonths = months.stream()
            .filter(month -> month.result().excessWithheldToDate().signum() > 0)
            .map(Month::month)
            .toList();

        assertThat(noticeMonths)
            .as("the excess is non-zero in every one of these months -- the payslip reports it, but as a ประมาณการ; only December names ภ.ง.ด.91 (see PayslipRendererTest)")
            .isNotEmpty();
        assertThat(noticeMonths.get(0))
            .withFailMessage(
                "DEFECT PINNED: the ภ.ง.ด.91 notice first prints in month %s (all of %s), nine "
                + "months before the tax year is settled and while the projection that produced "
                + "the figure is still moving. Gate it to the final period, or word it as "
                + "provisional. (If this now reads 12 the notice has been gated and this pin "
                + "should be replaced.)",
                noticeMonths.get(0), noticeMonths)
            .isEqualTo(4);
    }

    /**
     * DEFECT, sharper. A commission spike in MARCH followed by a RECOVERY later in the year: the
     * mid-year "excess" is entirely absorbed by months that withhold nothing, and December's excess
     * is a different, smaller number. Every notice printed before then was a false alarm about money
     * that payroll did in fact hand back — by not withholding it.
     */
    @Test
    @DisplayName("a mid-year excess can still be absorbed by later periods")
    void aMidYearExcessIsAbsorbedByLaterPeriods() {
        List<Month> months = year(
            month -> new BigDecimal("30000.00"),
            month -> switch (month) {
                case 3 -> new BigDecimal("300000.00");
                case 9, 10, 11, 12 -> new BigDecimal("60000.00");
                default -> BigDecimal.ZERO;
            });

        BigDecimal peakMidYear = months.stream()
            .limit(8)
            .map(month -> month.result().excessWithheldToDate())
            .reduce(BigDecimal.ZERO, BigDecimal::max);
        BigDecimal december = months.get(11).result().excessWithheldToDate();

        assertThat(peakMidYear)
            .as("a notice IS printed mid-year")
            .isGreaterThan(BigDecimal.ZERO);
        assertThat(december)
            .withFailMessage(
                "mid-year peak notice was %s but the year ends %s over — the employee was told to "
                + "reclaim money that payroll went on to return through reduced withholding",
                peakMidYear, december)
            .isLessThan(peakMidYear);
    }

    /**
     * The structural invariant the two withholding limbs are documented to satisfy — "regular +
     * variable = withholding_tax, always" (V92's own COMMENT ON) — held through both scenarios above,
     * including in the periods where the {@code collectableThisYear} ceiling binds.
     */
    @Test
    @DisplayName("the persisted withholding limbs sum to the remitted figure in every period")
    void theWithholdingLimbsAlwaysSumToTheRemittedFigure() {
        for (List<Month> run : List.of(
            year(month -> new BigDecimal("30000.00"),
                month -> month == 3 ? new BigDecimal("300000.00") : BigDecimal.ZERO),
            year(month -> new BigDecimal("30000.00"),
                month -> month % 2 == 0 ? new BigDecimal("120000.00") : BigDecimal.ZERO),
            year(month -> new BigDecimal("30000.00"),
                month -> month == 12 ? new BigDecimal("-40000.00") : new BigDecimal("20000.00")))) {
            for (Month month : run) {
                assertThat(month.result().regularWithholdingTax()
                        .add(month.result().variableWithholdingTax()))
                    .as("month %s: limbs must sum to withholdingTax", month.month())
                    .isEqualByComparingTo(month.result().withholdingTax());
                assertThat(month.result().regularTaxableIncome()
                        .add(month.result().variableTaxableIncome()))
                    .as("month %s: income limbs must sum to grossTaxableIncome", month.month())
                    .isEqualByComparingTo(month.result().grossTaxableIncome());
            }
        }
    }
}
