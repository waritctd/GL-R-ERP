package th.co.glr.hr.mail;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Decorates the active Mailer so that when app.mail.override-to is configured, EVERY outbound email is
 * redirected to that one test inbox instead of the caller-supplied recipient - no matter which class placed
 * the call, and no matter which transport is active underneath. */
final class OverrideRedirectingMailer implements Mailer {
    private static final Logger log = LoggerFactory.getLogger(OverrideRedirectingMailer.class);

    private final Mailer delegate;
    private final String overrideTo;

    OverrideRedirectingMailer(Mailer delegate, String overrideTo) {
        if (overrideTo == null || overrideTo.isBlank()) {
            // Enforces the invariant MailOverrideBeanPostProcessor already relies on: this class exists only when a
            // redirect is actually wanted.
            throw new IllegalArgumentException("OverrideRedirectingMailer requires a non-blank override-to");
        }
        this.delegate = delegate;
        this.overrideTo = overrideTo;
    }

    @Override
    public void send(String to, String subject, String body) {
        log.info("app.mail.override-to active: redirecting mail from {} to {}", describe(to), overrideTo);
        delegate.send(overrideTo, subject, plainNote(body, to));
    }

    @Override
    public void sendHtml(String to, String subject, String htmlBody, String textBody, List<InlineImage> inlineImages) {
        log.info("app.mail.override-to active: redirecting mail from {} to {}", describe(to), overrideTo);
        delegate.sendHtml(overrideTo, subject, htmlNote(htmlBody, to), plainNote(textBody, to), inlineImages);
    }

    @Override
    public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
        log.info("app.mail.override-to active: redirecting mail from {} to {}", describe(to), overrideTo);
        delegate.sendWithAttachment(overrideTo, subject, plainNote(body, to), filename, bytes);
    }

    @Override
    public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {
        log.info("app.mail.override-to active: redirecting mail from {} to {}", describe(to), overrideTo);
        delegate.sendWithAttachments(overrideTo, subject, plainNote(body, to), attachments);
    }

    @Override
    public void send(OutgoingEmail email) {
        // CC is DROPPED, not redirected: a UAT deployment must never mail a real manager via the CC list, so the whole
        // message collapses to the single override inbox. See this class's Javadoc / issue #782.
        log.info("app.mail.override-to active: redirecting mail from {} (cc dropped: {}) to {}",
            describe(email.to()), email.cc(), overrideTo);
        String recipients = email.cc().isEmpty()
            ? email.to()
            : describe(email.to()) + " (cc: " + String.join(", ", email.cc()) + ")";
        delegate.send(new OutgoingEmail(
            overrideTo,
            List.of(),
            email.subject(),
            htmlNote(email.htmlBody(), recipients),
            plainNote(email.textBody(), recipients),
            email.inlineImages(),
            email.attachments()));
    }

    /** Appends the redirect note as a plain trailing paragraph - used for every non-HTML body. */
    private String plainNote(String body, String originalTo) {
        String base = body == null ? "" : body;
        return base + "\n\n" + note(originalTo);
    }

    /** Inserts the redirect note as a styled paragraph just before </body> (case-insensitive) so it renders inside
     * NotificationEmailService's templated HTML instead of trailing the document where a client's lenient parser
     * might drop it. */
    private String htmlNote(String htmlBody, String originalTo) {
        String base = htmlBody == null ? "" : htmlBody;
        String marker = "<p style=\"margin:16px 0 0 0;padding:12px 0 0 0;border-top:1px solid #e5e7eb;"
            + "font-family:monospace,monospace;font-size:12px;color:#9ca3af;\">"
            + escapeHtml(note(originalTo)) + "</p>";
        int idx = base.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        return idx >= 0 ? base.substring(0, idx) + marker + base.substring(idx) : base + marker;
    }

    private String note(String originalTo) {
        String original = (originalTo == null || originalTo.isBlank()) ? "no address on file" : originalTo;
        return "Redirected for testing — originally addressed to " + original + ".";
    }

    private String describe(String to) {
        return (to == null || to.isBlank()) ? "(no address)" : to;
    }

    /** Escapes the five HTML-significant characters - same minimal set NotificationEmailService escapes with, kept
     * local rather than shared since it is three lines and pulling in a dependency (or an inter-package helper)
     * for it would cost more than it saves. */
    private String escapeHtml(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
