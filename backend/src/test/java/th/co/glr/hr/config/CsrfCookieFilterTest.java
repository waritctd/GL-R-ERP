package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Locks in the OWASP double-submit CSRF enforcement of {@link CsrfCookieFilter} (audit P0-3
 * "verify CsrfCookieFilter rejects mismatches"): safe methods pass and receive a token cookie,
 * state-changing requests are rejected unless the {@code X-XSRF-TOKEN} header matches the
 * {@code XSRF-TOKEN} cookie, and the login/punch bootstrap paths are exempt from the check.
 */
class CsrfCookieFilterTest {
    private static final String COOKIE = "XSRF-TOKEN";
    private static final String HEADER = "X-XSRF-TOKEN";

    private final CsrfCookieFilter filter = new CsrfCookieFilter();

    @Test
    void safeGetPassesThroughAndIssuesATokenCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/employees");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("chain invoked for a safe GET").isNotNull();
        assertThat(response.getHeader("Set-Cookie")).as("token cookie issued").contains(COOKIE + "=");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void stateChangingRequestWithoutATokenIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/employees");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).as("chain NOT invoked when CSRF check fails").isNull();
    }

    @Test
    void stateChangingRequestWithAMismatchedHeaderIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/employees");
        request.setCookies(new Cookie(COOKIE, "cookie-token-value"));
        request.addHeader(HEADER, "different-header-value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void stateChangingRequestWithAMatchingTokenPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/employees");
        request.setCookies(new Cookie(COOKIE, "matching-token"));
        request.addHeader(HEADER, "matching-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).as("chain invoked when the double-submit token matches").isNotNull();
    }

    @Test
    void loginBootstrapPathIsExemptFromTheCheck() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("login is reachable without a prior token").isNotNull();
        assertThat(response.getHeader("Set-Cookie")).as("login response bootstraps the token cookie").contains(COOKIE + "=");
    }

    @Test
    void attendancePunchPathIsExemptFromTheCheck() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/attendance/punch");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("device punch is reachable without a session token").isNotNull();
    }

    @Test
    void nonApiRequestsAreNotFilteredForCsrf() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("non-/api paths skip the CSRF filter").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
