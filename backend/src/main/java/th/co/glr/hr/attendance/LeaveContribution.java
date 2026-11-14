package th.co.glr.hr.attendance;

import java.math.BigDecimal;

/** One APPROVED leave request's contribution to one calendar date, for one employee -- the unit
 * AttendanceMonthlySummaryService's leave-day attribution produces and AttendanceMonthlySummaryExporter
 * renders. */
record LeaveContribution(String leaveTypeCode, String leaveTypeNameTh, BigDecimal fraction) {
}
