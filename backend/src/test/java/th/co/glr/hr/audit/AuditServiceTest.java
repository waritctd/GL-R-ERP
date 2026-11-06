package th.co.glr.hr.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

/** The three shapes {@code viewingAs} is merged into when AuditService swaps in the real actor. */
class AuditServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AuditLogRepository repo = mock(AuditLogRepository.class);
    private final AuditService service = new AuditService(repo, JSON);
    private final UserPrincipal real = principal(1, "Owner");
    private final UserPrincipal target = principal(2, "Target");

    @BeforeEach
    void viewing() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, real);
        session.setAttribute(SessionContext.VIEW_AS_USER_KEY, target);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private JsonNode recorded(Object after) throws Exception {
        service.record(target, "X", "e", 5L, null, after);
        ArgumentCaptor<String> afterJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> actor = ArgumentCaptor.forClass(Long.class);
        verify(repo).insert(actor.capture(), any(), eq("X"), eq("e"), eq(5L), any(), afterJson.capture());
        assertThat(actor.getValue()).as("real actor").isEqualTo(1L);
        return JSON.readTree(afterJson.getValue());
    }

    @Test
    void objectAfter_getsTheKeyAdded() throws Exception {
        JsonNode after = recorded(Map.of("taxYear", 2026));
        assertThat(after.path("taxYear").asInt()).isEqualTo(2026);
        assertThat(after.path("viewingAs").path("employeeId").asLong()).isEqualTo(2L);
        assertThat(after.path("viewingAs").path("name").asText()).isEqualTo("Target");
    }

    @Test
    void nullAfter_becomesJustViewingAs() throws Exception {
        JsonNode after = recorded(null);
        assertThat(after.size()).isEqualTo(1);
        assertThat(after.path("viewingAs").path("employeeId").asLong()).isEqualTo(2L);
    }

    @Test
    void nonObjectAfter_isWrappedAsValue() throws Exception {
        JsonNode after = recorded(java.util.List.of(1, 2));
        assertThat(after.path("value").size()).isEqualTo(2);
        assertThat(after.path("viewingAs").path("name").asText()).isEqualTo("Target");
    }

    private static UserPrincipal principal(long id, String name) {
        return new UserPrincipal(id, name + "@example.test", name, "employee", id, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
