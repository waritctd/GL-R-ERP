package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import th.co.glr.hr.factoryquote.FactoryQuoteEmailDispatchWorker;
import th.co.glr.hr.support.PostgresTestSupport;

/**
 * Guards the fix for the {@code PricingChainEndToEndIntegrationTest} CI flake: {@code @Scheduled}
 * background workers must NOT run inside a {@code @SpringBootTest} context, because that context is
 * cached across the surefire JVM and shares the Testcontainers Postgres with the non-Spring
 * integration tests — a leaked worker thread would poll and mutate their rows mid-test (it did:
 * the factory-email outbox worker stole a test's own dispatches and failed to send them).
 *
 * <p>{@code @EnableScheduling} lives in {@link SchedulingConfig} gated on {@code @Profile("!test")},
 * so with the {@code test} profile active no scheduled task is registered at all. This asserts that
 * — and that the worker BEANS still exist, so tests can drive their logic directly.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@SpringBootTest
@ActiveProfiles("test")
class SchedulingDisabledInTestProfileIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    @Autowired
    ApplicationContext context;

    @Test
    void noScheduledTasksAreRegisteredUnderTheTestProfile() {
        Collection<ScheduledTaskHolder> holders =
            context.getBeansOfType(ScheduledTaskHolder.class).values();
        var tasks = holders.stream()
            .flatMap(h -> h.getScheduledTasks().stream())
            .map(ScheduledTask::getTask)
            .toList();
        assertThat(tasks)
            .as("no @Scheduled task may run in a @SpringBootTest context (would race shared-DB tests)")
            .isEmpty();
    }

    @Test
    void theOutboxWorkerBeanStillExistsSoTestsCanDriveItsLogicDirectly() {
        assertThat(context.getBeanNamesForType(FactoryQuoteEmailDispatchWorker.class))
            .as("the worker bean must still be created — only its timer is disabled in tests")
            .isNotEmpty();
    }
}
