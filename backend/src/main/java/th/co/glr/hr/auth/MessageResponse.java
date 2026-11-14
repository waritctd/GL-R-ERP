package th.co.glr.hr.auth;

/** Generic {message: string} response shape, used by both self-service password-reset endpoints: forgotPassword
 * returns the SAME message whether or not the address matched a real employee — never reveal account existence
 * via the response shape or content. */
public record MessageResponse(String message) {
}
