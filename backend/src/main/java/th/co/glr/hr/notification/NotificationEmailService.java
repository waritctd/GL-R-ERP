package th.co.glr.hr.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import th.co.glr.hr.mail.Mailer;

@Service
public class NotificationEmailService {
    private static final Logger log = LoggerFactory.getLogger(NotificationEmailService.class);

    /** Backend-served asset (see th.co.glr.hr.brand.BrandController) - built off asset-base-url (the
     * backend's own Render origin), deliberately NOT app-base-url (the Vercel frontend origin) as it
     * once was: if the frontend deployment is a Vercel branch preview with Deployment Protection
     * enabled, an auth wall sits in front of it and Gmail's image proxy - which fetches with no
     * cookies/session and cannot complete a login flow - gets blocked, so the logo silently breaks in
     * every email while portal links (built off app-base-url) still work fine. Hitting the backend
     * origin directly sidesteps that: it isn't behind Vercel's protection and vercel.json's
     * {@code /api/:path*} rewrite means the two origins serve the same bytes anyway. An absolute
     * HTTPS URL is required here regardless: email clients cannot resolve a relative path against
     * "the app". */
    private static final String LOGO_PATH = "/api/public/brand/logo.png";
    private static final String BRAND_COLOR = "#4f46e5";
    private static final String FONT_STACK = "'Sarabun',-apple-system,'Segoe UI',Tahoma,sans-serif";

    private final Mailer mailer;
    private final String overrideTo;
    private final String subjectPrefix;
    private final String appBaseUrl;
    private final String assetBaseUrl;

    public NotificationEmailService(Mailer mailer,
                                    @Value("${app.mail.override-to:}") String overrideTo,
                                    @Value("${app.mail.subject-prefix:}") String subjectPrefix,
                                    @Value("${app.mail.app-base-url:https://demo-glr-git-uat-waritctds-projects.vercel.app}") String appBaseUrl,
                                    @Value("${app.mail.asset-base-url:https://gl-r-erp-uat.onrender.com}") String assetBaseUrl) {
        this.mailer = mailer;
        this.overrideTo = clean(overrideTo);
        this.subjectPrefix = subjectPrefix == null ? "" : subjectPrefix;
        this.appBaseUrl = stripTrailingSlash(clean(appBaseUrl));
        this.assetBaseUrl = stripTrailingSlash(clean(assetBaseUrl));
    }

    @Async
    public void send(long employeeId, String to, String recipientName, String subject, String body, String link) {
        // override-to lets a test deployment redirect every notification email to one real inbox
        // (regardless of the employee's actual/fake address, or even a missing one) so the email
        // pipeline can be verified without real per-employee mailboxes. Blank on every other
        // deployment, so real deployments behave exactly as before.
        String recipient = overrideTo.isBlank() ? to : overrideTo;
        if (recipient == null || recipient.isBlank()) {
            log.info("Notification email skipped: employee={} has no email and no override configured",
                employeeId);
            return;
        }
        String finalSubject = subjectPrefix + "[GL&R HR] " + subject;
        String redirectNote = overrideTo.isBlank()
            ? null
            : "[Redirected for testing - originally addressed to employee #" + employeeId
                + (to == null || to.isBlank() ? ", no email on file]" : ", " + to + "]");
        String htmlBody = htmlBody(recipientName, body, link, redirectNote);
        String textBody = textBody(recipientName, body, link, redirectNote);
        try {
            mailer.sendHtml(recipient, finalSubject, htmlBody, textBody);
            log.info("Notification email sent: employee={} to={}", employeeId, recipient);
        } catch (Exception exception) {
            log.error("Failed to send notification email: employee={} to={} error={}",
                employeeId, recipient, exception.getMessage());
        }
    }

    public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
        String recipient = overrideTo.isBlank() ? to : overrideTo;
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("Email recipient is required");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Attachment is required");
        }
        String finalSubject = subjectPrefix + subject;
        String finalBody = overrideTo.isBlank()
            ? clean(body)
            : clean(body) + "\n\n[Redirected for testing - originally addressed to "
                + (to == null || to.isBlank() ? "no email on file]" : to + "]");
        try {
            mailer.sendWithAttachment(recipient, finalSubject, finalBody, filename, bytes);
            log.info("Notification email with attachment sent: to={} filename={}", recipient, filename);
        } catch (Exception exception) {
            log.error("Failed to send notification email with attachment: to={} filename={} error={}",
                recipient, filename, exception.getMessage());
            throw new IllegalStateException("Failed to send email with attachment", exception);
        }
    }

    /** Plain-text alternative - the original body format, unchanged, plus the redirect note when set. */
    private String textBody(String recipientName, String body, String link, String redirectNote) {
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
        if (redirectNote != null) {
            message.append("\n\n").append(redirectNote);
        }
        return message.toString();
    }

    /**
     * Table-based, inline-styled HTML for email-client compatibility (no {@code <style>} blocks, no
     * flexbox/grid). Every interpolated value is HTML-escaped before insertion - {@code body} in
     * particular is often free text typed by a manager (e.g. a leave reviewerNote) and must not be
     * able to inject markup.
     */
    private String htmlBody(String recipientName, String body, String link, String redirectNote) {
        String cleanName = clean(recipientName);
        String greeting = cleanName.isBlank()
            ? "เรียน ท่านผู้ใช้งาน,"
            : "เรียน คุณ" + escapeHtml(cleanName) + ",";
        String messageHtml = escapeHtml(clean(body)).replace("\r\n", "\n").replace("\n", "<br>");
        String cta = ctaHtml(link);
        String redirectHtml = redirectNote == null ? "" : """
            <tr>
              <td style="padding:16px 32px 0 32px;">
                <p style="margin:0;font-family:%s;font-size:12px;color:#9ca3af;">%s</p>
              </td>
            </tr>
            """.formatted(FONT_STACK, escapeHtml(redirectNote));
        String logoUrl = assetBaseUrl + LOGO_PATH;

        // The GL&R artwork is black-on-white with NO alpha channel, so the header band stays white
        // and the brand colour appears as the rule beneath it (and on the CTA) instead. Painting the
        // brand colour behind this logo would show a white rectangle floating on indigo. width/height
        // are pinned to the source's true 360x195 (1.85:1) ratio, halved for retina - an
        // aspect-ratio guess here letterboxes the wordmark in every client that honours the
        // attributes. The font/color styles apply to the ALT TEXT when the image is blocked (Outlook
        // always, Gmail for unknown senders), so a blocked image still reads as a deliberate wordmark
        // rather than a broken box - which is why no duplicate text wordmark sits beside it.
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
                        %s
                      </table>
                    </td>
                  </tr>
                </table>
              </body>
            </html>
            """.formatted(BRAND_COLOR, logoUrl, FONT_STACK, FONT_STACK, greeting, messageHtml, cta, FONT_STACK,
                FONT_STACK, redirectHtml);
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
