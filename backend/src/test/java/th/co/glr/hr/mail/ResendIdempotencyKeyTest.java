package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

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

    @Test
    void everyOtherEmailTypeSendsNoRequestOptions() {
        Recording transport = new Recording();
        ResendMailer mailer = new ResendMailer(transport, "notifications@example.com");

        mailer.sendHtml("reader@example.com", "s", "<p>h</p>", "t", List.of());
        mailer.send("reader@example.com", "s", "b");

        assertThat(transport.options).containsOnlyNulls().hasSize(2);
    }

    @Test
    void theOverrideDecoratorRedirectsAndKeepsTheKey() {
        Mailer delegate = mock(Mailer.class, withSettings().extraInterfaces(KeyedHtmlMailer.class));
        Mailer redirecting = new OverrideRedirectingMailer(delegate, "uat@example.com");

        ((KeyedHtmlMailer) redirecting).sendHtmlOnce("reader@example.com", "s", "<p>h</p>", "t", List.of(), "k-1");

        verify((KeyedHtmlMailer) delegate).sendHtmlOnce(eq("uat@example.com"), eq("s"), anyString(), anyString(),
            anyList(), eq("k-1"));
        verify(delegate, never()).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void theOverrideDecoratorFallsBackToThePlainSendForAMailerWithoutKeys() {
        Mailer delegate = mock(Mailer.class);
        Mailer redirecting = new OverrideRedirectingMailer(delegate, "uat@example.com");

        ((KeyedHtmlMailer) redirecting).sendHtmlOnce("reader@example.com", "s", "<p>h</p>", "t", List.of(), "k-1");

        verify(delegate).sendHtml(eq("uat@example.com"), eq("s"), anyString(), anyString(), any());
    }
}
