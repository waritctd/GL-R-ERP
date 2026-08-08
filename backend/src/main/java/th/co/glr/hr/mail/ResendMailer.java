package th.co.glr.hr.mail;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link Mailer} over Resend's HTTP API. Selected by {@code app.mail.provider=resend}. Used on cloud
 * hosts that block outbound SMTP (Render blocks 25/465/587) - outbound HTTPS is not blocked.
 *
 * <p>Notification emails fan out from an {@code @Async} burst (e.g. ~76 emails in a few seconds
 * during the UAT live-email pass), which can exceed Resend's 10 req/s rate limit and come back as
 * HTTP 429. Since this always runs on a background thread (never the request thread), a small
 * blocking retry-with-backoff here is safe and turns a transient rate-limit into a successful send
 * instead of a silently dropped notification.
 *
 * <p>{@code List-Unsubscribe} / {@code List-Unsubscribe-Post} were considered during the UAT
 * deliverability pass (2026-08-08) and deliberately NOT added: every message this class sends is
 * transactional HR mail (leave/OT notifications, payslips, factory quotes) that the recipient
 * cannot opt out of - there is no list to unsubscribe from. A header advertising an unsubscribe
 * mechanism that does not exist is worse for deliverability and worse for the recipient than no
 * header at all. Do not re-add it without a real list-management path behind it.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "resend")
public class ResendMailer implements Mailer {
    private static final Logger log = LoggerFactory.getLogger(ResendMailer.class);

    /** Functional seam over {@code Resend.emails()::send} - {@code Emails} is a final class, so this
     * lets unit tests fake the transport without needing an inline mock-maker. */
    @FunctionalInterface
    interface ResendSender {
        CreateEmailResponse send(CreateEmailOptions options) throws ResendException;
    }

    static final int MAX_ATTEMPTS = 4;
    static final long BASE_BACKOFF_MILLIS = 1000L;

    private final ResendSender sender;
    private final String fromAddress;
    private final String replyTo;

    @Autowired
    public ResendMailer(@Value("${app.mail.resend-api-key:}") String apiKey,
                        @Value("${app.mail.from:onboarding@resend.dev}") String fromAddress,
                        @Value("${app.mail.reply-to:}") String replyTo) {
        // Constructing with a blank key is harmless (no network call happens until send()).
        Resend client = new Resend(apiKey);
        this.sender = client.emails()::send;
        this.fromAddress = fromAddress;
        this.replyTo = replyTo == null ? "" : replyTo.trim();
    }

    /** Package-private constructor for tests: inject a fake transport, skip the real Resend client.
     * No Reply-To - see the 3-arg overload below for tests that need one. */
    ResendMailer(ResendSender sender, String fromAddress) {
        this(sender, fromAddress, "");
    }

    /** Package-private constructor for tests that also need to exercise Reply-To. */
    ResendMailer(ResendSender sender, String fromAddress, String replyTo) {
        this.sender = sender;
        this.fromAddress = fromAddress;
        this.replyTo = replyTo == null ? "" : replyTo.trim();
    }

    @Override
    public void send(String to, String subject, String body) {
        CreateEmailOptions.Builder request = CreateEmailOptions.builder()
            .from(fromAddress)
            .to(to)
            .subject(subject)
            .text(body);
        applyReplyTo(request);
        sendWithRetry(request.build(), to);
    }

    @Override
    public void sendHtml(String to, String subject, String htmlBody, String textBody, List<InlineImage> inlineImages) {
        CreateEmailOptions.Builder request = CreateEmailOptions.builder()
            .from(fromAddress)
            .to(to)
            .subject(subject)
            .html(htmlBody)
            .text(textBody);
        applyReplyTo(request);
        if (!inlineImages.isEmpty()) {
            // Resend has no separate "inline image" concept: an attachment becomes inline purely by
            // carrying a contentId that the html matches via cid:<contentId> - confirmed by
            // decompiling resend-java 4.13.0's Attachment.Builder (~/.m2), which exposes
            // .contentId(String).
            request.attachments(inlineImages.stream()
                .map(image -> com.resend.services.emails.model.Attachment.builder()
                    .fileName(image.filename())
                    .content(Base64.getEncoder().encodeToString(image.bytes()))
                    .contentType(image.mimeType() != null ? image.mimeType() : "application/octet-stream")
                    .contentId(image.contentId())
                    .build())
                .toList());
        }
        sendWithRetry(request.build(), to);
    }

    @Override
    public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
        sendWithAttachments(to, subject, body, List.of(new Attachment(filename, bytes, "application/pdf")));
    }

    @Override
    public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {
        List<com.resend.services.emails.model.Attachment> resendAttachments = attachments.stream()
            .map(a -> com.resend.services.emails.model.Attachment.builder()
                .fileName(a.filename())
                .content(Base64.getEncoder().encodeToString(a.bytes()))
                .contentType(a.mimeType() != null ? a.mimeType() : "application/pdf")
                .build())
            .toList();

        CreateEmailOptions.Builder request = CreateEmailOptions.builder()
            .from(fromAddress)
            .to(to)
            .subject(subject)
            .text(body)
            .attachments(resendAttachments);
        applyReplyTo(request);
        sendWithRetry(request.build(), to);
    }

    /** Sets Reply-To on the request being built, or leaves it unset when {@code app.mail.reply-to}
     * is blank (the default - no address is invented). Resend, like any RFC 5322 client, treats a
     * missing Reply-To as "replies go to From", so omitting the header is a safe, valid default. */
    private void applyReplyTo(CreateEmailOptions.Builder request) {
        if (!replyTo.isBlank()) {
            request.replyTo(replyTo);
        }
    }

    private void sendWithRetry(CreateEmailOptions request, String to) {
        ResendException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                CreateEmailResponse response = sender.send(request);
                log.info("Email sent via Resend: id={} to={}", response.getId(), to);
                return;
            } catch (ResendException exception) {
                boolean rateLimited = exception.getStatusCode() != null && exception.getStatusCode() == 429;
                if (!rateLimited || attempt == MAX_ATTEMPTS) {
                    throw new MailSendException(
                        "Resend send failed (status=" + exception.getStatusCode()
                            + " error=" + exception.getErrorName() + "): " + exception.getMessage(),
                        exception);
                }
                lastFailure = exception;
                long backoffMillis = BASE_BACKOFF_MILLIS * attempt;
                log.warn("Resend rate-limited (429) sending to {}, retrying in {}ms (attempt {}/{})",
                    to, backoffMillis, attempt, MAX_ATTEMPTS);
                sleep(backoffMillis);
            }
        }
        // Unreachable: the loop always returns or throws, but keep the compiler happy.
        throw new MailSendException("Resend send failed after retries: " + lastFailure, lastFailure);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
