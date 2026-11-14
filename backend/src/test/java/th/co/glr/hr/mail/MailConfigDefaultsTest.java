package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Pins the FIVE uat-only mail artifacts named in the Resend mail port brief as unreachable from a production
 * boot, by loading the REAL application.yml through ConfigDataApplicationContextInitializer.
 * application-uat.yml does not exist on this branch and must not be created here — see CLAUDE.md. */
class MailConfigDefaultsTest {

    private final ApplicationContextRunner defaultProfile = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(LogMailer.class, ResendMailer.class, SmtpMailer.class);

    /** prod,demo is exactly render.yaml's gl-r-erp service's active-profile value — the actual condition every one
     * of these assertions must hold under in the one place this repo is actually deployed today. */
    private final ApplicationContextRunner prodDemoProfile = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active=prod,demo")
        .withUserConfiguration(LogMailer.class, ResendMailer.class, SmtpMailer.class);

    // Artifact #1: uat's "(UAT)" subject-suffix literal lives ONLY in application-uat.yml, never in this repo's
    // base application.yml. A stray copy-paste of that literal into application.yml would make this go red.
    @Test
    void subjectSuffixIsBlankWithNoProfileActive() {
        defaultProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.subject-suffix"))
            .as("no profile must never mark a real email as (UAT)")
            .isEmpty());
    }

    @Test
    void subjectSuffixIsBlankUnderProdDemo() {
        prodDemoProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.subject-suffix"))
            .as("render.yaml's gl-r-erp service (prod,demo) must never mark a real email as (UAT)")
            .isEmpty());
    }

    // Artifact #2: override-to must default empty so production mail reaches the real employee address, not a
    // redirected test inbox.
    @Test
    void overrideToIsBlankWithNoProfileActive() {
        defaultProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.override-to"))
            .as("no profile must address employees directly, never redirect to a test inbox")
            .isEmpty());
    }

    @Test
    void overrideToIsBlankUnderProdDemo() {
        prodDemoProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.override-to"))
            .as("render.yaml's gl-r-erp service does not declare APP_MAIL_OVERRIDE_TO; the yml "
                + "default must stay the safe one")
            .isEmpty());
    }

    // Artifact #5: application.yml must NOT fall back to Resend's sandbox sender (onboarding@resend.dev) as a
    // usable production value.
    @Test
    void fromHasNoUsableDefaultWithNoProfileActive() {
        defaultProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.from"))
            .as("must not silently fall back to Resend's sandbox sender, which 403s on any real recipient")
            .isNotEqualTo("onboarding@resend.dev")
            .isEmpty());
    }

    @Test
    void fromHasNoUsableDefaultUnderProdDemo() {
        prodDemoProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.from"))
            .isNotEqualTo("onboarding@resend.dev")
            .isEmpty());
    }

    // Artifact #4: application.yml must NOT fall back to the UAT Vercel URL — every notification/ payslip email's
    // link is built from this property.
    @Test
    void appBaseUrlHasNoUsableDefaultWithNoProfileActive() {
        defaultProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.app-base-url"))
            .as("must not silently point every production email link at the UAT demo host")
            .isNotEqualTo("https://demo-glr-git-uat-waritctds-projects.vercel.app")
            .isEmpty());
    }

    @Test
    void appBaseUrlHasNoUsableDefaultUnderProdDemo() {
        prodDemoProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.app-base-url"))
            .isNotEqualTo("https://demo-glr-git-uat-waritctds-projects.vercel.app")
            .isEmpty());
    }

    // Checks the default mail provider logs messages without constructing a real transport.
    @Test
    void providerDefaultsToLogWithNoProfileActive() {
        defaultProfile.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getEnvironment().getProperty("app.mail.provider")).isEqualTo("log");
            assertThat(ctx.getBean(Mailer.class)).isInstanceOf(LogMailer.class);
        });
    }
}
