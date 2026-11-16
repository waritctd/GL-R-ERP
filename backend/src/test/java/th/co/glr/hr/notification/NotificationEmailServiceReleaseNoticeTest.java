package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.mail.MailSendException;
import th.co.glr.hr.mail.Mailer;

/**
 * How NotificationEmailService renders and sends one reader's release-notice email (the copy itself is
 * ReleaseNoticePolicy's). The title and why come from the publish request, so they are user-influenced input
 * reaching an email: every one is escaped in the HTML part.
 */
class NotificationEmailServiceReleaseNoticeTest {
    private static final String TO = "reader@example.com";

    private final Mailer mailer = mock(Mailer.class);

    private final ReleaseNoticeEmail email = new ReleaseNoticeEmail(
        "มีอะไรใหม่ 17 พ.ย. 2569: เมนูแบบใหม่",
        "ระบบ GL&R Portal อัปเดตเมื่อ 17 พ.ย. 2569 มี 7 เรื่องที่เกี่ยวกับงานของคุณ",
        List.of(
            new ReleaseNoticeEmail.Item("งานทั้งหมด <script>alert(1)</script>", "ดู \"ขั้น\" & 'งาน' <b>ทันที</b>"),
            new ReleaseNoticeEmail.Item("นัดเข้าพบ", null)),
        "และอีก 5 เรื่อง",
        "วิธีใช้ทีละขั้นตอนอยู่ในพอร์ทัล",
        "เปิดดูมีอะไรใหม่",
        "/whats-new/2026-11-17");

    private Rendered send(NotificationEmailService service) {
        service.sendReleaseNotice(TO, "ยา", email);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Mailer.InlineImage>> images = ArgumentCaptor.forClass(List.class);
        verify(mailer).sendHtml(eq(TO), subject.capture(), html.capture(), text.capture(), images.capture());
        return new Rendered(subject.getValue(), html.getValue(), text.getValue(), images.getValue());
    }

    private record Rendered(String subject, String html, String text, List<Mailer.InlineImage> images) {
    }

    private NotificationEmailService service(String subjectSuffix) {
        return new NotificationEmailService(mailer, new BrandAssets(), "", subjectSuffix, "https://glr-hub.com/");
    }

    @Test
    void theSubjectIsPrefixedAndSuffixedLikeEveryNotification() {
        assertThat(send(service(" (UAT)")).subject())
            .isEqualTo("[GL&R Portal] มีอะไรใหม่ 17 พ.ย. 2569: เมนูแบบใหม่ (UAT)");
    }

    @Test
    void theHtmlEscapesEveryTitleAndWhy() {
        String html = send(service("")).html();

        assertThat(html).doesNotContain("<script>").doesNotContain("<b>ทันที</b>")
            .contains("งานทั้งหมด &lt;script&gt;alert(1)&lt;/script&gt;")
            .contains("ดู &quot;ขั้น&quot; &amp; &#39;งาน&#39; &lt;b&gt;ทันที&lt;/b&gt;")
            .contains("เรียน คุณยา")
            .contains("ระบบ GL&amp;R Portal อัปเดตเมื่อ 17 พ.ย. 2569")
            .contains("นัดเข้าพบ")
            .contains("และอีก 5 เรื่อง")
            .contains("วิธีใช้ทีละขั้นตอนอยู่ในพอร์ทัล");
    }

    @Test
    void theLinkIsAButtonAndAlsoVisibleAsPlainText() {
        String html = send(service("")).html();
        String url = "https://glr-hub.com/whats-new/2026-11-17";

        assertThat(html).contains("href=\"" + url + "\"").contains("เปิดดูมีอะไรใหม่");
        // The same URL appears as visible text, for a client that hides the button.
        assertThat(html).contains(">" + url + "</a>");
    }

    @Test
    void thePlainTextAlternativeCarriesTheItemsTheirWhyAndTheUrl() {
        String text = send(service("")).text();

        assertThat(text).contains("เรียน คุณยา")
            .contains("• งานทั้งหมด <script>alert(1)</script>")
            .contains("ดู \"ขั้น\" & 'งาน' <b>ทันที</b>")
            .contains("• นัดเข้าพบ")
            .contains("และอีก 5 เรื่อง")
            .contains("https://glr-hub.com/whats-new/2026-11-17")
            .doesNotContain("&lt;");
    }

    @Test
    void noAttachment_onlyTheBrandLogoInline() {
        Rendered rendered = send(service(""));

        assertThat(rendered.images()).extracting(Mailer.InlineImage::contentId).containsOnly("glr-logo");
        verify(mailer, never()).sendWithAttachments(anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void aProviderFailureIsRethrownSoTheCallerCanRecordIt() {
        doThrow(new MailSendException("refused", null))
            .when(mailer).sendHtml(eq(TO), anyString(), anyString(), anyString(), anyList());

        assertThatThrownBy(() -> service("").sendReleaseNotice(TO, "ยา", email))
            .isInstanceOf(MailSendException.class);
    }
}
