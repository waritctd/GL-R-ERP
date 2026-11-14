package th.co.glr.hr.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record LoginRequest(
    @Email @Size(max = 254) String email,
    @Size(max = 128) String password,
    @Size(max = 32) String role
) {
    /** Canonicalises the email — trim, then lowercase — inside the constructor, so it happens before bean
     * validation rather than after. AuthController#login is @Valid, so @Email runs on the constructed record;
     * AuthService#login only reached for .trim() afterwards, which is too late to matter. */
    public LoginRequest {
        email = normalizeEmail(email);
    }

    /** @return the trimmed, lowercased address, or {@code null} when there was none. */
    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
