package th.co.glr.hr.notification;

/**
 * An employee's notification-email destination, resolved in one query
 * ({@link NotificationRepository#findEmployeeRecipient(long)}) so {@link NotificationService} can
 * pass both the address and the display name through to {@link NotificationEmailService} - the name
 * drives ONLY the "เรียน คุณ&lt;name&gt;," greeting (see {@code NotificationEmailService#textBody}/
 * {@code #htmlBody}) instead of the generic fallback; nothing else in this codebase reads it.
 *
 * @param email email address, or {@code null} when the employee has no address on file. Callers see
 *              this type whenever the employee row exists, address or not - {@code
 *              app.mail.override-to} may still redirect the notification to a test inbox even with
 *              no real address, so the address is deliberately not the gate on returning this type
 * @param name  first name only (owner ruling -- a Thai greeting addresses "คุณ&lt;first name&gt;", not
 *              the full name), or {@code null} when the employee has no first name on file
 */
public record EmailRecipient(String email, String name) {
}
