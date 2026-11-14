package th.co.glr.hr.attendance.daily;

/** Outcome of reconciling one date's CEO/HR stand-up (WFH) roster — see AttendanceDailyService#setWfhRoster. */
public record AttendanceWfhRosterResult(int markedCount, int clearedCount) {
}
