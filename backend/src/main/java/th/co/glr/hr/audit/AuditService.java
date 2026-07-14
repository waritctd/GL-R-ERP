package th.co.glr.hr.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Writes append-only audit rows for mutating HR actions (issue #4).
 *
 * <p>Intentionally participates in the caller's transaction: if the audit write fails, the mutation
 * it accompanies is rolled back, so no privileged HR change is ever persisted without an audit trail.
 */
@Service
public class AuditService {
    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(UserPrincipal actor, String action, String entity, Long entityId,
                       Object before, Object after) {
        repository.insert(
            actor == null ? null : actor.id(),
            actor == null ? null : actor.email(),
            action,
            entity,
            entityId,
            toJson(before),
            toJson(after));
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // A serialization failure must not silently drop the audit record.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize audit payload");
        }
    }
}
