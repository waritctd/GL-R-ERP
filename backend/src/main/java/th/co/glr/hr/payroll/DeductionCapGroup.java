package th.co.glr.hr.payroll;

/** WHICH statutory limit, if any, bounds a deduction (issue #376) -- orthogonal to DeductionRuleClass (WHO
 * directed it). error collapsed. See PayrollDeductionClassification for the kind-by-kind assignment. */
public enum DeductionCapGroup {
    /** No percentage/aggregate cap applies to this deduction under the current classification. */
    NONE,
    /** ม.76's 10% per-item / 20% aggregate limit -- applies ONLY to that section's items (2)-(5) (union fees,
     * cooperative/welfare debts, employee-caused damages, agreed savings funds), never to item (1) (tax and other
     * legally-required payments). */
    LABOUR_ACT_SECTION_76,
    /** ป.วิ.พ. ม.302's garnishment restriction. Already enforced via PayrollGarnishmentType /
     * PayrollCalculator#calculateClassified -- this cap group names that existing machinery, it does not duplicate
     * it. */
    LED_GARNISHMENT,
    /** A court/LED order that states its own bespoke percentage or floor rather than following the default
     * PayrollGarnishmentType schedule. Defined for completeness; nothing in the current engine is assigned this
     * group -- every LED order observed so far is handled by the default schedule. */
    CUSTOM_ORDER_DEFINED
}
