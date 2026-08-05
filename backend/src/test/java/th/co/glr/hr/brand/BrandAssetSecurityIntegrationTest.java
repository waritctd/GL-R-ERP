package th.co.glr.hr.brand;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
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
import th.co.glr.hr.support.PostgresTestSupport;

/**
 * Verifies the {@code SecurityConfig} allowlist addition for the emailed brand logo: an anonymous
 * {@code GET /api/public/brand/logo.png} is permitted under default-deny (email clients / Gmail's
 * image proxy fetch it with no session/cookie and cannot complete a login flow), while every other
 * surface - including a second, unrelated exposure path for the same bytes - stays behind
 * {@code anyRequest().authenticated()}. Boots the real context over the real
 * {@code SecurityFilterChain}, mirroring {@link th.co.glr.hr.config.ActuatorHealthIntegrationTest};
 * needs a real Postgres, resolved by {@link PostgresTestSupport} (TEST_DB_URL override, else a
 * throwaway Testcontainers Postgres).
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
@SpringBootTest
class BrandAssetSecurityIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private final MockMvc mvc;

    @Autowired
    BrandAssetSecurityIntegrationTest(WebApplicationContext context,
                                       @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
    }

    @Test
    void anonymousGetOnTheBrandLogoIsPermitted() throws Exception {
        mvc.perform(get("/api/public/brand/logo.png"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE));
    }

    @Test
    void anonymousGetOnAnUnrelatedProtectedEndpointStillRequiresAuthentication() throws Exception {
        // Wrong-way-round, and the assertion that actually matters: the brand permit must not widen
        // default-deny to anything else. If /api/public/brand/** were ever mistakenly broadened to
        // /api/public/** or /api/**, this goes red.
        mvc.perform(get("/api/employees")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousPostOnTheBrandLogoPathIsNotPermitted() throws Exception {
        // SecurityConfig scopes the permit with requestMatchers(HttpMethod.GET, "/api/public/brand/**")
        // - an explicit-method request matcher only matches that exact HTTP method, so a POST to the
        // same path does NOT match the permit rule and falls through to anyRequest().authenticated().
        // That authorization decision happens in the security filter chain, before the request is
        // ever dispatched to BrandController (whose @GetMapping would itself 405 a POST) - so an
        // anonymous POST here is a 401, not a 405. Nothing before this test proved the matcher was
        // GET-only rather than method-agnostic.
        mvc.perform(post("/api/public/brand/logo.png")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousGetOnTheStaticResourceRouteForTheSameBytesStillRequiresAuthentication() throws Exception {
        // Spring Boot auto-serves classpath:/static/** at the site root, so the same logo bytes
        // committed at static/brand/glr-logo.png are ALSO reachable at /brand/glr-logo.png - a second
        // exposure path SecurityConfig never explicitly names. It currently falls under
        // anyRequest().authenticated() by default; pin that here so it cannot silently open later
        // (e.g. a future "let's just permitAll static assets" change).
        mvc.perform(get("/brand/glr-logo.png")).andExpect(status().isUnauthorized());
    }
}
