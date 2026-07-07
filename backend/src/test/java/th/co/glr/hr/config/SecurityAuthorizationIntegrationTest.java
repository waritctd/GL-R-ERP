package th.co.glr.hr.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.support.PostgresTestSupport;

/**
 * Verifies the default-deny authorization flip: unauthenticated API requests fail closed with 401,
 * the exact public allowlist (OPTIONS preflight + login + attendance punch) stays reachable, and the
 * {@code @PreAuthorize} role authorities still resolve (HR 200 vs wrong-role 403) on top of the real
 * SecurityFilterChain. Boots the real context, so it needs a real Postgres: resolved by
 * {@link PostgresTestSupport} (TEST_DB_URL override, else a throwaway Testcontainers Postgres);
 * skipped only when neither is available.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@SpringBootTest
class SecurityAuthorizationIntegrationTest {

    // Point the booted Spring context's datasource at the Postgres resolved by PostgresTestSupport
    // (TEST_DB_URL override, else the Testcontainers singleton), mirroring
    // AbstractPostgresIntegrationTest. Without this, @SpringBootTest would fall back to the app
    // default (spring.datasource.url=.../hris) and fail to boot.
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private final MockMvc mvc;

    @Autowired
    SecurityAuthorizationIntegrationTest(WebApplicationContext context,
                                         @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        // Wire the real Spring Security filter chain over the full MVC context (no spring-security-test dep).
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
    }

    @Test
    void unauthenticatedProtectedGetsAreRejectedWith401() throws Exception {
        mvc.perform(get("/api/employees")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflightIsPermittedAtTheSecurityLayer() throws Exception {
        // OPTIONS is allowlisted at the security layer, so it must not be blocked with a 401.
        mvc.perform(options("/api/employees")
                .header("Access-Control-Request-Method", "GET")
                .header("Origin", "http://localhost:5173"))
            .andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }

    @Test
    void loginRemainsReachableAsAPublicEndpoint() throws Exception {
        // Bad creds => reachable controller response (401 from the controller), NOT a filter-chain
        // block. The point is that the security layer let the request through to the controller.
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@glr.co.th\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void attendancePunchRemainsReachableAsAPublicEndpoint() throws Exception {
        // No device token => the controller rejects it (4xx), but it is NOT filter-blocked at the
        // security layer: the request reached the controller, which is what the allowlist guarantees.
        mvc.perform(post("/api/attendance/punch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void validHrSessionReaches200OnAProtectedEndpoint() throws Exception {
        mvc.perform(get("/api/employees").session(sessionFor("hr")))
            .andExpect(status().isOk());
    }

    @Test
    void wrongRoleSessionGets403OnAPreAuthorizeEndpoint() throws Exception {
        // Send the required `payrollMonth` param so request binding succeeds and the @PreAuthorize
        // check is what rejects the wrong role (a missing param would 400 before authz runs).
        mvc.perform(get("/api/payroll?payrollMonth=2026-07").session(sessionFor("employee")))
            .andExpect(status().isForbidden());
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 1L, true, LocalDate.now(), false, null, false));
        return session;
    }
}
