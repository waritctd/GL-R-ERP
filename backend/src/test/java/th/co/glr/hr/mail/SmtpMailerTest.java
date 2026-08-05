package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Session;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailerTest {

    /** A real MimeMessage (no mock) - JavaMailSenderImpl.createMimeMessage() would also return one of
     * these, and MimeMessageHelper needs a real Session-backed instance to build multipart content. */
    private static MimeMessage newMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

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

    @Test
    void sendHtmlProducesAMultipartMessageWithBothAlternatives() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = newMimeMessage();
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        SmtpMailer mailer = new SmtpMailer("job@glr.co.th", sender);

        mailer.sendHtml("employee.personal@gmail.com", "OT submitted", "<p>Hello</p>", "Hello");

        verify(sender).send(mimeMessage);
        // saveChanges() finalizes the Content-Type header from the DataHandler chain that
        // MimeMessageHelper built. Normally Transport.send() does this as part of a real send; the
        // mocked sender here bypasses that, so the test does it explicitly.
        mimeMessage.saveChanges();
        assertThat(mimeMessage.getContentType()).startsWith("multipart/mixed");
        assertThat(mimeMessage.getFrom()[0].toString()).isEqualTo("job@glr.co.th");
        assertThat(mimeMessage.getAllRecipients()[0].toString()).isEqualTo("employee.personal@gmail.com");
        assertThat(mimeMessage.getSubject()).isEqualTo("OT submitted");

        var writer = new java.io.ByteArrayOutputStream();
        mimeMessage.writeTo(writer);
        String raw = writer.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(raw).contains("multipart/alternative");
        assertThat(raw).contains("<p>Hello</p>");
        assertThat(raw).contains("Hello");
    }

    @Test
    void sendHtmlWrapsTransportFailureInOurMailSendException() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(newMimeMessage());
        doThrow(new MailSendException("boom")).when(sender).send((MimeMessage) org.mockito.ArgumentMatchers.any());
        SmtpMailer mailer = new SmtpMailer("job@glr.co.th", sender);

        assertThatThrownBy(() -> mailer.sendHtml("x@example.com", "s", "<p>b</p>", "b"))
            .isInstanceOf(th.co.glr.hr.mail.MailSendException.class)
            .hasMessageContaining("SMTP HTML send failed to x@example.com");
    }
}
