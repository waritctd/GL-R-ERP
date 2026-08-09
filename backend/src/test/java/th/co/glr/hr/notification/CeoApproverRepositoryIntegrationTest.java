package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-DB pin for {@link CeoApproverRepository#findEmployeeIds()} against {@link
 * CeoApproverRule#SQL_PREDICATE} -- the gap a Mockito-mocked repository cannot cover (the query's
 * actual SQL, not just whatever a test stubs it to return). This is NOT a permission gate (see
 * {@link CeoApproverRule}'s Javadoc), but the same "prove the SQL, not just the decision"
 * discipline this codebase requires for authz applies here too, since a mock/SQL mismatch is
 * exactly the failure shape this class exists to catch.
 *
 * <p>Written wrong-way-round throughout: cases 2 and 7 in particular assert ABSENCE for a division
 * that would have matched the superseded {@code MD%}/{@code MN%} prefix convention -- proving the
 * set actually shrank is the whole point of this change, not incidental coverage.
 */
class CeoApproverRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private CeoApproverRepository repository;
    private int positionSequence;

    @BeforeEach
    void wireRepository() {
        repository = new CeoApproverRepository(jdbc);
        positionSequence = 0;
    }

    @Test
    void case1_mdDivisionOrdinaryPosition_isReturned() {
        long divisionId = insertDivision("MD", "ผู้บริหารระดับสูง");
        long employeeId = insertEmployee("C1", divisionId, "พนักงานทั่วไป", true);

        assertThat(repository.findEmployeeIds()).contains(employeeId);
    }

    /**
     * The case that shrinks the notified set -- the whole point of this change. {@code MN} used to
     * match the superseded {@code ILIKE 'MN%'} convention; the new rule is division {@code md}
     * EXACTLY (or a กรรมการ-family position), so a plain MN-division employee with no such position
     * must no longer be notified.
     */
    @Test
    void case2_mnDivisionOrdinaryPosition_isNotReturned() {
        long divisionId = insertDivision("MN", "ฝ่ายผลิต");
        long employeeId = insertEmployee("C2", divisionId, "พนักงานทั่วไป", true);

        assertThat(repository.findEmployeeIds()).doesNotContain(employeeId);
    }

    @Test
    void case3_executivePositionAnyOtherDivision_isReturned() {
        long divisionId = insertDivision("SA", "ฝ่ายขาย");
        long employeeId = insertEmployee("C3", divisionId, "กรรมการผู้จัดการ", true);

        assertThat(repository.findEmployeeIds()).contains(employeeId);
    }

    /**
     * Proves the {@code regexp_replace} whitespace-stripping mirror of {@code
     * DivisionAccessPolicy#contains} actually does something, not just that it's present.
     *
     * <p>Deliberately NOT "กรรมการ ผู้จัดการ" (space AFTER the complete word) -- "กรรมการ" is
     * already a fully intact 7-character run there, so {@code LIKE '%กรรมการ%'} matches it with or
     * without stripping and the case would prove nothing about the strip. The space has to fall
     * INSIDE the token being searched for -- here, between "กรรม" and "การผู้จัดการ" -- so that the
     * raw (unstripped) string does NOT contain "กรรมการ" as one contiguous run, and only stripping
     * makes it match.
     */
    @Test
    void case4_executivePositionWithInternalWhitespace_isReturned() {
        long divisionId = insertDivision("SA", "ฝ่ายขาย2");
        long employeeId = insertEmployee("C4", divisionId, "กรรม การผู้จัดการ", true);

        assertThat(repository.findEmployeeIds()).contains(employeeId);
    }

    /**
     * Proves the LEFT JOIN on {@code hr.division} -- active employees with no ฝ่าย at all are real
     * in this data (see {@code approval-routing-gap-audit.sql} section D); an INNER JOIN would drop
     * this row from the result set entirely regardless of the position match.
     */
    @Test
    void case5_nullDivisionWithExecutivePosition_isReturned() {
        long employeeId = insertEmployee("C5", null, "กรรมการ", true);

        assertThat(repository.findEmployeeIds()).contains(employeeId);
    }

    @Test
    void case6_mdDivisionButInactive_isNotReturned() {
        long divisionId = insertDivision("MD", "ผู้บริหารระดับสูง2");
        long employeeId = insertEmployee("C6", divisionId, null, false);

        assertThat(repository.findEmployeeIds()).doesNotContain(employeeId);
    }

    /**
     * Proves an EXACT division-code match ({@code = 'md'}), not the superseded prefix convention
     * ({@code ILIKE 'MD%'}) -- {@code MDX} would have matched the old rule but must not match this
     * one.
     */
    @Test
    void case7_mdxDivisionPrefixMatch_isNotReturned() {
        long divisionId = insertDivision("MDX", "ฝ่ายอื่น");
        long employeeId = insertEmployee("C7", divisionId, null, true);

        assertThat(repository.findEmployeeIds()).doesNotContain(employeeId);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId, String positionNameTh, boolean active) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("active", active);
        params.put("positionId", positionNameTh == null ? null : insertPosition(positionNameTh));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, position_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :positionId, DATE '2020-01-01', :active)
            RETURNING employee_id
            """, params, Long.class);
    }

    /**
     * {@code hr.position.source_code} is UNIQUE and the migrated schema already seeds real ones
     * ({@code MGR}, {@code AMGR} -- V30), so fixture codes are generated rather than derived from
     * the employee code -- mirrors {@code ManagerApproverInvariantIntegrationTest#insertPosition}.
     */
    private long insertPosition(String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", "ZZC" + positionSequence++, "name", nameTh), Long.class);
    }
}
