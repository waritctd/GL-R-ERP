package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the {@code app.mail.provider} switch selects AND successfully constructs the right
 * {@link Mailer} bean for each value. This exists because {@link SmtpMailer} has a second
 * (test-seam) constructor: without {@code @Autowired} on the primary one, Spring can't pick a
 * constructor and the whole context fails to wire whenever {@code provider=smtp} — which would
 * crash-loop an on-prem SMTP deploy at startup, yet is invisible to unit tests that construct the
 * mailers directly. A pure Mockito unit test cannot catch this; only Spring instantiation does.
 */
class MailProviderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(LogMailer.class, ResendMailer.class, SmtpMailer.class);

    @Test
    void defaultProviderWiresLogMailer() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(Mailer.class)).isInstanceOf(LogMailer.class);
        });
    }

    @Test
    void resendProviderWiresResendMailer() {
        runner.withPropertyValues("app.mail.provider=resend").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(Mailer.class)).isInstanceOf(ResendMailer.class);
        });
    }

    @Test
    void smtpProviderWiresSmtpMailer() {
        runner.withPropertyValues(
                "app.mail.provider=smtp",
                "app.mail.smtp.host=localhost",
                "app.mail.from=job@glr.co.th")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(Mailer.class)).isInstanceOf(SmtpMailer.class);
            });
    }
}
