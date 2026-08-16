package th.co.glr.hr.notification;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.mail.Mailer;

@Service
public class NotificationEmailService {
    private static final Logger log = LoggerFactory.getLogger(NotificationEmailService.class);

    /** Content-ID the logo is embedded under and referenced from the HTML via {@code cid:glr-logo}
     * (see {@link #htmlBody}). Previously this was a remote {@code <img src>} built from a separate
     * {@code asset-base-url} host (the backend's own Render origin) specifically to dodge a Vercel
     * Deployment-Protection auth wall that could block Gmail's image-fetching proxy. That whole
     * class of problem - any mail client refusing to FETCH a URL, whether from an auth wall or (the
     * defect that actually reached UAT) Gmail blocking remote images for mail it files as Spam or
     * from a sender it does not yet trust - is moot once the image travels INSIDE the message:
     * there is no URL, so there is nothing to block. {@code asset-base-url} /
     * {@code app.mail.asset-base-url} are retired along with it. {@code app-base-url} remains,
     * unrelated, for the portal CTA link below. */
    private static final String LOGO_CONTENT_ID = "glr-logo";
    private static final String BRAND_COLOR = "#4f46e5";
    private static final String FONT_STACK = "'Sarabun',-apple-system,'Segoe UI',Tahoma,sans-serif";

    private final Mailer mailer;
    /**
     * Whether {@code app.mail.override-to} is configured - NOT the address itself, and NOT used to
     * pick a recipient. Recipient redirection now lives entirely in
     * {@link th.co.glr.hr.mail.OverrideRedirectingMailer}, which wraps the {@link Mailer} bean this
     * class is handed (issue #782: this class and {@code FactoryEmailService} used to each need their
     * own copy of {@code overrideTo.isBlank() ? to : overrideTo} - one of them simply didn't have it -
     * which is the defect the decorator exists to make structurally impossible; see that class).
     *
     * <p>This class still needs to know WHETHER an override is active, for a narrower and unrelated
     * reason: {@code to} can legitimately arrive null/blank (an employee with no email on file -
     * {@code NotificationService} no longer gates on the address before calling this), and normally
     * that means there is nothing to send. Except when an override IS configured, where attempting the
     * send anyway - and letting the Mailer redirect it - is what lets a UAT/test deployment exercise
     * the full notification pipeline even for an employee seeded without a real address. That
     * send-or-skip decision is this class's business, not the Mailer's; the address actually dialled,
     * and the note marking a redirected send as redirected, are the Mailer's alone now - see
     * {@link #send} / {@link #sendWithAttachment} below, both of which pass {@code to} to the Mailer
     * completely unchanged, override configured or not.
     */
    private final boolean overrideConfigured;
    private final String subjectSuffix;
    private final String appBaseUrl;
    /** The logo, pre-wrapped as the single-element list {@link Mailer#sendHtml} expects - computed
     * once at construction (this service is a singleton bean), not per email. Loaded via
     * {@link BrandAssets#logoBytes()} - a small standalone component in the {@code brand} package,
     * not a method on {@code BrandController} (a {@code @RestController}, i.e. a web adapter):
     * this service depending on that would invert the normal dependency direction. Both this
     * service and {@code BrandController} depend on {@code BrandAssets} instead, so there is
     * exactly one place that knows where the asset lives on disk. Empty when the resource is
     * absent, in which case the email sends with no inline image and the {@code <img>} falls back
     * to its alt text - the same degrade-gracefully behavior a 404'd remote fetch would have had
     * before this change. */
    private final List<Mailer.InlineImage> logoInlineImages;

    public NotificationEmailService(Mailer mailer,
                                    BrandAssets brandAssets,
                                    @Value("${app.mail.override-to:}") String overrideTo,
                                    @Value("${app.mail.subject-suffix:}") String subjectSuffix,
                                    // Blank, NOT a URL. This annotation default is the last line of
                                    // defence and it must not name an environment. It carried uat's
                                    // preview host when ported; that was harmless only because
                                    // application.yml defines app.mail.app-base-url as
                                    // ${APP_MAIL_APP_BASE_URL:}, so the property is PRESENT-BUT-EMPTY
                                    // and an annotation default never fires. Delete or rename that
                                    // yml line and the property becomes ABSENT, the default fires,
                                    // and every production email links to UAT — silently. Absent and
                                    // empty are not the same thing, and this repo has been bitten by
                                    // that distinction before. ProductionReadinessConfig is what
                                    // actually forces a real value in a real deployment.
                                    @Value("${app.mail.app-base-url:}") String appBaseUrl) {
        this.mailer = mailer;
        this.overrideConfigured = !clean(overrideTo).isBlank();
        this.subjectSuffix = subjectSuffix == null ? "" : subjectSuffix;
        this.appBaseUrl = stripTrailingSlash(clean(appBaseUrl));
        byte[] logoBytes = brandAssets.logoBytes();
        this.logoInlineImages = logoBytes.length == 0
            ? List.of()
            : List.of(new Mailer.InlineImage(LOGO_CONTENT_ID, "glr-logo.png", logoBytes, "image/png"));
    }

    @Async
    public void send(long employeeId, String to, String recipientName, String subject, String body, String link) {
        // See overrideConfigured's Javadoc above: this is a send-or-skip decision, not a "who do I
        // send to" decision. `to` reaches the Mailer exactly as received below, whether or not an
        // override is configured - the Mailer is what redirects it (and marks that it did) when one
        // is active.
        if ((to == null || to.isBlank()) && !overrideConfigured) {
            log.info("Notification email skipped: employee={} has no email and no override configured",
                employeeId);
            return;
        }
        String finalSubject = "[GL&R HR] " + subject + subjectSuffix;
        String htmlBody = htmlBody(recipientName, body, link);
        String textBody = textBody(recipientName, body, link);
        try {
            mailer.sendHtml(to, finalSubject, htmlBody, textBody, logoInlineImages);
            log.info("Notification email dispatched: employee={} to={}", employeeId,
                (to == null || to.isBlank()) ? "(none - relying on app.mail.override-to)" : to);
        } catch (Exception exception) {
            log.error("Failed to send notification email: employee={} to={} error={}",
                employeeId, to, exception.getMessage());
        }
    }

    public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
        if ((to == null || to.isBlank()) && !overrideConfigured) {
            throw new IllegalArgumentException("Email recipient is required");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Attachment is required");
        }
        String finalSubject = subject + subjectSuffix;
        String finalBody = clean(body);
        try {
            mailer.sendWithAttachment(to, finalSubject, finalBody, filename, bytes);
            log.info("Notification email with attachment dispatched: to={} filename={}", to, filename);
        } catch (Exception exception) {
            log.error("Failed to send notification email with attachment: to={} filename={} error={}",
                to, filename, exception.getMessage());
            throw new IllegalStateException("Failed to send email with attachment", exception);
        }
    }

    /** Plain-text alternative - the original body format, unchanged. */
    private String textBody(String recipientName, String body, String link) {
        String greeting = clean(recipientName).isBlank()
            ? "เรียน ท่านผู้ใช้งาน,"
            : "เรียน คุณ" + clean(recipientName) + ",";
        StringBuilder message = new StringBuilder()
            .append(greeting)
            .append("\n\n")
            .append(clean(body))
            .append("\n\n");
        String cleanLink = clean(link);
        if (!cleanLink.isBlank()) {
            message.append("ดูรายละเอียดในระบบ: ")
                .append(appBaseUrl)
                .append(normalizeLink(cleanLink))
                .append("\n\n");
        }
        message.append("ขอแสดงความนับถือ\n")
            .append("ระบบบริหารงานบุคคล GL&R (GL&R HR Portal)\n")
            .append("— อีเมลฉบับนี้ส่งจากระบบอัตโนมัติ กรุณาอย่าตอบกลับ");
        return message.toString();
    }

    /**
     * Table-based, inline-styled HTML for email-client compatibility (no {@code <style>} blocks, no
     * flexbox/grid). Every interpolated value is HTML-escaped before insertion - {@code body} in
     * particular is often free text typed by a manager (e.g. a leave reviewerNote) and must not be
     * able to inject markup.
     */
    private String htmlBody(String recipientName, String body, String link) {
        String cleanName = clean(recipientName);
        String greeting = cleanName.isBlank()
            ? "เรียน ท่านผู้ใช้งาน,"
            : "เรียน คุณ" + escapeHtml(cleanName) + ",";
        String messageHtml = escapeHtml(clean(body)).replace("\r\n", "\n").replace("\n", "<br>");
        String cta = ctaHtml(link);
        String logoSrc = "cid:" + LOGO_CONTENT_ID;

        // The GL&R artwork is black-on-white with NO alpha channel, so the header band stays white
        // and the brand colour appears as the rule beneath it (and on the CTA) instead. Painting the
        // brand colour behind this logo would show a white rectangle floating on indigo. width/height
        // are pinned to the source's true 360x195 (1.85:1) ratio, halved for retina - an
        // aspect-ratio guess here letterboxes the wordmark in every client that honours the
        // attributes. The font/color styles apply to the ALT TEXT when the image is blocked (Outlook
        // always, or any client that disables inline images entirely), so a blocked image still
        // reads as a deliberate wordmark rather than a broken box - which is why no duplicate text
        // wordmark sits beside it.
        //
        // NOTE: this reasoning stays a Java comment, not an HTML one - an HTML comment here would
        // ship inside every recipient's email and be readable via "show original".
        return """
            <!doctype html>
            <html lang="th">
              <head>
                <meta charset="utf-8">
              </head>
              <body style="margin:0;padding:0;background-color:#f4f4f7;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f4f7;padding:24px 0;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="width:600px;max-width:600px;background-color:#ffffff;border-radius:8px;overflow:hidden;">
                        <tr>
                          <td style="padding:28px 32px 20px 32px;background-color:#ffffff;border-bottom:3px solid %s;">
                            <img src="%s" alt="GL&amp;R — The Finest Taps and Tiles" width="180" height="98"
                                 style="display:block;border:0;outline:none;text-decoration:none;width:180px;height:98px;font-family:%s;font-size:16px;font-weight:700;color:#111827;" />
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:32px;font-family:%s;font-size:15px;line-height:1.6;color:#111827;">
                            <p style="margin:0 0 16px 0;">%s</p>
                            <p style="margin:0;">%s</p>
                            %s
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:0 32px 32px 32px;">
                            <hr style="border:none;border-top:1px solid #e5e7eb;margin:8px 0 16px 0;" />
                            <p style="margin:0 0 4px 0;font-family:%s;font-size:12px;color:#6b7280;">ระบบบริหารงานบุคคล GL&amp;R (GL&amp;R HR Portal)</p>
                            <p style="margin:0;font-family:%s;font-size:12px;color:#9ca3af;">— อีเมลฉบับนี้ส่งจากระบบอัตโนมัติ กรุณาอย่าตอบกลับ</p>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
              </body>
            </html>
            """.formatted(BRAND_COLOR, logoSrc, FONT_STACK, FONT_STACK, greeting, messageHtml, cta, FONT_STACK,
                FONT_STACK);
    }

    /** Bulletproof CTA: a background-colored {@code <table>} with a padded {@code <a>}, not a styled
     * {@code <div>} - Outlook (Word rendering engine) ignores padding/border-radius on anchors but
     * honours them on table cells. Only rendered when link is non-blank. */
    private String ctaHtml(String link) {
        String cleanLink = clean(link);
        if (cleanLink.isBlank()) {
            return "";
        }
        String href = escapeHtml(appBaseUrl + normalizeLink(cleanLink));
        return """
            <table role="presentation" cellpadding="0" cellspacing="0" style="margin:24px 0 0 0;">
              <tr>
                <td align="center" bgcolor="%s" style="border-radius:6px;">
                  <a href="%s" target="_blank" rel="noopener" style="display:inline-block;padding:12px 28px;font-family:%s;font-size:14px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:6px;">ดูรายละเอียดในระบบ</a>
                </td>
              </tr>
            </table>
            """.formatted(BRAND_COLOR, href, FONT_STACK);
    }

    private String normalizeLink(String cleanLink) {
        return cleanLink.startsWith("/") ? cleanLink : "/" + cleanLink;
    }

    /** Escapes the five HTML-significant characters. No third-party dependency - this is the entire
     * surface a plain-text notification body/name/link can hit. Run BEFORE converting "\n" to "&lt;br&gt;". */
    private String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
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

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String stripTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
