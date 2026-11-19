package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.resend.core.exception.ResendException;
import org.junit.jupiter.api.Test;

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
        // 503: the service refused the request outright (round 3 ruling: stays FAILED; 500/502/504 are UNKNOWN).
        ResendException unavailable = new ResendException(503, "{\"name\":\"unavailable\",\"message\":\"x\"}");
        assertThat(MailFailures.certainlyNotAccepted(new MailSendException("Resend send failed", unavailable)))
            .isTrue();
    }

}
