package th.co.glr.hr.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
    @Email @Size(max = 254) String email,
    @Pattern(regexp = "(?i)admin|hr|director|supervisor|employee") String role,
    Boolean active
) {
}
