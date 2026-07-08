package th.co.glr.hr.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import th.co.glr.hr.mail.ResendMailer;

@Service
public class NotificationEmailService {
    private static final Logger log = LoggerFactory.getLogger(NotificationEmailService.class);

    private final ResendMailer mailer;
    private final String overrideTo;
    private final String subjectPrefix;

    public NotificationEmailService(ResendMailer mailer,
                                    @Value("${app.mail.override-to:}") String overrideTo,
                                    @Value("${app.mail.subject-prefix:}") String subjectPrefix) {
        this.mailer = mailer;
        this.overrideTo = overrideTo;
        this.subjectPrefix = subjectPrefix;
    }

    @Async
    public void send(long employeeId, String to, String subject, String body) {
        // override-to lets a test deployment redirect every notification email to one real inbox
        // (regardless of the employee's actual/fake address, or even a missing one) so the email
        // pipeline can be verified without real per-employee mailboxes. Blank on every other
        // deployment, so real deployments behave exactly as before.
        String recipient = overrideTo.isBlank() ? to : overrideTo;
        if (recipient == null || recipient.isBlank()) {
            log.info("Notification email skipped: employee={} has no email and no override configured",
                employeeId);
            return;
        }
        String finalSubject = subjectPrefix.isBlank() ? subject : subjectPrefix + subject;
        String finalBody = overrideTo.isBlank()
            ? body
            : body + "\n\n[Redirected for testing - originally addressed to employee #" + employeeId
                + (to == null || to.isBlank() ? ", no email on file]" : ", " + to + "]");
        try {
            mailer.send(recipient, finalSubject, finalBody);
            log.info("Notification email sent: employee={} to={}", employeeId, recipient);
        } catch (Exception exception) {
            log.error("Failed to send notification email: employee={} to={} error={}",
                employeeId, recipient, exception.getMessage());
        }
    }
}
