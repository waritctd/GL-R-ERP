package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculation;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculationInput;

/** REVIEW test (task 2, 2026-07-29). Nothing about ป.96's three-limb split changes that identity -- the limbs
 * decide WHEN tax is withheld, never how much annual income is taxable. */
class PayrollClassifiedLimbClampReviewTest {
    private final PayrollCalculator calculator = new PayrollCalculator();

    /** REVIEW test: the legacy PayrollCalculator#calculate derives the SSO wage base from baseSalary -
     * unpaidLeaveDeduction + leaveDeductionRefund, so an employee on unpaid leave contributes on wages actually
     * paid (and the 2026-07-23 cancel-after-close auto-refund restores the contribution). */
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
