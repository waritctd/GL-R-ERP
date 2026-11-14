package th.co.glr.hr.attendance.daily;

import java.time.LocalDate;

/** Scope for the day-range read. employeeId and divisionId are set by the service from the caller's role, never
 * taken from the request unchecked — see AttendanceService.resolveScope. */
public record AttendanceDailyFilter(
    Long employeeId,
    Long divisionId,
    LocalDate fromDate,
    LocalDate toDate
) {
}
