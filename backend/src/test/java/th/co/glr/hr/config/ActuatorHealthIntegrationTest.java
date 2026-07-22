package th.co.glr.hr.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import th.co.glr.hr.support.PostgresTestSupport;

/**
 * Verifies the P1-3 observability floor's Actuator health exposure: an anonymous {@code GET
 * /actuator/health} is permitted under default-deny (proves the {@code SecurityConfig} allowlist
 * addition works) and returns {@code UP} with no component/detail leak (per
 * {@code management.endpoint.health.show-details: never}), while an unauthenticated protected
 * endpoint stays a 401 — i.e. the actuator permit did not widen default-deny to anything else.
 * Boots the real context over the real {@code SecurityFilterChain}, mirroring
 * {@link SecurityAuthorizationIntegrationTest}; needs a real Postgres, resolved by
 * {@link PostgresTestSupport} (TEST_DB_URL override, else a throwaway Testcontainers Postgres).
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
@SpringBootTest
class ActuatorHealthIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private final MockMvc mvc;

    @Autowired
    ActuatorHealthIntegrationTest(WebApplicationContext context,
                                   @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
    }

    @Test
    void anonymousHealthCheckIsPermittedAndReportsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            // show-details: never -> no component breakdown (e.g. no "db"/"diskSpace" detail) leaks
            // to an anonymous caller.
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void protectedEndpointStillRequiresAuthentication() throws Exception {
        // The actuator permit must not widen default-deny: an unrelated protected endpoint is
        // still 401 when unauthenticated.
        mvc.perform(get("/api/employees")).andExpect(status().isUnauthorized());
    }
}
