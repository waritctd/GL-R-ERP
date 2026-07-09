package th.co.glr.hr.mail;

import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * {@link Mailer} over authenticated SMTP submission. Selected by {@code app.mail.provider=smtp} -
 * the on-prem transport: sends AS the company mailbox ({@code app.mail.from}, e.g. job@glr.co.th)
 * through the company mail host, to each recipient's real address (1-to-many). $0 - reuses an
 * existing mailbox; no per-email fee, no domain verification.
 *
 * <p>Builds its own {@link JavaMailSenderImpl} from {@code app.mail.smtp.*} rather than relying on
 * Spring Boot's mail auto-configuration - so no {@code JavaMailSender} bean exists on
 * resend/log deployments (which would otherwise activate Spring's SMTP-connecting mail health
 * indicator), and all SMTP config lives under one {@code app.mail} namespace.
 *
 * <p>M365 note: Microsoft 365 retired Basic-Auth SMTP (app-password) as of 30 Apr 2026. If the mail
 * host is M365, use a connector relay or Azure Communication Services instead of this transport.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "smtp")
public class SmtpMailer implements Mailer {
    private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);

    private final JavaMailSender sender;
    private final String fromAddress;

    public SmtpMailer(@Value("${app.mail.from:noreply@glr.co.th}") String fromAddress,
                      @Value("${app.mail.smtp.host:}") String host,
                      @Value("${app.mail.smtp.port:587}") int port,
                      @Value("${app.mail.smtp.username:}") String username,
                      @Value("${app.mail.smtp.password:}") String password,
                      @Value("${app.mail.smtp.starttls:true}") boolean startTls) {
        if (host.isBlank()) {
            throw new IllegalStateException(
                "app.mail.provider=smtp requires app.mail.smtp.host (set APP_MAIL_SMTP_HOST)");
        }
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(host);
        impl.setPort(port);
        impl.setUsername(username);
        impl.setPassword(password);
        Properties props = impl.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        this.sender = impl;
        this.fromAddress = fromAddress;
    }

    /** Test seam: inject a sender directly so message-building can be verified without a network. */
    SmtpMailer(String fromAddress, JavaMailSender sender) {
        this.fromAddress = fromAddress;
        this.sender = sender;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.send(message);
            log.info("Email sent via SMTP: from={} to={}", fromAddress, to);
        } catch (Exception exception) {
            throw new MailSendException("SMTP send failed to " + to + ": " + exception.getMessage(), exception);
        }
    }
}
