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
        assertUatDealPipelineSeed();
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

        // The quick-login personas offered by frontend/src/features/auth/uatQuickLogin.js.
        // admin@uat.glr is still seeded by V900 but is no longer offered (no distinct role —
        // it derives to plain employee), so it is not asserted here.
        List<String> personas = List.of(
            "ceo@uat.glr", "hr@uat.glr", "salesmgr@uat.glr", "sales@uat.glr", "import@uat.glr",
            "account@uat.glr", "divmgr@uat.glr", "employee@uat.glr", "nulldiv@uat.glr");
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

    private void assertUatDealPipelineSeed() {
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password());
            PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    (SELECT COUNT(DISTINCT sales_stage)
                       FROM sales.ticket
                      WHERE code LIKE 'UAT-TKT-%') AS stage_count,
                    (SELECT COUNT(*)
                       FROM sales.payment_receipt pr
                       JOIN sales.ticket t ON t.ticket_id = pr.ticket_id
                      WHERE t.code LIKE 'UAT-TKT-%') AS receipt_count,
                    (SELECT COUNT(*)
                       FROM sales.delivery_record dr
                       JOIN sales.ticket t ON t.ticket_id = dr.ticket_id
                      WHERE t.code IN ('UAT-TKT-04','UAT-TKT-08','UAT-TKT-14')) AS delivery_count,
                    (SELECT COUNT(*)
                       FROM sales.ticket
                      WHERE code = 'UAT-TKT-10'
                        AND deposit_policy = 'CREDIT_CUSTOMER'
                        AND due_date = DATE '2026-06-30') AS overdue_credit_count,
                    (SELECT COUNT(*)
                       FROM (
                            SELECT t.ticket_id
                              FROM sales.ticket t
                              JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id
                             WHERE t.code = 'UAT-TKT-08'
                             GROUP BY t.ticket_id
                            HAVING SUM(ti.qty_delivered) > 0
                               AND SUM(ti.qty_delivered) < SUM(ti.qty)
                       ) partial_delivery) AS partial_delivery_count,
                    (SELECT COUNT(*)
                       FROM sales.ticket
                      WHERE code = 'UAT-TKT-04'
                        AND sales_stage = 'CLOSED_PAID'
                        AND lifecycle = 'COMPLETED'
                        AND payment_status = 'FULLY_PAID'
                        AND fulfillment_status = 'FULLY_DELIVERED') AS closed_paid_count
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("stage_count")).isEqualTo(14);
                assertThat(rows.getInt("receipt_count")).isEqualTo(5);
                assertThat(rows.getInt("delivery_count")).isEqualTo(3);
                assertThat(rows.getInt("overdue_credit_count")).isEqualTo(1);
                assertThat(rows.getInt("partial_delivery_count")).isEqualTo(1);
                assertThat(rows.getInt("closed_paid_count")).isEqualTo(1);
            }
        } catch (Exception exception) {
            throw new AssertionError("UAT deal-pipeline seed assertions failed", exception);
        }
    }
}
