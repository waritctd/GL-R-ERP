package th.co.glr.hr.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeAuthRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public EmployeeAuthRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeLoginRecord> findByEmail(String email) {
        try {
            EmployeeLoginRecord employee = jdbc.queryForObject("""
                SELECT e.employee_id,
                       e.employee_code,
                       e.email,
                       e.is_active,
                       e.created_at::date AS created_at,
                       d.division_id,
                       d.source_code AS division_code,
                       d.name_th AS division_name,
                       COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email) AS display_name
                  FROM hr.employee e
                  LEFT JOIN hr.division d ON d.division_id = e.division_id
                 WHERE LOWER(e.email) = LOWER(:email)
                 ORDER BY e.is_active DESC, e.employee_id
                 LIMIT 1
                """, Map.of("email", email), (rs, rowNum) -> mapEmployee(rs));
            return Optional.ofNullable(employee);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
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
            rs.getObject("created_at", LocalDate.class)
        );
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
