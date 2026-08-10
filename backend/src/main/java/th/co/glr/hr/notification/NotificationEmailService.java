package th.co.glr.hr.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class NotificationEmailService {
    private static final Logger log = LoggerFactory.getLogger(NotificationEmailService.class);

    private final JavaMailSender mailer;
    private final String fromAddress;
    private final boolean configured;

    public NotificationEmailService(JavaMailSender mailer,
                                    @Value("${spring.mail.username:noreply@glr.co.th}") String fromAddress,
                                    @Value("${spring.mail.username:}") String username,
                                    @Value("${spring.mail.password:}") String password) {
        this.mailer = mailer;
        this.fromAddress = fromAddress;
        this.configured = !username.isBlank() && !password.isBlank();
        if (!configured) {
            // Say it ONCE, loudly, at startup. spring.mail.username/password default to blank, and
            // with mail.smtp.auth=true that means every send fails — but #send below catches
            // everything and only logs, so the failure is invisible unless someone greps the logs
            // for it after the fact. That is exactly how notification email sat dead on the hosted
            // service: the app-side path was correct end to end and the credentials were simply
            // never set (they were not even declared in render.yaml). One WARN at boot beats a
            // per-message error nobody reads.
            log.warn("Notification email is NOT CONFIGURED (spring.mail.username/password are blank)"
                + " — every notification email will fail. Set MAIL_USERNAME and MAIL_PASSWORD."
                + " Gmail requires a 16-character App Password, not the account password.");
        }
    }

    /** Whether SMTP credentials are present. Exposed so callers/health checks can tell "mail is
     *  switched off" apart from "mail is broken" — {@link #send} cannot, because it never throws. */
    public boolean isConfigured() {
        return configured;
    }

    @Async
    public void send(long employeeId, String to, String subject, String body) {
        // Distinguish unconfigured from broken. Both used to surface as the same generic
        // "Failed to send notification email" line, which reads like a transient SMTP problem and
        // sends you looking in the wrong place.
        if (!configured) {
            log.error("Notification email DROPPED (mail not configured): employee={} to={} subject={}."
                + " Set MAIL_USERNAME/MAIL_PASSWORD on this service.", employeeId, to, subject);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailer.send(message);
            log.info("Notification email sent: employee={} to={}", employeeId, to);
        } catch (Exception exception) {
            log.error("Failed to send notification email: employee={} to={} error={}",
                employeeId, to, exception.getMessage());
        }
    }

    public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("Email recipient is required");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Attachment is required");
        }
        try {
            var message = mailer.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            helper.addAttachment(filename, new ByteArrayResource(bytes));
            mailer.send(message);
            log.info("Notification email with attachment sent: to={} filename={}", to, filename);
        } catch (Exception exception) {
            log.error("Failed to send notification email with attachment: to={} filename={} error={}",
                to, filename, exception.getMessage());
            throw new IllegalStateException("Failed to send email with attachment", exception);
        }
    }
}
