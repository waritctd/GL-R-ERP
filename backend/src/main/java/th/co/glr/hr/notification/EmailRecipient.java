package th.co.glr.hr.notification;

/**
 * An employee's notification-email destination, resolved in one query
 * ({@link NotificationRepository#findEmployeeRecipient(long)}) so {@link NotificationService} can
 * pass both the address and the display name through to {@link NotificationEmailService} - the name
 * drives the "เรียน คุณ&lt;name&gt;," greeting instead of the generic fallback.
 *
 * @param email email address, or {@code null} when the employee has no address on file. Callers see
 *              this type whenever the employee row exists, address or not - {@code
 *              app.mail.override-to} may still redirect the notification to a test inbox even with
 *              no real address, so the address is deliberately not the gate on returning this type
 * @param name  Thai display name, or {@code null} when the employee has no first/last name on file
 */
public record EmailRecipient(String email, String name) {
}
