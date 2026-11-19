package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import th.co.glr.hr.config.AppProperties;

class LoginRateLimitFilterTest {
    private final AppProperties properties = new AppProperties();
    private final LoginAttemptTracker tracker = new LoginAttemptTracker();
    private final LoginRateLimitFilter filter =
        new LoginRateLimitFilter(properties, tracker, new ObjectMapper());

    @Test
    void blocksWithRetryAfterOnceAccountLimitTripped() throws Exception {
        properties.getLoginRateLimit().setMaxAccountFailures(5);

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse failed = attempt("user@glr.co.th", chainStatus(401));
            assertThat(failed.getStatus()).isEqualTo(401);
        }

        AtomicInteger chainCalls = new AtomicInteger();
        MockHttpServletResponse blocked = attempt("user@glr.co.th", (req, res) -> {
            chainCalls.incrementAndGet();
            ((HttpServletResponse) res).setStatus(401);
        });

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        assertThat(chainCalls.get()).isZero(); // short-circuited before the auth logic ran
    }

    private FilterChain chainStatus(int status) {
        return (req, res) -> ((HttpServletResponse) res).setStatus(status);
    }

    private MockHttpServletResponse attempt(String email, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContent(("{\"email\":\"" + email + "\",\"password\":\"guess\"}")
            .getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
