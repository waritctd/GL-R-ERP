package th.co.glr.hr.payroll;

/**
 * The three withholding-tax treatments a pay component can be given, per ป.96/2543 (the
 * Thai Revenue Department's withholding-tax calculation order). Owner decision 2026-07-29
 * (docs/agent-handoffs/118_feat-payroll-classification-and-hr-declarations.md, section 1):
 * HR classifies every pay component per employee, per tax year -- there is no company-wide
 * default except {@link #REGULAR_REPROJECT} being locked onto {@code SALARY}
 * (see {@code hr.payroll_component_tax_treatment}'s CHECK constraint, V95).
 *
 * <p>{@code null} at the storage layer means "not yet classified" and is a distinct, meaningful
 * state -- never coerce it to a default. A component with a non-zero amount in a given run and no
 * classification blocks that run (enforced in the service layer; this enum and its storage only
 * provide the vocabulary and the data).
 */
public enum PayrollTaxTreatment {
    /**
     * ป.96/2543 ข้อ 1(4): annualise this period's amount x จำนวนคราวที่ต้องจ่ายทั้งปี (the number of
     * times paid in the year), recomputing the annual projection every period. For salary and any
     * genuinely fixed, recurring allowance -- income the employee can count on every คราว.
     */
    REGULAR_REPROJECT,

    /**
     * ป.96/2543 ข้อ 1(5): multiply by the known count of occurrences in the year (1 for an annual
     * bonus), taxed as the difference this payment makes to the annual projection. For a bonus or
     * any other confirmed one-off payment whose frequency is known in advance.
     */
    EXTRA_KNOWN_FREQUENCY,

    /**
     * ป.96/2543 ข้อ 1(6): cumulative actual amount paid so far this year, less tax already withheld
     * on it. For overtime, irregular commission, or any component whose frequency and amount are not
     * known in advance.
     */
    EXTRA_CUMULATIVE_ACTUAL
}
