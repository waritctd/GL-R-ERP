package th.co.glr.hr.payroll;

/** Every deduction PayrollCalculator actually applies today, enumerated from the code (issue #376 is explicit:
 * enumerate from PayrollCalculator/hr.payroll_line/PayrollClassifiedCalculation, not from the issue's own
 * indicative table). */
public enum PayrollDeductionKind {
    /** ภาษีเงินได้บุคคลธรรมดา หัก ณ ที่จ่าย -- {@code hr.payroll_line.withholding_tax}. */
    WITHHOLDING_TAX,
    /** เงินสมทบประกันสังคม (employee's own 5%) -- {@code hr.payroll_line.social_security}. */
    SOCIAL_SECURITY,
    /** หัก กยศ. -- hr.payroll_line.student_loan_deduction,
     * th.co.glr.hr.payroll.obligation.DeductionObligationKind#STUDENT_LOAN. */
    STUDENT_LOAN,
    /** หักอายัดเงินเดือนตามหมายบังคับคดี -- hr.payroll_line.legal_execution_deduction, PayrollGarnishmentType,
     * th.co.glr.hr.payroll.obligation.DeductionObligationKind#LEGAL_EXECUTION. */
    LEGAL_EXECUTION_GARNISHMENT,
    /** หักตามใบเตือน -- hr.payroll_line.warning_letter_deduction. Post-tax only (handoff section 6, see
     * PayrollCalculator#calculateClassified). */
    WARNING_LETTER,
    /** หัก 6 ลูกค้าคืนสินค้า -- {@code hr.payroll_line.customer_return_deduction}. */
    CUSTOMER_RETURN,
    /** หักอื่นๆ ก่อนภาษี -- hr.payroll_line.other_pretax_deduction. Free-typed HR catch-all with no sub-typing. */
    OTHER_PRETAX,
    /** หักอื่นๆ หลังภาษี -- hr.payroll_line.other_post_tax_deductions. Free-typed HR catch-all with no sub-typing. */
    OTHER_POST_TAX
}
