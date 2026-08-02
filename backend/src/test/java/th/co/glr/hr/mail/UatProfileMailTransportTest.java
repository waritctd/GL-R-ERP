package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Guards the two mail settings the UAT stack actually overrides, by loading the REAL
 * {@code application-uat.yml} — unlike {@link MailProviderWiringTest}, which hand-feeds properties
 * and so cannot notice the yml drifting.
 *
 * <p>Both are silent-failure modes, which is why they get a test rather than a comment:
 * {@code app.mail.provider} defaults to {@code log} repo-wide, so losing the uat override doesn't
 * fail a deploy — it just stops sending mail while every log line still reads "email sent"; and
 * {@code app.mail.smtp.host} defaults to empty, which makes {@link SmtpMailer} throw at
 * construction, and under the uat profile's {@code lazy-initialization} that surfaces on the first
 * request rather than at startup, i.e. a green deploy that 500s on a tester.
 *
 * <p>Deliberately narrow: every OTHER app.mail.* key is declared in application.yml bound to its own
 * env var, so asserting it here would pass with the uat override deleted — a vacuous test. Each
 * assertion below has been mutation-checked to fail when its yml line is removed.
 */
class UatProfileMailTransportTest {

    /** Loads application.yml + application-uat.yml exactly as the gl-r-erp-uat service does. */
    private final ApplicationContextRunner uatProfile = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active=uat")
        .withUserConfiguration(LogMailer.class, ResendMailer.class, SmtpMailer.class);

    @Test
    void uatProfileSendsOverRealSmtpRatherThanResendOrLog() {
        uatProfile.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(Mailer.class))
                .as("losing this override silently downgrades UAT to LogMailer — no mail, no error")
                .isInstanceOf(SmtpMailer.class);
        });
    }

    @Test
    void uatProfileSuppliesAnSmtpHostSoTheMailerCanBeConstructed() {
        uatProfile.run(ctx -> {
            JavaMailSenderImpl sender =
                (JavaMailSenderImpl) ReflectionTestUtils.getField(ctx.getBean(SmtpMailer.class), "sender");
            assertThat(sender.getHost()).isEqualTo("smtp.gmail.com");
        });
    }

    /**
     * The uat profile must NOT pin app.mail.from. This file is loaded whenever the uat profile is
     * active — including while APP_MAIL_PROVIDER is still `resend` on the Render dashboard, which
     * a code push does not change. A from: default here would therefore override application.yml's
     * Resend sandbox sender and make live Resend sends fail 403 (unverified domain) on the next
     * deploy, silently, because NotificationEmailService swallows send failures.
     */
    @Test
    void uatProfileDoesNotPinTheSenderAndSoCannotBreakAResendRollback() {
        uatProfile
            .withPropertyValues("app.mail.provider=resend", "app.mail.resend-api-key=test-key")
            .run(ctx -> {
                assertThat(ctx.getBean(Mailer.class)).isInstanceOf(ResendMailer.class);
                assertThat(ctx.getEnvironment().getProperty("app.mail.from"))
                    .as("uat must inherit application.yml's sender, not force an unverified one")
                    .isEqualTo("onboarding@resend.dev");
            });
    }
}
