package th.co.glr.hr.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
}
