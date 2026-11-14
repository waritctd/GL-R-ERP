package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.mail.Mailer;

// Issue #782 narrowed what this class is responsible for: recipient redirection (app.mail.override-to) now
// lives entirely in th.co.glr.hr.mail.OverrideRedirectingMailer, which wraps the Mailer bean this class is
// handed - see that class, and NotificationEmailService's own overrideConfigured Javadoc.
class NotificationEmailServiceTest {
    private final Mailer mailer = mock(Mailer.class);

    /** True when the inline-image list is exactly the GL&R logo the constructor loads from the classpath
     * (static/brand/glr-logo.png, via BrandAssets.logoBytes()) - real bytes, not a test fixture, since
     * NotificationEmailService always loads the real asset. */
    private static boolean hasGlrLogoInline(List<Mailer.InlineImage> images) {
        return images.size() == 1
            && "glr-logo".equals(images.get(0).contentId())
            && "image/png".equals(images.get(0).mimeType())
            && images.get(0).bytes().length > 0;
    }

    // This class never emits its own "Redirected for testing" note any more (see the class comment above) - so the
    // absence assertions here hold unconditionally now, not just when overrideTo is blank.
    @Test
    void sendsToRealAddressWhenNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.example");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "/leave/1");

        verify(mailer).sendHtml(
            eq("employee@glr.co.th"),
            eq("[GL&R Portal] Leave submitted"),
            argThat(html -> html.contains("เรียน คุณสมชาย,")
                && html.contains("body text")
                && html.contains("https://portal.example/leave/1")
                && html.contains("ดูรายละเอียดในระบบ")
                && html.contains(">GL&amp;R Portal<") && !html.contains("ระบบบริหารงานบุคคล")
                && !html.contains("Redirected for testing")),
            argThat(text -> text.contains("เรียน คุณสมชาย,")
                && text.contains("body text")
                && text.contains("ดูรายละเอียดในระบบ: https://portal.example/leave/1")
                && text.contains("\nGL&R Portal\n") && !text.contains("ระบบบริหารงานบุคคล")
                && !text.contains("Redirected for testing")),
            argThat(NotificationEmailServiceTest::hasGlrLogoInline));
    }

    @Test
    void skipsSendWhenNoEmailAndNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.example");

        service.send(7L, null, "สมชาย", "Leave submitted", "body text", "/leave/1");

        verifyNoInteractions(mailer);
    }

    // Formerly "redirectsToOverrideAddressAndSuffixesSubjectWhenConfigured": before #782's fix this asserted
    // mailer.sendHtml received the OVERRIDE address ("tester@example.com"), because this class used to compute
    // that swap itself.
    @Test
    void passesRecipientThroughUnchangedAndAppliesSubjectSuffixEvenWithOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "tester@example.com", " (UAT)",
            "https://portal.example/");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "/leave/1");

        verify(mailer).sendHtml(
            eq("employee@glr.co.th"), // NOT swapped to "tester@example.com" - see OverrideRedirectingMailerTest for that
            eq("[GL&R Portal] Leave submitted (UAT)"),
            argThat(html -> html.contains("body text")
                && html.contains("https://portal.example/leave/1")
                && !html.contains("Redirected for testing")
                && !html.contains("[Redirected")),
            argThat(text -> text.contains("body text")
                && text.contains("ดูรายละเอียดในระบบ: https://portal.example/leave/1")
                && !text.contains("Redirected for testing")
                && !text.contains("[Redirected")),
            argThat(NotificationEmailServiceTest::hasGlrLogoInline));
    }

    // Formerly "redirectsToOverrideEvenWhenEmployeeHasNoEmailOnFile", which also asserted the HTML/text contained
    // "no email on file".
    @Test
    void attemptsTheSendWhenEmployeeHasNoEmailOnFileButOverrideIsConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "tester@example.com", " (UAT)",
            "https://portal.example");

        service.send(7L, null, null, "Leave submitted", "body text", null);

        verify(mailer).sendHtml(
            isNull(),
            eq("[GL&R Portal] Leave submitted (UAT)"),
            argThat(html -> html.contains("เรียน ท่านผู้ใช้งาน,")
                && html.contains("body text")
                && !html.contains("ดูรายละเอียดในระบบ")
                && !html.contains("Redirected for testing")),
            argThat(text -> text.contains("เรียน ท่านผู้ใช้งาน,")
                && text.contains("body text")
                && !text.contains("ดูรายละเอียดในระบบ:")),
            argThat(NotificationEmailServiceTest::hasGlrLogoInline));
    }

    @Test
    void htmlEscapesUserSuppliedNameAndBody() {
        // reviewerNote-style free text: a manager can type anything, including markup. An unescaped '<' or '&' would
        // corrupt the email (or worse, inject markup) - assert the raw characters never appear unescaped in the HTML
        // alternative.
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.example");

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
            anyString(),
            argThat(NotificationEmailServiceTest::hasGlrLogoInline));
    }

    @Test
    void htmlPreservesLineBreaksAfterEscaping() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.example");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "line one\nline two", null);

        verify(mailer).sendHtml(
            eq("employee@glr.co.th"),
            anyString(),
            argThat(html -> html.contains("line one<br>line two")),
            anyString(),
            argThat(NotificationEmailServiceTest::hasGlrLogoInline));
    }

    @Test
    void ctaOnlyRenderedWhenLinkIsNonBlank() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.example");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "   ");

        verify(mailer).sendHtml(
            eq("employee@glr.co.th"),
            anyString(),
            argThat(html -> !html.contains("ดูรายละเอียดในระบบ")),
            anyString(),
            argThat(NotificationEmailServiceTest::hasGlrLogoInline));
    }

    // The logo used to be a remote <img src> built from a SEPARATE asset-base-url host (the backend's own Render
    // origin), specifically to dodge a Vercel Deployment-Protection auth wall that could block Gmail's
    // image-fetching proxy.
    @Test
    void logoIsCidInlineImageWhilePortalCtaUsesAppBaseUrlLink() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.example");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "/leave/1");

        verify(mailer).sendHtml(
            eq("employee@glr.co.th"),
            anyString(),
            argThat(html -> html.contains("src=\"cid:glr-logo\"")
                && !html.contains("src=\"https://")
                && html.contains("href=\"https://portal.example/leave/1\"")),
            anyString(),
            argThat(images -> images.size() == 1
                && images.get(0).contentId().equals("glr-logo")
                && images.get(0).filename().equals("glr-logo.png")
                && images.get(0).mimeType().equals("image/png")
                && images.get(0).bytes().length > 0));
    }

    // Formerly "sendWithAttachmentDropsTheBracketedRedirectShapeWhenOverrideConfigured". Before #782's fix this
    // asserted mailer.sendWithAttachment received the OVERRIDE address and a body carrying a redirect note,
    // because this method used to compute both itself.
    @Test
    void sendWithAttachmentPassesRecipientAndBodyThroughUnchangedEvenWithOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(),
            "tester@example.com", " (UAT)", "https://portal.example");

        service.sendWithAttachment("employee@glr.co.th", "Payslip", "Attached", "payslip.pdf", "%PDF".getBytes());

        verify(mailer).sendWithAttachment(
            eq("employee@glr.co.th"),
            eq("Payslip (UAT)"),
            argThat(body -> body.contains("Attached")
                && !body.contains("Redirected for testing")
                && !body.contains("[Redirected")),
            eq("payslip.pdf"),
            aryEq("%PDF".getBytes()));
    }

    // The no-override baseline this class has always had, still true after #782 - kept alongside the with-override
    // test above (rather than merged) to show the invariant ("no note, recipient unchanged") holds in EITHER
    // configuration, not only the blank one.
    @Test
    void sendWithAttachmentOmitsTheRedirectNoteWhenNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.example");

        service.sendWithAttachment("employee@glr.co.th", "Payslip", "Attached", "payslip.pdf", "%PDF".getBytes());

        verify(mailer).sendWithAttachment(
            eq("employee@glr.co.th"),
            eq("Payslip"),
            argThat(body -> body.contains("Attached")
                && !body.contains("Redirected for testing")
                && !body.contains("[Redirected")),
            eq("payslip.pdf"),
            aryEq("%PDF".getBytes()));
    }

    @Test
    void sendWithAttachmentThrowsWhenNoRecipientAndNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.example");

        assertThatThrownBy(() -> service.sendWithAttachment(null, "Payslip", "Attached", "payslip.pdf", "%PDF".getBytes()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Email recipient is required");
        verifyNoInteractions(mailer);
    }

    // New coverage (no prior test drove this method's blank-to branch at all): parity with
    // attemptsTheSendWhenEmployeeHasNoEmailOnFileButOverrideIsConfigured above - the same "attempt anyway, let the
    // Mailer redirect it" rescue applies to the attachment path too, not only the async notification path.
    @Test
    void sendWithAttachmentAttemptsTheSendWhenNoRecipientButOverrideIsConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "tester@example.com", "",
            "https://portal.example");

        service.sendWithAttachment(null, "Payslip", "Attached", "payslip.pdf", "%PDF".getBytes());

        verify(mailer).sendWithAttachment(isNull(), eq("Payslip"), eq("Attached"), eq("payslip.pdf"),
            aryEq("%PDF".getBytes()));
    }
}
