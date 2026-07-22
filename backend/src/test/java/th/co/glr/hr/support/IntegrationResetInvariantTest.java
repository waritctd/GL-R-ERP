package th.co.glr.hr.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression guard for the integration-test database reset strategy (handoff 89).
 *
 * <p>The backend integration suite must reset each test by cloning a pre-migrated golden template
 * ({@code CREATE DATABASE wrk_it TEMPLATE golden_it}), NOT by replaying all 66 Flyway migrations in
 * {@code @BeforeEach} — the latter was the dominant cost of backend CI (~21 min). Two invariants keep
 * that from silently regressing; both fail loudly if a change reverts to per-test clean+migrate or
 * points repository tests back at the shared boot database:
 *
 * <ol>
 *   <li>The full migration set is applied <b>exactly once per JVM</b>, regardless of how many
 *       integration tests run and reset.</li>
 *   <li>Every {@link AbstractPostgresIntegrationTest} runs against the dedicated per-test clone
 *       database ({@code wrk_it}), never the shared {@code test} DB the {@code @SpringBootTest}s boot
 *       against.</li>
 * </ol>
 *
 * <p>Scoped to the Testcontainers path; skipped under an external {@code TEST_DB_URL}, which
 * intentionally keeps the legacy clean+migrate reset (we never drop a database we don't own).
 */
class IntegrationResetInvariantTest extends AbstractPostgresIntegrationTest {

    @Test
    void migrationsAreAppliedOncePerJvmNotPerTest() {
        assumeTrue(PostgresTestSupport.usesContainer(),
            "Clone-reset invariant applies to the Testcontainers path; skipped for TEST_DB_URL");
        // This class alone triggered two @BeforeEach resets (one per test method), and every other
        // integration class in the JVM reset too — yet the migration set is replayed exactly once.
        assertThat(PostgresTestSupport.goldenMigrateCount())
            .as("full migration set must be replayed once per JVM, not per test")
            .isEqualTo(1);
    }

    @Test
    void repositoryTestsRunAgainstTheDedicatedCloneDatabase() {
        assumeTrue(PostgresTestSupport.usesContainer(),
            "Clone-reset invariant applies to the Testcontainers path; skipped for TEST_DB_URL");
        String currentDatabase = jdbc.getJdbcTemplate().queryForObject("SELECT current_database()", String.class);
        assertThat(currentDatabase)
            .as("repository integration tests must run on the per-test clone DB, not the shared boot DB")
            .isEqualTo(PostgresTestSupport.workingDatabaseName());
    }
}
