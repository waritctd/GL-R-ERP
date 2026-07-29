package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculation;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculationInput;

/**
 * REVIEW test (task 2, 2026-07-29). The three-stage layering in {@link
 * PayrollCalculator#calculateClassified} clamps the REGULAR limb's taxable figure at zero
 * ({@code taxableRegularOnly = (regularAnnualProjection - expense - allowances).max(ZERO)}) and then
 * stacks the KNOWN and CUMULATIVE limbs on top of the clamped value. When an employee's allowances
 * plus expense deduction exceed their REGULAR limb alone -- a modest salary with real declared
 * allowances, plus a large irregular commission -- the unused allowance headroom is destroyed by the
 * clamp instead of sheltering the other two limbs.
 *
 * <p>The invariant asserted here is the one the whole engine exists to satisfy and is independent of
 * how the limbs are layered: total annual taxable income must be
 * {@code projectedAnnualIncome - taxExpenseDeduction - taxAllowanceTotal}, using the engine's OWN
 * reported figures for each term. Nothing about ป.96's three-limb split changes that identity --
 * the limbs decide WHEN tax is withheld, never how much annual income is taxable.
 */
class PayrollClassifiedLimbClampReviewTest {
    private final PayrollCalculator calculator = new PayrollCalculator();

    @Test
    void allowanceHeadroomUnusedByTheRegularLimbMustStillShelterTheCumulativeLimb() {
        Map<PayrollComponent, BigDecimal> amounts = new EnumMap<>(PayrollComponent.class);
        amounts.put(PayrollComponent.SALARY, new BigDecimal("15000.00"));
        // A closing month for a sales rep: one large irregular commission, ป.96 ข้อ 1(6).
        amounts.put(PayrollComponent.COMMISSION_PAY, new BigDecimal("400000.00"));

        Map<PayrollComponent, PayrollTaxTreatment> treatments = new EnumMap<>(PayrollComponent.class);
        treatments.put(PayrollComponent.COMMISSION_PAY, PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);

        Map<PayrollComponent, Boolean> sso = new EnumMap<>(PayrollComponent.class);
        sso.put(PayrollComponent.SALARY, Boolean.TRUE);

        // Ordinary, entirely legal declarations: spouse 60,000 + life 100,000 + home-loan interest
        // 100,000. Together with the 60,000 personal allowance these exceed the 180,000 annualised
        // salary on their own.
        PayrollTaxAllowanceInput allowances = new PayrollTaxAllowanceInput(
            new BigDecimal("60000.00"),  // spouse
            BigDecimal.ZERO,             // child
            BigDecimal.ZERO,             // parent care (baht)
            BigDecimal.ZERO,             // disabled care
            BigDecimal.ZERO,             // maternity
            new BigDecimal("100000.00"), // life insurance
            BigDecimal.ZERO,             // health insurance
            BigDecimal.ZERO,             // parent health
            BigDecimal.ZERO,             // rmf
            BigDecimal.ZERO,             // ssf
            BigDecimal.ZERO,             // pension
            BigDecimal.ZERO,             // thai esg
            new BigDecimal("100000.00"), // home loan interest
            BigDecimal.ZERO,             // education donation
            BigDecimal.ZERO,             // general donation
            BigDecimal.ZERO,             // political donation
            0                            // parent care count
        );

        PayrollClassifiedCalculation result = calculator.calculateClassified(new PayrollClassifiedCalculationInput(
            1L, "REV-001 ทดสอบ", amounts, treatments, sso,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, false, BigDecimal.ZERO, null,
            allowances, PayrollYearToDate.empty(), 1, 2026, null
        ));

        // The identity, using only figures the engine itself reports.
        BigDecimal expectedTaxable = result.projectedAnnualIncome()
            .subtract(result.taxExpenseDeduction())
            .subtract(result.taxAllowanceTotal())
            .max(BigDecimal.ZERO);

        assertThat(result.taxableAnnualIncome())
            .withFailMessage(
                "annual taxable must be projected(%s) - expense(%s) - allowances(%s) = %s, but the engine reports %s"
                    + " -- allowance headroom the REGULAR limb could not absorb was destroyed by the .max(ZERO) clamp",
                result.projectedAnnualIncome(), result.taxExpenseDeduction(), result.taxAllowanceTotal(),
                expectedTaxable, result.taxableAnnualIncome())
            .isEqualByComparingTo(expectedTaxable);

        assertThat(result.annualTax())
            .withFailMessage("annual tax must follow from the correct annual taxable income")
            .isEqualByComparingTo(calculator.progressiveTax(expectedTaxable));
    }

    /**
     * REVIEW test: the legacy {@link PayrollCalculator#calculate} derives the SSO wage base from
     * {@code baseSalary - unpaidLeaveDeduction + leaveDeductionRefund}, so an employee on unpaid
     * leave contributes on wages actually paid (and the 2026-07-23 cancel-after-close auto-refund
     * restores the contribution). {@code calculateClassified} sums raw component amounts with no
     * leave adjustment, so the contribution is computed on the nominal salary instead.
     */
    @Test
    void unpaidLeaveMustStillReduceTheSsoWageBase() {
        Map<PayrollComponent, BigDecimal> amounts = new EnumMap<>(PayrollComponent.class);
        amounts.put(PayrollComponent.SALARY, new BigDecimal("15000.00"));
        Map<PayrollComponent, Boolean> sso = new EnumMap<>(PayrollComponent.class);
        sso.put(PayrollComponent.SALARY, Boolean.TRUE);

        // 10 unpaid days on a 30-day divisor: 5,000.00 of salary is not paid at all.
        PayrollClassifiedCalculation result = calculator.calculateClassified(new PayrollClassifiedCalculationInput(
            1L, "REV-002 ทดสอบ", amounts, new EnumMap<>(PayrollComponent.class), sso,
            new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, false, BigDecimal.ZERO, null,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 1, 2026, null
        ));

        // Legacy engine, same facts: ssoWageBase(15,000 - 5,000) = 10,000 -> 500.00.
        PayrollCalculation legacy = calculator.calculate(new PayrollCalculationInput(
            new BigDecimal("15000.00"), java.util.List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null
        ));

        assertThat(result.ssoWageBase())
            .withFailMessage("SSO wage base must be wages actually paid (legacy engine: %s), engine reports %s",
                legacy.ssoWageBase(), result.ssoWageBase())
            .isEqualByComparingTo(legacy.ssoWageBase());
        assertThat(result.socialSecurity()).isEqualByComparingTo(legacy.socialSecurity());
    }
}
