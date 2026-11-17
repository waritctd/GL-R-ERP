package th.co.glr.hr.mail;

import java.util.List;

/**
 * Optional capability of a {@link Mailer}: the same HTML send as {@link Mailer#sendHtml}, carrying an idempotency key
 * the provider uses to drop a second request with the same key (Resend: the {@code Idempotency-Key} header, honoured
 * for 24 hours). Only the release notice uses it; every other email type keeps calling the plain methods.
 *
 * <p>The key protects only a repeat of the SAME request: the provider's own retry loop, or a resubmission of the same
 * attempt. A new attempt (a retry press) carries a new key on purpose, so it is a new mail.
 */
public interface KeyedHtmlMailer {
    void sendHtmlOnce(String to, String subject, String htmlBody, String textBody,
                      List<Mailer.InlineImage> inlineImages, String idempotencyKey);
}
