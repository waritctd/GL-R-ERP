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

/** Applies every Flyway migration to a real, empty PostgreSQL database to catch errors that the Mockito-based
 * unit tests cannot — e.g. a later migration re-creating an object an earlier one already defined (which
 * previously slipped V13 past CI and broke fresh-DB startup). */
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

        // V91: the sample fixtures V16/V23/V24/V25 seed must NOT survive on a plain production migrate. This is the
        // assertion that stops a rebuilt production database from coming back up with four invented customers and a
        // catalog of tiles GL&R does not sell.
        assertThat(countRows("SELECT count(*) FROM customers.customer")).isZero();
        assertThat(countRows("SELECT count(*) FROM customers.contact")).isZero();
        assertThat(countRows("SELECT count(*) FROM customers.project")).isZero();
        assertThat(countRows("SELECT count(*) FROM sales.catalog")).isZero();
        // V163 merged sales.factory_config's columns onto price_catalog.factories and dropped the table outright, so
        // by the time a full migrate completes it no longer exists at all -- a stronger property than "V25's seed
        // didn't survive", and one that would fail loudly if V163 were ever reverted or reordered.
        assertThat(tableExists("sales", "factory_config")).isFalse();
    }

    /** Mirrors application-demo.yml's spring.flyway.locations (the demo profile, activated as prod,demo for the
     * Render showcase), which appends db/migration-demo on top of the normal migrations. The prod profile alone no
     * longer carries the demo seed, so real production never applies it. */
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

        // The other half of V91: the showcase is the one environment these fixtures were written for, so
        // db/migration-demo/V91.1 must put back everything V91 removed. If this goes red while the test above stays
        // green, the demo restore has drifted from the core delete.
        assertThat(countRows("SELECT count(*) FROM customers.customer")).isEqualTo(4);
        assertThat(countRows("SELECT count(*) FROM customers.contact")).isEqualTo(5);
        assertThat(countRows("SELECT count(*) FROM customers.project")).isEqualTo(5);
        assertThat(countRows("SELECT count(*) FROM sales.catalog")).isEqualTo(14);
        // V163's own header states this ordering explicitly: db/migration-demo/V91.1 runs before V163 in the combined
        // location set used here, so its 4 rows are inserted and then the table itself is dropped -- the demo
        // email/unit fixtures are discarded BY DESIGN, not migrated forward.
        assertThat(tableExists("sales", "factory_config")).isFalse();
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

    private static boolean tableExists(String schema, String table) {
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT EXISTS (SELECT 1 FROM information_schema.tables"
                     + " WHERE table_schema = '" + schema + "' AND table_name = '" + table + "')")) {
            rows.next();
            return rows.getBoolean(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check table existence for: " + schema + "." + table, e);
        }
    }
}
