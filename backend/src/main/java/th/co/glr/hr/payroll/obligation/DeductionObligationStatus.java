package th.co.glr.hr.payroll.obligation;

/** Lifecycle of a hr.deduction_obligation row. #ACTIVE -- being drawn down every payroll period, clamped to the
 * remaining balance when instructedTotal is set. #COMPLETED -- cumulative remittance has reached
 * instructedTotal. */
public enum DeductionObligationStatus {
    ACTIVE,
    COMPLETED,
    STOPPED
}
