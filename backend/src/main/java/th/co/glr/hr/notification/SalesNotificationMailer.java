package th.co.glr.hr.notification;

import java.util.List;

/** Email delivery for the sales-pipeline notifications that NotificationRepository writes. Why this hangs off
 * the repository rather than the nine sales services. Adding a parallel dispatch at each call site would have
 * been 18 places for the two channels to disagree. */
public interface SalesNotificationMailer {

    /** A sales-pipeline notification was fanned out to a role. */
    void emailForRole(String role, List<Long> notifiedEmployeeIds, String title, String message, String link);

    /** A sales-pipeline notification was addressed at one employee. */
    void emailForEmployee(long employeeId, String title, String message, String link);

    /** In-app only — writes the notification row and sends nothing. Exists for the integration tests that assert on
     * hr.notification rows and have no interest in mail. */
    SalesNotificationMailer NO_OP = new SalesNotificationMailer() {
        @Override
        public void emailForRole(String role, List<Long> notifiedEmployeeIds, String title, String message,
                                 String link) {
            // in-app only
        }

        @Override
        public void emailForEmployee(long employeeId, String title, String message, String link) {
            // in-app only
        }
    };
}
