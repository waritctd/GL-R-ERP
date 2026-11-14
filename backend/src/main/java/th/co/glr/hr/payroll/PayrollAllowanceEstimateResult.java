package th.co.glr.hr.payroll;

import java.math.BigDecimal;

/** Result of PayrollService#estimateAllowanceEffect: the tax-effect estimate behind decision #4 of the
 * tax-allowance declaration plan ("declare + upload evidence + status + estimated 'what this saves me'"). */
public record PayrollAllowanceEstimateResult(
    boolean estimateAvailable,
    String unavailableReason,
    int taxYear,
    int effectiveMonth,
    BigDecimal baselineWithholdingTax,
    BigDecimal proposedWithholdingTax,
    // baselineWithholdingTax - proposedWithholdingTax; positive means the proposed declaration saves the employee
    // money relative to their current standing allowance.
    BigDecimal monthlyTaxSavings,
    BigDecimal baselineTaxAllowanceTotal,
    BigDecimal proposedTaxAllowanceTotal,
    String disclaimer
) {
}
