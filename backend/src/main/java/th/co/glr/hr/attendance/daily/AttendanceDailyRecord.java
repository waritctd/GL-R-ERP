package th.co.glr.hr.attendance.daily;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;

/** One derived row of hr.attendance_daily, as computed by AttendanceDailyCalculator. overtimeMinutes is the
 * only pay-relevant figure, it only ever increases pay, and it comes from an independently APPROVED overtime
 * request. */
public record AttendanceDailyRecord(
    long employeeId,
    LocalDate workDate,
    String siteCode,
    Long checkInPunchId,
    Long checkOutPunchId,
    OffsetDateTime checkIn,
    OffsetDateTime checkOut,
    Integer totalMinutes,
    int lateMinutes,
    int earlyLeaveMinutes,
    int overtimeMinutes,
    int punchCount,
    boolean absent,
    AttendanceDayStatus status,
    Set<AttendanceDayFlag> flags
) {
    public AttendanceDailyRecord {
        flags = flags == null ? Set.of() : Set.copyOf(flags);
    }
}
