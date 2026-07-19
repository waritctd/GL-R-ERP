package th.co.glr.hr.attendance.daily;

/**
 * Notable conditions on one attendance day. A day may carry several at once (late in <em>and</em>
 * early out).
 *
 * <p><strong>§76:</strong> {@link #LATE} and {@link #EARLY_LEAVE} are reporting signals only. Thai
 * Labour Protection Act §76 forbids deducting wages as a penalty for lateness or absence, so these
 * must never be turned into a pay deduction.
 */
public enum AttendanceDayFlag {
    /** Checked in after work-start plus the grace threshold. */
    LATE,
    /** Checked out before work-end. */
    EARLY_LEAVE,
    /** Only an afternoon punch exists — the arrival scan is missing. */
    MISSING_CHECK_IN,
    /** Only a morning punch exists — the departure scan is missing. */
    MISSING_CHECK_OUT,
    /** An APPROVED overtime request covers this day. This is the only pay-relevant flag. */
    OVERTIME_APPROVED,
    /**
     * Left well after work-end with no approved overtime. Deliberately distinct from
     * {@link #OVERTIME_APPROVED}: overtime pay requires approval, so labelling an unapproved late
     * stay as overtime would promise money payroll will not produce.
     */
    WORKED_LATE_UNAPPROVED,
    /** The date falls outside the configured workdays; late/early are not evaluated. */
    NON_WORKDAY
}
