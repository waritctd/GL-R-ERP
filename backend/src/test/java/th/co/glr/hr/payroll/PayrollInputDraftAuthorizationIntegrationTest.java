package th.co.glr.hr.payroll;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * Payroll input draft (2026-07-30): verifies {@code GET/PUT /api/payroll/input-draft} on top of
 * the real {@code SecurityFilterChain} -- HR reaches both, CEO reaches only the GET (view), any
 * other role is rejected (403) from both. Written wrong-way-round -- the assertions that matter
 * are the roles that must NOT reach it.
 *
 * <p>No NEW authorization decision is introduced here: both endpoints reuse
 * {@link PayrollService}'s existing {@code PAYROLL_VIEW_ROLES} (hr, ceo) / {@code
 * PAYROLL_EDIT_ROLES} (hr) gate verbatim -- the identical split already enforced for
 * tax-allowances/ytd-seed/component-tax-treatments and (for the GET side)
 * {@code suggested-inputs}, mirrored via {@code @PreAuthorize("hasAnyRole('HR','CEO')")} /
 * {@code @PreAuthorize("hasRole('HR')")} on {@link PayrollController}. This test class exists so
 * that reuse is proven against the real filter chain rather than merely asserted by inspection,
 * mirroring {@link PayrollSuggestedInputsAuthorizationIntegrationTest}'s pattern exactly (new test
 * class, real Postgres via {@link PostgresTestSupport}, rather than editing a shared file a
 * concurrent branch might also be touching).
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test")
@SpringBootTest
class PayrollInputDraftAuthorizationIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private static final String EMPTY_SAVE_BODY = "{\"payrollMonth\":\"2026-08-01\",\"inputs\":[]}";

    private final MockMvc mvc;

    @Autowired
    PayrollInputDraftAuthorizationIntegrationTest(WebApplicationContext context,
                                                   @Qualifier("springSecurityFilterChain") Filter securityFilterChain) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
    }

    @Test
    void hrCanReadTheDraft() throws Exception {
        mvc.perform(get("/api/payroll/input-draft?payrollMonth=2026-08").session(sessionFor("hr")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.payrollMonth").value("2026-08-01"))
            .andExpect(jsonPath("$.drafts").isArray());
    }

    @Test
    void ceoCanReadTheDraft() throws Exception {
        mvc.perform(get("/api/payroll/input-draft?payrollMonth=2026-08").session(sessionFor("ceo")))
            .andExpect(status().isOk());
    }

    @Test
    void aPlainEmployeeCannotReadTheDraft() throws Exception {
        mvc.perform(get("/api/payroll/input-draft?payrollMonth=2026-08").session(sessionFor("employee")))
            .andExpect(status().isForbidden());
    }

    @Test
    void aSalesRoleCannotReadTheDraftEither() throws Exception {
        mvc.perform(get("/api/payroll/input-draft?payrollMonth=2026-08").session(sessionFor("sales")))
            .andExpect(status().isForbidden());
    }

    @Test
    void anUnauthenticatedReadIsRejectedWith401() throws Exception {
        mvc.perform(get("/api/payroll/input-draft?payrollMonth=2026-08"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void hrCanSaveTheDraft() throws Exception {
        mvc.perform(put("/api/payroll/input-draft")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMPTY_SAVE_BODY))
            .andExpect(status().isOk());
    }

    @Test
    void ceoCanReadButCannotSaveTheDraft() throws Exception {
        // CEO is view-only for payroll input (same split as process/tax-allowances/ytd-seed edits).
        mvc.perform(put("/api/payroll/input-draft")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMPTY_SAVE_BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    void aPlainEmployeeCannotSaveTheDraft() throws Exception {
        mvc.perform(put("/api/payroll/input-draft")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMPTY_SAVE_BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    void anUnauthenticatedSaveIsRejectedWith401() throws Exception {
        mvc.perform(put("/api/payroll/input-draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMPTY_SAVE_BODY))
            .andExpect(status().isUnauthorized());
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 1L, true, LocalDate.now(), false, null, false));
        return session;
    }
}
