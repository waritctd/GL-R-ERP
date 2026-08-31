package th.co.glr.hr.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record LoginRequest(
    @Email @Size(max = 254) String email,
    @Size(max = 128) String password,
    @Size(max = 32) String role
) {
    /**
     * Canonicalises the email — trim, then lowercase — inside the constructor, so it happens
     * <b>before</b> bean validation rather than after.
     *
     * <p>Ordering is the whole point. {@code AuthController#login} is {@code @Valid}, so
     * {@code @Email} runs on the constructed record; {@code AuthService#login} only reached for
     * {@code .trim()} <em>afterwards</em>, which is too late to matter. Hibernate Validator's email
     * pattern admits no whitespace in either the local part or the domain, so an address that
     * arrived with a stray leading or trailing space — pasted from a spreadsheet, or supplied by a
     * phone keyboard that appended one after autocorrecting — was rejected with a 400 that never
     * reached {@code EmployeeAuthRepository#findByEmail} at all. To the person typing it, an
     * address that is correct in every respect simply does not work.
     *
     * <p>Lowercasing here is belt-and-braces rather than a fix: {@code findByEmail} already compares
     * {@code LOWER(btrim(e.email)) = LOWER(:email)}, so case has never decided a login. Doing it at
     * the boundary anyway means every downstream consumer — this record's own {@code email()}, and
     * anything a future caller derives from it — sees one canonical form, instead of each having to
     * remember to fold case for itself. That is the same "fix it by construction" reasoning
     * {@code OverrideRedirectingMailer} applies to the mail path.
     *
     * <p>The password is deliberately left alone. Leading and trailing spaces are legitimate
     * password characters, and silently trimming them would lock out anyone whose password has one.
     */
    public LoginRequest {
        email = normalizeEmail(email);
    }

    /** @return the trimmed, lowercased address, or {@code null} when there was none. */
    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
