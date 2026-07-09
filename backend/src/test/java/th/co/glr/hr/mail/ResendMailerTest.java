package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailResponse;
import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResendMailerTest {

    private ResendMailer mailerWith(ResendMailer.ResendSender sender) {
        try {
            Constructor<ResendMailer> ctor =
                ResendMailer.class.getDeclaredConstructor(ResendMailer.ResendSender.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(sender, "job@glr.co.th");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void retriesOn429ThenSucceeds() {
        AtomicInteger calls = new AtomicInteger(0);
        ResendMailer mailer = mailerWith(options -> {
            int attempt = calls.incrementAndGet();
            if (attempt < 3) {
                throw new ResendException(429, "rate_limit_exceeded");
            }
            CreateEmailResponse response = new CreateEmailResponse();
            response.setId("email-id-123");
            return response;
        });

        mailer.send("employee@example.com", "Subject", "Body");

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void givesUpAfterMaxAttemptsOf429() {
        AtomicInteger calls = new AtomicInteger(0);
        ResendMailer mailer = mailerWith(options -> {
            calls.incrementAndGet();
            throw new ResendException(429, "rate_limit_exceeded");
        });

        assertThatThrownBy(() -> mailer.send("employee@example.com", "Subject", "Body"))
            .isInstanceOf(MailSendException.class);

        assertThat(calls.get()).isEqualTo(ResendMailer.MAX_ATTEMPTS);
    }

    @Test
    void doesNotRetryNon429Failures() {
        AtomicInteger calls = new AtomicInteger(0);
        ResendMailer mailer = mailerWith(options -> {
            calls.incrementAndGet();
            throw new ResendException(500, "internal_server_error");
        });

        assertThatThrownBy(() -> mailer.send("employee@example.com", "Subject", "Body"))
            .isInstanceOf(MailSendException.class)
            .hasMessageContaining("status=500");

        assertThat(calls.get()).isEqualTo(1);
    }
}
