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
 * <p>The first assertion below guards a SILENT failure, which is why it is tested rather than
 * commented: {@code app.mail.provider} defaults to {@code log} repo-wide, so losing the uat
 * override stops all mail while every log line still reads "email sent".
 *
 * <p>The second assertion is narrower than its old name/comment claimed (fixed 2026-08-08) - it
 * does NOT describe the mail that actually leaves the deployed gl-r-erp-uat service. The owner
 * confirmed (2026-08-08) that the live {@code From:} header is an address on {@code glr-trial.lat},
 * set directly via {@code APP_MAIL_FROM} on the Render dashboard (see render.yaml), which overrides
 * the yml-resolved value entirely and is invisible to this test - an
 * {@link ApplicationContextRunner} loads yml files, not the Render environment's dashboard-set
 * variables. What this assertion actually guards: neither {@code application.yml} nor
 * {@code application-uat.yml} hardcodes a literal {@code app.mail.from} value in source - both
 * leave it as an env-var placeholder falling back to Resend's sandbox sender, so which address UAT
 * sends as is always an operator's explicit dashboard choice, never a stray commit. Do not read a
 * green run here as evidence about what a received UAT email's From: header contains.
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
    void uatProfileFallsBackToResendSandboxSenderWhenAppMailFromEnvVarIsUnset() {
        // NOT a claim about the deployed From: header - see the class Javadoc above. This pins that
        // neither application.yml nor application-uat.yml hardcodes an app.mail.from literal, so the
        // safe sandbox-sender fallback is still there if the Render dashboard's APP_MAIL_FROM is
        // ever unset.
        uatProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.from"))
            .as("yml default only, NOT the deployed value (the dashboard sets APP_MAIL_FROM directly) - see class Javadoc")
            .isEqualTo("onboarding@resend.dev"));
    }

    /**
     * The one {@code app.mail.*} key whose repo-wide default is DANGEROUS rather than merely inert.
     * {@code application.yml} leaves {@code override-to} blank, and blank means "address each
     * employee directly" - so losing the Render dashboard's {@code APP_MAIL_OVERRIDE_TO} does not
     * degrade UAT, it points a test deployment at real people. UAT's {@code hr.employee} mirrors
     * prod: measured 2026-08-10, 99 rows carry real external inboxes, 29 of them active staff, and
     * none sit on a safe {@code @uat.glr} domain.
     *
     * <p>This is the inverse of {@link #uatProfileFallsBackToResendSandboxSenderWhenAppMailFromEnvVarIsUnset}
     * above: there, the yml default is a SAFE fallback and the dashboard supplies the real value.
     * Here the yml default must itself be the containment, because the accident being guarded is the
     * dashboard variable going missing.
     */
    @Test
    void uatProfileStillRedirectsMailToTheTestInboxWhenTheOverrideEnvVarIsUnset() {
        uatProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.override-to"))
            .as("a blank override on the uat profile mails real employees from a test deployment")
            .isEqualTo("fasaiglrhr@gmail.com"));
    }

    /** prod/demo must keep the blank default — the containment above is UAT-only, and lifting it
     *  into application.yml would silently stop real deployments reaching real employees. */
    @Test
    void defaultProfileLeavesTheOverrideBlankSoRealDeploymentsAddressEmployeesDirectly() {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.override-to"))
                .as("prod/demo must address employees directly; only the uat profile redirects")
                .isEmpty());
    }
}
