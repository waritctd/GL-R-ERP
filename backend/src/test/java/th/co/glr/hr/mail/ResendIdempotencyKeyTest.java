package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.resend.core.exception.ResendException;
import com.resend.core.net.RequestOptions;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The release notice sends its per-person attempt token as Resend's {@code Idempotency-Key} (fix round 3). Every other
 * email type is untouched: plain {@code sendHtml} sends no RequestOptions at all.
 */
class ResendIdempotencyKeyTest {

    /** A fake transport that records the RequestOptions of every call (null when the plain path was used). */
    private static final class Recording implements ResendMailer.ResendSender {
        final List<RequestOptions> options = new ArrayList<>();
        int failFirst;

        @Override
        public CreateEmailResponse send(CreateEmailOptions email) throws ResendException {
            return send(email, null);
        }

        @Override
        public CreateEmailResponse send(CreateEmailOptions email, RequestOptions requestOptions)
            throws ResendException {
            options.add(requestOptions);
            if (failFirst-- > 0) {
                throw new ResendException(429, "{\"name\":\"rate_limit_exceeded\",\"message\":\"slow down\"}");
            }
            CreateEmailResponse response = new CreateEmailResponse();
            response.setId("email-id");
            return response;
        }
    }

    @Test
    void theKeyedSendCarriesTheKey_andTheProvidersOwn429RetryReusesIt() {
        Recording transport = new Recording();
        transport.failFirst = 1;
        ResendMailer mailer = new ResendMailer(transport, "notifications@example.com");

        mailer.sendHtmlOnce("reader@example.com", "s", "<p>h</p>", "t", List.of(), "attempt-123");

        assertThat(transport.options).hasSize(2);
        assertThat(transport.options).allSatisfy(o -> assertThat(o.getIdempotencyKey()).isEqualTo("attempt-123"));
    }

}
