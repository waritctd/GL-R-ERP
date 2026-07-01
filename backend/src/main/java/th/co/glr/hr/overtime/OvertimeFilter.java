package th.co.glr.hr.overtime;

import java.time.LocalDate;

public record OvertimeFilter(
    Long employeeId,
    Long managerEmployeeId,
    Long managerDivisionId,
    LocalDate fromDate,
    LocalDate toDate,
    OvertimeStatus status
) {
}
