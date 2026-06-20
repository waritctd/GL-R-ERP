package th.co.glr.hr.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @Email @Size(max = 254) String email,
    @Size(max = 128) String password,
    @Size(max = 32) String role
) {
}
