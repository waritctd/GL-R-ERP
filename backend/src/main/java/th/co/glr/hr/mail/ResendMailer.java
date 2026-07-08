package th.co.glr.hr.mail;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends transactional email over Resend's HTTP API instead of SMTP. Outbound SMTP (ports
 * 25/465/587) is commonly blocked by cloud-host free tiers (Render) and by corporate/on-prem
 * network policy alike; outbound HTTPS is not, so this transport is expected to keep working
 * unchanged through the app's planned on-prem migration - unlike an SMTP relay, which would need
 * re-plumbing (or a firewall exception) at every new hosting environment.
 *
 * <p>Shared by {@link th.co.glr.hr.notification.NotificationEmailService} and
 * {@link th.co.glr.hr.factory.FactoryEmailService} - the two places that used to depend on
 * Spring's {@code JavaMailSender}.
 */
@Component
public class ResendMailer {
    private static final Logger log = LoggerFactory.getLogger(ResendMailer.class);

    private final Resend client;
    private final String fromAddress;

    public ResendMailer(@Value("${app.mail.resend-api-key:}") String apiKey,
                        @Value("${app.mail.from:onboarding@resend.dev}") String fromAddress) {
        // Constructing with a blank key is harmless (no network call happens until send()), so
        // local/dev/CI can boot without APP_MAIL_RESEND_API_KEY set.
        this.client = new Resend(apiKey);
        this.fromAddress = fromAddress;
    }

    /**
     * @throws MailSendException wrapping any Resend API failure (network, auth, invalid
     *     recipient, etc.) - callers decide whether that should be swallowed-and-logged (best
     *     effort notifications) or surfaced to the caller (e.g. factory emails, which must fail
     *     loudly today).
     */
    public void send(String to, String subject, String body) {
        CreateEmailOptions request = CreateEmailOptions.builder()
            .from(fromAddress)
            .to(to)
            .subject(subject)
            .text(body)
            .build();
        try {
            CreateEmailResponse response = client.emails().send(request);
            log.info("Email sent via Resend: id={} to={}", response.getId(), to);
        } catch (ResendException exception) {
            throw new MailSendException(
                "Resend send failed (status=" + exception.getStatusCode()
                    + " error=" + exception.getErrorName() + "): " + exception.getMessage(),
                exception);
        }
    }

    /** Unchecked wrapper so callers aren't forced to declare {@code throws ResendException}. */
    public static class MailSendException extends RuntimeException {
        public MailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
