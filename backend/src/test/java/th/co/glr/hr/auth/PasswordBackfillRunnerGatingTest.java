package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The employee-code temp-password backfill must be OFF by default (guessable low-entropy password)
 * and only present when a dev explicitly opts in via {@code app.auth.seed-employee-code-passwords=true}.
 */
class PasswordBackfillRunnerGatingTest {
    // Import the runner as a component so its class-level @ConditionalOnProperty is evaluated.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(EmployeeAuthRepository.class, () -> mock(EmployeeAuthRepository.class))
        .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
        .withUserConfiguration(ImportRunner.class);

    @Test
    void backfillRunnerIsAbsentByDefault() {
        runner.run(context ->
            assertThat(context).doesNotHaveBean(PasswordBackfillRunner.class));
    }

    @Test
    void backfillRunnerIsAbsentWhenFlagIsExplicitlyFalse() {
        runner.withPropertyValues("app.auth.seed-employee-code-passwords=false")
            .run(context -> assertThat(context).doesNotHaveBean(PasswordBackfillRunner.class));
    }

    @Test
    void backfillRunnerIsPresentOnlyWhenFlagIsTrue() {
        runner.withPropertyValues("app.auth.seed-employee-code-passwords=true")
            .run(context -> assertThat(context).hasSingleBean(PasswordBackfillRunner.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PasswordBackfillRunner.class)
    static class ImportRunner {
    }
}
