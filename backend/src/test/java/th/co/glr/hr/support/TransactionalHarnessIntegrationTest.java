package th.co.glr.hr.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Self-test for the two capabilities AbstractPostgresIntegrationTest adds on top of the plain hand-wired (new
 * Service) integration-test pattern this suite otherwise uses throughout: a real transactionTemplate and a
 * real transactional AOP proxy via AbstractPostgresIntegrationTest#transactional. */
class TransactionalHarnessIntegrationTest extends AbstractPostgresIntegrationTest {

    // Capability A: transactionTemplate rolls back every statement in its body on failure.

    // Capability B: transactional(service) proxy honours @Transactional, and its absence.

    /** The mutation-sensitivity guard that needs no production mutation: Probe has two behaviourally identical
     * methods (insert a row, then throw) differing ONLY in whether @Transactional is present. */
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

    /** Probe service for #transactionalProxyHonoursTheAnnotation_andItsAbsence(). Public and non-final (class and
     * methods) — required for CGLIB to subclass it. */
    public static class Probe {
        private final NamedParameterJdbcTemplate jdbc;

        public Probe(NamedParameterJdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional
        public void insertThenThrow_annotated(String name) {
            jdbc.update("INSERT INTO price_catalog.factories (name, country) VALUES (:name, 'TH')",
                new MapSqlParameterSource().addValue("name", name));
            throw new RuntimeException("harness probe: annotated failure");
        }

        // Deliberately NOT @Transactional — the negative half of the guard above.
        public void insertThenThrow_unannotated(String name) {
            jdbc.update("INSERT INTO price_catalog.factories (name, country) VALUES (:name, 'TH')",
                new MapSqlParameterSource().addValue("name", name));
            throw new RuntimeException("harness probe: unannotated failure");
        }
    }

    // Consequence #2 of the missing proxy: pg_advisory_xact_lock is transaction-scoped, so without a real
    // transaction it is released the instant its own SELECT completes.

    // Fixture helpers

    private int countFactoryByName(String name) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM price_catalog.factories WHERE name = :name",
            Map.of("name", name), Integer.class);
        return count == null ? 0 : count;
    }
}
