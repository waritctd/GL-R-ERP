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
        assertUatGoldenPcrDeal();
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

    /**
     * V910 seeds one golden deal ({@code UAT-GOLD-01}, deliberately outside the {@code UAT-TKT-%}
     * pattern {@link #assertUatDealPipelineSeed()} filters on, so it cannot perturb those counts)
     * driven all the way through the pricing-request (PCR) redesign chain on the direct-factory-import
     * path: PricingRequest -&gt; FactoryQuote -&gt; PricingCosting -&gt; PricingDecision -&gt; CustomerQuotation
     * (which extends {@code sales.quotation}, not a separate table — V74) -&gt; OrderConfirmation bridge
     * -&gt; deposit/balance payment -&gt; FactoryPurchaseOrder -&gt; delivery -&gt; CLOSED_PAID -&gt; commission.
     * Asserts the terminal state of every link in that chain so a future migration that breaks the
     * seed (a bad rename, a dropped join) fails this test instead of silently shipping a broken
     * fixture to hosted UAT.
     */
    private void assertUatGoldenPcrDeal() {
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password());
            PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    (SELECT COUNT(*)
                       FROM sales.ticket
                      WHERE code = 'UAT-GOLD-01'
                        AND status = 'closed'
                        AND sales_stage = 'CLOSED_PAID'
                        AND lifecycle = 'COMPLETED'
                        AND payment_status = 'FULLY_PAID'
                        AND fulfillment_status = 'FULLY_DELIVERED'
                        AND close_confirmed_by IS NOT NULL) AS ticket_closed_paid_count,
                    (SELECT COUNT(*)
                       FROM sales.pricing_request pr
                       JOIN sales.ticket t ON t.ticket_id = pr.ticket_id
                      WHERE t.code = 'UAT-GOLD-01'
                        AND pr.request_code = 'PCR-UAT-GOLD-01'
                        AND pr.status = 'QUOTATION_ACCEPTED'
                        AND pr.order_confirmed_at IS NOT NULL) AS pricing_request_accepted_count,
                    (SELECT COUNT(*)
                       FROM sales.factory_quote fq
                       JOIN sales.pricing_request pr ON pr.pricing_request_id = fq.pricing_request_id
                      WHERE pr.request_code = 'PCR-UAT-GOLD-01') AS factory_quote_count,
                    (SELECT COUNT(*)
                       FROM sales.pricing_costing pc
                       JOIN sales.pricing_request pr ON pr.pricing_request_id = pc.pricing_request_id
                      WHERE pr.request_code = 'PCR-UAT-GOLD-01'
                        AND pc.status = 'SUBMITTED') AS pricing_costing_submitted_count,
                    (SELECT COUNT(*)
                       FROM sales.pricing_decision pd
                       JOIN sales.pricing_request pr ON pr.pricing_request_id = pd.pricing_request_id
                      WHERE pr.request_code = 'PCR-UAT-GOLD-01'
                        AND pd.status = 'APPROVED') AS pricing_decision_approved_count,
                    (SELECT COUNT(*)
                       FROM sales.quotation q
                      WHERE q.number = 'QN-UAT-GOLD-01'
                        AND q.doc_status = 'ACCEPTED'
                        AND q.pricing_decision_id IS NOT NULL) AS quotation_accepted_count,
                    (SELECT COUNT(*)
                       FROM sales.factory_purchase_order po
                      WHERE po.po_number = 'PO-UAT-GOLD-01'
                        AND po.status = 'RECEIVED') AS factory_po_received_count,
                    (SELECT COUNT(*)
                       FROM sales.delivery_record dr
                       JOIN sales.ticket t ON t.ticket_id = dr.ticket_id
                      WHERE t.code = 'UAT-GOLD-01') AS delivery_record_count,
                    (SELECT COUNT(*)
                       FROM (
                            SELECT t.ticket_id
                              FROM sales.ticket t
                              JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id
                             WHERE t.code = 'UAT-GOLD-01'
                             GROUP BY t.ticket_id
                            HAVING SUM(ti.qty_delivered) = SUM(ti.qty)
                       ) fully_delivered) AS fully_delivered_items_count,
                    (SELECT COALESCE(SUM(pr.amount), 0)
                       FROM sales.payment_receipt pr
                       JOIN sales.ticket t ON t.ticket_id = pr.ticket_id
                      WHERE t.code = 'UAT-GOLD-01') AS payment_receipt_total,
                    (SELECT COUNT(*)
                       FROM sales.commission_record cr
                       JOIN sales.ticket t ON t.ticket_id = cr.source_ticket_id
                      WHERE t.code = 'UAT-GOLD-01'
                        AND cr.kind = 'SALE'
                        AND cr.status = 'APPROVED') AS commission_approved_count
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("ticket_closed_paid_count")).isEqualTo(1);
                assertThat(rows.getInt("pricing_request_accepted_count")).isEqualTo(1);
                assertThat(rows.getInt("factory_quote_count")).isEqualTo(1);
                assertThat(rows.getInt("pricing_costing_submitted_count")).isEqualTo(1);
                assertThat(rows.getInt("pricing_decision_approved_count")).isEqualTo(1);
                assertThat(rows.getInt("quotation_accepted_count")).isEqualTo(1);
                assertThat(rows.getInt("factory_po_received_count")).isEqualTo(1);
                assertThat(rows.getInt("delivery_record_count")).isEqualTo(1);
                assertThat(rows.getInt("fully_delivered_items_count")).isEqualTo(1);
                assertThat(rows.getBigDecimal("payment_receipt_total"))
                    .isEqualByComparingTo("584007.07");
                assertThat(rows.getInt("commission_approved_count")).isEqualTo(1);
            }
        } catch (Exception exception) {
            throw new AssertionError("UAT golden PCR-deal seed assertions failed", exception);
        }
    }
}
