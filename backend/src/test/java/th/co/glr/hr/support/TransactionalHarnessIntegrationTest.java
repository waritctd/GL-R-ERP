package th.co.glr.hr.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-test for the two capabilities {@link AbstractPostgresIntegrationTest} adds on top of the
 * plain hand-wired ({@code new Service(...)}) integration-test pattern this suite otherwise uses
 * throughout: a real {@code transactionTemplate} (capability A) and a real transactional AOP proxy
 * via {@link AbstractPostgresIntegrationTest#transactional} (capability B).
 *
 * <p>Uses {@code price_catalog.factories} as the trivially-writable table: {@code name TEXT UNIQUE
 * NOT NULL} is its only column without a default (V42 {@code price_catalog.factories}), so a
 * single-column insert is enough to prove either capability.
 */
class TransactionalHarnessIntegrationTest extends AbstractPostgresIntegrationTest {

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Capability A: transactionTemplate rolls back every statement in its body on failure.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void transactionTemplateRollsBackEveryStatementOnFailure() {
        String rolledBackName = "harness-tt-rollback-" + UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            insertFactory(rolledBackName);
            throw new RuntimeException("harness: forced failure inside transactionTemplate");
        })).isInstanceOf(RuntimeException.class);

        assertThat(countFactoryByName(rolledBackName))
            .as("transactionTemplate.executeWithoutResult must roll back every statement its body "
                + "ran once that body throws — otherwise capability A supplies no real transaction "
                + "boundary at all")
            .isZero();

        // Positive control: proves the zero above is a real rollback, not the insert silently
        // failing for every input (e.g. a bad table/column name would also leave the row absent).
        String committedName = "harness-tt-commit-" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> insertFactory(committedName));
        assertThat(countFactoryByName(committedName))
            .as("positive control: a non-throwing transactionTemplate body must commit")
            .isOne();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Capability B: transactional(service) proxy honours @Transactional, and its absence.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The mutation-sensitivity guard that needs no production mutation: {@link Probe} has two
     * behaviourally identical methods (insert a row, then throw) differing ONLY in whether
     * {@code @Transactional} is present. If {@link AbstractPostgresIntegrationTest#transactional}
     * ever stopped reading the annotation (e.g. wired the wrong advisor, or no advisor at all),
     * this test would go red on the annotated case — that is the whole point of Task 3's
     * {@code confirmOrder} coverage being trustworthy: it pins that {@code transactional()} really
     * distinguishes annotated from un-annotated, permanently.
     */
    @Test
    void transactionalProxyHonoursTheAnnotation_andItsAbsence() {
        Probe probe = transactional(new Probe(jdbc));

        String annotatedName = "harness-proxy-annotated-" + UUID.randomUUID();
        assertThatThrownBy(() -> probe.insertThenThrow_annotated(annotatedName))
            .isInstanceOf(RuntimeException.class);
        assertThat(countFactoryByName(annotatedName))
            .as("the @Transactional method's insert must be rolled back by the proxy")
            .isZero();

        String unannotatedName = "harness-proxy-unannotated-" + UUID.randomUUID();
        assertThatThrownBy(() -> probe.insertThenThrow_unannotated(unannotatedName))
            .isInstanceOf(RuntimeException.class);
        assertThat(countFactoryByName(unannotatedName))
            .as("the un-annotated method has nothing for the interceptor to act on, so its insert "
                + "auto-commits before the throw is even reached — proving the proxy adds no "
                + "transaction of its own where the annotation is absent")
            .isOne();
    }

    /** Probe service for {@link #transactionalProxyHonoursTheAnnotation_andItsAbsence()}. Public
     * and non-final (class and methods) — required for CGLIB to subclass it. */
    public static class Probe {
        private final NamedParameterJdbcTemplate jdbc;

        public Probe(NamedParameterJdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional
        public void insertThenThrow_annotated(String name) {
            jdbc.update("INSERT INTO price_catalog.factories (name) VALUES (:name)",
                new MapSqlParameterSource().addValue("name", name));
            throw new RuntimeException("harness probe: annotated failure");
        }

        // Deliberately NOT @Transactional — the negative half of the guard above.
        public void insertThenThrow_unannotated(String name) {
            jdbc.update("INSERT INTO price_catalog.factories (name) VALUES (:name)",
                new MapSqlParameterSource().addValue("name", name));
            throw new RuntimeException("harness probe: unannotated failure");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Consequence #2 of the missing proxy: pg_advisory_xact_lock is transaction-scoped, so
    // without a real transaction it is released the instant its own SELECT completes.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private static final long LOCK_KEY = 987654321L;

    @Test
    void advisoryXactLockIsHeldForTheTransaction_andReleasedInstantlyWithoutOne() throws SQLException {
        // Inside a real transaction: the lock is held for the whole transaction, so a second,
        // independent physical connection cannot acquire it while the first is still open.
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")");
            try {
                assertThat(tryAdvisoryLockFromSecondConnection())
                    .as("a lock taken inside an open transaction must still be held from a second, "
                        + "independent connection's point of view")
                    .isFalse();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        // Outside any transaction (capability A/B not used here — this is the plain, un-proxied,
        // no-template pattern all ~123 other integration tests in this suite use): the SAME lock
        // key, taken via a single autocommitted statement, dies the instant that statement ends.
        //
        // The lock is deliberately taken on a raw connection that STAYS OPEN for the probe below,
        // rather than through jdbc.getJdbcTemplate() (which returns its connection to the pool
        // immediately). Closing a connection ALSO releases its advisory locks, so probing after
        // the holder had already gone would confound "released at end of statement" with
        // "released because the connection closed" and the assertion would overclaim.
        try (Connection autocommitting = jdbc.getJdbcTemplate().getDataSource().getConnection()) {
            assertThat(autocommitting.getAutoCommit())
                .as("premise guard: this connection must be in autocommit, otherwise the lock below "
                    + "would be held by a genuine open transaction and prove nothing")
                .isTrue();
            try (Statement holder = autocommitting.createStatement()) {
                holder.execute("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")");
            }
            assertThat(tryAdvisoryLockFromSecondConnection())
                .as("outside a real transaction, pg_advisory_xact_lock is released at the end of its "
                    + "own auto-committed statement — a second connection acquires it immediately "
                    + "even though the holder's connection is STILL OPEN. This is consequence #2 of "
                    + "the missing AOP proxy: every lockPricingRequest-then-replay-check guard in "
                    + "this suite runs unprotected")
                .isTrue();
        }
    }

    /** A second, independent physical connection trying to take {@link #LOCK_KEY} without blocking,
     * obtained straight from the DataSource so it never joins any Spring-managed transaction.
     *
     * <p>Both callers below invoke this while their own connection is still checked out, and that is
     * what makes "independent" true: the pool ({@code AbstractPostgresIntegrationTest} runs on
     * HikariCP, size 8) cannot hand back a connection that nobody has returned yet, so this is
     * necessarily a different physical connection from the holder's. Do not "simplify" either caller
     * by closing its connection first — the second acquisition would then be free to hand back the
     * very same socket, and both assertions would quietly stop proving anything.
     *
     * <p>Closed via try-with-resources — never left to the per-test
     * {@code DROP DATABASE ... WITH (FORCE)} to clean up. */
    private boolean tryAdvisoryLockFromSecondConnection() throws SQLException {
        try (Connection second = jdbc.getJdbcTemplate().getDataSource().getConnection();
             Statement statement = second.createStatement();
             ResultSet rs = statement.executeQuery("SELECT pg_try_advisory_xact_lock(" + LOCK_KEY + ")")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private void insertFactory(String name) {
        jdbc.update("INSERT INTO price_catalog.factories (name) VALUES (:name)",
            new MapSqlParameterSource().addValue("name", name));
    }

    private int countFactoryByName(String name) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM price_catalog.factories WHERE name = :name",
            Map.of("name", name), Integer.class);
        return count == null ? 0 : count;
    }
}
