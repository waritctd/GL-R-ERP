package th.co.glr.hr.payroll;

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
 * Verifies {@code GET /api/payroll/suggested-inputs} (special-pay carry-forward, 2026-07-23) on top of
 * the real {@code SecurityFilterChain}: HR and CEO reach it (200), any other role is rejected (403).
 * Written wrong-way-round — the assertions that matter are the roles that must NOT reach it.
 *
 * <p>New test class, mirroring {@link th.co.glr.hr.config.SecurityAuthorizationIntegrationTest}'s
 * pattern exactly (real filter chain, real Postgres via {@link PostgresTestSupport}), rather than
 * adding cases to that file directly — a concurrent branch
 * ({@code feat/payroll-statutory-export-files}) edits {@code SecurityAuthorizationIntegrationTest}
 * at the same time.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test")
@SpringBootTest
class PayrollSuggestedInputsAuthorizationIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private final MockMvc mvc;

    @Autowired
    PayrollSuggestedInputsAuthorizationIntegrationTest(WebApplicationContext context,
                                                         @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
    }

    @Test
    void hrCanReachSuggestedInputs() throws Exception {
        mvc.perform(get("/api/payroll/suggested-inputs?payrollMonth=2026-07").session(sessionFor("hr")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.payrollMonth").value("2026-07-01"))
            .andExpect(jsonPath("$.suggestions").isArray());
    }

    @Test
    void ceoCanReachSuggestedInputs() throws Exception {
        mvc.perform(get("/api/payroll/suggested-inputs?payrollMonth=2026-07").session(sessionFor("ceo")))
            .andExpect(status().isOk());
    }

    @Test
    void aPlainEmployeeCannotReachSuggestedInputs() throws Exception {
        mvc.perform(get("/api/payroll/suggested-inputs?payrollMonth=2026-07").session(sessionFor("employee")))
            .andExpect(status().isForbidden());
    }

    @Test
    void aSalesRoleCannotReachSuggestedInputsEither() throws Exception {
        mvc.perform(get("/api/payroll/suggested-inputs?payrollMonth=2026-07").session(sessionFor("sales")))
            .andExpect(status().isForbidden());
    }

    @Test
    void anUnauthenticatedRequestIsRejectedWith401() throws Exception {
        mvc.perform(get("/api/payroll/suggested-inputs?payrollMonth=2026-07"))
            .andExpect(status().isUnauthorized());
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 1L, true, LocalDate.now(), false, null, false));
        return session;
    }
}
