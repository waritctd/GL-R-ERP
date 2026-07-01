package th.co.glr.hr.audit;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLogRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AuditLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long actorUserId, String actorEmail, String action, String entity,
                       Long entityId, String beforeJson, String afterJson) {
        jdbc.update("""
            INSERT INTO hr.audit_log
                (actor_user_id, actor_email, action, entity, entity_id, before_json, after_json)
            VALUES
                (:actorUserId, :actorEmail, :action, :entity, :entityId,
                 CAST(:beforeJson AS jsonb), CAST(:afterJson AS jsonb))
            """, new MapSqlParameterSource()
                .addValue("actorUserId", actorUserId)
                .addValue("actorEmail", actorEmail)
                .addValue("action", action)
                .addValue("entity", entity)
                .addValue("entityId", entityId)
                .addValue("beforeJson", beforeJson)
                .addValue("afterJson", afterJson));
    }
}
