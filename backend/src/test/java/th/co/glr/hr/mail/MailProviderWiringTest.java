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
    void resendProviderWiresResendMailer() {
        runner.withPropertyValues("app.mail.provider=resend").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(Mailer.class)).isInstanceOf(ResendMailer.class);
        });
    }

}
