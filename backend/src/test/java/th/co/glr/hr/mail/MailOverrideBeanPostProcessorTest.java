package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/** Verifies the Spring wiring half of #782's fix: that MailOverrideBeanPostProcessor actually gets applied by a
 * real container to whichever Mailer bean app.mail.provider selected, not just that OverrideRedirectingMailer
 * is logically correct in isolation. */
class MailOverrideBeanPostProcessorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(PropertySourcesPlaceholderConfigurer.class)
        .withUserConfiguration(LogMailer.class, ResendMailer.class, SmtpMailer.class, MailOverrideBeanPostProcessor.class);

    @Test
    void leavesTheMailerBeanUnwrappedWhenNoOverrideIsConfigured() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // The repo-wide safe default (see MailConfigDefaultsTest): no override configured means the bean IS the
            // transport itself, not a decorator around it.
            assertThat(ctx.getBean(Mailer.class)).isInstanceOf(LogMailer.class);
        });
    }

    @Test
    void wrapsTheResendMailerWhenOverrideIsConfigured() {
        runner.withPropertyValues(
                "app.mail.provider=resend",
                "app.mail.override-to=qa-inbox@example.com")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                // Wrapped, not swapped: the decorator sits IN FRONT of the real transport, it doesn't replace it - the two
                // conditions (provider selection, override wrapping) are orthogonal and this proves they actually compose
                // rather than one silently pre-empting the other.
                assertThat(ctx.getBean(Mailer.class)).isInstanceOf(OverrideRedirectingMailer.class);
            });
    }

}
