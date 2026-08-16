package th.co.glr.hr.mail;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorates the active {@link Mailer} so that when {@code app.mail.override-to} is configured, EVERY
 * outbound email is redirected to that one test inbox instead of the caller-supplied recipient - no
 * matter which class placed the call, and no matter which transport ({@link LogMailer} /
 * {@link SmtpMailer} / {@link ResendMailer}) is active underneath.
 *
 * <p><b>Issue #782.</b> Before this class existed, {@code NotificationEmailService} hand-rolled
 * {@code overrideTo.isBlank() ? to : overrideTo} at two separate call sites, and
 * {@code FactoryEmailService} had no such check at all - factory-quote mail reached
 * {@code sales.factory_config.email} exactly as stored, in every environment, including a UAT
 * deployment with {@code app.mail.override-to} set. Two hand-written copies of the same rule, one of
 * them simply missing, with nothing structural to notice the gap - that is the defect class, not
 * "FactoryEmailService forgot a check". Wrapping the {@link Mailer} bean itself - installed by
 * {@link MailOverrideBeanPostProcessor}, never constructed any other way - fixes it by construction:
 * every current caller of {@link Mailer} inherits containment, and so does every future one, because
 * there is no other way to send mail in this codebase (see {@link Mailer}'s own class Javadoc). A
 * sender cannot opt out of a rule it never implements.
 *
 * <p><b>The redirect note.</b> {@code NotificationEmailService} used to also mark a redirected email
 * as redirected, by appending a note naming the real intended recipient - but only on its own two
 * paths, so a redirected factory-quote email was completely indistinguishable from a delivered one
 * (half of what #782 is about: silent containment is not trustworthy containment). That note is
 * generalized here instead of preserved where it was: every {@link Mailer} method now gets one,
 * because every method now redirects. The note necessarily loses the domain-specific framing
 * {@code NotificationEmailService} used to add (it named the employee id) - this class sits below the
 * domain layer and only ever sees the {@code to} address a caller passed in - but the property that
 * actually matters (a redirected email must never look like a delivered one) now holds everywhere,
 * not just on two of four paths. No bracketed prefix ({@code "[Redirected...]"}) - the same
 * classic-spam-filter-trigger shape {@code NotificationEmailService}'s subject-suffix and body note
 * were both already written to avoid; see its history for why.
 *
 * <p>Package-private and instantiated only by {@link MailOverrideBeanPostProcessor}, which already
 * guards the blank case - so this class enforces non-blank at construction instead of re-checking
 * {@code isBlank()} on every send.
 */
final class OverrideRedirectingMailer implements Mailer {
    private static final Logger log = LoggerFactory.getLogger(OverrideRedirectingMailer.class);

    private final Mailer delegate;
    private final String overrideTo;

    OverrideRedirectingMailer(Mailer delegate, String overrideTo) {
        if (overrideTo == null || overrideTo.isBlank()) {
            // Enforces the invariant MailOverrideBeanPostProcessor already relies on: this class
            // exists only when a redirect is actually wanted. A blank override has no business
            // constructing this decorator at all - keeping that invariant true here too means a
            // future caller of this constructor cannot silently build a no-op wrapper by mistake.
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

    /** Appends the redirect note as a plain trailing paragraph - used for every non-HTML body. */
    private String plainNote(String body, String originalTo) {
        String base = body == null ? "" : body;
        return base + "\n\n" + note(originalTo);
    }

    /** Inserts the redirect note as a styled paragraph just before {@code </body>} (case-insensitive)
     * so it renders inside {@code NotificationEmailService}'s templated HTML instead of trailing the
     * document where a client's lenient parser might drop it. Falls back to a plain append when no
     * {@code </body>} is found, so this never depends on every future HTML caller using that exact
     * template. */
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

    /** Escapes the five HTML-significant characters - same minimal set
     * {@code NotificationEmailService} escapes with, kept local rather than shared since it is three
     * lines and pulling in a dependency (or an inter-package helper) for it would cost more than it
     * saves. */
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
