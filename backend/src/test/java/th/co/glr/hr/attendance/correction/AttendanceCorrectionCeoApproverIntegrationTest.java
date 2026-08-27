package th.co.glr.hr.attendance.correction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-DB pin for the CEO-notification recipient lookup {@code AttendanceCorrectionService} uses.
 * This class used to construct {@code AttendanceCorrectionRepository} directly and call its own
 * copy of the query, {@code findCeoApproverEmployeeIds()}. The 2026-08-16 consolidation (see
 * {@link CeoApproverRepository}'s class Javadoc) deleted that copy -- along with the equivalent
 * copies on {@code CommissionRepository}, {@code OvertimeRepository} and {@code
 * SpecialMoneyRepository} -- in favour of the single shared {@link
 * CeoApproverRepository#findEmployeeIds()} that {@code AttendanceCorrectionService} now depends on
 * directly (a constructor-injected {@code ceoApprovers} field). This test is rewritten against that
 * shared repository, keeping its original assertions verbatim, so the coverage this file has always
 * provided -- proving AttendanceCorrectionService's actual CEO-notification behaviour, not just a
 * decision in isolation -- survives the consolidation rather than being deleted with the method it
 * used to call. NOT a permission gate -- see {@code CeoApproverRule}'s Javadoc; the only caller of
 * this lookup, {@code AttendanceCorrectionService#notifyCeoOfSubmission}, feeds the returned ids
 * straight into {@code notificationService.notify(...)}.
 *
 * <p>Mirrors {@link th.co.glr.hr.notification.CeoApproverRepositoryIntegrationTest}'s wrong-way-
 * round shape, trimmed to what was specific to this call site (the shared predicate's whitespace-
 * stripping and inactive-employee behaviour are pinned once there, and independently re-proven for
 * the consolidated lookup by {@code CeoApproverConsolidationIntegrationTest}).
 */
class AttendanceCorrectionCeoApproverIntegrationTest extends AbstractPostgresIntegrationTest {
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
        long employeeId = insertEmployee("ACC1", divisionId, "กรรมการผู้จัดการ", true);

        assertThat(repository.findEmployeeIds()).contains(employeeId);
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
        long employeeId = insertEmployee("ACC2", divisionId, "ผู้จัดการ", true);

        assertThat(repository.findEmployeeIds()).doesNotContain(employeeId);
    }

    /**
     * Proves the predicate is division-independent by design, and that the join change (from the
     * old mandatory {@code JOIN hr.division} to no division join at all) does not silently drop an
     * active employee who has no division -- the exact risk flagged for this port.
     */
    @Test
    void noDivisionWithManagingDirectorPosition_isReturned() {
        long employeeId = insertEmployee("ACC3", null, "กรรมการผู้จัดการ", true);

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

    /**
     * {@code hr.position.source_code} is UNIQUE and the migrated schema already seeds real ones, so
     * fixture codes are generated rather than derived from the employee code -- mirrors {@code
     * CeoApproverRepositoryIntegrationTest#insertPosition}.
     */
    private long insertPosition(String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", "ZZG" + positionSequence++, "name", nameTh), Long.class);
    }
}
