package th.co.glr.hr.notification;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

}
