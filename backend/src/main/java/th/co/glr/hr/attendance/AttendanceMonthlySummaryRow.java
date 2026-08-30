package th.co.glr.hr.attendance;

import java.math.BigDecimal;

/**
 * One employee's aggregated row on the monthly summary sheet ({@code สรุปรายเดือน}) -- see
 * {@code AttendanceMonthlySummaryService#buildEmployeeRow} for how every field here is derived, and
 * that class's own javadoc for the ขาดงาน / ลา rules. Every count/sum here is folded from exactly
 * the {@code AttendanceDailyDto} rows {@code AttendanceDailyService#list} already returns for this
 * employee, plus the leave overlay -- never a second read against {@code attendance_daily}.
 *
 * <p><strong>§76:</strong> {@code lateMinutes}/{@code earlyLeaveMinutes} here are reporting signals
 * only, exactly as they are on {@code AttendanceDailyDto} itself -- see that record's javadoc. This
 * type carries no baht amount and must never grow one.
 */
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
