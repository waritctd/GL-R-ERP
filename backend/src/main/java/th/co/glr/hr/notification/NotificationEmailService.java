package th.co.glr.hr.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import th.co.glr.hr.mail.Mailer;

@Service
public class NotificationEmailService {
    private static final Logger log = LoggerFactory.getLogger(NotificationEmailService.class);

    private final Mailer mailer;
    private final String overrideTo;
    private final String subjectPrefix;
    private final String appBaseUrl;

    public NotificationEmailService(Mailer mailer,
                                    @Value("${app.mail.override-to:}") String overrideTo,
                                    @Value("${app.mail.subject-prefix:}") String subjectPrefix,
                                    @Value("${app.mail.app-base-url:https://demo-glr-git-uat-waritctds-projects.vercel.app}") String appBaseUrl) {
        this.mailer = mailer;
        this.overrideTo = clean(overrideTo);
        this.subjectPrefix = subjectPrefix == null ? "" : subjectPrefix;
        this.appBaseUrl = stripTrailingSlash(clean(appBaseUrl));
    }

    @Async
    public void send(long employeeId, String to, String recipientName, String subject, String body, String link) {
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
        String finalSubject = subjectPrefix + "[GL&R HR] " + subject;
        String templatedBody = professionalBody(recipientName, body, link);
        String finalBody = overrideTo.isBlank()
            ? templatedBody
            : templatedBody + "\n\n[Redirected for testing - originally addressed to employee #" + employeeId
                + (to == null || to.isBlank() ? ", no email on file]" : ", " + to + "]");
        try {
            mailer.send(recipient, finalSubject, finalBody);
            log.info("Notification email sent: employee={} to={}", employeeId, recipient);
        } catch (Exception exception) {
            log.error("Failed to send notification email: employee={} to={} error={}",
                employeeId, recipient, exception.getMessage());
        }
    }

    public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
        String recipient = overrideTo.isBlank() ? to : overrideTo;
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("Email recipient is required");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Attachment is required");
        }
        String finalSubject = subjectPrefix + subject;
        String finalBody = overrideTo.isBlank()
            ? clean(body)
            : clean(body) + "\n\n[Redirected for testing - originally addressed to "
                + (to == null || to.isBlank() ? "no email on file]" : to + "]");
        try {
            mailer.sendWithAttachment(recipient, finalSubject, finalBody, filename, bytes);
            log.info("Notification email with attachment sent: to={} filename={}", recipient, filename);
        } catch (Exception exception) {
            log.error("Failed to send notification email with attachment: to={} filename={} error={}",
                recipient, filename, exception.getMessage());
            throw new IllegalStateException("Failed to send email with attachment", exception);
        }
    }

    private String professionalBody(String recipientName, String body, String link) {
        String greeting = clean(recipientName).isBlank()
            ? "เรียน ท่านผู้ใช้งาน,"
            : "เรียน คุณ" + clean(recipientName) + ",";
        StringBuilder message = new StringBuilder()
            .append(greeting)
            .append("\n\n")
            .append(clean(body))
            .append("\n\n");
        String cleanLink = clean(link);
        if (!cleanLink.isBlank()) {
            message.append("ดูรายละเอียดในระบบ: ")
                .append(appBaseUrl)
                .append(cleanLink.startsWith("/") ? cleanLink : "/" + cleanLink)
                .append("\n\n");
        }
        return message
            .append("ขอแสดงความนับถือ\n")
            .append("ระบบบริหารงานบุคคล GL&R (GL&R HR Portal)\n")
            .append("— อีเมลฉบับนี้ส่งจากระบบอัตโนมัติ กรุณาอย่าตอบกลับ")
            .toString();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String stripTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
