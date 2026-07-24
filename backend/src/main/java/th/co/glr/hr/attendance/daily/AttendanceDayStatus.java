package th.co.glr.hr.attendance.daily;

/**
 * The single headline state for an attendance day, for UIs that show one badge per row.
 * {@link AttendanceDayFlag} carries the full detail.
 */
public enum AttendanceDayStatus {
    /** No punches at all — rendered as "-". Never stored; derived at read time. */
    NO_RECORD,
    /** Both scans present, on time. */
    PRESENT,
    /** Both scans present, checked in past the grace threshold. */
    LATE,
    /**
     * Marked present with no punches at all — a CEO/HR stand-up roster or a WFH day. Never derived
     * from scanner data; see {@link AttendanceDailyRepository#upsertWfhPresent}.
     */
    WFH,
    MISSING_CHECK_IN,
    MISSING_CHECK_OUT,
    /** Outside the configured workdays. */
    NON_WORKDAY
}
