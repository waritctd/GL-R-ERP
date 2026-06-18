package th.co.glr.hr.profile;

import java.time.LocalDate;
import th.co.glr.hr.employee.EmployeeDto;

public record ProfileRequestDto(
    long id,
    long employeeId,
    String fieldKey,
    String fieldLabel,
    String oldValue,
    String newValue,
    String requestedBy,
    LocalDate requestedAt,
    String status,
    LocalDate reviewedAt,
    EmployeeDto employee
) {
}
