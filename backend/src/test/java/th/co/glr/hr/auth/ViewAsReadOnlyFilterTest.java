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

/** Decision half of the read-only "view as" gate: does {@link ViewAsReadOnlyFilter} pass or 403 a request. */
class ViewAsReadOnlyFilterTest {

    private static final String MESSAGE = "โหมดดูแทนพนักงานเป็นแบบอ่านอย่างเดียว ไม่สามารถทำรายการนี้ได้";

    private final ViewAsReadOnlyFilter filter = new ViewAsReadOnlyFilter();

    @ParameterizedTest(name = "[{index}] {0} {1} passes when not viewing")
    @CsvSource({
        "POST,   /api/employees",
        "PUT,    /api/employees/1",
        "PATCH,  /api/employees/1",
        "DELETE, /api/employees/1",
    })
    void withoutViewAs_writesPass(String method, String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(method, path, session(false)), response, chain);

        assertThat(chain.getRequest()).as("chain invoked").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "[{index}] {0} {1} passes while viewing")
    @CsvSource({
        "GET,     /api/employees",
        "GET,     /api/leave/balances",
        "GET,     /api/auth/me",
        "HEAD,    /api/leave/policy-document",
        "OPTIONS, /api/employees",
    })
    void whileViewing_readsPass(String method, String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(method, path, session(true)), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("chain invoked").isNotNull();
    }

    @ParameterizedTest(name = "[{index}] {0} {1} is refused while viewing")
    @CsvSource({
        "POST,   /api/employees",
        "POST,   /api/auth/change-password",
        "POST,   /api/leave",
        "POST,   /api/leave/1/approve",
        "POST,   /api/payroll/tax-allowances/declarations/me",
        "POST,   /api/auth/forgot-password",
        "PUT,    /api/employees/1",
        "PATCH,  /api/employees/1",
        "PATCH,  /api/notifications/1/read",
        "DELETE, /api/payroll/tax-allowances/declarations/1",
        "DELETE, /api/employees/1",
        // Same namespace as allowlisted entries, but not on it: per-endpoint match, not a prefix.
        "POST,   /api/view-as/candidates",
        "PUT,    /api/view-as",
        "POST,   /api/payroll/preview/other",
        "POST,   /api/payroll/tax-allowances/declarations/me/form.pdf/x",
        "DELETE, /api/auth/logout",
    })
    void whileViewing_writesAreRefused(String method, String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(method, path, session(true)), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("{\"message\":\"" + MESSAGE + "\",\"status\":403}");
        assertThat(chain.getRequest()).as("chain NOT invoked").isNull();
    }

    @ParameterizedTest(name = "[{index}] {0} {1} is allowlisted while viewing")
    @CsvSource({
        "POST,   /api/view-as",
        "DELETE, /api/view-as",
        "POST,   /api/auth/logout",
        "POST,   /api/auth/login",
        "POST,   /api/payroll/tax-allowances/declarations/me/form.pdf",
        "POST,   /api/payroll/tax-allowances/declarations/me/estimate",
        "POST,   /api/payroll/preview",
        "POST,   /api/payroll/preview/export/kbank",
        "POST,   /api/deposit-notices/12/preview",
        "POST,   /api/customer-quotations/12/preview",
        "POST,   /api/deal-quotations/calculate-line",
        "POST,   /api/leave/preview",
        "POST,   /api/commissions/simulator",
    })
    void whileViewing_allowlistedPostsPass(String method, String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(method, path, session(true)), response, chain);

        assertThat(chain.getRequest()).as("chain invoked").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void nonApiPathsAreNeverTouched() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("POST", "/index.html", session(true)), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void sessionlessRequestPasses() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("POST", "/api/employees") {{ setRequestURI("/api/employees"); }},
            new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpSession session(boolean viewing) {
        MockHttpSession session = new MockHttpSession();
        UserPrincipal real = new UserPrincipal(1L, "owner@example.test", "Owner", "employee", 1L, true,
            LocalDate.of(2020, 1, 1), false, null, false);
        session.setAttribute(SessionContext.SESSION_USER_KEY, real);
        if (viewing) {
            session.setAttribute(SessionContext.VIEW_AS_USER_KEY, new UserPrincipal(2L, "target@example.test",
                "Target", "hr", 2L, true, LocalDate.of(2020, 1, 1), false, null, false));
        }
        return session;
    }

    private static MockHttpServletRequest request(String method, String path, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setSession(session);
        return request;
    }
}
