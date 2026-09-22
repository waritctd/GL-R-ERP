package th.co.glr.hr.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Hand-written {@code NamedParameterJdbcTemplate} repository for {@code hr.password_reset_token}
 * (V190) — this codebase has no JPA, so every repository is hand-wired SQL, same as
 * {@link EmployeeAuthRepository}.
 *
 * <p>Only ever stores/queries the SHA-256 hash of a raw token, never the raw value itself — see
 * {@code PasswordResetService} for where the raw token is generated and hashed.
 */
@Repository
public class PasswordResetTokenRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PasswordResetTokenRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Marks every currently-unused token for this employee as used, without touching already-used
     * or already-expired-but-marked-used rows. Called before issuing a fresh token, so at most one
     * token is ever live per employee at a time — an old emailed link stops working the moment a
     * newer one is requested.
     */
    public void invalidateOutstanding(long employeeId) {
        jdbc.update("""
            UPDATE hr.password_reset_token
               SET used_at = now()
             WHERE employee_id = :employeeId
               AND used_at IS NULL
            """, Map.of("employeeId", employeeId));
    }

    /**
     * The creation time of this employee's most recent still-open (unused) token, expired or not —
     * used only for the 60-second anti-spam cooldown in {@code PasswordResetService#forgotPassword},
     * which must fire even against an already-expired token so a caller cannot dodge the cooldown
     * by waiting it out mid-window. Not restricted to {@code expires_at > now()} for that reason.
     */
    public Optional<Instant> mostRecentOpenTokenCreatedAt(long employeeId) {
        try {
            Timestamp createdAt = jdbc.queryForObject("""
                SELECT created_at
                  FROM hr.password_reset_token
                 WHERE employee_id = :employeeId
                   AND used_at IS NULL
                 ORDER BY created_at DESC
                 LIMIT 1
                """, Map.of("employeeId", employeeId), Timestamp.class);
            return Optional.ofNullable(createdAt).map(Timestamp::toInstant);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public void create(long employeeId, String tokenHash, Instant expiresAt) {
        jdbc.update("""
            INSERT INTO hr.password_reset_token (employee_id, token_hash, expires_at)
            VALUES (:employeeId, :tokenHash, :expiresAt)
            """, new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("tokenHash", tokenHash)
                .addValue("expiresAt", Timestamp.from(expiresAt)));
    }

    /** A still-valid (unused, unexpired) token by its hash, or empty if not found/used/expired. */
    public Optional<OpenToken> findValidByTokenHash(String tokenHash) {
        try {
            OpenToken token = jdbc.queryForObject("""
                SELECT id, employee_id
                  FROM hr.password_reset_token
                 WHERE token_hash = :tokenHash
                   AND used_at IS NULL
                   AND expires_at > now()
                """, Map.of("tokenHash", tokenHash),
                (rs, rowNum) -> new OpenToken(rs.getLong("id"), rs.getLong("employee_id")));
            return Optional.ofNullable(token);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public void markUsed(long tokenId) {
        jdbc.update("""
            UPDATE hr.password_reset_token
               SET used_at = now()
             WHERE id = :id
            """, Map.of("id", tokenId));
    }

    public record OpenToken(long id, long employeeId) {
    }
}
