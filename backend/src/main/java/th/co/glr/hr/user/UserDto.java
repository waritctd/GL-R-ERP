package th.co.glr.hr.user;

import java.time.LocalDate;

public record UserDto(
    long id,
    String email,
    String name,
    String role,
    Long employeeId,
    boolean active,
    LocalDate createdAt
) {
}
