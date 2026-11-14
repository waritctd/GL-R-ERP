package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/** Verifies the app.mail.provider switch selects AND successfully constructs the right Mailer bean for each
 * value. A pure Mockito unit test cannot catch this; only Spring instantiation does. */
class MailProviderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(PropertySourcesPlaceholderConfigurer.class)
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

    @Test
    void configurerResolvesValueForABeanPostProcessorAddedToThisRunner() {
        // Load-bearing proof for the PropertySourcesPlaceholderConfigurer registered on `runner` above. No
        // app.mail.override-to property is set, so a correctly-resolved @Value must come back BLANK (the default after
        // the ":"), leaving the Mailer bean unwrapped.
        ApplicationContextRunner runnerWithBeanPostProcessor = new ApplicationContextRunner()
            .withBean(PropertySourcesPlaceholderConfigurer.class)
            .withUserConfiguration(LogMailer.class, ResendMailer.class, SmtpMailer.class, MailOverrideBeanPostProcessor.class);

        runnerWithBeanPostProcessor.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // Correctly resolved -> overrideTo is blank -> the processor leaves the bean alone.
            assertThat(ctx.getBean(Mailer.class)).isInstanceOf(LogMailer.class);
        });
    }
}
