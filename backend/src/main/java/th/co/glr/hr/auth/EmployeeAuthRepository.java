package th.co.glr.hr.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeAuthRepository {
    private static final String LOGIN_SELECT = """
        SELECT e.employee_id,
               e.employee_code,
               COALESCE(substring(e.email from '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+'), btrim(e.email)) AS email,
               e.is_active,
               e.created_at::date AS created_at,
               e.password_hash,
               e.must_change_password,
               d.division_id,
               d.source_code AS division_code,
               d.name_th AS division_name,
               p.name_th AS position_name,
               COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email) AS display_name
          FROM hr.employee e
          LEFT JOIN hr.division d ON d.division_id = e.division_id
          LEFT JOIN hr.position p ON p.position_id = e.position_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public EmployeeAuthRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeLoginRecord> findByEmail(String email) {
        try {
            EmployeeLoginRecord employee = jdbc.queryForObject(LOGIN_SELECT + """
                 WHERE e.email IS NOT NULL
                   AND btrim(e.email) <> ''
                   AND (
                       LOWER(btrim(e.email)) = LOWER(:email)
                       OR LOWER(substring(e.email from '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+')) = LOWER(:email)
                   )
                 ORDER BY e.is_active DESC, e.employee_id
                 LIMIT 1
                """, Map.of("email", email), (rs, rowNum) -> mapEmployee(rs));
            return Optional.ofNullable(employee);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Optional<EmployeeLoginRecord> findByEmployeeId(long employeeId) {
        try {
            EmployeeLoginRecord employee = jdbc.queryForObject(
                LOGIN_SELECT + " WHERE e.employee_id = :id",
                Map.of("id", employeeId),
                (rs, rowNum) -> mapEmployee(rs));
            return Optional.ofNullable(employee);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /** Stores a user-chosen password and clears the forced-change flag. */
    public void updatePassword(long employeeId, String passwordHash) {
        jdbc.update("""
            UPDATE hr.employee
               SET password_hash = :hash,
                   must_change_password = FALSE,
                   updated_at = now()
             WHERE employee_id = :id
            """, Map.of("id", employeeId, "hash", passwordHash));
    }

    /**
     * Admin reset: overwrites the password with a freshly issued temporary hash and forces a
     * change on next login. Unlike {@link #backfillTemporaryPassword} there is NO {@code IS NULL}
     * guard — an HR reset is an intentional overwrite of whatever password the row currently has.
     */
    public void setTemporaryPassword(long employeeId, String passwordHash) {
        jdbc.update("""
            UPDATE hr.employee
               SET password_hash = :hash,
                   must_change_password = TRUE,
                   updated_at = now()
             WHERE employee_id = :id
            """, Map.of("id", employeeId, "hash", passwordHash));
    }

    /**
     * Seeds a temporary password hash for a row that has none, leaving
     * must_change_password = TRUE. The {@code IS NULL} guard makes it idempotent and
     * race-safe so it never overwrites a password a user has already set.
     */
    public void backfillTemporaryPassword(long employeeId, String passwordHash) {
        jdbc.update("""
            UPDATE hr.employee
               SET password_hash = :hash
             WHERE employee_id = :id
               AND password_hash IS NULL
            """, Map.of("id", employeeId, "hash", passwordHash));
    }

    /** employee_id -> employee_code for active rows that still lack a password hash. */
    public Map<Long, String> findActiveCodesNeedingPassword() {
        return jdbc.query("""
            SELECT employee_id, employee_code
              FROM hr.employee
             WHERE password_hash IS NULL
               AND is_active = TRUE
               AND employee_code IS NOT NULL
               AND btrim(employee_code) <> ''
            """, new MapSqlParameterSource(), rs -> {
            Map<Long, String> codes = new LinkedHashMap<>();
            while (rs.next()) {
                codes.put(rs.getLong("employee_id"), rs.getString("employee_code"));
            }
            return codes;
        });
    }

    private EmployeeLoginRecord mapEmployee(ResultSet rs) throws SQLException {
        return new EmployeeLoginRecord(
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getBoolean("is_active"),
            nullableLong(rs, "division_id"),
            rs.getString("division_code"),
            rs.getString("division_name"),
            rs.getString("position_name"),
            rs.getObject("created_at", LocalDate.class),
            rs.getString("password_hash"),
            rs.getBoolean("must_change_password")
        );
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
