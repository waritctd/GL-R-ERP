package th.co.glr.hr.employee;

/**
 * Result of an HR password reset. Carries the plaintext temporary password ONCE, back to the HR
 * caller so they can hand it to the employee; it is never persisted or logged in plaintext.
 */
public record PasswordResetResult(String temporaryPassword) {
}
