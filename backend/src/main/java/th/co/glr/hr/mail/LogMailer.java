package th.co.glr.hr.mail;

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
}
