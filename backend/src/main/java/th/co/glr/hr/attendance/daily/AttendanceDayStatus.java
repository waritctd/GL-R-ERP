package th.co.glr.hr.attendance.daily;

/** The single headline state for an attendance day, for UIs that show one badge per row. AttendanceDayFlag
 * carries the full detail. */
public enum AttendanceDayStatus {
    /** No punches at all — rendered as "-". Never stored; derived at read time. */
    NO_RECORD,
    /** On time. Normally both scans present; also covers a lone check-in on a schedule with requiresCheckOut =
     * false (V117, ฝ่ายขาย scan-in-only) — that is a complete, compliant day, not a missing one. */
    PRESENT,
    /** Checked in past the grace threshold. Normally both scans present; also covers a late lone check-in under the
     * same requiresCheckOut = false exemption as #PRESENT. */
    LATE,
    /** Marked present with no punches at all — a CEO/HR stand-up roster or a WFH day. Never derived from scanner
     * data; see AttendanceDailyRepository#upsertWfhPresent. */
    WFH,
    MISSING_CHECK_IN,
    /** A morning punch with no departure scan, on a schedule that requires one. Never assigned when
     * th.co.glr.hr.attendance.schedule.WorkSchedule#requiresCheckOut() is false (V117, ฝ่ายขาย) — that case is
     * #PRESENT/#LATE instead. */
    MISSING_CHECK_OUT,
    /** Outside the configured workdays. */
    NON_WORKDAY,
    /** A company holiday (นักขัตฤกษ์). Distinct from #NON_WORKDAY because it carries a different pay meaning for a
     * rostered ฝ่ายขาย shift — see AttendanceDayFlag#HOLIDAY. */
    HOLIDAY
}
