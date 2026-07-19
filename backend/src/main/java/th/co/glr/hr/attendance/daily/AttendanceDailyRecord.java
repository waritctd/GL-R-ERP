package th.co.glr.hr.attendance.daily;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * One derived row of {@code hr.attendance_daily}, as computed by {@link AttendanceDailyCalculator}.
 *
 * <p>{@code status} and {@code flags} are <em>not</em> stored — every column below except those two
 * maps to a real column, and both are re-derived on read. That keeps the schema unchanged (V7
 * already has every column this needs) and keeps a single place where the labels are decided.
 *
 * <p><strong>§76:</strong> {@code lateMinutes} / {@code earlyLeaveMinutes} are reporting-only and
 * must never feed a payroll deduction. {@code overtimeMinutes} is the only pay-relevant figure, it
 * only ever increases pay, and it comes from an independently APPROVED overtime request.
 */
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
