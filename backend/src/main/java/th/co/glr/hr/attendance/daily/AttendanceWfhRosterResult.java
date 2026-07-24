package th.co.glr.hr.attendance.daily;

/**
 * Outcome of reconciling one date's CEO/HR stand-up (WFH) roster — see
 * {@link AttendanceDailyService#setWfhRoster}.
 *
 * @param markedCount employees marked present or refreshed for the date — the whole submitted
 *                     roster, since the manual mark is authoritative and supersedes any existing
 *                     day, including a scanner-derived one (see
 *                     {@link AttendanceDailyRepository#upsertWfhPresent})
 * @param clearedCount previously-marked WFH rows removed because they were not resubmitted
 */
public record AttendanceWfhRosterResult(int markedCount, int clearedCount) {
}
