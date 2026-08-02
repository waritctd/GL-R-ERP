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
    /**
     * Marked present with no punches — a CEO/HR stand-up roster or WFH day. Reporting only, same as
     * every other status here; carries no pay effect of its own.
     */
    WFH,
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
    NON_WORKDAY,
    /**
     * The date is a company holiday (นักขัตฤกษ์ or other {@code hr.holiday} row), regardless of
     * what the employee's {@code WorkSchedule} would otherwise say. Late/early are not evaluated,
     * exactly as {@link #NON_WORKDAY} behaves — but kept as a distinct flag/status rather than
     * folded into {@code NON_WORKDAY}: a rostered นักขัตฤกษ์ shift by ฝ่ายขาย is OT-eligible (the
     * company pays overtime for those shifts specifically), while an ordinary Saturday scan by an
     * office worker on {@code NON_WORKDAY} is not. Collapsing the two would erase that difference
     * for anyone reading the daily table or building a payroll-adjacent report off it.
     */
    HOLIDAY
}
