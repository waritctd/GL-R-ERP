package th.co.glr.hr.employee;

import java.time.LocalDate;

public record AssignmentDto(
    LocalDate from,
    LocalDate to,
    String title,
    String division,
    String department,
    boolean current
) {
}
