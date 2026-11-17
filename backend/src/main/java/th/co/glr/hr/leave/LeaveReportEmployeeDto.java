package th.co.glr.hr.leave;

import java.time.LocalDate;
import java.util.List;

/** One employee's section of the printable leave-records report -- either the sole section of an employee's own
 * "me" report, or one of several sections in a manager's grouped "team" report. */
public record LeaveReportEmployeeDto(
    long employeeId,
    String employeeCode,
    String employeeName,
    String divisionNameTh,
    LocalDate hireDate,
    List<LeaveBalanceDto> balances,
    List<LeaveRequestDto> requests
) {
}
