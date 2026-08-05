package th.co.glr.hr.notification;

/**
 * An employee's notification-email destination, resolved in one query
 * ({@link NotificationRepository#findEmployeeEmail(long)}) so {@link NotificationService} can pass
 * both the address and the display name through to {@link NotificationEmailService} - the name
 * drives the "เรียน คุณ&lt;name&gt;," greeting instead of the generic fallback.
 *
 * @param email non-blank email address (callers only ever see this type when an address exists)
 * @param name  Thai display name, or {@code null} when the employee has no first/last name on file
 */
public record EmailRecipient(String email, String name) {
}
