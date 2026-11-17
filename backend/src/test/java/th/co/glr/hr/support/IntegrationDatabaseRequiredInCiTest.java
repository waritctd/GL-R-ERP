package th.co.glr.hr.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Makes the DB-backed guards refuse to vanish quietly: in CI, a missing integration database is a stated build
 * failure. Same for every real-DB authz proof CLAUDE.md requires — the requirement is that the test runs, not
 * that it exists. */
@EnabledIfEnvironmentVariable(named = "CI", matches = "(?i)true|1",
    disabledReason = "Not a CI run — a DB-less local `mvnw verify` is not this test's business")
class IntegrationDatabaseRequiredInCiTest {

    @Test
    void ciRunsMustHaveAnIntegrationDatabaseSoNoDbBackedGuardCanSelfDisable() {
        assertThat(PostgresTestSupport.isAvailable())
            .as("""
                CI has neither TEST_DB_URL nor a Docker daemon Testcontainers can reach. Every \
                DB-backed guard in this build is therefore worthless — DealStageCheckConstraint\
                IntegrationTest's Java<->Postgres stage mirror, and every real-DB authz proof \
                CLAUDE.md requires. Restore Docker on the runner, or set TEST_DB_URL. Do not \
                silence this by unsetting CI.""")
            .isTrue();
    }
}
