package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

class SecurityHeadersFilterTest {
    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void setsBaselineHeadersOnEveryResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/employees");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
    }

    @Test
    void emitsHstsOnlyOverHttps() throws Exception {
        MockHttpServletRequest insecure = new MockHttpServletRequest("GET", "/api/employees");
        MockHttpServletResponse insecureResponse = new MockHttpServletResponse();
        filter.doFilter(insecure, insecureResponse, new MockFilterChain());
        assertThat(insecureResponse.getHeader("Strict-Transport-Security")).isNull();

        MockHttpServletRequest secure = new MockHttpServletRequest("GET", "/api/employees");
        secure.setSecure(true);
        MockHttpServletResponse secureResponse = new MockHttpServletResponse();
        filter.doFilter(secure, secureResponse, new MockFilterChain());
        assertThat(secureResponse.getHeader("Strict-Transport-Security")).contains("max-age=");
    }
}
