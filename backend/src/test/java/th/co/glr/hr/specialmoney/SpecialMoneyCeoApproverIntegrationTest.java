package th.co.glr.hr.specialmoney;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-DB pin for {@link SpecialMoneyRepository#findCeoApproverEmployeeIds()} now that it uses
 * {@link th.co.glr.hr.notification.CeoApproverRule#SQL_PREDICATE} instead of the {@code
 * MD%}/{@code MN%} division-prefix convention it carried until this change (still visible in
 * {@code git log -p} for this file). NOT a permission gate -- see that class's Javadoc; this
 * repository's only caller is {@code SpecialMoneyService#notifySubmitted}, which feeds the
 * returned ids straight into {@code notificationService.notify(...)}.
 *
 * <p>Mirrors {@link th.co.glr.hr.notification.CeoApproverRepositoryIntegrationTest}'s wrong-way-
 * round shape, trimmed to what is specific to THIS repository's own SQL assembly (the shared
 * predicate's whitespace-stripping and inactive-employee behaviour are already pinned once, there
 * -- re-proving them here would test the shared constant's content again, not this query).
 */
class SpecialMoneyCeoApproverIntegrationTest extends AbstractPostgresIntegrationTest {
    private SpecialMoneyRepository repository;
    private int positionSequence;

    @BeforeEach
    void wireRepository() {
        repository = new SpecialMoneyRepository(jdbc, new ObjectMapper());
        positionSequence = 0;
    }

    @Test
    void managingDirectorPosition_isReturned() {
        long divisionId = insertDivision("MD", "ผู้บริหารระดับสูง");
        long employeeId = insertEmployee("SMC1", divisionId, "กรรมการผู้จัดการ", true);

        assertThat(repository.findCeoApproverEmployeeIds()).contains(employeeId);
    }

    /**
     * The wrong-way-round case, and the whole point of this port: division {@code MD} used to be
     * sufficient on its own under the superseded predicate. A plain ผู้จัดการ (manager -- no
     * กรรมการ prefix) in division MD matched that predicate's division arm regardless of position;
     * under the new position-only rule they must no longer be notified.
     */
    @Test
    void plainManagerInMdDivision_isNotReturned() {
        long divisionId = insertDivision("MD", "ผู้บริหารระดับสูง2");
        long employeeId = insertEmployee("SMC2", divisionId, "ผู้จัดการ", true);

        assertThat(repository.findCeoApproverEmployeeIds()).doesNotContain(employeeId);
    }

    /**
     * Proves the predicate is division-independent by design, and that the join change (from the
     * old mandatory {@code JOIN hr.division} to no division join at all) does not silently drop an
     * active employee who has no division -- the exact risk flagged for this port.
     */
    @Test
    void noDivisionWithManagingDirectorPosition_isReturned() {
        long employeeId = insertEmployee("SMC3", null, "กรรมการผู้จัดการ", true);

        assertThat(repository.findCeoApproverEmployeeIds()).contains(employeeId);
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

    /**
     * {@code hr.position.source_code} is UNIQUE and the migrated schema already seeds real ones, so
     * fixture codes are generated rather than derived from the employee code -- mirrors {@code
     * CeoApproverRepositoryIntegrationTest#insertPosition}.
     */
    private long insertPosition(String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", "ZZF" + positionSequence++, "name", nameTh), Long.class);
    }
}
