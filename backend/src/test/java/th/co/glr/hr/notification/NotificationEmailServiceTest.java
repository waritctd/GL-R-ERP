package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

// Locks in the app.mail.override-to / app.mail.subject-prefix behavior used to redirect every
// notification email to one real inbox on a test/UAT deployment (verifies the email pipeline works
// without needing real per-employee mailboxes). Both are blank by default so every other deployment
// keeps sending to the real employee address unchanged - see NotificationServiceTest for that path.
class NotificationEmailServiceTest {
    private final JavaMailSender mailer = mock(JavaMailSender.class);

    @Test
    void sendsToRealAddressWhenNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, "noreply@test.glr", "", "");

        service.send(7L, "employee@glr.co.th", "Leave submitted", "body text");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailer).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("employee@glr.co.th");
        assertThat(captor.getValue().getSubject()).isEqualTo("Leave submitted");
        assertThat(captor.getValue().getText()).isEqualTo("body text");
    }

    @Test
    void skipsSendWhenNoEmailAndNoOverrideConfigured() {
        NotificationEmailService service = new NotificationEmailService(mailer, "noreply@test.glr", "", "");

        service.send(7L, null, "Leave submitted", "body text");

        verifyNoInteractions(mailer);
    }

    @Test
    void redirectsToOverrideAddressAndPrefixesSubjectWhenConfigured() {
        NotificationEmailService service = new NotificationEmailService(
            mailer, "noreply@test.glr", "tester@example.com", "[TEST] ");

        service.send(7L, "employee@glr.co.th", "Leave submitted", "body text");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailer).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("tester@example.com");
        assertThat(captor.getValue().getSubject()).isEqualTo("[TEST] Leave submitted");
        assertThat(captor.getValue().getText())
            .contains("body text")
            .contains("Redirected for testing")
            .contains("employee@glr.co.th");
    }

    @Test
    void redirectsToOverrideEvenWhenEmployeeHasNoEmailOnFile() {
        NotificationEmailService service = new NotificationEmailService(
            mailer, "noreply@test.glr", "tester@example.com", "[TEST] ");

        service.send(7L, null, "Leave submitted", "body text");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailer).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("tester@example.com");
        assertThat(captor.getValue().getText()).contains("no email on file");
    }
}
