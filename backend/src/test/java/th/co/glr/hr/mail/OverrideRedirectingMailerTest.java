package th.co.glr.hr.mail;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;

/** this class is THE single implementation of app.mail.override-to redirection. depend on this interface, never
 * a concrete transport"), so proving all four redirect proves every caller does, present or future. */
class OverrideRedirectingMailerTest {
    private static final String OVERRIDE_TO = "qa-inbox@example.com";
    private static final String REAL_RECIPIENT = "real-person@example.com";

    private final Mailer delegate = mock(Mailer.class);
    private final Mailer mailer = new OverrideRedirectingMailer(delegate, OVERRIDE_TO);

    @Test
    void sendRedirectsAwayFromTheRealRecipient() {
        mailer.send(REAL_RECIPIENT, "RFQ", "please quote this");

        verify(delegate).send(
            argThat(to -> !REAL_RECIPIENT.equals(to)),
            eq("RFQ"),
            anyString());
        verify(delegate).send(eq(OVERRIDE_TO), eq("RFQ"),
            argThat(body -> body.contains("please quote this")
                && body.contains("Redirected for testing")
                && body.contains(REAL_RECIPIENT)));
    }

    // The rich send path (leave-submission email): the CC list must be DROPPED, not redirected, so a UAT run never
    // mails a real manager via CC.
    @Test
    void richSendDropsCcAndRedirectsToTheOverrideInbox() {
        String manager = "manager@example.com";
        Mailer.Attachment pdf = new Mailer.Attachment("ใบลา.pdf", "%PDF".getBytes(), "application/pdf");
        Mailer.OutgoingEmail email = new Mailer.OutgoingEmail(
            "hr-inbox@example.com", List.of(REAL_RECIPIENT, manager), "leave",
            "<html><body><p>letter</p></body></html>", "letter", List.of(), List.of(pdf));

        mailer.send(email);

        verify(delegate).send(argThat(sent ->
            OVERRIDE_TO.equals(sent.to())
            && sent.cc().isEmpty()                                  // CC dropped entirely
            && sent.attachments().equals(List.of(pdf))             // attachments preserved
            && sent.htmlBody().contains("<p>letter</p>")
            && sent.htmlBody().contains("Redirected for testing")
            && sent.htmlBody().contains(REAL_RECIPIENT)             // real recipients disclosed in note
            && sent.htmlBody().contains(manager)
            && sent.textBody().contains(REAL_RECIPIENT)
            && sent.textBody().contains(manager)));
    }

}
