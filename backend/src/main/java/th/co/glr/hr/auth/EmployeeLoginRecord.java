package th.co.glr.hr.auth;

import java.time.LocalDate;

public record EmployeeLoginRecord(
    long employeeId,
    String employeeCode,
    String email,
    String name,
    boolean active,
    Long divisionId,
    String divisionCode,
    String divisionName,
    String positionName,
    LocalDate createdAt,
    String passwordHash,
    boolean mustChangePassword
) {
}
