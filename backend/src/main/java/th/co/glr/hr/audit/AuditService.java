package th.co.glr.hr.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Writes append-only audit rows for mutating HR actions (issue #4).
 *
 * <p>Intentionally participates in the caller's transaction: if the audit write fails, the mutation
 * it accompanies is rolled back, so no HR/admin change is ever persisted without an audit trail.
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
        UserPrincipal real = realActorWhileViewingAs(actor);
        if (real != null && (actor == null || real.id() != actor.id())) {
            // Swapped to the real person: also record WHOSE data was read.
            after = withViewingAs(after, actor);
            actor = real;
        }
        repository.insert(
            actor == null ? null : actor.id(),
            actor == null ? null : actor.email(),
            action,
            entity,
            entityId,
            toJson(before),
            toJson(after));
    }

    /** While the owner views as an employee, every row names the REAL person, never the target. */
    private static UserPrincipal realActorWhileViewingAs(UserPrincipal actor) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpSession session = attrs.getRequest().getSession(false);
            if (session != null && session.getAttribute(SessionContext.VIEW_AS_USER_KEY) != null
                && session.getAttribute(SessionContext.SESSION_USER_KEY) instanceof UserPrincipal real) {
                return real;
            }
        }
        return actor;
    }

    /** object -> add the key; null -> only the key; anything else -> {"value": after, "viewingAs": ...}. */
    private Object withViewingAs(Object after, UserPrincipal target) {
        Map<String, Object> viewingAs = new LinkedHashMap<>();
        viewingAs.put("employeeId", target == null ? null : target.id());
        viewingAs.put("name", target == null ? null : target.name());
        JsonNode node = after == null ? null : objectMapper.valueToTree(after);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            // key only
        } else if (node.isObject()) {
            node.properties().forEach(e -> merged.put(e.getKey(), e.getValue()));
        } else {
            merged.put("value", node);
        }
        merged.put("viewingAs", viewingAs);
        return merged;
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
