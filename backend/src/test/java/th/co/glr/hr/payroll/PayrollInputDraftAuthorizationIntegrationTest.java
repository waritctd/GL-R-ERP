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
import org.springframework.http.HttpHeaders;
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

/** Payroll input draft (2026-07-30): verifies GET/PUT /api/payroll/input-draft on top of the real
 * SecurityFilterChain -- HR reaches both, CEO reaches only the GET (view), any other role is rejected (403)
 * from both. */
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

    /** Optimistic concurrency (issue #422 follow-up): PUT now REQUIRES If-Match (428 without it -- see
     * missingIfMatchIsRejectedWith428 below), so the positive case must GET first to obtain the month's current
     * ETag and thread it through, exactly like the real frontend does. */
    @Test
    void hrCanSaveTheDraft() throws Exception {
        MockHttpSession hrSession = sessionFor("hr");
        String getResponse = mvc.perform(get("/api/payroll/input-draft?payrollMonth=2026-08").session(hrSession))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String etag = extractEtag(getResponse);

        mvc.perform(put("/api/payroll/input-draft")
                .session(hrSession)
                .header(HttpHeaders.IF_MATCH, etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMPTY_SAVE_BODY))
            .andExpect(status().isOk());
    }

    /** Optimistic concurrency (issue #422 follow-up): a missing If-Match is refused with 428, never silently
     * allowed -- that headerless write is exactly the hole this feature closes (see
     * PayrollService#saveInputDraft's javadoc). */
    @Test
    void missingIfMatchIsRejectedWith428() throws Exception {
        mvc.perform(put("/api/payroll/input-draft")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMPTY_SAVE_BODY))
            .andExpect(status().is(428));
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

    // Cheap, dependency-free extraction (this file has no Jackson ObjectMapper wired) --
    // {"payrollMonth":"...","drafts":[...],"etag":"<hex>"} -- same pattern as
    // SecurityAuthorizationIntegrationTest#extractAttachmentId.
    private String extractEtag(String json) {
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("\"etag\":\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("no etag field in response: " + json);
        }
        return matcher.group(1);
    }
}
