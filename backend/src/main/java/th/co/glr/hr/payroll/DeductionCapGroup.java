package th.co.glr.hr.payroll;

/**
 * WHICH statutory limit, if any, bounds a deduction (issue #376) -- orthogonal to {@link
 * DeductionRuleClass} (WHO directed it). A deduction can be {@code STATUTORY_AUTHORITY_DIRECTED}
 * and still carry {@code NONE} here (กยศ.: a law compels it, but that same law states no percentage
 * cap for payroll to enforce), which is exactly the distinction #373's original ม.76-on-กยศ. error
 * collapsed. See {@link PayrollDeductionClassification} for the kind-by-kind assignment.
 */
public enum DeductionCapGroup {
    /** No percentage/aggregate cap applies to this deduction under the current classification. */
    NONE,
    /**
     * ม.76's 10% per-item / 20% aggregate limit -- applies ONLY to that section's items (2)-(5)
     * (union fees, cooperative/welfare debts, employee-caused damages, agreed savings funds), never
     * to item (1) (tax and other legally-required payments). <b>NOT enforced by this system</b> --
     * see {@link PayrollDeductionOrderingPolicy} and the issue's own instruction not to encode a cap
     * without a confirmed per-row mapping.
     */
    LABOUR_ACT_SECTION_76,
    /**
     * ป.วิ.พ. ม.302's garnishment restriction. <b>Already enforced</b> via {@link
     * PayrollGarnishmentType} / {@link PayrollCalculator#calculateClassified} -- this cap group
     * names that existing machinery, it does not duplicate it.
     */
    LED_GARNISHMENT,
    /**
     * A court/LED order that states its own bespoke percentage or floor rather than following the
     * default {@link PayrollGarnishmentType} schedule. Defined for completeness; nothing in the
     * current engine is assigned this group -- every LED order observed so far is handled by the
     * default schedule. Available for a future order that genuinely differs.
     */
    CUSTOM_ORDER_DEFINED
}
