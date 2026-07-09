package th.co.glr.hr.factory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import th.co.glr.hr.mail.Mailer;

@Service
public class FactoryEmailService {
    private static final Logger log = LoggerFactory.getLogger(FactoryEmailService.class);

    private final Mailer mailer;

    public FactoryEmailService(Mailer mailer) {
        this.mailer = mailer;
    }

    public void send(long ticketId, String factory, String to, String subject, String body) {
        try {
            mailer.send(to, subject, body);
            log.info("Factory email sent: ticket={} factory={} to={}", ticketId, factory, to);
        } catch (Exception e) {
            log.error("Failed to send factory email: ticket={} factory={} to={} error={}", ticketId, factory, to, e.getMessage());
            throw new RuntimeException("ส่งอีเมลไม่สำเร็จ: " + e.getMessage(), e);
        }
    }
}
