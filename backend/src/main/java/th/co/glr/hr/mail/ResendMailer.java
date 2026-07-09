package th.co.glr.hr.mail;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link Mailer} over Resend's HTTP API. Selected by {@code app.mail.provider=resend}. Used on cloud
 * hosts that block outbound SMTP (Render blocks 25/465/587) - outbound HTTPS is not blocked.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "resend")
public class ResendMailer implements Mailer {
    private static final Logger log = LoggerFactory.getLogger(ResendMailer.class);

    private final Resend client;
    private final String fromAddress;

    public ResendMailer(@Value("${app.mail.resend-api-key:}") String apiKey,
                        @Value("${app.mail.from:onboarding@resend.dev}") String fromAddress) {
        // Constructing with a blank key is harmless (no network call happens until send()).
        this.client = new Resend(apiKey);
        this.fromAddress = fromAddress;
    }

    @Override
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
}
