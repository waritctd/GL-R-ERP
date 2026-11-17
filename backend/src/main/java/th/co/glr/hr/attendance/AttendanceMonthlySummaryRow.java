package th.co.glr.hr.attendance;

import java.math.BigDecimal;

/** One employee's aggregated row on the monthly summary sheet (สรุปรายเดือน) -- see
 * AttendanceMonthlySummaryService#buildEmployeeRow for how every field here is derived, and that class's own
 * javadoc for the ขาดงาน / ลา rules. */
record AttendanceMonthlySummaryRow(
    long employeeId,
    String employeeCode,
    String employeeName,
    String nickName,
    String positionTh,
    int calendarWorkdays,
    int daysPresent,
    int lateCount,
    int lateMinutes,
    int earlyLeaveCount,
    int earlyLeaveMinutes,
    int missingCheckInCount,
    int missingCheckOutCount,
    BigDecimal sickDays,
    BigDecimal personalDays,
    BigDecimal vacationDays,
    BigDecimal unpaidLeaveDays,
    BigDecimal otherLeaveDays,
    BigDecimal totalLeaveDays,
    int absentDays,
    BigDecimal totalHours,
    BigDecimal approvedOtHours
) {
}
