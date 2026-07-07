package th.co.glr.hr;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import th.co.glr.hr.support.PostgresTestSupport;

/**
 * Applies every Flyway migration to a real, empty PostgreSQL database to catch errors that the
 * Mockito-based unit tests cannot — e.g. a later migration re-creating an object an earlier one
 * already defined (which previously slipped V13 past CI and broke fresh-DB startup).
 *
 * <p>The database is resolved by {@link PostgresTestSupport}: an explicit {@code TEST_DB_URL}
 * overrides everything, otherwise a throwaway Testcontainers Postgres is used. Skips gracefully when
 * neither is available so a DB-less {@code mvnw verify} still runs green. Mirrors the app's Flyway
 * settings in {@code application.yml} (schemas + default schema).
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class FlywayMigrationTest {

    @Test
    void allMigrationsApplyToACleanDatabase() {
        Flyway flyway = Flyway.configure()
            .dataSource(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password())
            .schemas("hr", "hr_restricted", "sales")
            .defaultSchema("hr")
            .cleanDisabled(false)
            .load();

        // Start from a known-empty state so the test is repeatable against the same database.
        flyway.clean();

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);
    }
}
