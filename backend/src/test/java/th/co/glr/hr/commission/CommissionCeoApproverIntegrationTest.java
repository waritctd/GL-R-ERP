package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Real-DB pin for the CEO-notification recipient lookup CommissionService uses. This class used to construct
 * CommissionRepository directly and call its own copy of the query, findCeoApproverEmployeeIds(). */
class CommissionCeoApproverIntegrationTest extends AbstractPostgresIntegrationTest {
    private CeoApproverRepository repository;
    private int positionSequence;

    @BeforeEach
    void wireRepository() {
        repository = new CeoApproverRepository(jdbc);
        positionSequence = 0;
    }

    @Test
    void managingDirectorPosition_isReturned() {
        long divisionId = insertDivision("MD", "ผู้บริหารระดับสูง");
        long employeeId = insertEmployee("CM1", divisionId, "กรรมการผู้จัดการ", true);

        assertThat(repository.findEmployeeIds()).contains(employeeId);
    }

    /** The wrong-way-round case, and the whole point of this port: division MD used to be sufficient on its own
     * under the superseded predicate. */
    @Test
    void plainManagerInMdDivision_isNotReturned() {
        long divisionId = insertDivision("MD", "ผู้บริหารระดับสูง2");
        long employeeId = insertEmployee("CM2", divisionId, "ผู้จัดการ", true);

        assertThat(repository.findEmployeeIds()).doesNotContain(employeeId);
    }

    /** Proves the predicate is division-independent by design, and that the join change (from the old mandatory
     * JOIN hr.division to no division join at all) does not silently drop an active employee who has no division
     * -- the exact risk flagged for this port. */
    @Test
    void noDivisionWithManagingDirectorPosition_isReturned() {
        long employeeId = insertEmployee("CM3", null, "กรรมการผู้จัดการ", true);

        assertThat(repository.findEmployeeIds()).contains(employeeId);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId, String positionNameTh, boolean active) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, division_id, position_id, is_active)
            VALUES (:code, :divisionId, :positionId, :active)
            RETURNING employee_id
            """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("divisionId", divisionId)
                .addValue("positionId", positionNameTh == null ? null : insertPosition(positionNameTh))
                .addValue("active", active),
            Long.class);
    }

    /** hr.position.source_code is UNIQUE and the migrated schema already seeds real ones, so fixture codes are
     * generated rather than derived from the employee code -- mirrors
     * CeoApproverRepositoryIntegrationTest#insertPosition. */
    private long insertPosition(String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", "ZZD" + positionSequence++, "name", nameTh), Long.class);
    }
}
