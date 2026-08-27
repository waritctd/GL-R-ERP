package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #782: this class is THE single implementation of {@code app.mail.override-to} redirection -
 * every {@link Mailer} method is covered here, exhaustively, which is what makes a future sender safe
 * without a new test being written for it: a sender can only ever reach a transport through one of
 * these four methods (see {@link Mailer}'s own class Javadoc - "Callers... depend on this interface,
 * never a concrete transport"), so proving all four redirect proves every caller does, present or
 * future. {@code MailOverrideContainmentTest} additionally drives TODAY's two known senders
 * end-to-end through this exact class, as a concrete (not just structural) confirmation.
 *
 * <p>Every assertion here is written wrong-way-round per the issue: it asserts the caller-supplied
 * address is NOT what the delegate receives, not merely that the override address IS - the failure
 * mode this is guarding is a silent pass-through, and "the override address showed up somewhere"
 * would not catch a bug that sends to BOTH addresses, or that only checks its own argument in
 * isolation from the real one.
 */
class OverrideRedirectingMailerTest {
    private static final String OVERRIDE_TO = "qa-inbox@example.com";
    private static final String REAL_RECIPIENT = "real-person@example.com";

    private final Mailer delegate = mock(Mailer.class);
    private final Mailer mailer = new OverrideRedirectingMailer(delegate, OVERRIDE_TO);

    @Test
    void constructorRejectsABlankOverride() {
        assertThatThrownBy(() -> new OverrideRedirectingMailer(delegate, ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OverrideRedirectingMailer(delegate, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

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

    @Test
    void sendHtmlRedirectsAwayFromTheRealRecipientInBothAlternativesAndPassesAttachmentsThrough() {
        Mailer.InlineImage logo = new Mailer.InlineImage("logo", "logo.png", new byte[] {1, 2, 3}, "image/png");

        mailer.sendHtml(REAL_RECIPIENT, "Leave submitted", "<html><body><p>hi</p></body></html>",
            "hi", List.of(logo));

        verify(delegate).sendHtml(
            argThat(to -> !REAL_RECIPIENT.equals(to)),
            eq("Leave submitted"),
            argThat(html -> html.contains("<p>hi</p>") // original content untouched...
                && html.contains("Redirected for testing")
                && html.contains(REAL_RECIPIENT) // ...but the real address is DISCLOSED in the note
                && html.indexOf("</body>") > html.indexOf(REAL_RECIPIENT) // note lands INSIDE <body>
                && html.trim().endsWith("</html>")),
            argThat(text -> text.contains("hi")
                && text.contains("Redirected for testing")
                && text.contains(REAL_RECIPIENT)),
            eq(List.of(logo)));
        verify(delegate).sendHtml(eq(OVERRIDE_TO), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void sendHtmlFallsBackToAppendingTheNoteWhenThereIsNoClosingBodyTag() {
        mailer.sendHtml(REAL_RECIPIENT, "subject", "<p>no body tag here</p>", "no body tag here", List.of());

        verify(delegate).sendHtml(eq(OVERRIDE_TO), anyString(),
            argThat(html -> html.startsWith("<p>no body tag here</p>") && html.contains("Redirected for testing")),
            anyString(), anyList());
    }

    @Test
    void sendWithAttachmentRedirectsAwayFromTheRealRecipient() {
        byte[] pdf = "%PDF".getBytes();

        mailer.sendWithAttachment(REAL_RECIPIENT, "Payslip", "Attached", "payslip.pdf", pdf);

        verify(delegate, never()).sendWithAttachment(eq(REAL_RECIPIENT), anyString(), anyString(), anyString(), any());
        verify(delegate).sendWithAttachment(
            eq(OVERRIDE_TO),
            eq("Payslip"),
            argThat(body -> body.contains("Attached")
                && body.contains("Redirected for testing")
                && body.contains(REAL_RECIPIENT)),
            eq("payslip.pdf"),
            eq(pdf));
    }

    // The exact path #782 named as completely unguarded: FactoryEmailService.send(...) with
    // attachments calls Mailer.sendWithAttachments (plural) - not the singular sendWithAttachment
    // NotificationEmailService's payslip path uses. Both must redirect; they are different methods.
    @Test
    void sendWithAttachmentsPluralRedirectsAwayFromTheRealRecipient() {
        List<Mailer.Attachment> attachments = List.of(new Mailer.Attachment("quote.pdf", "%PDF".getBytes(), "application/pdf"));

        mailer.sendWithAttachments(REAL_RECIPIENT, "RFQ with drawing", "please quote", attachments);

        verify(delegate, never()).sendWithAttachments(eq(REAL_RECIPIENT), anyString(), anyString(), anyList());
        verify(delegate).sendWithAttachments(
            eq(OVERRIDE_TO),
            eq("RFQ with drawing"),
            argThat(body -> body.contains("please quote")
                && body.contains("Redirected for testing")
                && body.contains(REAL_RECIPIENT)),
            eq(attachments));
    }

    // The interface's 4-arg sendHtml is a DEFAULT method that delegates to `this.sendHtml(..., List.of())`
    // - not overridden separately in OverrideRedirectingMailer. This pins that Java's default-method
    // dispatch really does route it through the 5-arg override above (and therefore through the
    // redirect), rather than silently reaching some other implementation.
    @Test
    void fourArgSendHtmlDefaultMethodStillRedirects() {
        mailer.sendHtml(REAL_RECIPIENT, "subject", "<html><body>hi</body></html>", "hi");

        verify(delegate).sendHtml(eq(OVERRIDE_TO), eq("subject"), anyString(), anyString(), eq(List.of()));
    }

    @Test
    void blankOriginalToStillRedirectsAndNotesNoAddressWasOnFile() {
        mailer.send(null, "subject", "body");

        verify(delegate).send(eq(OVERRIDE_TO), eq("subject"),
            argThat(body -> body.contains("no address on file")));
    }

    // The old bracketed "[Redirected for testing - ...]" shape was a classic spam-filter trigger -
    // NotificationEmailService was already fixed to drop it (see its test history). Pin that this
    // class - the new single home for the note - never reintroduces that shape.
    @Test
    void theNoteIsNeverBracketed() {
        mailer.send(REAL_RECIPIENT, "subject", "body");

        verify(delegate).send(eq(OVERRIDE_TO), anyString(), argThat(body -> !body.contains("[Redirected")));
    }
}
