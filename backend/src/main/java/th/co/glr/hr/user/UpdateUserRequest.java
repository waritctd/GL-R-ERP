package th.co.glr.hr.user;

import jakarta.validation.constraints.Email;

public record UpdateUserRequest(
    @Email String email,
    String role,
    Boolean active
) {
}
