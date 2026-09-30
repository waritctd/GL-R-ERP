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
 * <p>Callers ({@link th.co.glr.hr.notification.NotificationEmailService}) depend on this
 * interface, never a concrete transport - swapping providers is a config change, not a code
 * change. ({@code th.co.glr.hr.factory.FactoryEmailService} used to be a second caller; it was
 * deleted when factory RFQ email became manual-only — see {@code FactoryQuoteService#send}.)
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

    /**
     * Sends one fully-specified message: an HTML body with a required plain-text alternative, an
     * optional CC list, inline images, and file attachments, all in one email. The single-purpose
     * methods above cannot express two of these at once - none carries a {@code cc}, and none
     * combines HTML with attachments - which the auto-generated leave-submission email needs (it
     * mails the ใบลา PDF to the shared HR inbox while CC'ing the requester and their manager). Kept as
     * one method taking {@link OutgoingEmail} rather than a seven-argument signature so a caller
     * cannot transpose {@code htmlBody}/{@code textBody} or {@code to}/{@code cc} positionally.
     *
     * <p>CC containment note: {@link OverrideRedirectingMailer} redirects {@code to} AND drops
     * {@code cc} when {@code app.mail.override-to} is set, so a UAT run never mails a real manager via
     * CC - the same by-construction guarantee issue #782 established for {@code to}.
     *
     * <p>A default that throws rather than an abstract method: all four shipping transports
     * ({@link LogMailer}/{@link ResendMailer}/{@link SmtpMailer}) and the {@link
     * OverrideRedirectingMailer} decorator override it, so production always has a real
     * implementation. The default exists only so a test double that never drives the rich path (most
     * of them) does not have to stub a method it never calls - and it FAILS LOUDLY, never silently
     * dropping the CC/attachments, if such a double is ever routed a rich send by accident.
     *
     * @throws MailSendException if the underlying transport fails.
     * @throws UnsupportedOperationException if a transport does not implement the rich send path.
     */
    default void send(OutgoingEmail email) {
        throw new UnsupportedOperationException(
            getClass().getSimpleName() + " does not implement send(OutgoingEmail)");
    }

    /** One file attached to an outbound email, already resolved to bytes. */
    record Attachment(String filename, byte[] bytes, String mimeType) {}

    /**
     * A fully-specified outbound email for {@link #send(OutgoingEmail)}: HTML + plain-text body, an
     * optional CC list, inline images (e.g. the brand logo), and file attachments. {@code cc},
     * {@code inlineImages} and {@code attachments} are defensively copied and never null after
     * construction (a null becomes an empty list), so implementations can iterate them directly. Blank
     * CC entries are dropped so a caller need not pre-filter an absent manager address.
     */
    record OutgoingEmail(
        String to,
        List<String> cc,
        String subject,
        String htmlBody,
        String textBody,
        List<InlineImage> inlineImages,
        List<Attachment> attachments
    ) {
        public OutgoingEmail {
            cc = cc == null ? List.of()
                : cc.stream().filter(a -> a != null && !a.isBlank()).map(String::trim).toList();
            inlineImages = inlineImages == null ? List.of() : List.copyOf(inlineImages);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    /** One image embedded in an HTML email and referenced from the HTML via {@code cid:<contentId>}
     * (no leading {@code cid:} in {@code contentId} itself - that prefix is an HTML/CSS URL-scheme
     * concern, not part of the identifier), already resolved to bytes. */
    record InlineImage(String contentId, String filename, byte[] bytes, String mimeType) {}
}
