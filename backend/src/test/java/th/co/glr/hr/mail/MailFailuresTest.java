package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.resend.core.exception.ResendException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;

/**
 * Which send failures prove the provider did NOT accept the mail (so a retry cannot double it) and which leave that
 * unknowable. The release notice records only the first kind as FAILED; the second kind gets no outcome, shows
 * "sending" then "ไม่ทราบผล", and is re-sent only through the warned opt-in (fix round 2 ruling).
 *
 * <p>Shapes are what each transport really throws: the Resend SDK (4.13) turns an HTTP error response into a
 * {@link ResendException} with a status code, and wraps any OkHttp {@code IOException} (timeout, reset, interrupt) in
 * a bare {@code RuntimeException}; Spring's JavaMailSender (SMTP) throws its own {@code MailException} family with the
 * Jakarta Mail cause inside.
 */
class MailFailuresTest {

    // ── Certainly not accepted ────────────────────────────────────────────────────────────────────────────────

    @Test
    void aResendErrorResponseIsARejection() {
        ResendException rejected = new ResendException(422, "{\"name\":\"validation_error\",\"message\":\"bad\"}");
        assertThat(MailFailures.certainlyNotAccepted(new MailSendException("Resend send failed", rejected))).isTrue();
        ResendException server = new ResendException(500, "{\"name\":\"internal_server_error\",\"message\":\"x\"}");
        assertThat(MailFailures.certainlyNotAccepted(new MailSendException("Resend send failed", server))).isTrue();
    }

    @Test
    void aMailerThatDoesNotDeliverIsARejection() {
        assertThat(MailFailures.certainlyNotAccepted(new MailNotDeliveredException("mail provider not configured")))
            .isTrue();
    }

    @Test
    void neverConnectingIsARejection() {
        assertThat(MailFailures.certainlyNotAccepted(new RuntimeException(new ConnectException("refused")))).isTrue();
        assertThat(MailFailures.certainlyNotAccepted(new RuntimeException(new UnknownHostException("api.example"))))
            .isTrue();
        assertThat(MailFailures.certainlyNotAccepted(new org.springframework.mail.MailSendException(
            "Mail server connection failed", new MessagingException("connect", new ConnectException("refused")))))
            .isTrue();
    }

    @Test
    void smtpRefusalsBeforeOrAtTheEnvelopeAreRejections() {
        assertThat(MailFailures.certainlyNotAccepted(new MailSendException("SMTP HTML send failed",
            new MailAuthenticationException("535 auth failed")))).isTrue();
        assertThat(MailFailures.certainlyNotAccepted(new MailSendException("SMTP HTML send failed",
            new org.springframework.mail.MailSendException("failed", new SendFailedException("550 rejected")))))
            .isTrue();
        assertThat(MailFailures.certainlyNotAccepted(new MailSendException("SMTP HTML send failed",
            new MailPreparationException("could not build")))).isTrue();
        // What JavaMailSenderImpl really throws after Transport.sendMessage fails: no cause, the per-message
        // exceptions in failedMessages. The refusal must be found there.
        assertThat(MailFailures.certainlyNotAccepted(new MailSendException("SMTP HTML send failed",
            new org.springframework.mail.MailSendException(
                java.util.Map.of(new Object(), new SendFailedException("550 mailbox unavailable")))))).isTrue();
    }

    // ── Unknowable: the request may have reached the provider ────────────────────────────────────────────────

    @Test
    void anInterruptOrATimeoutOrAResetDuringTheCallIsUnknowable() {
        assertThat(MailFailures.certainlyNotAccepted(new RuntimeException(new InterruptedIOException("interrupted"))))
            .isFalse();
        assertThat(MailFailures.certainlyNotAccepted(new RuntimeException(new SocketTimeoutException("read timed out"))))
            .isFalse();
        assertThat(MailFailures.certainlyNotAccepted(new RuntimeException(new SocketException("Connection reset"))))
            .isFalse();
        assertThat(MailFailures.certainlyNotAccepted(new MailSendException("SMTP HTML send failed",
            new org.springframework.mail.MailSendException("failed",
                new MessagingException("IO during DATA", new SocketTimeoutException("read timed out")))))).isFalse();
    }

    @Test
    void anythingUnrecognisedIsUnknowable() {
        assertThat(MailFailures.certainlyNotAccepted(new IllegalStateException("boom"))).isFalse();
        assertThat(MailFailures.certainlyNotAccepted(new MailSendException("provider refused", null))).isFalse();
        assertThat(MailFailures.certainlyNotAccepted(new MailSendException("Resend",
            new ResendException("no status")))).isFalse();
        assertThat(MailFailures.certainlyNotAccepted(null)).isFalse();
    }
}
