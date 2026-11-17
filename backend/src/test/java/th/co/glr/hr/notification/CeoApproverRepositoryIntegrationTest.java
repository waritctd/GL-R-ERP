package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Real-DB pin for CeoApproverRepository#findEmployeeIds() against CeoApproverRule#SQL_PREDICATE -- the gap a
 * Mockito-mocked repository cannot cover (the query's actual SQL, not just whatever a test stubs it to
 * return). */
class CeoApproverRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private CeoApproverRepository repository;
    private int positionSequence;

    @BeforeEach
    void wireRepository() {
        repository = new CeoApproverRepository(jdbc);
        positionSequence = 0;
    }

    @Test
    void case1_managingDirectorPositionMdDivision_isReturned() {
        long divisionId = insertDivision("MD", "ผู้บริหารระดับสูง");
        long employeeId = insertEmployee("C1", divisionId, "กรรมการผู้จัดการ", true);

        assertThat(repository.findEmployeeIds()).contains(employeeId);
    }

    /** The case that shrinks the notified set -- the whole point of this change. */
    @Test
    void case2_directorPositionMdDivision_isNotReturned() {
        long divisionId = insertDivision("MD", "ผู้บริหารระดับสูง2");
        long employeeId = insertEmployee("C2", divisionId, "กรรมการ", true);

        assertThat(repository.findEmployeeIds()).doesNotContain(employeeId);
    }

    /** ประธานกรรมการ (chairman) is a กรรมการ-family position and division MD -- both arms of the superseded
     * predicate -- but is not กรรมการผู้จัดการ, so must not be notified under the new one. This person still HOLDS
     * the ceo role and can still approve; they are simply no longer told. */
    @Test
    void case3_chairmanPositionMdDivision_isNotReturned() {
        long divisionId = insertDivision("MD", "ผู้บริหารระดับสูง3");
        long employeeId = insertEmployee("C3", divisionId, "ประธานกรรมการ", true);

        assertThat(repository.findEmployeeIds()).doesNotContain(employeeId);
    }

    /** Proves the regexp_replace whitespace-stripping mirror of DivisionAccessPolicy#contains actually does
     * something for THIS (position-only) predicate, not just that it's present. */
    @Test
    void case4_managingDirectorPositionWithInternalWhitespace_isReturned() {
        long divisionId = insertDivision("SA", "ฝ่ายขาย");
        long employeeId = insertEmployee("C4", divisionId, "กรรมการ ผู้จัดการ", true);

        assertThat(repository.findEmployeeIds()).contains(employeeId);
    }

    /** Proves the predicate is division-independent BY DESIGN: an active employee with no ฝ่าย at all (real in this
     * data; see approval-routing-gap-audit.sql section D) is still returned on the strength of the position match
     * alone. */
    @Test
    void case5_nullDivisionWithManagingDirectorPosition_isReturned() {
        long employeeId = insertEmployee("C5", null, "กรรมการผู้จัดการ", true);

        assertThat(repository.findEmployeeIds()).contains(employeeId);
    }

    @Test
    void case6_managingDirectorPositionButInactive_isNotReturned() {
        long divisionId = insertDivision("MD", "ผู้บริหารระดับสูง4");
        long employeeId = insertEmployee("C6", divisionId, "กรรมการผู้จัดการ", false);

        assertThat(repository.findEmployeeIds()).doesNotContain(employeeId);
    }

    /** Proves division MD no longer carries the predicate on its own. A plain ผู้จัดการ (manager -- no กรรมการ
     * prefix at all) in division MD would have matched the superseded predicate's division arm regardless of
     * position; under the new position-only rule it must not match. */
    @Test
    void case7_plainManagerPositionMdDivision_isNotReturned() {
        long divisionId = insertDivision("MD", "ผู้บริหารระดับสูง5");
        long employeeId = insertEmployee("C7", divisionId, "ผู้จัดการ", true);

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

    /** hr.position.source_code is UNIQUE and the migrated schema already seeds real ones (MGR, AMGR -- V30), so
     * fixture codes are generated rather than derived from the employee code -- mirrors
     * ManagerApproverInvariantIntegrationTest#insertPosition. */
    private long insertPosition(String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", "ZZC" + positionSequence++, "name", nameTh), Long.class);
    }
}
