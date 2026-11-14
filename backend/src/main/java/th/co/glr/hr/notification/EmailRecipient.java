package th.co.glr.hr.notification;

/** An employee's notification-email destination, resolved in one query
 * (NotificationRepository#findEmployeeRecipient) so NotificationService can pass both the address and the
 * display name through to NotificationEmailService. */
public record EmailRecipient(String email, String name) {
}
