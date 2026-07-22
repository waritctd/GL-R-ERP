package th.co.glr.hr.mail;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link Mailer} that logs instead of sending. Default provider ({@code app.mail.provider} unset or
 * {@code log}) so dev/CI/local boots and runs every business flow with no mail credentials and no
 * accidental outbound email.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "log", matchIfMissing = true)
public class LogMailer implements Mailer {
    private static final Logger log = LoggerFactory.getLogger(LogMailer.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("[LogMailer] email NOT sent (provider=log). to={} subject={}", to, subject);
    }

    @Override
    public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
        int byteCount = bytes == null ? 0 : bytes.length;
        log.info("[LogMailer] email with attachment NOT sent (provider=log). to={} subject={} filename={} bytes={}",
            to, subject, filename, byteCount);
    }

    @Override
    public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {
        log.info("[LogMailer] email with {} attachment(s) NOT sent (provider=log). to={} subject={}",
            attachments.size(), to, subject);
    }
}
