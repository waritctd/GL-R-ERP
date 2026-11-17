package th.co.glr.hr.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import th.co.glr.hr.config.CsrfCookieFilter;
import th.co.glr.hr.support.PostgresTestSupport;

/** Verifies the SecurityConfig/CsrfCookieFilter allowlist additions for the self-service forgot-password
 * endpoints over the REAL SecurityFilterChain AND the REAL CsrfCookieFilter together. Why this file exists in
 * addition to PasswordResetIntegrationTest and th.co.glr.hr.config.CsrfCookieFilterTest. */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
@SpringBootTest
class PasswordResetSecurityIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private final MockMvc mvc;

    @Autowired
    PasswordResetSecurityIntegrationTest(WebApplicationContext context,
                                          CsrfCookieFilter csrfCookieFilter,
                                          @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        // Order matters: CsrfCookieFilter (@Order(0)) runs BEFORE the security chain in production.
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(csrfCookieFilter, securityFilterChain)
            .build();
    }

    @Test
    void anonymousPostToForgotPasswordWithNoCsrfHeaderIsReachable() throws Exception {
        // No X-XSRF-TOKEN header, no prior request, no session - exactly a fresh caller landing on /forgot-password.
        // 200 (not 401/403) proves BOTH SecurityConfig's permitAll AND CsrfCookieFilter's exemption cleared.
        mvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"no-such-employee@glr.co.th\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void anonymousPostToResetPasswordWithNoCsrfHeaderIsReachable() throws Exception {
        // A syntactically-valid-but-unknown token: the endpoint must still be REACHED (past both guards) and rejected
        // by BUSINESS logic (400, PasswordResetService's own invalid/expired-token error), never blocked by
        // security/CSRF (401/403) first.
        mvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"not-a-real-token\",\"newPassword\":\"SomeValidPass1\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getOnForgotPasswordIsNotPermitted() throws Exception {
        // SecurityConfig scopes the permit with requestMatchers(HttpMethod.POST, ...) - an explicit-method request
        // matcher only matches that exact HTTP method.
        mvc.perform(get("/api/auth/forgot-password")).andExpect(status().isUnauthorized());
    }

    @Test
    void getOnResetPasswordIsNotPermitted() throws Exception {
        mvc.perform(get("/api/auth/reset-password")).andExpect(status().isUnauthorized());
    }

    @Test
    void aNearbyNonExemptAuthEndpointStaysAuthenticatedOnly() throws Exception {
        // Wrong-way-round, and the assertion that actually matters: the two new permits must not have widened
        // default-deny to the REST of /api/auth/**. change-password is the closest possible neighbour (same
        // controller, same base path) and was never exempted - it must still require authentication.
        mvc.perform(post("/api/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"SomeValidPass1\"}")
                .cookie(new Cookie("XSRF-TOKEN", "test-csrf-token"))
                .header("X-XSRF-TOKEN", "test-csrf-token"))
            .andExpect(status().isUnauthorized());
    }
}
