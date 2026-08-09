package th.co.glr.hr.notification;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.mail.Mailer;

// Locks in the app.mail.override-to / app.mail.subject-suffix behavior used to redirect every
// notification email to one real inbox on a test/UAT deployment (verifies the email pipeline works
// without needing real per-employee mailboxes). Both are blank by default so every other deployment
// keeps sending to the real employee address unchanged - see NotificationServiceTest for that path.
//
// Every send() now routes through Mailer.sendHtml(to, subject, html, text, inlineImages): assertions
// check both text alternatives (so a regression in either builder is caught) plus that the GL&R logo
// is always handed to the Mailer as an inline image (so a regression that silently drops the
// attachment while leaving the <img src="cid:..."> in place would be caught too).
class NotificationEmailServiceTest {
    private final Mailer mailer = mock(Mailer.class);

    /** True when the inline-image list is exactly the GL&R logo the constructor loads from the
     * classpath (static/brand/glr-logo.png, via BrandAssets.logoBytes()) - real bytes, not a
     * test fixture, since NotificationEmailService always loads the real asset.
     *
     * <p>MUTATION-CHECK (verified live, not just by reasoning): temporarily changed {@code send()}
     * to call {@code mailer.sendHtml(..., List.of())} instead of {@code logoInlineImages} -- 7 of
     * this class's 8 tests went red (every send-path one; only {@code
     * skipsSendWhenNoEmailAndNoOverrideConfigured} stayed green, correctly, since it never sends),
     * plus {@code NotificationServiceTest}'s equivalent assertion. Reverted to an empty diff. */
    private static boolean hasGlrLogoInline(List<Mailer.InlineImage> images) {
        return images.size() == 1
            && "glr-logo".equals(images.get(0).contentId())
            && "image/png".equals(images.get(0).mimeType())
            && images.get(0).bytes().length > 0;
    }

    @Test
    void sendsToRealAddressWhenNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.example");

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
                && text.contains("ระบบบริหารงานบุคคล GL&R")),
            argThat(NotificationEmailServiceTest::hasGlrLogoInline));
    }

    @Test
    void skipsSendWhenNoEmailAndNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.example");

        service.send(7L, null, "สมชาย", "Leave submitted", "body text", "/leave/1");

        verifyNoInteractions(mailer);
    }

    // MUTATION-CHECK (verified live, not just by reasoning): temporarily changed send() back to
    // `subjectSuffix + "[GL&R HR] " + subject` (the old prefix order) -- exactly this one test went
    // red, nothing else. Separately, reverted redirectNote to its old bracketed
    // "[Redirected for testing - ...]" shape -- again exactly this one test (the !contains("[Redirected")
    // assertion) went red. Both reverted to an empty diff.
    @Test
    void redirectsToOverrideAddressAndSuffixesSubjectWhenConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "tester@example.com", " (UAT)",
            "https://portal.example/");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "/leave/1");

        verify(mailer).sendHtml(
            eq("tester@example.com"),
            eq("[GL&R HR] Leave submitted (UAT)"),
            argThat(html -> html.contains("body text")
                && html.contains("https://portal.example/leave/1")
                && html.contains("Redirected for testing")
                && html.contains("employee@glr.co.th")
                // The redirect note used to be wrapped in a literal "[...]" - the same
                // classic-filter-trigger shape as the old subject prefix. It is dropped from the
                // body too now, not just the subject.
                && !html.contains("[Redirected")),
            argThat(text -> text.contains("body text")
                && text.contains("ดูรายละเอียดในระบบ: https://portal.example/leave/1")
                && text.contains("Redirected for testing")
                && text.contains("employee@glr.co.th")
                && !text.contains("[Redirected")),
            argThat(NotificationEmailServiceTest::hasGlrLogoInline));
    }

    @Test
    void redirectsToOverrideEvenWhenEmployeeHasNoEmailOnFile() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "tester@example.com", " (UAT)",
            "https://portal.example");

        service.send(7L, null, null, "Leave submitted", "body text", null);

        verify(mailer).sendHtml(
            eq("tester@example.com"),
            anyString(),
            argThat(html -> html.contains("เรียน ท่านผู้ใช้งาน,")
                && html.contains("no email on file")
                && !html.contains("ดูรายละเอียดในระบบ")),
            argThat(text -> text.contains("เรียน ท่านผู้ใช้งาน,")
                && text.contains("no email on file")
                && !text.contains("ดูรายละเอียดในระบบ:")),
            argThat(NotificationEmailServiceTest::hasGlrLogoInline));
    }

    @Test
    void htmlEscapesUserSuppliedNameAndBody() {
        // reviewerNote-style free text: a manager can type anything, including markup. An unescaped
        // '<' or '&' would corrupt the email (or worse, inject markup) - assert the raw characters
        // never appear unescaped in the HTML alternative.
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

    // The logo used to be a remote <img src> built from a SEPARATE asset-base-url host (the
    // backend's own Render origin), specifically to dodge a Vercel Deployment-Protection auth wall
    // that could block Gmail's image-fetching proxy. That whole failure mode - and the Gmail
    // spam-folder image-blocking defect that actually reached UAT - is gone now that the logo
    // travels INSIDE the message as a cid: reference: there is no URL for anything to block. What's
    // worth pinning now: the HTML references "cid:glr-logo" (not a URL), the portal CTA still
    // resolves through app-base-url (the only base URL left), and an inline image with that exact
    // content-id is actually handed to the Mailer - so a regression that drops the attachment while
    // leaving the <img src> alone would be caught.
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

    // sendWithAttachment (payslips - real attachments to real employees) had the SAME bracketed
    // "[Redirected for testing - ...]" shape send() used to have, just not caught in the first pass
    // since this method has no test coverage of its override branch at all until now. Same defect
    // class as the subject/body fix above, on a path that arguably matters more (a payslip has a
    // real attachment). No pre-existing test asserted the old bracketed text, so there is nothing to
    // update elsewhere - this is net-new coverage.
    //
    // MUTATION-CHECK (verified live, not just by reasoning): temporarily reverted the body back to
    // `"\n\n[Redirected for testing - originally addressed to " + ... + "]"` -- exactly this test
    // went red (the !contains("[Redirected") assertion), nothing else in this class. Reverted to an
    // empty diff.
    @Test
    void sendWithAttachmentDropsTheBracketedRedirectShapeWhenOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(),
            "tester@example.com", " (UAT)", "https://portal.example");

        service.sendWithAttachment("employee@glr.co.th", "Payslip", "Attached", "payslip.pdf", "%PDF".getBytes());

        verify(mailer).sendWithAttachment(
            eq("tester@example.com"),
            eq("Payslip (UAT)"),
            argThat(body -> body.contains("Attached")
                && body.contains("Redirected for testing")
                && body.contains("employee@glr.co.th")
                && !body.contains("[Redirected")),
            eq("payslip.pdf"),
            aryEq("%PDF".getBytes()));
    }
}
