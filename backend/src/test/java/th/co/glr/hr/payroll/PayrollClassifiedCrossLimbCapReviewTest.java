package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculation;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculationInput;

/**
 * REVIEW test (fix, 2026-09-26). {@link PayrollCalculator#calculateClassified} computes the three
 * ป.96/2543 limbs (regular, known, cumulative) and floors EACH ONE at zero independently against its
 * OWN prior year-to-date withholding on that same limb -- but never checks whether, ACROSS all three
 * limbs, the employee has already had more withheld this tax year than the year's TOTAL liability. A
 * salaried employee whose regular-limb withholding has already exceeded the year's FULL projected tax
 * (e.g. from a mid-year raise or allowance correction) still gets charged the entire tax on a
 * commission (cumulative limb) that lands later in the year, because {@code withholdCumulative} only
 * floors against ITS OWN prior cumulative-limb withholding -- never against what the regular limb
 * already over-collected. That is a double charge: the employee pays commission tax on top of a
 * regular-limb withholding that alone already covers the whole year.
 *
 * <p>The legacy single-limb {@link PayrollCalculator#calculate} already guards against exactly this
 * with its own {@code collectableThisYear} ceiling (see that method's comment, ~line 292); this test
 * proves the same ceiling now also holds for the classified engine that actually runs in production
 * (PayrollService#calculateLine calls {@code calculateClassified} exclusively).
 */
class PayrollClassifiedCrossLimbCapReviewTest {
    private final PayrollCalculator calculator = new PayrollCalculator();

    private static final BigDecimal MONTHLY_SALARY = new BigDecimal("30000.00");
    // 11 months already earned (Jan-Nov) at the same monthly salary, carried as this tax year's
    // regular-limb taxable income so far.
    private static final BigDecimal YTD_REGULAR_TAXABLE_INCOME = new BigDecimal("330000.00");
    private static final BigDecimal COMMISSION_THIS_PERIOD = new BigDecimal("200000.00");

    private PayrollClassifiedCalculationInput input(PayrollYearToDate yearToDate) {
        Map<PayrollComponent, BigDecimal> amounts = new EnumMap<>(PayrollComponent.class);
        amounts.put(PayrollComponent.SALARY, MONTHLY_SALARY);
        amounts.put(PayrollComponent.COMMISSION_PAY, COMMISSION_THIS_PERIOD);

        Map<PayrollComponent, PayrollTaxTreatment> treatments = new EnumMap<>(PayrollComponent.class);
        treatments.put(PayrollComponent.COMMISSION_PAY, PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);

        Map<PayrollComponent, Boolean> sso = new EnumMap<>(PayrollComponent.class);
        sso.put(PayrollComponent.SALARY, Boolean.TRUE);

        // payrollMonthValue = 12 (December) -> monthsRemaining = 1, so the regular limb's annual
        // reprojection divides by 1 and needs no rounding to reason about by hand.
        return new PayrollClassifiedCalculationInput(
            1L, "REV-CAP ทดสอบ", amounts, treatments, sso,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, false, BigDecimal.ZERO, null,
            PayrollTaxAllowanceInput.empty(), yearToDate, 12, 2026, null
        );
    }

    private static BigDecimal wholeBaht(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
    }

    @Test
    void regularLimbAlreadyOverWithheldMustNotBeDoubleChargedByTheCumulativeLimb() {
        // ---- Pass A: nothing withheld yet this year on any limb. This is the NORMAL case -- the fix
        // must not change it at all. Both limbs are individually within what's still owed, so the new
        // cross-limb ceiling never engages.
        PayrollYearToDate ytdBaseline = new PayrollYearToDate(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, YTD_REGULAR_TAXABLE_INCOME, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO
        );
        PayrollClassifiedCalculation baseline = calculator.calculateClassified(input(ytdBaseline));

        // Hand-derive the regular limb's own annual tax from the engine's OWN reported deduction
        // figures (same technique as PayrollClassifiedLimbClampReviewTest): regularAnnualProjection is
        // fully known from the inputs (YTD regular taxable income + this period's salary, times
        // monthsRemaining = 1), and taxExpenseDeduction/taxAllowanceTotal are read back off the result.
        BigDecimal regularAnnualProjection = YTD_REGULAR_TAXABLE_INCOME.add(MONTHLY_SALARY);
        BigDecimal netRegular = regularAnnualProjection
            .subtract(baseline.taxExpenseDeduction())
            .subtract(baseline.taxAllowanceTotal());
        BigDecimal annualTaxRegular = calculator.progressiveTax(netRegular.max(BigDecimal.ZERO));

        // No EXTRA_KNOWN_FREQUENCY component exists in this scenario, so annualTaxWithCumulativeYtd
        // (the internal figure the fix ceiling is measured against) collapses to the reported
        // annualTax() field exactly -- see the fix's own comment on why known-limb income must be zero
        // for that equivalence to hold.
        BigDecimal fullYearLiability = baseline.annualTax();

        BigDecimal expectedUncappedTotal = wholeBaht(annualTaxRegular.add(fullYearLiability.subtract(annualTaxRegular)));
        assertThat(baseline.withholdingTax())
            .withFailMessage(
                "normal (not over-withheld) case must be unchanged by the cross-limb cap: expected %s, got %s",
                expectedUncappedTotal, baseline.withholdingTax())
            .isEqualByComparingTo(expectedUncappedTotal);
        // Sanity: the commission really is taxed this period (otherwise this test would not be
        // exercising the cumulative limb at all).
        assertThat(baseline.withholdingTax()).isGreaterThan(BigDecimal.ZERO);

        // ---- Pass B: THE BUG. Same facts, except the employee has already had more withheld this
        // year (all of it on the regular limb) than the year's FULL liability -- fullYearLiability +
        // 5,000 guarantees this for BOTH limbs at once: it exceeds fullYearLiability itself (so the
        // cross-limb ceiling must fully engage), and since annualTaxRegular can never exceed
        // fullYearLiability (adding a positive commission on top of the same base can only raise
        // progressive tax, never lower it), it necessarily exceeds annualTaxRegular too (so the
        // regular limb is individually over-withheld against its own annual tax, matching the reported
        // real-world bug).
        BigDecimal alreadyWithheldYtd = fullYearLiability.add(new BigDecimal("5000.00"));
        PayrollYearToDate ytdOverWithheld = new PayrollYearToDate(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            alreadyWithheldYtd, YTD_REGULAR_TAXABLE_INCOME, alreadyWithheldYtd, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO
        );
        PayrollClassifiedCalculation overWithheld = calculator.calculateClassified(input(ytdOverWithheld));

        BigDecimal expectedTotal = fullYearLiability.subtract(alreadyWithheldYtd).max(BigDecimal.ZERO);
        assertThat(expectedTotal).isEqualByComparingTo(BigDecimal.ZERO); // sanity: this IS the fully-capped case

        assertThat(overWithheld.withholdingTax())
            .withFailMessage(
                "employee already over-withheld %s against a %s full-year liability must NOT be charged"
                    + " %s more on the commission -- expected the capped total %s",
                alreadyWithheldYtd, fullYearLiability, overWithheld.withholdingTax(), expectedTotal)
            .isEqualByComparingTo(wholeBaht(expectedTotal));

        // The persisted limb columns must still sum to the capped total -- PayrollService writes both
        // withholding_tax_regular_limb and withholding_tax_cumulative_limb, and a filing/report that
        // sums them must agree with the payslip's total.
        assertThat(overWithheld.withholdingTaxRegularLimb().add(overWithheld.withholdingTaxCumulativeLimb()))
            .withFailMessage("persisted limb columns must sum to the capped total")
            .isEqualByComparingTo(overWithheld.withholdingTax());
    }
}
