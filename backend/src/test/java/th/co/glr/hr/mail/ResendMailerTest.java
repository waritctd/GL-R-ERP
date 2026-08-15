package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import java.lang.reflect.Constructor;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    private ResendMailer mailerWith(ResendMailer.ResendSender sender, String replyTo) {
        try {
            Constructor<ResendMailer> ctor = ResendMailer.class.getDeclaredConstructor(
                ResendMailer.ResendSender.class, String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(sender, "job@glr.co.th", replyTo);
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

    @Test
    void sendHtmlSetsBothHtmlAndTextOnTheRequest() {
        AtomicReference<CreateEmailOptions> captured = new AtomicReference<>();
        ResendMailer mailer = mailerWith(options -> {
            captured.set(options);
            CreateEmailResponse response = new CreateEmailResponse();
            response.setId("email-id-html");
            return response;
        });

        mailer.sendHtml("employee@example.com", "Subject", "<p>Hello</p>", "Hello");

        assertThat(captured.get().getHtml()).isEqualTo("<p>Hello</p>");
        assertThat(captured.get().getText()).isEqualTo("Hello");
    }

    @Test
    void sendHtmlRoutesThroughTheSameRetryPathAs429Backoff() {
        AtomicInteger calls = new AtomicInteger(0);
        ResendMailer mailer = mailerWith(options -> {
            int attempt = calls.incrementAndGet();
            if (attempt < 2) {
                throw new ResendException(429, "rate_limit_exceeded");
            }
            CreateEmailResponse response = new CreateEmailResponse();
            response.setId("email-id-retry");
            return response;
        });

        mailer.sendHtml("employee@example.com", "Subject", "<p>Hello</p>", "Hello");

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void sendHtmlWithNoInlineImagesSendsNoAttachments() {
        // The plain 4-arg sendHtml is Mailer's default method, delegating to the 5-arg one with
        // List.of() - this pins that the Resend request built from an empty list carries no
        // attachments at all (not an empty-but-present list), i.e. behaves exactly like it did
        // before inline images existed.
        AtomicReference<CreateEmailOptions> captured = new AtomicReference<>();
        ResendMailer mailer = mailerWith(options -> {
            captured.set(options);
            CreateEmailResponse response = new CreateEmailResponse();
            response.setId("email-id-no-inline");
            return response;
        });

        mailer.sendHtml("employee@example.com", "Subject", "<p>Hello</p>", "Hello");

        assertThat(captured.get().getAttachments()).isNullOrEmpty();
    }

    @Test
    void sendHtmlWithInlineImagesAttachesEachWithItsContentIdAndBase64Content() {
        // This is the mechanism #5 (broken logo) actually relies on: Resend has no separate
        // "inline image" concept, an attachment becomes inline purely by carrying a contentId the
        // HTML matches via cid:<contentId> - confirmed by decompiling resend-java 4.13.0's
        // Attachment.Builder, which exposes .contentId(String).
        AtomicReference<CreateEmailOptions> captured = new AtomicReference<>();
        ResendMailer mailer = mailerWith(options -> {
            captured.set(options);
            CreateEmailResponse response = new CreateEmailResponse();
            response.setId("email-id-inline");
            return response;
        });
        byte[] logoBytes = {1, 2, 3, 4, 5};

        mailer.sendHtml("employee@example.com", "Subject", "<img src=\"cid:glr-logo\">", "text",
            List.of(new Mailer.InlineImage("glr-logo", "glr-logo.png", logoBytes, "image/png")));

        List<com.resend.services.emails.model.Attachment> attachments = captured.get().getAttachments();
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).getContentId()).isEqualTo("glr-logo");
        assertThat(attachments.get(0).getFileName()).isEqualTo("glr-logo.png");
        assertThat(attachments.get(0).getContentType()).isEqualTo("image/png");
        assertThat(Base64.getDecoder().decode(attachments.get(0).getContent())).isEqualTo(logoBytes);
    }

    // MUTATION-CHECK (verified live, not just by reasoning): temporarily removed the
    // `if (!replyTo.isBlank())` guard in applyReplyTo() so it always called request.replyTo(replyTo)
    // -- exactly this one test went red (getReplyTo() became a 1-element list containing "" rather
    // than null/empty), nothing else in this class. Reverted to an empty diff.
    @Test
    void replyToIsOmittedWhenBlank() {
        // No address is ever invented - blank app.mail.reply-to must leave Resend's replyTo unset,
        // not default to something.
        AtomicReference<CreateEmailOptions> captured = new AtomicReference<>();
        ResendMailer mailer = mailerWith(options -> {
            captured.set(options);
            CreateEmailResponse response = new CreateEmailResponse();
            response.setId("email-id-no-reply-to");
            return response;
        });

        mailer.send("employee@example.com", "Subject", "Body");

        assertThat(captured.get().getReplyTo()).isNullOrEmpty();
    }

    @Test
    void replyToIsSetOnEveryMethodWhenConfigured() {
        AtomicReference<CreateEmailOptions> captured = new AtomicReference<>();
        ResendMailer mailer = mailerWith(options -> {
            captured.set(options);
            CreateEmailResponse response = new CreateEmailResponse();
            response.setId("email-id-reply-to");
            return response;
        }, "hr@glr.co.th");

        mailer.send("employee@example.com", "Subject", "Body");
        assertThat(captured.get().getReplyTo()).containsExactly("hr@glr.co.th");

        mailer.sendHtml("employee@example.com", "Subject", "<p>Hello</p>", "Hello");
        assertThat(captured.get().getReplyTo()).containsExactly("hr@glr.co.th");

        mailer.sendWithAttachments("employee@example.com", "Subject", "Body", List.of());
        assertThat(captured.get().getReplyTo()).containsExactly("hr@glr.co.th");
    }
}
