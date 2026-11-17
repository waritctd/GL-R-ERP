package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/** One APPROVED leave request's span, for a caller OUTSIDE this package that needs to overlay leave onto
 * attendance data. startTime/endTime are both null for a whole-day request and both non-null for a timed one
 * (chk_leave_time_pairing, V90). */
public record ApprovedLeaveSpanDto(
    long employeeId,
    String leaveTypeCode,
    String leaveTypeNameTh,
    LocalDate startDate,
    LocalDate endDate,
    LocalTime startTime,
    LocalTime endTime,
    BigDecimal totalDays
) {
}
