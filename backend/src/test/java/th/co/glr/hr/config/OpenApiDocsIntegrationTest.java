package th.co.glr.hr.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.support.PostgresTestSupport;

/**
 * Verifies the P2-1 OpenAPI documentation exposure under default-deny. The docs are intentionally
 * NOT allowlisted in {@code SecurityConfig}: an anonymous {@code GET /v3/api-docs} must be rejected
 * with 401 (no anonymous endpoint enumeration), while an authenticated session can read the
 * generated OpenAPI contract with the expected API title (proving springdoc actually serves it).
 * Boots the real context over the real {@code SecurityFilterChain}, mirroring
 * {@link SecurityAuthorizationIntegrationTest}; needs a real Postgres, resolved by
 * {@link PostgresTestSupport} (TEST_DB_URL override, else a throwaway Testcontainers Postgres).
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
@SpringBootTest
class OpenApiDocsIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private final MockMvc mvc;

    @Autowired
    OpenApiDocsIntegrationTest(WebApplicationContext context,
                               @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
    }

    @Test
    void apiDocsRequireAuthentication() throws Exception {
        // Docs are behind default-deny: an anonymous caller cannot enumerate endpoints/schemas.
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanReadApiDocs() throws Exception {
        mvc.perform(get("/v3/api-docs").session(sessionFor("employee")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openapi").exists())
            .andExpect(jsonPath("$.info.title").value("GL-R HR Portal API"));
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 1L, true, LocalDate.now(), false, null, false));
        return session;
    }
}
