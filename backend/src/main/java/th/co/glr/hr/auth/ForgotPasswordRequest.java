package th.co.glr.hr.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(
    @NotBlank @Email @Size(max = 254) String email
) {
    /**
     * Same trim-then-lowercase normalisation as {@link LoginRequest}, and for the same reason:
     * so a lookup here behaves identically to a login lookup (case- and whitespace-insensitive
     * against {@code hr.employee.email}), and so {@code @Email} sees the address AFTER trimming
     * rather than rejecting a pasted-with-a-space address before the constructor ever runs.
     */
    public ForgotPasswordRequest {
        email = LoginRequest.normalizeEmail(email);
    }
}
