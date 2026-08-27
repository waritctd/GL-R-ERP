package th.co.glr.hr.employee;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeReferenceRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public EmployeeReferenceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long ensureTitle(String name) {
        jdbc.update("""
            INSERT INTO hr.title(name_th)
            VALUES (:name)
            ON CONFLICT (name_th) DO NOTHING
            """, Map.of("name", name));
        return jdbc.queryForObject("SELECT title_id FROM hr.title WHERE name_th = :name", Map.of("name", name), Long.class);
    }

    public Long ensureDivision(String sourceCode, String name) {
        String fallbackName = defaultText(name, defaultText(sourceCode, "ไม่ระบุ"));
        if (hasText(sourceCode)) {
            Long databaseId = parseLong(sourceCode);
            if (databaseId != null && divisionExists(databaseId)) {
                if (hasText(name)) {
                    jdbc.update("UPDATE hr.division SET name_th = :name, is_active = TRUE WHERE division_id = :id",
                        Map.of("id", databaseId, "name", fallbackName));
                }
                return databaseId;
            }
            jdbc.update("""
                INSERT INTO hr.division(source_code, name_th, is_active)
                VALUES (:sourceCode, :name, TRUE)
                ON CONFLICT (source_code) DO UPDATE SET name_th = EXCLUDED.name_th, is_active = TRUE
                """, Map.of("sourceCode", sourceCode, "name", fallbackName));
            return jdbc.queryForObject("SELECT division_id FROM hr.division WHERE source_code = :sourceCode",
                Map.of("sourceCode", sourceCode), Long.class);
        }
        return findOrInsertDivisionByName(fallbackName);
    }

    private boolean divisionExists(long divisionId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM hr.division WHERE division_id = :id)
            """, Map.of("id", divisionId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public Long ensureDepartment(String name, Long divisionId) {
        if (!hasText(name)) {
            return null;
        }
        List<Long> existing = jdbc.queryForList("""
            SELECT department_id
             FROM hr.department
             WHERE name_th = :name
               AND ((:divisionId IS NULL AND division_id IS NULL) OR division_id = :divisionId)
             LIMIT 1
            """, new MapSqlParameterSource().addValue("name", name).addValue("divisionId", divisionId), Long.class);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        return jdbc.queryForObject("""
            INSERT INTO hr.department(name_th, division_id, is_active)
            VALUES (:name, :divisionId, TRUE)
            RETURNING department_id
            """, new MapSqlParameterSource().addValue("name", name).addValue("divisionId", divisionId), Long.class);
    }

    public Long ensurePosition(String name) {
        if (!hasText(name)) {
            return null;
        }
        return findOrInsertPositionByName(name);
    }

    public Long ensureLevel(String level) {
        if (!hasText(level)) {
            return null;
        }
        jdbc.update("""
            INSERT INTO hr.employee_level(source_code, name_th)
            VALUES (:level, :level)
            ON CONFLICT (source_code) DO UPDATE SET name_th = EXCLUDED.name_th
            """, Map.of("level", level));
        return jdbc.queryForObject("SELECT level_id FROM hr.employee_level WHERE source_code = :level", Map.of("level", level), Long.class);
    }

    public Long ensureLocation(String name) {
        if (!hasText(name)) {
            return null;
        }
        jdbc.update("""
            INSERT INTO hr.work_location(name_th)
            VALUES (:name)
            ON CONFLICT (name_th) DO NOTHING
            """, Map.of("name", name));
        return jdbc.queryForObject("SELECT location_id FROM hr.work_location WHERE name_th = :name", Map.of("name", name), Long.class);
    }

    public Long ensureStatus(String statusId) {
        String normalized = defaultText(statusId, "ACT");
        jdbc.update("""
            INSERT INTO hr.employment_status(source_code, name_th, name_en)
            VALUES (:sourceCode, :nameTh, :nameEn)
            ON CONFLICT (source_code) DO UPDATE SET name_th = EXCLUDED.name_th, name_en = EXCLUDED.name_en
            """, Map.of("sourceCode", normalized, "nameTh", EmployeeStatus.name(normalized), "nameEn", EmployeeStatus.englishName(normalized)));
        return jdbc.queryForObject("SELECT status_id FROM hr.employment_status WHERE source_code = :sourceCode",
            Map.of("sourceCode", normalized), Long.class);
    }

    public Long currentDivisionId(long employeeId) {
        return jdbc.queryForObject("SELECT division_id FROM hr.employee WHERE employee_id = :id", Map.of("id", employeeId), Long.class);
    }

    /**
     * Resolves {@code hr.division} by name for callers that supplied a division NAME but a
     * blank/absent division CODE (the {@code !hasText(sourceCode)} branch of {@link
     * #ensureDivision}, reachable over HTTP via the hr-gated {@code POST /api/employees} and
     * {@code PATCH /api/employees/{id}} -- PATCH, not PUT: confirmed against {@code
     * EmployeeController}, which has no {@code @PutMapping} at all).
     *
     * <p><b>Same-name rows can carry different {@code source_code} values</b> -- confirmed in
     * production: {@code "Sales Support 1"} exists as both {@code '0011'} and {@code '0013'},
     * {@code "QC&ISO"} as both {@code '0009'} and {@code 'QC'} -- so picking among candidates
     * can never be an unordered {@code LIMIT 1}; Postgres does not guarantee a row order without
     * an explicit {@code ORDER BY}, and an arbitrary pick could just as easily land on a
     * blank-coded duplicate as a coded one.
     *
     * <p><b>Owner's ruling:</b> when one or more rows already carry this name, reuse the
     * existing row instead of inserting a duplicate, and choose deterministically among
     * candidates by (1) preferring a row whose {@code source_code} is non-null and non-blank
     * over one that is not, then (2) tie-breaking on the LOWEST {@code division_id} (the
     * earliest-created row), so the choice is stable across calls and never depends on physical
     * row order. A {@code source_code} is never invented or derived from the name: production's
     * name-to-code mapping is arbitrary business knowledge (e.g. ฝ่ายขาย -&gt; {@code SA},
     * ผู้บริหาร -&gt; {@code MN}) rather than a rule, and only one of prod's 19 division names
     * even carries a {@code '-'} prefix that could tempt a derivation scheme -- so guessing a
     * code would be worse than leaving it blank.
     *
     * <p><b>This narrows the hole; it does not close it.</b> A genuinely new division name --
     * one that matches NO existing row -- still inserts with {@code source_code} left NULL,
     * because there is no existing row to reuse a code from. That row is then invisible to the
     * {@code d.source_code ILIKE 'SA%'} (also {@code 'MD%'}/{@code 'MN%'}/{@code 'PCIM%'})
     * predicates in {@code CommissionRepository} and {@code NotificationRepository}, the same
     * blind spot {@code V146__drop_orphaned_null_source_code_divisions.sql} cleaned up: a sales
     * manager assigned to that division could approve a commission but never be notified one is
     * waiting. Fully closing this needs either a caller-supplied {@code source_code} on first
     * creation of a division, or an owner-approved name -&gt; code mapping; both are out of scope
     * here. See that migration's comment for the fuller incident history.
     */
    private Long findOrInsertDivisionByName(String name) {
        List<Long> existing = jdbc.queryForList("""
            SELECT division_id
              FROM hr.division
             WHERE name_th = :name
             ORDER BY (source_code IS NULL OR btrim(source_code) = '') ASC, division_id ASC
             LIMIT 1
            """, Map.of("name", name), Long.class);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        // Genuinely new name: no existing row of this name to reuse a source_code from. Left
        // NULL deliberately -- see the javadoc above for why this remains a known, un-closed gap.
        return jdbc.queryForObject("""
            INSERT INTO hr.division(name_th, is_active)
            VALUES (:name, TRUE)
            RETURNING division_id
            """, Map.of("name", name), Long.class);
    }

    private Long findOrInsertPositionByName(String name) {
        List<Long> existing = jdbc.queryForList("""
            SELECT position_id
              FROM hr.position
             WHERE name_th = :name
             LIMIT 1
            """, Map.of("name", name), Long.class);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        return jdbc.queryForObject("""
            INSERT INTO hr.position(name_th, is_active)
            VALUES (:name, TRUE)
            RETURNING position_id
            """, Map.of("name", name), Long.class);
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
