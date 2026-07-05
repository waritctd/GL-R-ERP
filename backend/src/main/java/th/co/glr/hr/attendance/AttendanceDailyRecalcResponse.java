package th.co.glr.hr.attendance;

/** Result of an attendance daily recompute: how many employee-day records were (re)written. */
public record AttendanceDailyRecalcResponse(int recalculated) {}
