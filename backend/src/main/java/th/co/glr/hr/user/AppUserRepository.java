package th.co.glr.hr.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AppUserRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AppUserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUserRecord> findByEmail(String email) {
        return findOne("""
            SELECT au.user_id,
                   au.employee_id,
                   au.username,
                   au.password_hash,
                   au.is_enabled,
                   au.created_at::date AS created_at,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), au.username) AS display_name
              FROM hr.app_user au
              LEFT JOIN hr.employee e ON e.employee_id = au.employee_id
             WHERE LOWER(au.username) = LOWER(:email)
            """, Map.of("email", email));
    }

    public Optional<AppUserRecord> findById(long id) {
        return findOne("""
            SELECT au.user_id,
                   au.employee_id,
                   au.username,
                   au.password_hash,
                   au.is_enabled,
                   au.created_at::date AS created_at,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), au.username) AS display_name
              FROM hr.app_user au
              LEFT JOIN hr.employee e ON e.employee_id = au.employee_id
             WHERE au.user_id = :id
            """, Map.of("id", id));
    }

    public Optional<AppUserRecord> findFirstEnabledByRole(String role) {
        return findOne("""
            SELECT au.user_id,
                   au.employee_id,
                   au.username,
                   au.password_hash,
                   au.is_enabled,
                   au.created_at::date AS created_at,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), au.username) AS display_name
              FROM hr.app_user au
              JOIN hr.user_role ur ON ur.user_id = au.user_id
              JOIN hr.role r ON r.role_id = ur.role_id
              LEFT JOIN hr.employee e ON e.employee_id = au.employee_id
             WHERE au.is_enabled = TRUE
               AND LOWER(r.name) = LOWER(:role)
             ORDER BY au.user_id
             LIMIT 1
            """, Map.of("role", role));
    }

    public List<AppUserRecord> findAll() {
        List<AppUserRecord> users = jdbc.query("""
            SELECT au.user_id,
                   au.employee_id,
                   au.username,
                   au.password_hash,
                   au.is_enabled,
                   au.created_at::date AS created_at,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), au.username) AS display_name
              FROM hr.app_user au
              LEFT JOIN hr.employee e ON e.employee_id = au.employee_id
             ORDER BY au.user_id
            """, Map.of(), (rs, rowNum) -> mapUser(rs));
        return users.stream().map(user -> userWithRoles(user, loadRoles(user.id()))).toList();
    }

    public long create(CreateUserRequest request, String passwordHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.app_user(employee_id, username, password_hash, is_enabled)
            VALUES (:employeeId, :email, :passwordHash, TRUE)
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", request.employeeId())
                .addValue("email", request.email())
                .addValue("passwordHash", passwordHash),
            keyHolder,
            new String[] {"user_id"});

        Number key = keyHolder.getKey();
        long userId = key == null ? findByEmail(request.email()).orElseThrow().id() : key.longValue();
        assignSingleRole(userId, request.role());
        return userId;
    }

    public void update(long id, UpdateUserRequest request) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", id);
        if (request.email() != null && !request.email().isBlank()) {
            jdbc.update("UPDATE hr.app_user SET username = :email WHERE user_id = :id",
                params.addValue("email", request.email()));
        }
        if (request.active() != null) {
            jdbc.update("UPDATE hr.app_user SET is_enabled = :active WHERE user_id = :id",
                params.addValue("active", request.active()));
        }
        if (request.role() != null && !request.role().isBlank()) {
            assignSingleRole(id, request.role());
        }
    }

    public long ensureRole(String role) {
        String normalized = role.toLowerCase(Locale.ROOT);
        jdbc.update("""
            INSERT INTO hr.role(name, description)
            VALUES (:name, :description)
            ON CONFLICT (name) DO NOTHING
            """, Map.of("name", normalized, "description", normalized + " application role"));
        return jdbc.queryForObject("SELECT role_id FROM hr.role WHERE name = :name", Map.of("name", normalized), Long.class);
    }

    public void assignSingleRole(long userId, String role) {
        long roleId = ensureRole(role);
        jdbc.update("DELETE FROM hr.user_role WHERE user_id = :userId", Map.of("userId", userId));
        jdbc.update("""
            INSERT INTO hr.user_role(user_id, role_id)
            VALUES (:userId, :roleId)
            ON CONFLICT DO NOTHING
            """, Map.of("userId", userId, "roleId", roleId));
    }

    public void assignRole(long userId, String role) {
        long roleId = ensureRole(role);
        jdbc.update("""
            INSERT INTO hr.user_role(user_id, role_id)
            VALUES (:userId, :roleId)
            ON CONFLICT DO NOTHING
            """, Map.of("userId", userId, "roleId", roleId));
    }

    public boolean existsByEmail(String email) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM hr.app_user WHERE LOWER(username) = LOWER(:email))
            """, Map.of("email", email), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public long insertDemoUser(Long employeeId, String email, String passwordHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.app_user(employee_id, username, password_hash, is_enabled)
            VALUES (:employeeId, :email, :passwordHash, TRUE)
            ON CONFLICT (username) DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("email", email)
                .addValue("passwordHash", passwordHash),
            keyHolder,
            new String[] {"user_id"});
        return findByEmail(email).orElseThrow().id();
    }

    private Optional<AppUserRecord> findOne(String sql, Map<String, ?> params) {
        try {
            AppUserRecord user = jdbc.queryForObject(sql, params, (rs, rowNum) -> mapUser(rs));
            return Optional.of(userWithRoles(user, loadRoles(user.id())));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private List<String> loadRoles(long userId) {
        return jdbc.queryForList("""
            SELECT r.name
              FROM hr.user_role ur
              JOIN hr.role r ON r.role_id = ur.role_id
             WHERE ur.user_id = :userId
             ORDER BY r.name
            """, Map.of("userId", userId), String.class);
    }

    private AppUserRecord mapUser(ResultSet rs) throws SQLException {
        return new AppUserRecord(
            rs.getLong("user_id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            nullableLong(rs, "employee_id"),
            rs.getBoolean("is_enabled"),
            rs.getObject("created_at", LocalDate.class),
            List.of()
        );
    }

    private AppUserRecord userWithRoles(AppUserRecord user, List<String> roles) {
        return new AppUserRecord(user.id(), user.email(), user.passwordHash(), user.name(), user.employeeId(), user.active(), user.createdAt(), roles);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
