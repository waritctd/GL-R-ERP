package th.co.glr.hr.payroll;

/**
 * Every deduction {@link PayrollCalculator} actually applies today, enumerated from the code
 * (issue #376 is explicit: enumerate from {@code PayrollCalculator}/{@code hr.payroll_line}/{@code
 * PayrollClassifiedCalculation}, not from the issue's own indicative table). One value per {@code
 * hr.payroll_line} deduction column or calculator-computed deduction -- mirrors {@link
 * PayrollComponent}'s own javadoc pattern ("one value per column"), but for deductions instead of
 * earned/taxable income.
 *
 * <p><b>Deliberately excluded: {@code unpaid_leave_deduction} / {@code leave_deduction_refund}.</b>
 * These are not a third-party-directed or capped "deduction" in the ม.76/ป.วิ.พ. sense at all -- they
 * are an adjustment of EARNED pay for days not worked (and its symmetric refund), computed from
 * {@code dailyRate * days}, with no external authority and no statutory cap. Classifying them here
 * would misrepresent them as belonging to the same taxonomy as กยศ./garnishment/etc.
 *
 * <p><b>Deliberately excluded: {@code tax_expense_deduction} / allowance figures.</b> These are
 * intermediate figures INSIDE the withholding-tax computation (ค่าใช้จ่าย, ค่าลดหย่อน), not a
 * deduction taken from net pay -- {@link #WITHHOLDING_TAX} is the actual deduction they feed into.
 */
public enum PayrollDeductionKind {
    /** ภาษีเงินได้บุคคลธรรมดา หัก ณ ที่จ่าย -- {@code hr.payroll_line.withholding_tax}. */
    WITHHOLDING_TAX,
    /** เงินสมทบประกันสังคม (employee's own 5%) -- {@code hr.payroll_line.social_security}. */
    SOCIAL_SECURITY,
    /** หัก กยศ. -- {@code hr.payroll_line.student_loan_deduction}, {@link
     * th.co.glr.hr.payroll.obligation.DeductionObligationKind#STUDENT_LOAN}. */
    STUDENT_LOAN,
    /** หักอายัดเงินเดือนตามหมายบังคับคดี -- {@code hr.payroll_line.legal_execution_deduction}, {@link
     * PayrollGarnishmentType}, {@link th.co.glr.hr.payroll.obligation.DeductionObligationKind#LEGAL_EXECUTION}. */
    LEGAL_EXECUTION_GARNISHMENT,
    /** หักตามใบเตือน -- {@code hr.payroll_line.warning_letter_deduction}. Post-tax only (handoff
     * section 6, see {@link PayrollCalculator#calculateClassified}). */
    WARNING_LETTER,
    /** หัก 6 ลูกค้าคืนสินค้า -- {@code hr.payroll_line.customer_return_deduction}. */
    CUSTOMER_RETURN,
    /** หักอื่นๆ ก่อนภาษี -- {@code hr.payroll_line.other_pretax_deduction}. Free-typed HR catch-all
     * with no sub-typing. */
    OTHER_PRETAX,
    /** หักอื่นๆ หลังภาษี -- {@code hr.payroll_line.other_post_tax_deductions}. Free-typed HR catch-all
     * with no sub-typing. */
    OTHER_POST_TAX
}
