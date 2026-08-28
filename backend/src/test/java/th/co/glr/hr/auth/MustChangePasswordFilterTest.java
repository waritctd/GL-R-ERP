package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/**
 * The decision half of the forced-password-change gate: given a session principal, does
 * {@link MustChangePasswordFilter} let the request through or 403 it?
 *
 * <p>Deliberately written wrong-way-round — the assertions that matter are the ones proving a
 * must-change session <em>cannot</em> reach the API, not that a normal session can. The enforcement
 * half (that this filter is actually mounted in the real chain, in front of the real controllers)
 * is pinned by {@code SecurityAuthorizationIntegrationTest}; a passing test here says nothing about
 * whether the filter is wired in at all.
 */
class MustChangePasswordFilterTest {

    private final MustChangePasswordFilter filter = new MustChangePasswordFilter();

    // ---------------------------------------------------------------- blocked

    @ParameterizedTest(name = "[{index}] {0} {1} is blocked for a must-change session")
    @CsvSource({
        "GET,    /api/employees",
        "GET,    /api/employees/1",
        "GET,    /api/payroll",
        "GET,    /api/payroll/1/export/kbank",
        "POST,   /api/employees",
        "PUT,    /api/employees/1",
        "DELETE, /api/employees/1",
        "GET,    /api/dashboard/summary",
        "GET,    /api/profile-requests",
        "GET,    /api/tickets",
        // Same namespace as the allowlist, but not on it: proving the match is per-endpoint and
        // not a lazy `/api/auth/**` prefix that would hand the whole auth surface over.
        "POST,   /api/auth/reset-password",
    })
    void mustChangeSessionIsBlockedFromTheApi(String method, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(method, path, sessionWith(true)), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).as("chain NOT invoked — the request never reaches the controller").isNull();
    }

    @Test
    void theBlockedResponseCarriesTheStandardErrorShape() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/api/employees", sessionWith(true)), response, new MockFilterChain());

        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
            .as("matches ApiExceptionHandler.ErrorResponse, so the SPA's client.js reads .message")
            .isEqualTo("{\"message\":\"กรุณาเปลี่ยนรหัสผ่านก่อนใช้งานระบบ\",\"status\":403}");
    }

    @Test
    void theAllowlistIsMethodSensitive() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // GET /api/auth/me is allowed; POST to the same path is not.
        filter.doFilter(request("POST", "/api/auth/me", sessionWith(true)), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    // ---------------------------------------------------------------- allowed

    @ParameterizedTest(name = "[{index}] {0} {1} stays reachable for a must-change session")
    @CsvSource({
        "POST, /api/auth/login",
        "POST, /api/auth/logout",
        "POST, /api/auth/change-password",
        "GET,  /api/auth/me",
    })
    void theEscapeHatchesStayOpen(String method, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(method, path, sessionWith(true)), response, chain);

        assertThat(chain.getRequest()).as("chain invoked — otherwise the user is locked out for good").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aNormalSessionIsUntouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("GET", "/api/employees", sessionWith(false)), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void anAnonymousRequestIsUntouchedAndLeftToTheRestOfTheChain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // No session at all — e.g. the device agent's POST /api/attendance/punch, or any
        // unauthenticated call that SecurityConfig's default-deny should 401 further down.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/attendance/punch");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("this filter must not become a second authentication gate").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void nonApiPathsAreNotFilteredAtAll() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // The SPA's own static assets are served from the same origin; a must-change user still
        // has to be able to load the app that renders the change-password modal.
        filter.doFilter(request("GET", "/index.html", sessionWith(true)), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequest request(String method, String path, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest(method.trim(), path.trim());
        request.setSession(session);
        return request;
    }

    private MockHttpSession sessionWith(boolean mustChangePassword) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, "hr@glr.co.th", "hr", "hr", 1L, true, LocalDate.now(),
                mustChangePassword, null, false));
        return session;
    }
}
