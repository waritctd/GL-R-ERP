package th.co.glr.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
            .schemas("hr", "hr_restricted", "sales", "customers", "price_catalog")
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
            .schemas("hr", "hr_restricted", "sales", "customers", "price_catalog")
            .defaultSchema("hr")
            .cleanDisabled(false)
            .load();

        flyway.clean();

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);
    }

    /**
     * The login screen's UAT quick-login buttons (gated on {@code VITE_UAT_QUICK_LOGIN}) post the
     * shared {@code Uat@2026} to the real {@code /api/auth/login} for each seeded persona. That only
     * works while the seed's end state keeps the shared password valid and the forced change cleared
     * — which is what {@code V907__uat_clear_forced_password_change.sql} exists to guarantee, undoing
     * V900's {@code must_change_password = TRUE}. Assert the end state directly: a future edit to
     * either migration that re-forces the change, or renames a persona email out from under V907's
     * IN-list, silently breaks every quick-login button, and the migrations would still apply
     * cleanly, so the test above would not catch it.
     */
    @Test
    void uatPersonasCanSignInWithTheSharedQuickLoginPassword() throws Exception {
        Flyway flyway = Flyway.configure()
            .dataSource(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password())
            .locations("classpath:db/migration", "classpath:db/migration-uat")
            .schemas("hr", "hr_restricted", "sales", "customers", "price_catalog")
            .defaultSchema("hr")
            .cleanDisabled(false)
            .load();

        // These tests share one database, so start from empty — otherwise this inherits whichever
        // profile's history the previously-run test left behind and validate() fails.
        flyway.clean();
        flyway.migrate();

        List<String> personas = List.of(
            "ceo@uat.glr", "hr@uat.glr", "salesmgr@uat.glr", "sales@uat.glr", "import@uat.glr",
            "divmgr@uat.glr", "employee@uat.glr", "nulldiv@uat.glr", "admin@uat.glr");
        PasswordEncoder encoder = new BCryptPasswordEncoder();

        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password());
            PreparedStatement statement = connection.prepareStatement(
                "SELECT email, password_hash, must_change_password, is_active"
                    + " FROM hr.employee WHERE email = ?")) {

            for (String email : personas) {
                statement.setString(1, email);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("persona %s is seeded", email).isTrue();
                    assertThat(encoder.matches("Uat@2026", rows.getString("password_hash")))
                        .as("persona %s accepts the shared quick-login password", email)
                        .isTrue();
                    assertThat(rows.getBoolean("must_change_password"))
                        .as("persona %s is not forced through a password change", email)
                        .isFalse();
                    assertThat(rows.getBoolean("is_active"))
                        .as("persona %s is active, so AuthService will not reject it", email)
                        .isTrue();
                }
            }
        }
    }
}
