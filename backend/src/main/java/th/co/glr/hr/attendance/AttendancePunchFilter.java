package th.co.glr.hr.attendance;

import java.time.LocalDate;

record AttendancePunchFilter(
    Long employeeId,
    LocalDate fromDate,
    LocalDate toDate,
    int limit
) {
}
