package th.co.glr.hr.payroll.obligation;

/**
 * Which third-party authority instructed the deduction (issue #373). Both share one shape: an
 * external authority dictates the amount and the employer executes and remits -- the system
 * records the instruction, it never computes one. Modelled as an enum (rather than a free-text
 * column) specifically so a future kind can be added without touching every call site that
 * switches on it exhaustively.
 */
public enum DeductionObligationKind {
    /** หัก กยศ. -- กองทุนเงินให้กู้ยืมเพื่อการศึกษา. No ม.76 10%/20% cap (see issue #373; that item is
     * item (1), the cap binds only items (2)-(5)). */
    STUDENT_LOAN,
    /** หักอายัดเงินเดือนตามหมายบังคับคดี -- กรมบังคับคดี. Composes with the ป.วิ.พ. ม.302 garnishment
     * cap already enforced in {@code PayrollCalculator#garnishmentDeduction}; this obligation's own
     * remaining-balance clamp does not replace that cap. */
    LEGAL_EXECUTION
}
