package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Behaviour-preservation pin for the 2026-08-16 consolidation of {@code
 * CommissionRepository#findCeoApproverEmployeeIds()}, {@code
 * OvertimeRepository#findCeoApproverEmployeeIds()}, {@code
 * SpecialMoneyRepository#findCeoApproverEmployeeIds()} and {@code
 * AttendanceCorrectionRepository#findCeoApproverEmployeeIds()} into the single {@link
 * CeoApproverRepository#findEmployeeIds()}. All five methods ran byte-identical SQL before this
 * change (confirmed by direct comparison of the five source files, not assumed), so deleting four
 * of them and pointing every caller at the fifth changes nothing about who gets notified -- this
 * test is the proof.
 *
 * <p><b>The "old" side of this comparison is a literal, independent copy of the query the four
 * deleted methods used to run</b> -- {@link #oldPerRepositoryQuery()} -- not a call back into
 * {@link CeoApproverRule#SQL_PREDICATE}. That is deliberate: if this test instead ran {@code
 * CeoApproverRepository.findEmployeeIds()} against something built from the same {@code
 * SQL_PREDICATE} constant, a mutation that widened or corrupted the predicate itself would move
 * both sides of the comparison together and this test could never catch it. Writing the oracle out
 * by hand keeps the two sides of the assertion genuinely independent, so a mutation to either the
 * predicate or the surrounding query in {@link CeoApproverRepository} has one specific side to
 * diverge from the other. See {@code CeoApproverRepositoryIntegrationTest} for the predicate's own
 * case-by-case pin (whitespace-stripping, inactive exclusion, division-independence) and the four
 * {@code *CeoApproverIntegrationTest} files for the same proof from each calling service's side.
 */
class CeoApproverConsolidationIntegrationTest extends AbstractPostgresIntegrationTest {
    private CeoApproverRepository repository;
    private int positionSequence;

    @BeforeEach
    void wireRepository() {
        repository = new CeoApproverRepository(jdbc);
        positionSequence = 0;
    }

    /**
     * Seeds one employee per discriminator the predicate cares about -- active vs inactive,
     * matching vs non-matching position (including a director who is not a MANAGING director), a
     * position name with irregular internal whitespace, and no position at all -- then asserts
     * {@link CeoApproverRepository#findEmployeeIds()} returns EXACTLY (same ids, same order) what
     * {@link #oldPerRepositoryQuery()} returns against the identical DB state.
     */
    @Test
    void consolidatedLookup_matchesPreConsolidationQuery_exactlyAndInOrder() {
        // Positive: active, exact position match, no division -- proves the join change (mandatory
        // JOIN hr.division dropped entirely) does not lose a matching employee with none.
        long exactMatch = insertEmployee("BP1", null, "กรรมการผู้จัดการ", true);
        // Positive: active, matching position with irregular internal whitespace (three spaces,
        // not the single space CeoApproverRepositoryIntegrationTest#case4 already covers) --
        // regexp_replace(..., '\s+', '', 'g') must still collapse it to a match.
        long irregularWhitespace = insertEmployee("BP2", insertDivision("MD", "ผู้บริหารระดับสูง BP"),
            "กรรมการ   ผู้จัดการ", true);
        // Negative: otherwise-matching position, but INACTIVE -- the case a dropped is_active
        // filter would leak.
        long inactiveMatch = insertEmployee("BP3", null, "กรรมการผู้จัดการ", false);
        // Negative: active, non-matching position (plain manager, no กรรมการ prefix at all).
        long plainManager = insertEmployee("BP4", null, "ผู้จัดการ", true);
        // Negative: active, no position at all (position_id NULL) -- the case a bare p.name_th
        // reference (no COALESCE) would NPE/exclude for the wrong reason, or a widened predicate
        // would wrongly admit.
        long noPosition = insertEmployee("BP5", null, null, true);
        // Negative: active, กรรมการ (director) only -- NOT กรรมการผู้จัดการ (managing director).
        // The case a widened predicate (e.g. bare '%กรรมการ%') would wrongly admit.
        long plainDirector = insertEmployee("BP6", null, "กรรมการ", true);

        List<Long> expected = oldPerRepositoryQuery();
        List<Long> actual = repository.findEmployeeIds();

        assertThat(actual).isEqualTo(expected);
        assertThat(actual).containsExactly(exactMatch, irregularWhitespace);
        assertThat(actual).doesNotContain(inactiveMatch, plainManager, noPosition, plainDirector);
    }

    /**
     * Byte-identical (modulo incidental whitespace -- Postgres does not care, and neither side of
     * this test compares SQL text, only query RESULTS) copy of the SQL every one of the four
     * now-deleted {@code findCeoApproverEmployeeIds()} methods ran -- {@code CommissionRepository},
     * {@code OvertimeRepository}, {@code SpecialMoneyRepository} and {@code
     * AttendanceCorrectionRepository}, all confirmed identical to each other and to {@link
     * CeoApproverRepository#findEmployeeIds()} before this consolidation (see the consolidation
     * commit). Kept as a hand-written literal, independent of {@link CeoApproverRule#SQL_PREDICATE},
     * for the reason given in this class's own Javadoc.
     */
    private List<Long> oldPerRepositoryQuery() {
        return jdbc.query("""
            SELECT e.employee_id
              FROM hr.employee e
              LEFT JOIN hr.position p ON p.position_id = e.position_id
             WHERE e.is_active = TRUE
               AND (regexp_replace(COALESCE(p.name_th, ''), '\\s+', '', 'g') LIKE '%กรรมการผู้จัดการ%')
             ORDER BY e.employee_id
            """, Map.of(), (rs, rowNum) -> rs.getLong("employee_id"));
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
     * the employee code -- mirrors {@code CeoApproverRepositoryIntegrationTest#insertPosition}.
     */
    private long insertPosition(String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", "ZZH" + positionSequence++, "name", nameTh), Long.class);
    }
}
