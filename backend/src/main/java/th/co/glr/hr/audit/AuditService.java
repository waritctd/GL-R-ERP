package th.co.glr.hr.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Writes append-only entries to {@code hr.audit_log} for accountable, mutating actions
 * (employee edits, profile-request approvals, user/role changes).
 *
 * <p>Calls are made from within the mutating service's {@code @Transactional} boundary, so the
 * audit row commits atomically with the change it describes. The table is append-only by design;
 * a database trigger blocks any UPDATE/DELETE.
 */
@Service
public class AuditService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Record a single audit entry.
     *
     * @param actorUserId the user performing the action ({@code null} only for system actions)
     * @param action      a stable action key, e.g. {@code "employee.update"}
     * @param entity      the entity type, e.g. {@code "employee"}
     * @param entityId    the affected entity's identifier (stringified)
     * @param before      snapshot before the change ({@code null} for creates), serialized to JSON
     * @param after       snapshot after the change ({@code null} for deletes), serialized to JSON
     */
    public void record(Long actorUserId, String action, String entity, Object entityId, Object before, Object after) {
        jdbc.update("""
            INSERT INTO hr.audit_log(actor_user_id, action, entity, entity_id, before_json, after_json)
            VALUES (:actorUserId, :action, :entity, :entityId, CAST(:beforeJson AS jsonb), CAST(:afterJson AS jsonb))
            """,
            new MapSqlParameterSource()
                .addValue("actorUserId", actorUserId)
                .addValue("action", action)
                .addValue("entity", entity)
                .addValue("entityId", entityId == null ? null : String.valueOf(entityId))
                .addValue("beforeJson", toJson(before))
                .addValue("afterJson", toJson(after)));
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            // Never let an audit-serialization issue abort the business transaction silently;
            // store a marker so the row is still written with traceable context.
            return "{\"_auditSerializationError\":\"" + value.getClass().getSimpleName() + "\"}";
        }
    }
}
