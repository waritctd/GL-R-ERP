package th.co.glr.hr.auth;

/**
 * Generic {@code {message: string}} response shape, used by both self-service password-reset
 * endpoints:
 *
 * <ul>
 *   <li>{@code forgotPassword} returns the SAME message whether or not the address matched a
 *       real employee — never reveal account existence via the response shape or content.</li>
 *   <li>{@code resetPassword} returns a plain success message; the frontend redirects to
 *       {@code /login} on receiving one.</li>
 * </ul>
 *
 * <p>Not reused from elsewhere in the codebase — a repo-wide grep found no existing
 * generically-named {@code {message}} response record to share instead of duplicating.
 */
public record MessageResponse(String message) {
}
