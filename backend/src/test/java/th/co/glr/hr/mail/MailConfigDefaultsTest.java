package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Pins the FIVE uat-only mail artifacts named in the Resend mail port brief as unreachable from a production
 * boot, by loading the REAL application.yml through ConfigDataApplicationContextInitializer.
 * application-uat.yml does not exist on this branch and must not be created here — see CLAUDE.md. */
class MailConfigDefaultsTest {

    /** prod,demo is exactly render.yaml's gl-r-erp service's active-profile value — the actual condition every one
     * of these assertions must hold under in the one place this repo is actually deployed today. */
    private final ApplicationContextRunner prodDemoProfile = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active=prod,demo")
        .withUserConfiguration(LogMailer.class, ResendMailer.class, SmtpMailer.class);

    @Test
    void overrideToIsBlankUnderProdDemo() {
        prodDemoProfile.run(ctx -> assertThat(ctx.getEnvironment().getProperty("app.mail.override-to"))
            .as("render.yaml's gl-r-erp service does not declare APP_MAIL_OVERRIDE_TO; the yml "
                + "default must stay the safe one")
            .isEmpty());
    }

}
