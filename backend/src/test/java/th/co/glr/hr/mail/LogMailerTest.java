package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class LogMailerTest {

    @Test
    void logsInsteadOfSendingAndNeverThrows() {
        // The default provider (dev/CI): send() must be a harmless no-op so business flows run with
        // no mail credentials configured.
        assertThatCode(() -> new LogMailer().send("someone@example.com", "subject", "body"))
            .doesNotThrowAnyException();
    }
}
