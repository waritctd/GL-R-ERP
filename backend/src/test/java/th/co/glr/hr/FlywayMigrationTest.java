package th.co.glr.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
            .schemas("hr", "hr_restricted", "sales", "customers", "price_catalog")
            .defaultSchema("hr")
            .cleanDisabled(false)
            .load();

        // Start from a known-empty state so the test is repeatable against the same database.
        flyway.clean();

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);

        // V91: the sample fixtures V16/V23/V24/V25 seed must NOT survive on a plain production
        // migrate. This is the assertion that stops a rebuilt production database from coming back
        // up with four invented customers and a catalog of tiles GL&R does not sell. Whole-table
        // counts, not filtered ones: nothing else writes these tables during a migrate, so anything
        // left here is seed that escaped.
        assertThat(countRows("SELECT count(*) FROM customers.customer")).isZero();
        assertThat(countRows("SELECT count(*) FROM customers.contact")).isZero();
        assertThat(countRows("SELECT count(*) FROM customers.project")).isZero();
        assertThat(countRows("SELECT count(*) FROM sales.catalog")).isZero();
        assertThat(countRows("SELECT count(*) FROM sales.factory_config")).isZero();
    }

    /**
     * Mirrors {@code application-demo.yml}'s {@code spring.flyway.locations} (the {@code demo}
     * profile, activated as {@code prod,demo} for the Render showcase), which appends
     * {@code db/migration-demo} on top of the normal migrations. The {@code prod} profile alone no
     * longer carries the demo seed, so real production never applies it. Flyway
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
            .schemas("hr", "hr_restricted", "sales", "customers", "price_catalog")
            .defaultSchema("hr")
            .cleanDisabled(false)
            .load();

        flyway.clean();

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);

        // The other half of V91: the showcase is the one environment these fixtures were written
        // for, so db/migration-demo/V91.1 must put back everything V91 removed. If this goes red
        // while the test above stays green, the demo restore has drifted from the core delete.
        assertThat(countRows("SELECT count(*) FROM customers.customer")).isEqualTo(4);
        assertThat(countRows("SELECT count(*) FROM customers.contact")).isEqualTo(5);
        assertThat(countRows("SELECT count(*) FROM customers.project")).isEqualTo(5);
        assertThat(countRows("SELECT count(*) FROM sales.catalog")).isEqualTo(14);
        assertThat(countRows("SELECT count(*) FROM sales.factory_config")).isEqualTo(4);
    }

    private static int countRows(String sql) {
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count rows for: " + sql, e);
        }
    }
}
