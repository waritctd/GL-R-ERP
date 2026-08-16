package th.co.glr.hr.mail;

import java.util.List;

/**
 * Transport-agnostic email sender. One implementation is active per deployment, selected by the
 * {@code app.mail.provider} property so the transport is a config switch, not per-environment code:
 * <ul>
 *   <li>{@code resend} ({@link ResendMailer}) - Resend HTTP API. Used on cloud hosts that block
 *       outbound SMTP (e.g. Render).</li>
 *   <li>{@code smtp} ({@link SmtpMailer}) - authenticated SMTP submission. Used on-prem, sending as
 *       the company mailbox ({@code app.mail.from}, e.g. job@glr.co.th) through the company mail host.</li>
 *   <li>{@code log} ({@link LogMailer}) - logs instead of sending. Default for dev/CI so the app
 *       boots and business flows run without any mail credentials.</li>
 * </ul>
 *
 * <p>Callers ({@link th.co.glr.hr.notification.NotificationEmailService},
 * {@link th.co.glr.hr.factory.FactoryEmailService}) depend on this interface, never a concrete
 * transport - swapping providers is a config change, not a code change.
 *
 * <p><b>{@code app.mail.override-to} containment lives here too, transparently.</b> When that
 * property is set, {@link MailOverrideBeanPostProcessor} wraps whichever transport is active in
 * {@link OverrideRedirectingMailer}, so the {@code Mailer} bean any of the above actually receives
 * already redirects every send. No implementor of this interface, and no caller of it, needs its own
 * override-to check - see {@link OverrideRedirectingMailer}'s Javadoc for why one hand-rolled per
 * caller (issue #782) was the bug, not a pattern to repeat for the next caller.
 */
public interface Mailer {
    /**
     * @throws MailSendException if the underlying transport fails (network, auth, rejected
     *     recipient, etc.). Callers decide whether to swallow-and-log (best-effort notifications) or
     *     surface it (e.g. factory emails, which fail loudly today).
     */
    void send(String to, String subject, String body);

    /**
     * Sends an HTML email with a required plain-text alternative, so clients that block or strip
     * HTML (or read via a plain-text-only reader) still render something readable. No attachment
     * support - this is for transactional notification emails only.
     *
     * <p>Equivalent to {@link #sendHtml(String, String, String, String, List)} with no inline
     * images. Kept as a separate default method (rather than requiring every caller to pass
     * {@code List.of()}) so every existing call site compiles unchanged.
     *
     * @throws MailSendException if the underlying transport fails.
     */
    default void sendHtml(String to, String subject, String htmlBody, String textBody) {
        sendHtml(to, subject, htmlBody, textBody, List.of());
    }

    /**
     * Sends an HTML email with a required plain-text alternative and zero or more images embedded
     * IN the message, referenced from the HTML via {@code cid:<contentId>} (e.g. a logo). This is
     * what lets a client that blocks or delays remote image fetches - Gmail does this for mail
     * filed as Spam, and for senders/domains it does not yet trust - still render the image: the
     * bytes travel with the message instead of being fetched afterward from a URL that may never be
     * requested. An empty list behaves exactly like
     * {@link #sendHtml(String, String, String, String)}.
     *
     * @throws MailSendException if the underlying transport fails.
     */
    void sendHtml(String to, String subject, String htmlBody, String textBody, List<InlineImage> inlineImages);

    /**
     * Sends one attachment with a plain-text email body.
     *
     * @throws MailSendException if the underlying transport fails.
     */
    void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes);

    /**
     * Sends zero or more attachments with a plain-text email body. An empty list behaves like
     * {@link #send(String, String, String)}.
     *
     * @throws MailSendException if the underlying transport fails.
     */
    void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments);

    /** One file attached to an outbound email, already resolved to bytes. */
    record Attachment(String filename, byte[] bytes, String mimeType) {}

    /** One image embedded in an HTML email and referenced from the HTML via {@code cid:<contentId>}
     * (no leading {@code cid:} in {@code contentId} itself - that prefix is an HTML/CSS URL-scheme
     * concern, not part of the identifier), already resolved to bytes. */
    record InlineImage(String contentId, String filename, byte[] bytes, String mimeType) {}
}
