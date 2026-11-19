package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import th.co.glr.hr.mail.MailSendException;

/** The failure reason stored on a RELEASE_NOTE_EMAIL_FAILED audit row: readable, one line, capped, and never an
 * email address (a provider message can quote the sender or the recipient). */
class ReleaseNoticeDeliveryTest {

    @Test
    void theStoredReasonMasksAddressesAndStaysOnOneLine() {
        String reason = ReleaseNoticeDelivery.reason(new MailSendException(
            "Resend send failed (status=403)\nThe sender noreply@example.com is not verified for to someone@example.org",
            null));

        assertThat(reason)
            .isEqualTo("Resend send failed (status=403) The sender <address> is not verified for to <address>")
            .doesNotContain("@");
    }

}
