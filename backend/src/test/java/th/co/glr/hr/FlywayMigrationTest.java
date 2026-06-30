package th.co.glr.hr;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Applies every Flyway migration to a real, empty PostgreSQL database to catch errors that the
 * Mockito-based unit tests cannot — e.g. a later migration re-creating an object an earlier one
 * already defined (which previously slipped V13 past CI and broke fresh-DB startup).
 *
 * <p>Gated on {@code TEST_DB_URL} so the default {@code mvnw verify} (no database) still runs
 * green; CI sets the variable and provides a Postgres service. Mirrors the app's Flyway settings
 * in {@code application.yml} (schemas + default schema).
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class FlywayMigrationTest {

    @Test
    void allMigrationsApplyToACleanDatabase() {
        Flyway flyway = Flyway.configure()
            .dataSource(
                System.getenv("TEST_DB_URL"),
                System.getenv("TEST_DB_USERNAME"),
                System.getenv("TEST_DB_PASSWORD"))
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
