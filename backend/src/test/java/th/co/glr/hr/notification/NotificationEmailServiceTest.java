package th.co.glr.hr.notification;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import th.co.glr.hr.mail.Mailer;

// Locks in the app.mail.override-to / app.mail.subject-prefix behavior used to redirect every
// notification email to one real inbox on a test/UAT deployment (verifies the email pipeline works
// without needing real per-employee mailboxes). Both are blank by default so every other deployment
// keeps sending to the real employee address unchanged - see NotificationServiceTest for that path.
//
// Every send() now routes through Mailer.sendHtml(to, subject, html, text): assertions check both
// alternatives so a regression in either the HTML builder or the plain-text builder is caught.
class NotificationEmailServiceTest {
    private final Mailer mailer = mock(Mailer.class);

    @Test
    void sendsToRealAddressWhenNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, "", "", "https://portal.example", "https://assets.example");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "/leave/1");

        verify(mailer).sendHtml(
            eq("employee@glr.co.th"),
            eq("[GL&R HR] Leave submitted"),
            argThat(html -> html.contains("เรียน คุณสมชาย,")
                && html.contains("body text")
                && html.contains("https://portal.example/leave/1")
                && html.contains("ดูรายละเอียดในระบบ")
                && html.contains("ระบบบริหารงานบุคคล GL&amp;R")),
            argThat(text -> text.contains("เรียน คุณสมชาย,")
                && text.contains("body text")
                && text.contains("ดูรายละเอียดในระบบ: https://portal.example/leave/1")
                && text.contains("ระบบบริหารงานบุคคล GL&R")));
    }

    @Test
    void skipsSendWhenNoEmailAndNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, "", "", "https://portal.example", "https://assets.example");

        service.send(7L, null, "สมชาย", "Leave submitted", "body text", "/leave/1");

        verifyNoInteractions(mailer);
    }

    @Test
    void redirectsToOverrideAddressAndPrefixesSubjectWhenConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, "tester@example.com", "[TEST] ",
            "https://portal.example/", "https://assets.example");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "/leave/1");

        verify(mailer).sendHtml(
            eq("tester@example.com"),
            eq("[TEST] [GL&R HR] Leave submitted"),
            argThat(html -> html.contains("body text")
                && html.contains("https://portal.example/leave/1")
                && html.contains("Redirected for testing")
                && html.contains("employee@glr.co.th")),
            argThat(text -> text.contains("body text")
                && text.contains("ดูรายละเอียดในระบบ: https://portal.example/leave/1")
                && text.contains("Redirected for testing")
                && text.contains("employee@glr.co.th")));
    }

    @Test
    void redirectsToOverrideEvenWhenEmployeeHasNoEmailOnFile() {
        NotificationEmailService service = new NotificationEmailService(mailer, "tester@example.com", "[TEST] ",
            "https://portal.example", "https://assets.example");

        service.send(7L, null, null, "Leave submitted", "body text", null);

        verify(mailer).sendHtml(
            eq("tester@example.com"),
            anyString(),
            argThat(html -> html.contains("เรียน ท่านผู้ใช้งาน,")
                && html.contains("no email on file")
                && !html.contains("ดูรายละเอียดในระบบ")),
            argThat(text -> text.contains("เรียน ท่านผู้ใช้งาน,")
                && text.contains("no email on file")
                && !text.contains("ดูรายละเอียดในระบบ:")));
    }

    @Test
    void htmlEscapesUserSuppliedNameAndBody() {
        // reviewerNote-style free text: a manager can type anything, including markup. An unescaped
        // '<' or '&' would corrupt the email (or worse, inject markup) - assert the raw characters
        // never appear unescaped in the HTML alternative.
        NotificationEmailService service = new NotificationEmailService(mailer, "", "", "https://portal.example", "https://assets.example");

        service.send(7L, "employee@glr.co.th", "<b>Somchai</b> & Co",
            "Leave submitted", "Please review <script>alert('x')</script> & confirm", "/leave/1");

        verify(mailer).sendHtml(
            eq("employee@glr.co.th"),
            anyString(),
            argThat(html -> !html.contains("<script>")
                && !html.contains("<b>Somchai</b>")
                && html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")
                && html.contains("&amp; confirm")
                && html.contains("&lt;b&gt;Somchai&lt;/b&gt; &amp; Co")),
            anyString());
    }

    @Test
    void htmlPreservesLineBreaksAfterEscaping() {
        NotificationEmailService service = new NotificationEmailService(mailer, "", "", "https://portal.example", "https://assets.example");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "line one\nline two", null);

        verify(mailer).sendHtml(
            eq("employee@glr.co.th"),
            anyString(),
            argThat(html -> html.contains("line one<br>line two")),
            anyString());
    }

    @Test
    void ctaOnlyRenderedWhenLinkIsNonBlank() {
        NotificationEmailService service = new NotificationEmailService(mailer, "", "", "https://portal.example", "https://assets.example");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "   ");

        verify(mailer).sendHtml(
            eq("employee@glr.co.th"),
            anyString(),
            argThat(html -> !html.contains("ดูรายละเอียดในระบบ")),
            anyString());
    }

    // app-base-url (the Vercel frontend) and asset-base-url (the backend's own Render origin) are
    // deliberately different hosts in this test: a Vercel branch-preview with Deployment Protection
    // enabled would auth-wall Gmail's image proxy fetching the logo, silently breaking it in every
    // email, while the portal CTA link must still resolve to the frontend the user actually uses.
    // Asserting they're distinct in the emitted HTML is what would have caught the two concerns being
    // wired to the same property.
    @Test
    void logoUsesAssetBaseUrlWhilePortalCtaUsesAppBaseUrl() {
        NotificationEmailService service = new NotificationEmailService(mailer, "", "",
            "https://portal.example", "https://assets.example");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "/leave/1");

        verify(mailer).sendHtml(
            eq("employee@glr.co.th"),
            anyString(),
            argThat(html -> html.contains("src=\"https://assets.example/api/public/brand/logo.png\"")
                && !html.contains("src=\"https://portal.example/api/public/brand/logo.png\"")
                && html.contains("href=\"https://portal.example/leave/1\"")
                && !html.contains("href=\"https://assets.example/leave/1\"")),
            anyString());
    }
}
