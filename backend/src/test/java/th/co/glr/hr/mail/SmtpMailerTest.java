package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailerTest {

    @Test
    void requiresSmtpHostWhenProviderIsSmtp() {
        assertThatThrownBy(() ->
            new SmtpMailer("job@glr.co.th", "", 587, "job@glr.co.th", "pw", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.mail.smtp.host");
    }

    @Test
    void sendsAsConfiguredFromAddressToTheRecipient() {
        JavaMailSender sender = mock(JavaMailSender.class);
        SmtpMailer mailer = new SmtpMailer("job@glr.co.th", sender);

        mailer.send("employee.personal@gmail.com", "OT submitted", "body");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo("job@glr.co.th");            // 1-to-many: single sender
        assertThat(sent.getTo()).containsExactly("employee.personal@gmail.com"); // real recipient
        assertThat(sent.getSubject()).isEqualTo("OT submitted");
        assertThat(sent.getText()).isEqualTo("body");
    }

    @Test
    void wrapsTransportFailureInOurMailSendException() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailSendException("boom")).when(sender).send((SimpleMailMessage) org.mockito.ArgumentMatchers.any());
        SmtpMailer mailer = new SmtpMailer("job@glr.co.th", sender);

        assertThatThrownBy(() -> mailer.send("x@example.com", "s", "b"))
            .isInstanceOf(th.co.glr.hr.mail.MailSendException.class)
            .hasMessageContaining("SMTP send failed to x@example.com");
    }
}
