package th.co.glr.hr.mail;

import java.util.List;

/** Transport-agnostic email sender. No implementor of this interface, and no caller of it, needs its own
 * override-to check - see OverrideRedirectingMailer's Javadoc for why one hand-rolled per caller (issue #782)
 * was the bug, not a pattern to repeat for the next caller. */
public interface Mailer {
    /** @throws MailSendException if the underlying transport fails (network, auth, rejected recipient, etc.).
     * Callers decide whether to swallow-and-log (best-effort notifications) or surface it (e.g. factory emails,
     * which fail loudly today). */
    void send(String to, String subject, String body);

    /** Sends an HTML email with a required plain-text alternative, so clients that block or strip HTML (or read via
     * a plain-text-only reader) still render something readable. No attachment support - this is for transactional
     * notification emails only. */
    default void sendHtml(String to, String subject, String htmlBody, String textBody) {
        sendHtml(to, subject, htmlBody, textBody, List.of());
    }

    /** Sends an HTML email with a required plain-text alternative and zero or more images embedded IN the message,
     * referenced from the HTML via cid:<contentId> (e.g. a logo). An empty list behaves exactly like
     * #sendHtml(String, String, String, String). */
    void sendHtml(String to, String subject, String htmlBody, String textBody, List<InlineImage> inlineImages);

    /** Sends one attachment with a plain-text email body. */
    void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes);

    /** Sends zero or more attachments with a plain-text email body. An empty list behaves like #send(String,
     * String, String). */
    void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments);

    /** Sends one fully-specified message: an HTML body with a required plain-text alternative, an optional CC list,
     * inline images, and file attachments, all in one email. */
    default void send(OutgoingEmail email) {
        throw new UnsupportedOperationException(
            getClass().getSimpleName() + " does not implement send(OutgoingEmail)");
    }

    /** One file attached to an outbound email, already resolved to bytes. */
    record Attachment(String filename, byte[] bytes, String mimeType) {}

    /** A fully-specified outbound email for #send(OutgoingEmail): HTML + plain-text body, an optional CC list,
     * inline images (e.g. the brand logo), and file attachments. Blank CC entries are dropped so a caller need not
     * pre-filter an absent manager address. */
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

    /** One image embedded in an HTML email and referenced from the HTML via cid:<contentId> (no leading cid: in
     * contentId itself - that prefix is an HTML/CSS URL-scheme concern, not part of the identifier), already
     * resolved to bytes. */
    record InlineImage(String contentId, String filename, byte[] bytes, String mimeType) {}
}
