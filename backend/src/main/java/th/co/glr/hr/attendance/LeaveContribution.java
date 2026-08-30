package th.co.glr.hr.attendance;

import java.math.BigDecimal;

/**
 * One APPROVED leave request's contribution to one calendar date, for one employee -- the unit
 * {@code AttendanceMonthlySummaryService}'s leave-day attribution produces and
 * {@code AttendanceMonthlySummaryExporter} renders. See that service's "Leave-day attribution"
 * javadoc for how {@code fraction} is derived (always {@code 1.00} for a whole-day request on a
 * workday, the request's own {@code total_days} for a sub-day one).
 *
 * <p>A list of these, not a single value, sits behind each date in
 * {@code AttendanceMonthlySummaryService}'s per-(employee, date) ledger: two different APPROVED
 * leave types are not supposed to both cover the same date in practice, but nothing at the database
 * level forbids it, and silently dropping one would understate ลารวม for whichever employee it
 * happened to. Summing every contribution instead keeps ลารวม self-consistent by construction as
 * costs almost nothing extra.
 */
record LeaveContribution(String leaveTypeCode, String leaveTypeNameTh, BigDecimal fraction) {
}
