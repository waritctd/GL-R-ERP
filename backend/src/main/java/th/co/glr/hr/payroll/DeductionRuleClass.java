package th.co.glr.hr.payroll;

/**
 * WHO directed a deduction and under what source of authority (issue #376). Orthogonal to {@link
 * DeductionCapGroup} (WHICH limit, if any, applies) so the two questions are never conflated -- the
 * mistake #373 originally made when it assumed ม.76's cap governed กยศ. simply because both are
 * "statutory-sounding" deductions. See {@link PayrollDeductionClassification} for the full
 * kind-by-kind assignment with cited legal basis.
 */
public enum DeductionRuleClass {
    /**
     * A different law than the Labour Protection Act compels the deduction and dictates the
     * amount -- the employer's role is to execute and remit, never to compute or negotiate the
     * figure. ม.76 item (1) territory ("ภาษีเงินได้ ... หรือชำระเงินอื่นตามที่มีกฎหมายบัญญัติไว้"):
     * withholding tax, social security, กยศ.
     */
    STATUTORY_AUTHORITY_DIRECTED,
    /** A court or the เจ้าพนักงานบังคับคดี (Legal Execution Department) ordered the deduction. */
    COURT_OR_LED_ORDER,
    /**
     * One of ม.76's own items (2)-(5) -- union fees, cooperative/welfare debts, employee-caused
     * damages, agreed savings funds -- which is where that section's 10% per-item / 20% aggregate
     * limit actually applies (see {@link DeductionCapGroup#LABOUR_ACT_SECTION_76}).
     */
    LABOUR_ACT_CAPPED,
    /** The employee opted in; no statute or order compels it. Nothing in the current engine is
     * confidently classified here today -- see {@link PayrollDeductionClassification}'s class
     * javadoc. */
    VOLUNTARY,
    /**
     * The employer is recovering its own money from the employee (e.g. a documented internal
     * shortfall or an unearned amount already paid out) rather than remitting to a third party.
     */
    INTERNAL_RECOVERY
}
