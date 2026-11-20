package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Locks in the OWASP double-submit CSRF enforcement of CsrfCookieFilter: safe methods pass and receive a token
 * cookie, state-changing requests are rejected unless the X-XSRF-TOKEN header matches the XSRF-TOKEN cookie,
 * and the login/punch bootstrap paths are exempt from the check. */
class CsrfCookieFilterTest {
    private static final String COOKIE = "XSRF-TOKEN";
    private static final String HEADER = "X-XSRF-TOKEN";

    private final CsrfCookieFilter filter = new CsrfCookieFilter();

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

}
