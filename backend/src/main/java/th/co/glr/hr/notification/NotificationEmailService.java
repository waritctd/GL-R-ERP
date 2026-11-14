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

    /** Content-ID the logo is embedded under and referenced from the HTML via cid:glr-logo (see #htmlBody).
     * asset-base-url / app.mail.asset-base-url are retired along with it. app-base-url remains, unrelated, for the
     * portal CTA link below. */
    private static final String LOGO_CONTENT_ID = "glr-logo";
    private static final String BRAND_COLOR = "#4f46e5";
    private static final String FONT_STACK = "'Sarabun',-apple-system,'Segoe UI',Tahoma,sans-serif";

    private final Mailer mailer;
    /** Whether app.mail.override-to is configured - NOT the address itself, and NOT used to pick a recipient. to :
     * overrideTo - one of them simply didn't have it - which is the defect the decorator exists to make
     * structurally impossible; see that class). */
    private final boolean overrideConfigured;
    private final String subjectSuffix;
    private final String appBaseUrl;
    /** The logo, pre-wrapped as the single-element list Mailer#sendHtml expects - computed once at construction
     * (this service is a singleton bean), not per email. Both this service and BrandController depend on
     * BrandAssets instead, so there is exactly one place that knows where the asset lives on disk. */
    private final List<Mailer.InlineImage> logoInlineImages;

    public NotificationEmailService(Mailer mailer,
                                    BrandAssets brandAssets,
                                    @Value("${app.mail.override-to:}") String overrideTo,
                                    @Value("${app.mail.subject-suffix:}") String subjectSuffix,
                                    // Keep the URL default blank so a missing property cannot send production email links to another environment.
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
        // See overrideConfigured's Javadoc above: this is a send-or-skip decision, not a "who do I send to" decision.
        // `to` reaches the Mailer exactly as received below, whether or not an override is configured - the Mailer is
        // what redirects it (and marks that it did) when one is active.
        if ((to == null || to.isBlank()) && !overrideConfigured) {
            log.info("Notification email skipped: employee={} has no email and no override configured",
                employeeId);
            return;
        }
        String finalSubject = "[GL&R Portal] " + subject + subjectSuffix;
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

    /**
     * The auto-generated leave-submission email (2026-09): the ใบลา F-HR-020 PDF (plus any medical
     * certificate) mailed to the shared HR inbox ({@code to}) while CC'ing the requester and their
     * manager. {@code letterBody} is the formal Thai leave letter, supplied as plain text and rendered
     * into both the plain-text alternative and the branded HTML shell here (so escaping and the shell
     * stay in one place, exactly as {@link #send} does for a notification body). {@code subject} is
     * used as given - NOT prefixed with {@code [GL&R Portal] }, because the requested subject already
     * leads with the employee code/nickname - but the {@code app.mail.subject-suffix} UAT marker is
     * still appended. Best-effort: a transport failure is logged, never thrown, so a mail outage never
     * fails a leave submission that already committed.
     */
    @Async
    public void sendLeaveSubmission(String to, List<String> cc, String subject, String letterBody,
                                    String link, List<Mailer.Attachment> attachments) {
        List<String> ccList = cc == null ? List.of() : cc;
        boolean noRecipient = (to == null || to.isBlank()) && ccList.isEmpty();
        if (noRecipient && !overrideConfigured) {
            log.info("Leave submission email skipped: no recipient and no override configured");
            return;
        }
        String finalSubject = clean(subject) + subjectSuffix;
        String htmlBody = wrapShell(letterHtmlContent(letterBody, link));
        String textBody = letterTextContent(letterBody, link);
        try {
            mailer.send(new Mailer.OutgoingEmail(to, ccList, finalSubject, htmlBody, textBody,
                logoInlineImages, attachments == null ? List.of() : attachments));
            log.info("Leave submission email dispatched: to={} cc={} attachments={}",
                to, ccList.size(), attachments == null ? 0 : attachments.size());
        } catch (Exception exception) {
            log.error("Failed to send leave submission email: to={} cc={} error={}",
                to, ccList, exception.getMessage());
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
            .append("GL&R Portal\n")
            .append("— อีเมลฉบับนี้ส่งจากระบบอัตโนมัติ กรุณาอย่าตอบกลับ");
        return message.toString();
    }

    /** Table-based, inline-styled HTML for email-client compatibility (no <style> blocks, no flexbox/grid). Every
     * interpolated value is HTML-escaped before insertion - body in particular is often free text typed by a
     * manager (e.g. a leave reviewerNote) and must not be able to inject markup. */
    private String htmlBody(String recipientName, String body, String link) {
        String cleanName = clean(recipientName);
        String greeting = cleanName.isBlank()
            ? "เรียน ท่านผู้ใช้งาน,"
            : "เรียน คุณ" + escapeHtml(cleanName) + ",";
        String messageHtml = escapeHtml(clean(body)).replace("\r\n", "\n").replace("\n", "<br>");
        String cta = ctaHtml(link);
        return wrapShell(
            "<p style=\"margin:0 0 16px 0;\">" + greeting + "</p>"
                + "<p style=\"margin:0;\">" + messageHtml + "</p>"
                + cta);
    }

    /** Plain-text alternative for the leave-submission letter: the letter verbatim (control chars are sanitized
     * downstream by the PDF/mail layer), the portal link, and the standard auto-mail footer #textBody uses. The
     * letter already carries its own greeting and signature, so no เรียน คุณ... */
    private String letterTextContent(String letterBody, String link) {
        StringBuilder message = new StringBuilder(clean(letterBody)).append("\n\n");
        String cleanLink = clean(link);
        if (!cleanLink.isBlank()) {
            message.append("ดูรายละเอียดในระบบ: ").append(appBaseUrl).append(normalizeLink(cleanLink)).append("\n\n");
        }
        message.append("GL&R Portal\n")
            .append("— อีเมลฉบับนี้ส่งจากระบบอัตโนมัติ กรุณาอย่าตอบกลับ");
        return message.toString();
    }

    /** HTML content (the inner block placed inside #wrapShell) for the leave-submission letter: the letter
     * HTML-escaped with newlines turned into , plus the portal CTA. */
    private String letterHtmlContent(String letterBody, String link) {
        String letterHtml = escapeHtml(clean(letterBody)).replace("\r\n", "\n").replace("\n", "<br>");
        return "<p style=\"margin:0;\">" + letterHtml + "</p>" + ctaHtml(link);
    }

    /** Wraps caller-supplied content HTML in the shared branded shell (inline logo header, brand rule, auto-mail
     * footer). Extracted from #htmlBody so the notification body and the leave-submission letter render in one
     * identical shell instead of two copies that can drift. */
    private String wrapShell(String contentHtml) {
        String logoSrc = "cid:" + LOGO_CONTENT_ID;

        // The GL&R artwork is black-on-white with NO alpha channel, so the header band stays white and the brand
        // colour appears as the rule beneath it (and on the CTA) instead. Painting the brand colour behind this logo
        // would show a white rectangle floating on indigo.
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
                            %s
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:0 32px 32px 32px;">
                            <hr style="border:none;border-top:1px solid #e5e7eb;margin:8px 0 16px 0;" />
                            <p style="margin:0 0 4px 0;font-family:%s;font-size:12px;color:#6b7280;">GL&amp;R Portal</p>
                            <p style="margin:0;font-family:%s;font-size:12px;color:#9ca3af;">— อีเมลฉบับนี้ส่งจากระบบอัตโนมัติ กรุณาอย่าตอบกลับ</p>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
              </body>
            </html>
            """.formatted(BRAND_COLOR, logoSrc, FONT_STACK, FONT_STACK, contentHtml, FONT_STACK, FONT_STACK);
    }

    /** Bulletproof CTA: a background-colored <table> with a padded {@code }, not a styled <div> - Outlook (Word
     * rendering engine) ignores padding/border-radius on anchors but honours them on table cells. Only rendered
     * when link is non-blank. */
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

    /** Escapes the five HTML-significant characters. No third-party dependency - this is the entire surface a
     * plain-text notification body/name/link can hit. Run BEFORE converting "\n" to "<br>". */
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
