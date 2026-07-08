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

    /**
     * Mirrors {@code application-prod.yml}'s {@code spring.flyway.locations}, which appends
     * {@code db/migration-demo} on top of the normal migrations for the Render demo deploy. Flyway
     * validates version numbers across ALL configured locations combined, not per-folder — a
     * {@code db/migration-demo} file numbered against only its own folder's history can collide with
     * an unrelated {@code db/migration} file of the same version and crash-loop the deploy at
     * startup (happened twice: PR #115 for V31, and again for V32 — see the fix that renumbered
     * {@code V32__hr_notification_schema.sql} to V36). This test only default-location tests above
     * cannot catch, since they never scan {@code db/migration-demo}.
     */
    @Test
    void demoProfileCombinedLocationsApplyToACleanDatabase() {
        Flyway flyway = Flyway.configure()
            .dataSource(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password())
            .locations("classpath:db/migration", "classpath:db/migration-demo")
            .schemas("hr", "hr_restricted", "sales")
            .defaultSchema("hr")
            .cleanDisabled(false)
            .load();

        flyway.clean();

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);
    }

    /**
     * Mirrors {@code application-uat.yml}'s {@code spring.flyway.locations}, which appends
     * {@code db/migration-uat} (the synthetic, PII-free UAT seed) on top of the normal migrations for
     * the {@code gl-r-erp-uat} Render service. Same combined-location version-collision guard as the
     * demo test above: the UAT seed uses the V900+ range to stay clear of the real {@code db/migration}
     * history, and this test is what catches a UAT-vs-real version/checksum collision before the
     * uat-profile deploy fails at {@code validate()}. The default-location tests never scan
     * {@code db/migration-uat}, so only this test covers it.
     */
    @Test
    void uatProfileCombinedLocationsApplyToACleanDatabase() {
        Flyway flyway = Flyway.configure()
            .dataSource(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password())
            .locations("classpath:db/migration", "classpath:db/migration-uat")
            .schemas("hr", "hr_restricted", "sales")
            .defaultSchema("hr")
            .cleanDisabled(false)
            .load();

        flyway.clean();

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);
    }
}
