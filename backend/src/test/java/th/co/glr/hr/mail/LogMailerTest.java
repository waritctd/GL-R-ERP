package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;

class LogMailerTest {

    @Test
    void logsInsteadOfSendingAndNeverThrows() {
        // The default provider (dev/CI): send() must be a harmless no-op so business flows run with
        // no mail credentials configured.
        assertThatCode(() -> new LogMailer().send("someone@example.com", "subject", "body"))
            .doesNotThrowAnyException();
    }

    @Test
    void logsHtmlVariantInsteadOfSendingAndNeverThrows() {
        // The plain 4-arg call is Mailer's default method, delegating to the 5-arg one with
        // List.of() - exercising it here pins that the delegation itself never throws.
        assertThatCode(() -> new LogMailer()
                .sendHtml("someone@example.com", "subject", "<p>html body</p>", "text body"))
            .doesNotThrowAnyException();
    }

    @Test
    void logsInlineImageCountInsteadOfSendingAndNeverThrows() {
        assertThatCode(() -> new LogMailer().sendHtml("someone@example.com", "subject",
                "<p>html body</p>", "text body",
                List.of(new Mailer.InlineImage("glr-logo", "glr-logo.png", new byte[]{1, 2, 3}, "image/png"))))
            .doesNotThrowAnyException();
    }
}
