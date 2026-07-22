package th.co.glr.hr.factory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    /**
     * Sends the factory email and returns a message identifier for this attempt.
     *
     * <p>{@link Mailer} does not surface the underlying transport's own message id, so this is a
     * locally-minted marker rather than a provider one — but it is always non-null on success,
     * which is what {@code FactoryQuoteService.attemptSend} depends on: it is persisted right
     * after this call returns so that if the app crashes before the pricing request is finalized,
     * a later retry sees a non-null marker and skips re-sending (the email already reached the
     * factory) instead of sending it a second time.
     */
    public String send(long ticketId, String factory, String to, String subject, String body) {
        return send(ticketId, factory, to, subject, body, List.of());
    }

    /**
     * Overload used by {@code FactoryQuoteService.attemptSend} (COMMIT 4, attachment-workflow
     * remediation): {@code attachments} are the Pricing Request attachments Sales uploaded and
     * Import marked {@code include_in_factory_email}. Files are read into memory and sent via
     * {@link Mailer#sendWithAttachments}, so the transport (SMTP/Resend/log) stays a config
     * switch, not a code path. An empty/null list keeps the original plain-text path unchanged.
     *
     * <p>A referenced file that no longer exists on disk (e.g. removed out-of-band) is skipped
     * with a warning rather than failing the whole send — the factory should still receive the
     * email body and the other attachments rather than nothing at all.
     */
    public String send(long ticketId, String factory, String to, String subject, String body,
                       List<EmailAttachment> attachments) {
        try {
            if (attachments == null || attachments.isEmpty()) {
                mailer.send(to, subject, body);
                log.info("Factory email sent: ticket={} factory={} to={}", ticketId, factory, to);
                return UUID.randomUUID().toString();
            }

            List<Mailer.Attachment> resolved = new ArrayList<>();
            for (EmailAttachment attachment : attachments) {
                Path path = Path.of(attachment.filePath());
                if (!Files.exists(path)) {
                    log.warn("Skipping missing pricing-request attachment for factory email: ticket={} factory={} path={}",
                        ticketId, factory, attachment.filePath());
                    continue;
                }
                String mimeType = attachment.mimeType() != null ? attachment.mimeType() : "application/octet-stream";
                resolved.add(new Mailer.Attachment(attachment.fileName(), Files.readAllBytes(path), mimeType));
            }
            mailer.sendWithAttachments(to, subject, body, resolved);
            String messageId = UUID.randomUUID().toString();
            log.info("Factory email sent: ticket={} factory={} to={} messageId={} attachments={}",
                ticketId, factory, to, messageId, resolved.size());
            return messageId;
        } catch (Exception e) {
            log.error("Failed to send factory email: ticket={} factory={} to={} error={}", ticketId, factory, to, e.getMessage());
            throw new RuntimeException("ส่งอีเมลไม่สำเร็จ: " + e.getMessage(), e);
        }
    }

    /** A Pricing Request attachment file, resolved for outbound email use. */
    public record EmailAttachment(String fileName, String filePath, String mimeType) {}
}
