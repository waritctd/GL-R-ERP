package th.co.glr.hr.factory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class FactoryEmailService {
    private static final Logger log = LoggerFactory.getLogger(FactoryEmailService.class);

    private final JavaMailSender mailer;
    private final String fromAddress;

    public FactoryEmailService(JavaMailSender mailer,
                               @Value("${spring.mail.username:noreply@glr.co.th}") String fromAddress) {
        this.mailer      = mailer;
        this.fromAddress = fromAddress;
    }

    public void send(long ticketId, String factory, String to, String subject, String body) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailer.send(msg);
            log.info("Factory email sent: ticket={} factory={} to={}", ticketId, factory, to);
        } catch (Exception e) {
            log.error("Failed to send factory email: ticket={} factory={} to={} error={}", ticketId, factory, to, e.getMessage());
            throw new RuntimeException("ส่งอีเมลไม่สำเร็จ: " + e.getMessage(), e);
        }
    }
}
