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
class NotificationEmailServiceTest {
    private final Mailer mailer = mock(Mailer.class);

    @Test
    void sendsToRealAddressWhenNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, "", "", "https://portal.example");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "/leave/1");

        verify(mailer).send(
            eq("employee@glr.co.th"),
            eq("[GL&R HR] Leave submitted"),
            argThat(body -> body.contains("เรียน คุณสมชาย,")
                && body.contains("body text")
                && body.contains("ดูรายละเอียดในระบบ: https://portal.example/leave/1")
                && body.contains("ระบบบริหารงานบุคคล GL&R")));
    }

    @Test
    void skipsSendWhenNoEmailAndNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, "", "", "https://portal.example");

        service.send(7L, null, "สมชาย", "Leave submitted", "body text", "/leave/1");

        verifyNoInteractions(mailer);
    }

    @Test
    void redirectsToOverrideAddressAndPrefixesSubjectWhenConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, "tester@example.com", "[TEST] ",
            "https://portal.example/");

        service.send(7L, "employee@glr.co.th", "สมชาย", "Leave submitted", "body text", "/leave/1");

        verify(mailer).send(
            eq("tester@example.com"),
            eq("[TEST] [GL&R HR] Leave submitted"),
            argThat(body -> body.contains("body text")
                && body.contains("ดูรายละเอียดในระบบ: https://portal.example/leave/1")
                && body.contains("Redirected for testing")
                && body.contains("employee@glr.co.th")));
    }

    @Test
    void redirectsToOverrideEvenWhenEmployeeHasNoEmailOnFile() {
        NotificationEmailService service = new NotificationEmailService(mailer, "tester@example.com", "[TEST] ",
            "https://portal.example");

        service.send(7L, null, null, "Leave submitted", "body text", null);

        verify(mailer).send(
            eq("tester@example.com"),
            anyString(),
            argThat(body -> body.contains("เรียน ท่านผู้ใช้งาน,")
                && body.contains("no email on file")
                && !body.contains("ดูรายละเอียดในระบบ:")));
    }
}
