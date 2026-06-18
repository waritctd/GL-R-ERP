package th.co.glr.hr.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateUserRequest(
    Long employeeId,
    @Email @NotBlank String email,
    @NotBlank String role,
    @NotBlank String password,
    String name
) {
}
