package th.co.glr.hr.attendance;

import java.time.LocalDate;

record AttendancePunchFilter(
    Long employeeId,
    Long divisionId,
    LocalDate fromDate,
    LocalDate toDate,
    int limit
) {
}
