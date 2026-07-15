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

    public NotificationEmailService(JavaMailSender mailer,
                                    @Value("${spring.mail.username:noreply@glr.co.th}") String fromAddress) {
        this.mailer = mailer;
        this.fromAddress = fromAddress;
    }

    @Async
    public void send(long employeeId, String to, String subject, String body) {
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
