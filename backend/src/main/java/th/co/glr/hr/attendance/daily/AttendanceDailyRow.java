package th.co.glr.hr.attendance.daily;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A raw row from the day-range query, before status/flags are derived.
 *
 * <p>{@code hasRecord} distinguishes "we know they did not scan" from "there is simply no data",
 * which is what lets the UI render "-" instead of implying an absence.
 */
public record AttendanceDailyRow(
    long employeeId,
    String employeeCode,
    String employeeName,
    String nickName,
    String positionTh,
    LocalDate workDate,
    OffsetDateTime checkIn,
    OffsetDateTime checkOut,
    Integer totalMinutes,
    int lateMinutes,
    int earlyLeaveMinutes,
    int overtimeMinutes,
    int punchCount,
    String siteCode,
    boolean manualOverride,
    String notes,
    boolean hasRecord
) {
}
