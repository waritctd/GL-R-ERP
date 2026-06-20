package th.co.glr.hr.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    Long employeeId,
    @Email @NotBlank @Size(max = 254) String email,
    @NotBlank @Pattern(regexp = "(?i)admin|hr|director|supervisor|employee") String role,
    @NotBlank @Size(min = 8, max = 128) String password,
    @Size(max = 200) String name
) {
}
