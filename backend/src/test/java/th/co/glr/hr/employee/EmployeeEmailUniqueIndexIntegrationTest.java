package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Pins {@code V158}'s unique index on {@code LOWER(btrim(email))} against real PostgreSQL.
 *
 * <p><b>Every insert here is raw JDBC, deliberately.</b> {@code EmployeeRepository#normalizeEmail}
 * already lowercases on the way in, so driving this through the repository would prove only that
 * the repository normalises — the constraint would never be tested, because no duplicate would ever
 * reach it. The whole point of moving this into the schema is that it holds for writers that do
 * <em>not</em> go through that method: direct SQL, a seed migration, a future repository, another
 * service. So the test writes the way those writers write.
 *
 * <p><b>The blank-email cases are the ones that would have bitten.</b> Postgres treats NULLs as
 * distinct in a unique index, so 108 null-email rows in prod were never at risk. Empty strings are
 * <em>not</em> distinct — without the partial predicate the second employee saved with a blank
 * address would be rejected outright, and since neither prod nor UAT has one today, that failure
 * would first appear in production the day somebody cleared an email.
 */
class EmployeeEmailUniqueIndexIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void refusesASecondEmployeeWhoseAddressDiffersOnlyInCase() {
        insertEmployee("UQ-001", "somchai@glr.co.th");

        // The exact shape V158 exists to prevent: two rows that findByEmail's LOWER() comparison
        // cannot tell apart, so its LIMIT 1 would silently pick one and the other person's password
        // would appear not to work.
        assertThatThrownBy(() -> insertEmployee("UQ-002", "Somchai@GLR.co.th"))
            .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> insertEmployee("UQ-003", "SOMCHAI@GLR.CO.TH"))
            .isInstanceOf(DuplicateKeyException.class);
    }

    /** Surrounding whitespace is folded by the index expression too, so it cannot smuggle one past. */
    @Test
    void refusesADuplicateHiddenBySurroundingWhitespace() {
        insertEmployee("UQ-010", "warehouse.manager@glr.co.th");

        assertThatThrownBy(() -> insertEmployee("UQ-011", "  warehouse.manager@glr.co.th  "))
            .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> insertEmployee("UQ-012", " Warehouse.Manager@GLR.co.th"))
            .isInstanceOf(DuplicateKeyException.class);
    }

    /**
     * Asked the wrong way round. Everything above would also pass against an index that rejected
     * every second insert, so this is the case that gives them meaning: genuinely different
     * addresses must still be accepted, including ones that differ only late in the string.
     */
    @Test
    void stillAcceptsGenuinelyDifferentAddresses() {
        insertEmployee("UQ-020", "suneesallim@gmail.com");

        assertThatCode(() -> {
            insertEmployee("UQ-021", "suneesllim.1977@gmail.com");
            insertEmployee("UQ-022", "somchai@glr.co.th");
            insertEmployee("UQ-023", "suneesallim@gmail.co");
        }).doesNotThrowAnyException();
    }

    /** 108 rows in prod carry a NULL email; every one of them must remain insertable. */
    @Test
    void leavesEmployeesWithNoEmailUnconstrained() {
        assertThatCode(() -> {
            insertEmployee("UQ-030", null);
            insertEmployee("UQ-031", null);
            insertEmployee("UQ-032", null);
        }).doesNotThrowAnyException();
    }

    /**
     * The case the partial predicate is actually load-bearing for. Unlike NULL, two empty strings
     * ARE equal to Postgres, so a non-partial unique index would reject the second one. Neither prod
     * nor UAT has a blank address today, which is exactly why this needs a test rather than a note:
     * without it, the regression would surface only in production, in whatever code path first
     * clears an employee's email.
     */
    @Test
    void leavesEmployeesWithABlankEmailUnconstrained() {
        assertThatCode(() -> {
            insertEmployee("UQ-040", "");
            insertEmployee("UQ-041", "");
            insertEmployee("UQ-042", "   ");
        }).doesNotThrowAnyException();
    }

    /**
     * Reads the catalog rather than inferring the index's shape from behaviour, so a future
     * migration that recreates it non-unique or non-partial fails here and names what changed.
     * Also asserts V158's drop of the now-redundant plain index actually happened.
     */
    @Test
    void theIndexIsUniqueAndPartialAndTheOldPlainIndexIsGone() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT indexname, indexdef
              FROM pg_indexes
             WHERE schemaname = 'hr' AND tablename = 'employee' AND indexdef ILIKE '%email%'
             ORDER BY indexname
            """, Map.of());

        assertThat(rows).hasSize(1);
        String def = String.valueOf(rows.get(0).get("indexdef"));
        assertThat(rows.get(0).get("indexname")).isEqualTo("uq_employee_email_lower_ci");
        assertThat(def).contains("UNIQUE INDEX");
        assertThat(def).contains("lower(btrim(");
        // Partial — the predicate is what keeps NULL and blank addresses out of the constraint.
        assertThat(def).contains("WHERE");
        // V158 drops it; if a later migration resurrects it, the two indexes are redundant.
        assertThat(rows).noneSatisfy(row ->
            assertThat(row.get("indexname")).isEqualTo("idx_employee_email_lower"));
    }

    private void insertEmployee(String code, String email) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("email", email);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        jdbc.update("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     email, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :email, :hireDate, TRUE)
            """, params);
    }
}
