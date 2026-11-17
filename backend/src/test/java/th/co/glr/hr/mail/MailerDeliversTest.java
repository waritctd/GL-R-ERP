package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Mailer#delivers: whether a "sent" from this mailer means a mail actually left. The release notice records an
 * outcome per person and must never record SENT for a mailer that only logs (fix round 1, review MEDIUM-1).
 */
class MailerDeliversTest {

    @Test
    void theLogMailerSaysItDoesNotDeliver() {
        assertThat(new LogMailer().delivers()).isFalse();
    }

    @Test
    void aRealTransportDeliversByDefault() {
        Mailer transport = new Mailer() {
            @Override
            public void send(String to, String subject, String body) {
            }

            @Override
            public void sendHtml(String to, String subject, String html, String text, List<InlineImage> images) {
            }

            @Override
            public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
            }

            @Override
            public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {
            }
        };
        assertThat(transport.delivers()).isTrue();
    }

    @Test
    void theOverrideDecoratorDeliversExactlyWhenTheMailerItWrapsDoes() {
        Mailer logging = mock(Mailer.class);
        when(logging.delivers()).thenReturn(false);
        Mailer real = mock(Mailer.class);
        when(real.delivers()).thenReturn(true);

        assertThat(new OverrideRedirectingMailer(logging, "uat@example.com").delivers()).isFalse();
        assertThat(new OverrideRedirectingMailer(real, "uat@example.com").delivers()).isTrue();
    }
}
