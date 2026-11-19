package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Pins V158's unique index on LOWER(btrim(email)) against real PostgreSQL. The whole point of moving this into
 * the schema is that it holds for writers that do not go through that method: direct SQL, a seed migration, a
 * future repository, another service. */
class EmployeeEmailUniqueIndexIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void refusesASecondEmployeeWhoseAddressDiffersOnlyInCase() {
        insertEmployee("UQ-001", "somchai@glr.co.th");

        // The exact shape V158 exists to prevent: two rows that findByEmail's LOWER() comparison cannot tell apart, so
        // its LIMIT 1 would silently pick one and the other person's password would appear not to work.
        assertThatThrownBy(() -> insertEmployee("UQ-002", "Somchai@GLR.co.th"))
            .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> insertEmployee("UQ-003", "SOMCHAI@GLR.CO.TH"))
            .isInstanceOf(DuplicateKeyException.class);
    }

    /** Asked the wrong way round. Everything above would also pass against an index that rejected every second
     * insert, so this is the case that gives them meaning: genuinely different addresses must still be accepted,
     * including ones that differ only late in the string. */
    @Test
    void stillAcceptsGenuinelyDifferentAddresses() {
        insertEmployee("UQ-020", "suneesallim@gmail.com");

        assertThatCode(() -> {
            insertEmployee("UQ-021", "suneesllim.1977@gmail.com");
            insertEmployee("UQ-022", "somchai@glr.co.th");
            insertEmployee("UQ-023", "suneesallim@gmail.co");
        }).doesNotThrowAnyException();
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
