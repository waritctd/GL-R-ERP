package th.co.glr.hr.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/** Regression guard for the integration-test database reset strategy (handoff 89). Every
 * AbstractPostgresIntegrationTest runs against the dedicated per-test clone database (wrk_it), never the
 * shared test DB the @SpringBootTests boot against. */
class IntegrationResetInvariantTest extends AbstractPostgresIntegrationTest {

    @Test
    void migrationsAreAppliedOncePerJvmNotPerTest() {
        assumeTrue(PostgresTestSupport.usesContainer(),
            "Clone-reset invariant applies to the Testcontainers path; skipped for TEST_DB_URL");
        // This class alone triggered two @BeforeEach resets (one per test method), and every other integration class
        // in the JVM reset too — yet the migration set is replayed exactly once.
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
