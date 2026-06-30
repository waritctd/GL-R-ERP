package th.co.glr.hr.leave;

import java.time.LocalDate;

public record LeaveFilter(
    Long employeeId,
    Long managerEmployeeId,
    LocalDate fromDate,
    LocalDate toDate,
    LeaveStatus status
) {
}
