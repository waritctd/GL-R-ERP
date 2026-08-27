package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/**
 * Verifies the {@code app.mail.provider} switch selects AND successfully constructs the right
 * {@link Mailer} bean for each value. This exists because {@link SmtpMailer} has a second
 * (test-seam) constructor: without {@code @Autowired} on the primary one, Spring can't pick a
 * constructor and the whole context fails to wire whenever {@code provider=smtp} — which would
 * crash-loop an on-prem SMTP deploy at startup, yet is invisible to unit tests that construct the
 * mailers directly. A pure Mockito unit test cannot catch this; only Spring instantiation does.
 *
 * <p><b>{@link PropertySourcesPlaceholderConfigurer}:</b> registered below for the same reason
 * {@code MailOverrideBeanPostProcessorTest} registers one — see that class's Javadoc for the general
 * gap (a bare {@code ApplicationContextRunner.withUserConfiguration(...)} does not resolve
 * {@code @Value("${...}")} without it). For THIS class's three beans specifically, confirmed live
 * (not by assumption, by removing the bean and diffing behaviour) that it is currently a no-op:
 * {@link LogMailer}/{@link ResendMailer}/{@link SmtpMailer} are plain singletons instantiated late
 * enough in context refresh ({@code finishBeanFactoryInitialization}) that Spring installs its own
 * Environment-backed fallback embedded-value resolver first, which already resolves their
 * {@code @Value} fields correctly with or without this bean — unlike a
 * {@link org.springframework.beans.factory.config.BeanPostProcessor}, which is instantiated earlier
 * ({@code registerBeanPostProcessors}) and does NOT get that fallback: exactly the shape that bit
 * {@link MailOverrideBeanPostProcessor} in #782/#798. Kept anyway for defensive consistency, and
 * because it is the one thing standing between this runner and repeating that defect the moment a
 * {@code BeanPostProcessor} bean is ever added to it — proven load-bearing for that shape by
 * {@link #configurerResolvesValueForABeanPostProcessorAddedToThisRunner()} below, which is not
 * itself a re-test of override-wrapping behaviour (see {@code MailOverrideBeanPostProcessorTest}
 * for the full matrix of that).
 */
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
        // Load-bearing proof for the PropertySourcesPlaceholderConfigurer registered on `runner`
        // above. It is a no-op for the three tests above (see this class's Javadoc) because
        // LogMailer/ResendMailer/SmtpMailer are regular singletons that get Spring's own fallback
        // resolver anyway -- so this uses a BeanPostProcessor instead, the one bean shape that does
        // NOT get that fallback (instantiated earlier, in registerBeanPostProcessors). Reuses the
        // real MailOverrideBeanPostProcessor -- the exact class whose missing-configurer gap #798
        // found -- as the canary, on a LOCAL runner (not the shared `runner` field), so this does not
        // change what the three tests above exercise. No app.mail.override-to property is set, so a
        // correctly-resolved @Value must come back BLANK (the default after the ":"), leaving the
        // Mailer bean unwrapped. This is not a re-test of override-wrapping behaviour -- see
        // MailOverrideBeanPostProcessorTest for the full matrix of that.
        ApplicationContextRunner runnerWithBeanPostProcessor = new ApplicationContextRunner()
            .withBean(PropertySourcesPlaceholderConfigurer.class)
            .withUserConfiguration(LogMailer.class, ResendMailer.class, SmtpMailer.class, MailOverrideBeanPostProcessor.class);

        runnerWithBeanPostProcessor.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // Correctly resolved -> overrideTo is blank -> the processor leaves the bean alone.
            // Without the configurer, @Value would receive the literal, non-blank
            // "${app.mail.override-to:}" text instead, and the bean would be wrongly wrapped in
            // OverrideRedirectingMailer -- confirmed directly by this test's own mutation check.
            assertThat(ctx.getBean(Mailer.class)).isInstanceOf(LogMailer.class);
        });
    }
}
