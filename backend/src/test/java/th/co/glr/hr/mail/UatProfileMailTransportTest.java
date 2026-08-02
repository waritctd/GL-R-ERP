package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Pins the UAT stack's mail transport by loading the REAL {@code application-uat.yml} — unlike
 * {@link MailProviderWiringTest}, which hand-feeds properties and so cannot see the yml drift.
 *
 * <p>UAT must send via the Resend HTTP API, never SMTP: Render blocks outbound SMTP from the
 * gl-r-erp-uat service on ports 25/465/587, confirmed live on 2026-08-02 with real Gmail
 * credentials ({@code MailConnectException: Couldn't connect to host … smtp.gmail.com, 587}). That
 * is a host restriction, not a defect in {@link SmtpMailer}, which is verified working and is what
 * the on-prem deployment uses.
 *
 * <p>Both assertions guard SILENT failures, which is why they are tested rather than commented:
 * {@code app.mail.provider} defaults to {@code log} repo-wide, so losing the uat override stops all
 * mail while every log line still reads "email sent"; and pinning {@code app.mail.from} away from
 * Resend's sandbox sender makes every send return 403 domain-not-verified, which
 * {@code NotificationEmailService} swallows.
 *
 * <p>Deliberately narrow: every other {@code app.mail.*} key is declared in {@code application.yml}
 * bound to its own env var, so asserting it here would pass with the uat override deleted — a
 * vacuous test. Each assertion below is mutation-checked to fail when its yml line changes.
 */
class UatProfileMailTransportTest {

    /** Loads application.yml + application-uat.yml exactly as the gl-r-erp-uat service does. */
    private final ApplicationContextRunner uatProfile = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active=uat")
        .withUserConfiguration(LogMailer.class, ResendMailer.class, SmtpMailer.class);

    @Test
    void uatProfileSendsViaResendAndNeverSmtpBecauseRenderBlocksOutboundSmtp() {
        uatProfile.withPropertyValues("app.mail.resend-api-key=test-key").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(Mailer.class))
                .as("Render blocks SMTP egress; losing this override also silently downgrades to LogMailer")
                .isInstanceOf(ResendMailer.class);
        });
    }

    @Test
    void uatProfileDoesNotPinTheSenderBecauseResendIsNotDomainVerified() {
        uatProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.from"))
            .as("any From other than Resend's sandbox sender returns 403, and the failure is swallowed")
            .isEqualTo("onboarding@resend.dev"));
    }
}
