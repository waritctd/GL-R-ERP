package th.co.glr.hr.factory;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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

    /**
     * Sends the factory email and returns a message identifier for this attempt.
     *
     * <p>{@code JavaMailSender}/{@code SimpleMailMessage} do not surface the SMTP provider's own
     * message id, so this is a locally-minted marker rather than a provider one — but it is
     * always non-null on success, which is what {@code FactoryQuoteService.attemptSend} depends
     * on: it is persisted right after this call returns so that if the app crashes before the
     * pricing request is finalized, a later retry sees a non-null marker and skips re-sending
     * (the email already reached the factory) instead of sending it a second time.
     */
    public String send(long ticketId, String factory, String to, String subject, String body) {
        return send(ticketId, factory, to, subject, body, List.of());
    }

    /**
     * Overload used by {@code FactoryQuoteService.attemptSend} (COMMIT 4, attachment-workflow
     * remediation): {@code attachments} are the Pricing Request attachments Sales uploaded and
     * Import marked {@code include_in_factory_email}. {@code SimpleMailMessage} cannot carry
     * attachments at all, so a non-empty list switches to a multipart {@code MimeMessage} built
     * via {@code MimeMessageHelper} — same pattern already used by {@code
     * NotificationEmailService.sendWithAttachment} elsewhere in this codebase. An empty/null list
     * keeps the original plain-text path unchanged, so every existing caller (the 5-arg overload
     * above, and any test asserting on {@code SimpleMailMessage}) is unaffected.
     *
     * <p>A referenced file that no longer exists on disk (e.g. removed out-of-band) is skipped
     * with a warning rather than failing the whole send — the factory should still receive the
     * email body and the other attachments rather than nothing at all.
     */
    public String send(long ticketId, String factory, String to, String subject, String body,
                       List<EmailAttachment> attachments) {
        try {
            int attachedCount = 0;
            if (attachments == null || attachments.isEmpty()) {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(fromAddress);
                msg.setTo(to);
                msg.setSubject(subject);
                msg.setText(body);
                mailer.send(msg);
            } else {
                var message = mailer.createMimeMessage();
                var helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(fromAddress);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(body, false);
                for (EmailAttachment attachment : attachments) {
                    FileSystemResource resource = new FileSystemResource(attachment.filePath());
                    if (!resource.exists()) {
                        log.warn("Skipping missing pricing-request attachment for factory email: ticket={} factory={} path={}",
                            ticketId, factory, attachment.filePath());
                        continue;
                    }
                    String mimeType = attachment.mimeType() != null ? attachment.mimeType() : "application/octet-stream";
                    helper.addAttachment(attachment.fileName(), resource, mimeType);
                    attachedCount++;
                }
                mailer.send(message);
            }
            String messageId = UUID.randomUUID().toString();
            log.info("Factory email sent: ticket={} factory={} to={} messageId={} attachments={}",
                ticketId, factory, to, messageId, attachedCount);
            return messageId;
        } catch (Exception e) {
            log.error("Failed to send factory email: ticket={} factory={} to={} error={}", ticketId, factory, to, e.getMessage());
            throw new RuntimeException("ส่งอีเมลไม่สำเร็จ: " + e.getMessage(), e);
        }
    }

    /** A Pricing Request attachment file, resolved for outbound email use. */
    public record EmailAttachment(String fileName, String filePath, String mimeType) {}
}
