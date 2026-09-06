package th.co.glr.hr.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} background workers (quotation expiry sweep, attendance
 * daily recalc, BOT FX fetch, BOT holiday fetch) — but <b>only outside the {@code test}
 * profile</b>. The factory-quote email outbox worker this list used to include ({@code
 * FactoryQuoteEmailDispatchWorker}) is deleted — factory RFQ email is manual-only now, so there
 * is nothing left on that surface for a scheduler to drive.
 *
 * <p>Integration tests that boot a full {@code @SpringBootTest} context point it at the same shared
 * Testcontainers Postgres the non-Spring {@code AbstractPostgresIntegrationTest} tests use. Spring
 * caches that context across the surefire JVM, so a scheduled worker thread from one test class
 * keeps polling the shared database while a later, unrelated test manipulates the same rows. That
 * exact race made {@code PricingChainEndToEndIntegrationTest} flaky in CI: the leaked
 * {@code FactoryQuoteEmailDispatchWorker} claimed the test's own PENDING dispatches and tried to
 * send them through the real mailer (which fails in CI), so a {@code FACTORY_EMAIL_SENT} event the
 * test expected never appeared.
 *
 * <p>Gating scheduling on {@code @Profile("!test")} (tests activate {@code @ActiveProfiles("test")})
 * means no {@code @Scheduled} method is ever registered in a test context — the worker beans still
 * exist and their logic is exercised directly by tests, but nothing fires on a timer to race them.
 * Production and every other environment (no {@code test} profile) keep scheduling exactly as before.
 */
@Configuration
@Profile("!test")
@EnableScheduling
public class SchedulingConfig {
}
