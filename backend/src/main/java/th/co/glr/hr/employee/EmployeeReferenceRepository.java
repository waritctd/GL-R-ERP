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

    private Long findOrInsertDivisionByName(String name) {
        List<Long> existing = jdbc.queryForList("""
            SELECT division_id
              FROM hr.division
             WHERE name_th = :name
             LIMIT 1
            """, Map.of("name", name), Long.class);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
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
