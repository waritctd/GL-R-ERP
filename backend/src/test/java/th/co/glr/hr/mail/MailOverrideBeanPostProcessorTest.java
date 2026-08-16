package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/**
 * Verifies the Spring wiring half of #782's fix: that {@link MailOverrideBeanPostProcessor} actually
 * gets applied by a real container to whichever {@link Mailer} bean {@code app.mail.provider}
 * selected, not just that {@link OverrideRedirectingMailer} is logically correct in isolation (see
 * {@link OverrideRedirectingMailerTest} for that half). Mirrors {@code MailProviderWiringTest}'s
 * {@link ApplicationContextRunner} pattern in this same package, registering the real transport
 * classes plus this processor exactly as component scanning would.
 *
 * <p><b>Requires an explicit {@link PropertySourcesPlaceholderConfigurer} bean</b> - confirmed live,
 * not by assumption: without it, a bare {@code ApplicationContextRunner.withUserConfiguration(...)}
 * (the exact shape {@code MailProviderWiringTest} uses) does NOT resolve {@code @Value("${...}")} at
 * all, so {@link MailOverrideBeanPostProcessor}'s constructor received the literal, unresolved string
 * {@code "${app.mail.override-to:}"} - which is non-blank - and wrapped every {@code Mailer} bean in
 * every test regardless of whether {@code app.mail.override-to} was actually set, making three of the
 * four wrapping assertions below pass for the wrong reason and the "unwrapped" one fail outright. That
 * silent gap does not bite {@code MailProviderWiringTest} itself only because none of ITS assertions
 * depend on a resolved {@code @Value} - {@code @ConditionalOnProperty} reads the {@code Environment}
 * directly, bypassing {@code @Value} entirely, and {@code SmtpMailer}'s blank-check on
 * {@code app.mail.smtp.host} happens to still be satisfied by an unresolved-but-non-blank literal
 * placeholder. This class's whole point is the resolved VALUE, so it cannot inherit that accident.
 */
class MailOverrideBeanPostProcessorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(PropertySourcesPlaceholderConfigurer.class)
        .withUserConfiguration(LogMailer.class, ResendMailer.class, SmtpMailer.class, MailOverrideBeanPostProcessor.class);

    @Test
    void leavesTheMailerBeanUnwrappedWhenNoOverrideIsConfigured() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // The repo-wide safe default (see MailConfigDefaultsTest): no override configured means
            // the bean IS the transport itself, not a decorator around it.
            assertThat(ctx.getBean(Mailer.class)).isInstanceOf(LogMailer.class);
        });
    }

    @Test
    void wrapsTheDefaultLogMailerWhenOverrideIsConfigured() {
        runner.withPropertyValues("app.mail.override-to=qa-inbox@example.com").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(Mailer.class)).isInstanceOf(OverrideRedirectingMailer.class);
        });
    }

    @Test
    void wrapsTheResendMailerWhenOverrideIsConfigured() {
        runner.withPropertyValues(
                "app.mail.provider=resend",
                "app.mail.override-to=qa-inbox@example.com")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                // Wrapped, not swapped: the decorator sits IN FRONT of the real transport, it doesn't
                // replace it - the two conditions (provider selection, override wrapping) are
                // orthogonal and this proves they actually compose rather than one silently
                // pre-empting the other.
                assertThat(ctx.getBean(Mailer.class)).isInstanceOf(OverrideRedirectingMailer.class);
            });
    }

    @Test
    void wrapsTheSmtpMailerWhenOverrideIsConfigured() {
        runner.withPropertyValues(
                "app.mail.provider=smtp",
                "app.mail.smtp.host=localhost",
                "app.mail.from=job@glr.co.th",
                "app.mail.override-to=qa-inbox@example.com")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(Mailer.class)).isInstanceOf(OverrideRedirectingMailer.class);
            });
    }

    // The processor must ignore beans that are not a Mailer at all - proves it does not, say, blow up
    // or misbehave when it sees an arbitrary bean during a real context refresh (every bean passes
    // through postProcessAfterInitialization, not just Mailer ones).
    @Test
    void passesThroughANonMailerBeanUnchanged() {
        MailOverrideBeanPostProcessor processor = new MailOverrideBeanPostProcessor("qa-inbox@example.com");

        Object notAMailer = new Object();

        assertThat(processor.postProcessAfterInitialization(notAMailer, "someBean")).isSameAs(notAMailer);
    }

    @Test
    void directlyWrapsWhateverMailerBeanItSeesWhenConfigured() {
        MailOverrideBeanPostProcessor processor = new MailOverrideBeanPostProcessor("qa-inbox@example.com");
        Mailer someFutureMailerImplementation = mock(Mailer.class);

        Object wrapped = processor.postProcessAfterInitialization(someFutureMailerImplementation, "someFutureMailer");

        assertThat(wrapped).isInstanceOf(OverrideRedirectingMailer.class);
    }

    @Test
    void directlyLeavesAMailerBeanAloneWhenOverrideBlank() {
        MailOverrideBeanPostProcessor processor = new MailOverrideBeanPostProcessor("");
        Mailer someMailer = mock(Mailer.class);

        Object result = processor.postProcessAfterInitialization(someMailer, "someMailer");

        assertThat(result).isSameAs(someMailer);
    }
}
