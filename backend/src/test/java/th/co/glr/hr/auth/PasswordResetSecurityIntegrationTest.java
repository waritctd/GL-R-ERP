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

/**
 * Verifies the {@code SecurityConfig}/{@code CsrfCookieFilter} allowlist additions for the
 * self-service forgot-password endpoints over the REAL {@code SecurityFilterChain} AND the REAL
 * {@code CsrfCookieFilter} together — same shape as
 * {@link th.co.glr.hr.brand.BrandAssetSecurityIntegrationTest} (the reference implementation for
 * "a scoped {@code permitAll}, including asserting OTHER paths stay locked down"), extended with
 * the CSRF filter that class does not need.
 *
 * <p><b>Why this file exists in addition to {@link PasswordResetIntegrationTest} and {@link
 * th.co.glr.hr.config.CsrfCookieFilterTest}.</b> Neither of those goes through the actual HTTP/
 * filter-chain layer for these two paths:
 * <ul>
 *   <li>{@link PasswordResetIntegrationTest} calls {@code PasswordResetService} methods directly —
 *       it proves the business logic is correct, but nothing about {@code SecurityConfig} ever
 *       runs, so it cannot prove the endpoint is anonymously REACHABLE (no 401) or that a
 *       nearby non-exempt path stays authenticated-only.</li>
 *   <li>{@link th.co.glr.hr.config.CsrfCookieFilterTest} unit-tests {@code CsrfCookieFilter} in
 *       isolation (no Spring context, no {@code SecurityConfig}) — it proves the CSRF exemption in
 *       isolation, but not that the request also clears {@code anyRequest().authenticated()}.</li>
 * </ul>
 *
 * <p><b>Both filters are wired in explicitly, in production order.</b> {@code CsrfCookieFilter} is
 * a plain {@code @Component @Order(0)} servlet filter — NOT part of the {@code SecurityFilterChain}
 * bean (see {@code SecurityConfig}: it only {@code addFilterBefore}/{@code addFilterAfter}s {@code
 * SessionSecurityFilter}/{@code MustChangePasswordFilter} inside that chain; {@code
 * CsrfCookieFilter} sits entirely outside it, earlier in the raw servlet pipeline). {@code
 * MockMvcBuilders.webAppContextSetup(...).addFilters(...)} — the pattern this file and {@code
 * BrandAssetSecurityIntegrationTest} both use instead of {@code @AutoConfigureMockMvc} — only runs
 * the filters explicitly passed to it. Passing {@code securityFilterChain} alone (as {@code
 * BrandAssetSecurityIntegrationTest} correctly does, since it makes no CSRF claim) would silently
 * skip {@code CsrfCookieFilter} entirely — a "no CSRF header needed" assertion would then pass for
 * the wrong reason: not because the exemption fired, but because nothing capable of rejecting on
 * CSRF ran at all. Both filters together is what actually proves the two guards clear for an
 * anonymous, CSRF-header-less caller — exactly the request shape a client landing fresh on
 * {@code /forgot-password}, or arriving via an emailed {@code /reset-password?token=...} link,
 * actually sends.
 */
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
        // No X-XSRF-TOKEN header, no prior request, no session - exactly a fresh caller landing on
        // /forgot-password. 200 (not 401/403) proves BOTH SecurityConfig's permitAll AND
        // CsrfCookieFilter's exemption cleared. The address does not need to exist -
        // forgotPassword's anti-enumeration contract returns the same 200 either way (see
        // PasswordResetIntegrationTest).
        mvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"no-such-employee@glr.co.th\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void anonymousPostToResetPasswordWithNoCsrfHeaderIsReachable() throws Exception {
        // A syntactically-valid-but-unknown token: the endpoint must still be REACHED (past both
        // guards) and rejected by BUSINESS logic (400, PasswordResetService's own
        // invalid/expired-token error), never blocked by security/CSRF (401/403) first.
        mvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"not-a-real-token\",\"newPassword\":\"SomeValidPass1\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getOnForgotPasswordIsNotPermitted() throws Exception {
        // SecurityConfig scopes the permit with requestMatchers(HttpMethod.POST, ...) - an
        // explicit-method request matcher only matches that exact HTTP method. A GET to the same
        // path does NOT match the permit rule and falls through to anyRequest().authenticated(),
        // exactly like BrandAssetSecurityIntegrationTest#anonymousPostOnTheBrandLogoPathIsNotPermitted
        // proves the mirror image for the brand logo's GET-only permit.
        mvc.perform(get("/api/auth/forgot-password")).andExpect(status().isUnauthorized());
    }

    @Test
    void getOnResetPasswordIsNotPermitted() throws Exception {
        mvc.perform(get("/api/auth/reset-password")).andExpect(status().isUnauthorized());
    }

    @Test
    void aNearbyNonExemptAuthEndpointStaysAuthenticatedOnly() throws Exception {
        // Wrong-way-round, and the assertion that actually matters: the two new permits must not
        // have widened default-deny to the REST of /api/auth/**. change-password is the closest
        // possible neighbour (same controller, same base path) and was never exempted - it must
        // still require authentication.
        //
        // A matching XSRF-TOKEN cookie/header pair is supplied deliberately, even though the
        // caller has no session: change-password is NOT CSRF-exempt, so without a matching pair
        // CsrfCookieFilter (@Order(0), running before SecurityConfig) would reject with 403 first,
        // which would make this assertion pass for the wrong reason - proving nothing about
        // SecurityConfig's own default-deny, only that the CSRF guard also still applies here
        // (already covered by CsrfCookieFilterTest). Supplying a matching pair clears THAT guard
        // so the request reaches SecurityConfig, and the 401 below is genuinely
        // anyRequest().authenticated() rejecting an anonymous caller.
        mvc.perform(post("/api/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"SomeValidPass1\"}")
                .cookie(new Cookie("XSRF-TOKEN", "test-csrf-token"))
                .header("X-XSRF-TOKEN", "test-csrf-token"))
            .andExpect(status().isUnauthorized());
    }
}
