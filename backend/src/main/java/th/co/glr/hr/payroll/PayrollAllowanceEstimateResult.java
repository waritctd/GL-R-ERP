package th.co.glr.hr.payroll;

import java.math.BigDecimal;

/**
 * Result of {@link PayrollService#estimateAllowanceEffect}: the tax-effect estimate behind decision
 * #4 of the tax-allowance declaration plan ("declare + upload evidence + status + estimated 'what
 * this saves me'"). Runs the REAL {@link PayrollCalculator} twice (baseline vs proposed
 * allowances) for one employee/month and reports the delta — never a reimplementation of Thai tax
 * math anywhere else.
 *
 * <p>Three failure modes are reported honestly via {@code estimateAvailable = false} plus a Thai
 * {@code unavailableReason}, rather than a fabricated number:
 * <ul>
 *   <li>{@code PayrollCalculator#calculateClassified} 409s on an unclassified non-zero component —
 *       caught, never papered over with a synthesized treatment.</li>
 *   <li>A daily-rate employee ({@code payType == 'D'}) has no {@code daysWorked} outside a real
 *       run, so gross would be a meaningless ฿0.</li>
 *   <li>An employee not found among active employees at all.</li>
 * </ul>
 * Zero/absent year-to-date figures do NOT trigger unavailability — the estimate proceeds, with
 * {@link #disclaimer} covering the caveat that this is an estimate, not the figure a real run would
 * necessarily reproduce if anything else about the employee's situation changes before then.
 */
public record PayrollAllowanceEstimateResult(
    boolean estimateAvailable,
    String unavailableReason,
    int taxYear,
    int effectiveMonth,
    BigDecimal baselineWithholdingTax,
    BigDecimal proposedWithholdingTax,
    // baselineWithholdingTax - proposedWithholdingTax; positive means the proposed declaration
    // saves the employee money relative to their current standing allowance.
    BigDecimal monthlyTaxSavings,
    BigDecimal baselineTaxAllowanceTotal,
    BigDecimal proposedTaxAllowanceTotal,
    String disclaimer
) {
}
