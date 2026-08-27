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

// Issue #782 narrowed what this class is responsible for: recipient redirection (app.mail.override-to)
// now lives entirely in th.co.glr.hr.mail.OverrideRedirectingMailer, which wraps the Mailer bean this
// class is handed - see that class, and NotificationEmailService's own overrideConfigured Javadoc.
// `mailer` below is a bare Mockito mock, NOT wrapped in the real decorator, so nothing in THIS file can
// observe an address actually being swapped any more - that assertion moved to
// OverrideRedirectingMailerTest / MailOverrideContainmentTest (both in th.co.glr.hr.mail), which drive
// this exact class through a REAL decorator. What stays here, and is still exactly this class's job:
//   - the send-or-skip decision when `to` is blank (an override rescues it; nothing else does)
//   - `to` reaching the Mailer completely unchanged either way (proves this class no longer swaps it)
//   - subject-suffix (an unrelated, still-local feature - #782 is only about the recipient)
//   - the HTML/text template itself: escaping, line breaks, CTA, the inline logo
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

    // This class never emits its own "Redirected for testing" note any more (see the class comment
    // above) - so the absence assertions here hold unconditionally now, not just when overrideTo is
    // blank. Kept anyway (rather than deleted) because a blank-override deployment is still the
    // baseline every OTHER test in this file implicitly relies on, and this is where that baseline
    // gets its own explicit pin.
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
                && html.contains("ระบบบริหารงานบุคคล GL&amp;R")
                && !html.contains("Redirected for testing")),
            argThat(text -> text.contains("เรียน คุณสมชาย,")
                && text.contains("body text")
                && text.contains("ดูรายละเอียดในระบบ: https://portal.example/leave/1")
                && text.contains("ระบบบริหารงานบุคคล GL&R")
                && !text.contains("Redirected for testing")),
            argThat(NotificationEmailServiceTest::hasGlrLogoInline));
    }

    @Test
    void skipsSendWhenNoEmailAndNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://portal.example");

        service.send(7L, null, "สมชาย", "Leave submitted", "body text", "/leave/1");

        verifyNoInteractions(mailer);
    }

    // Formerly "redirectsToOverrideAddressAndSuffixesSubjectWhenConfigured": before #782's fix this
    // asserted mailer.sendHtml received the OVERRIDE address ("tester@example.com"), because this
    // class used to compute that swap itself. It no longer does - see the class comment - so `to`
    // below is asserted UNCHANGED even with an override configured, which is now the whole point of
    // this test: proving this class stopped implementing that half of the rule. The subject-suffix
    // assertion is the one part of the old test that is still this class's own behaviour.
    //
    // MUTATION-CHECK (verified live, not just by reasoning, pre-#782): temporarily changed send() back
    // to `subjectSuffix + "[GL&R HR] " + subject` (the old prefix order) -- exactly this one test went
    // red, nothing else.
    @Test
    void passesRecipientThroughUnchangedAndAppliesSubjectSuffixEvenWithOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "tester@example.com", " (UAT)",
            "https://portal.example/");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "/leave/1");

        verify(mailer).sendHtml(
            eq("employee@glr.co.th"), // NOT swapped to "tester@example.com" - see OverrideRedirectingMailerTest for that
            eq("[GL&R HR] Leave submitted (UAT)"),
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

    // Formerly "redirectsToOverrideEvenWhenEmployeeHasNoEmailOnFile", which also asserted the HTML/text
    // contained "no email on file" - that phrase was always part of the OLD redirect note this class
    // used to generate (" (no email on file)." in the pre-#782 source), never part of the base
    // template, so it is gone from this class's output entirely now, not just when overrideTo is
    // blank; asserting its absence here would be as vacuous as asserting its presence used to be
    // informative. What THIS test still needs to prove, and does: an addressless employee is NOT
    // skipped when an override is configured (see overrideConfigured's Javadoc) - `to` reaches the
    // Mailer as null, the generic greeting is used (recipientName was also null), and no CTA renders
    // (link was also null) - the same content a no-override, no-link, no-name send would produce,
    // MINUS being skipped. The regression this guards - NotificationService used to gate on the
    // address before this class ever ran, so an addressless employee was silently dropped even with an
    // override configured (see NotificationServiceTest's equivalent test) - is unchanged by #782; only
    // WHERE the eventual redirect address comes from moved. See
    // MailOverrideContainmentTest#notificationEmailServiceStillRedirectsWhenTheEmployeeHasNoAddressOnFile
    // for the real-decorator version of this exact scenario, which is where that address is actually
    // pinned end-to-end.
    @Test
    void attemptsTheSendWhenEmployeeHasNoEmailOnFileButOverrideIsConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "tester@example.com", " (UAT)",
            "https://portal.example");

        service.send(7L, null, null, "Leave submitted", "body text", null);

        verify(mailer).sendHtml(
            isNull(),
            eq("[GL&R HR] Leave submitted (UAT)"),
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

    // Formerly "sendWithAttachmentDropsTheBracketedRedirectShapeWhenOverrideConfigured". Before #782's
    // fix this asserted mailer.sendWithAttachment received the OVERRIDE address and a body carrying a
    // redirect note, because this method used to compute both itself. Neither happens here any more -
    // `to` and `body` are asserted UNCHANGED even with an override configured. See
    // OverrideRedirectingMailerTest / MailOverrideContainmentTest for where the redirect + note
    // coverage on this exact Mailer method now lives.
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

    // The no-override baseline this class has always had, still true after #782 - kept alongside the
    // with-override test above (rather than merged) to show the invariant ("no note, recipient
    // unchanged") holds in EITHER configuration, not only the blank one.
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
    // attemptsTheSendWhenEmployeeHasNoEmailOnFileButOverrideIsConfigured above - the same "attempt
    // anyway, let the Mailer redirect it" rescue applies to the attachment path too, not only the
    // async notification path.
    @Test
    void sendWithAttachmentAttemptsTheSendWhenNoRecipientButOverrideIsConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, new BrandAssets(), "tester@example.com", "",
            "https://portal.example");

        service.sendWithAttachment(null, "Payslip", "Attached", "payslip.pdf", "%PDF".getBytes());

        verify(mailer).sendWithAttachment(isNull(), eq("Payslip"), eq("Attached"), eq("payslip.pdf"),
            aryEq("%PDF".getBytes()));
    }
}
