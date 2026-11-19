package th.co.glr.hr.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.Filter;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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

/** Verifies the default-deny authorization flip: unauthenticated API requests fail closed with 401, the exact
 * public allowlist stays reachable, and the @PreAuthorize role authorities still resolve on top of the real
 * SecurityFilterChain. */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
@SpringBootTest
class SecurityAuthorizationIntegrationTest {

    // Point the booted Spring context's datasource at the Postgres resolved by PostgresTestSupport (TEST_DB_URL
    // override, else the Testcontainers singleton), mirroring AbstractPostgresIntegrationTest.
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        // Tax-allowance evidence upload/download (2026-08-01) is exercised through the real FileStorageService bean
        // below -- point it at a JVM temp dir rather than the app default (./uploads relative to wherever this JVM
        // runs), so a test run never leaves real files behind in the working tree.
        registry.add("app.uploads-dir", () -> {
            try {
                return java.nio.file.Files.createTempDirectory("tax-allowance-evidence-security-test").toString();
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private final MockMvc mvc;
    private final NamedParameterJdbcTemplate jdbc;

    @Autowired
    SecurityAuthorizationIntegrationTest(WebApplicationContext context,
                                         @Qualifier("springSecurityFilterChain") Filter securityFilterChain,
                                         NamedParameterJdbcTemplate jdbc) {
        // Wire the real Spring Security filter chain over the full MVC context (no spring-security-test dep).
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
        this.jdbc = jdbc;
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
    void validHrSessionReaches200OnAProtectedEndpoint() throws Exception {
        mvc.perform(get("/api/employees").session(sessionFor("hr")))
            .andExpect(status().isOk());
    }

    @Test
    void wrongRoleSessionGets403OnAPreAuthorizeEndpoint() throws Exception {
        // Send the required `payrollMonth` param so request binding succeeds and the @PreAuthorize check is what
        // rejects the wrong role (a missing param would 400 before authz runs).
        mvc.perform(get("/api/payroll?payrollMonth=2026-07").session(sessionFor("employee")))
            .andExpect(status().isForbidden());
    }

    // Reconciliation additions (2026-07-21, C1/C2): the four new payroll endpoints. GET is broader (HR + CEO view)
    // than PUT (HR-only edit), mirroring the existing GET /api/payroll split.

    @Test
    void aPlainEmployeeCannotViewOrEditStoredTaxAllowancesOrYtdSeed() throws Exception {
        mvc.perform(get("/api/payroll/tax-allowances?year=2026").session(sessionFor("employee")))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/payroll/tax-allowances?year=2026")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(taxAllowanceBody(9001L)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/payroll/ytd-seed?year=2026").session(sessionFor("employee")))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/payroll/ytd-seed?year=2026")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ytdSeedBody(9001L)))
            .andExpect(status().isForbidden());

        assertThat(countTaxAllowanceRows(9001L)).isZero();
        assertThat(countYtdSeedRows(9001L)).isZero();
    }

    @Test
    void anHrSessionCanEditStoredTaxAllowancesAndYtdSeed() throws Exception {
        long employeeId = seedEmployeeForReconciliationAuthz("EMP-AUTHZ-1");

        mvc.perform(put("/api/payroll/tax-allowances?year=2026")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(taxAllowanceBody(employeeId)))
            .andExpect(status().isOk());
        mvc.perform(put("/api/payroll/ytd-seed?year=2026")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ytdSeedBody(employeeId)))
            .andExpect(status().isOk());

        assertThat(countTaxAllowanceRows(employeeId)).isEqualTo(1);
        assertThat(countYtdSeedRows(employeeId)).isEqualTo(1);
    }

    // the withholding-tax classification matrix's new HTTP surface
    // (PayrollController#getComponentTaxTreatments/putComponentTaxTreatments).

    // Statutory export files (KBank/PND1/SSO). These read PDPA-restricted PII (national id, SSN, tax id), so the
    // HR/CEO gate must hold on the real filter chain.

    // Tax-allowance DECLARATION workflow (PR A, 2026-08-01): TaxAllowanceDeclarationController. Everything else
    // (register, approve, reject, apply, on-behalf) is HR-only-to-mutate, CEO-view-only, same split as the C1/C2
    // endpoints above.

    // Tax-allowance evidence attachments (2026-08-01): TaxAllowanceDeclarationController's nested upload/list
    // endpoints and TaxAllowanceAttachmentController's flat download/delete endpoints.

    // CustomerController's three GETs called only sessions.requireUser — authenticated, not authorized — so any
    // role, including employee/warehouse/qc, could read the customer master and every contact's name/email/phone.

    // Forced password change (MustChangePasswordFilter). Until 2026-08-28 this gate lived ONLY in the SPA (App.jsx
    // returned <ChangePasswordModal forced/> instead of the routes), while the backend handed a must-change login
    // a fully privileged session.

    @Test
    void mustChangePasswordSessionKeepsItsEscapeHatches() throws Exception {
        // The gate must not brick the account: reading your own principal (how the SPA learns to render the modal),
        // changing the password, and logging out all stay reachable.
        mvc.perform(get("/api/auth/me").session(mustChangePasswordSessionFor("hr")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.mustChangePassword").value(true));

        mvc.perform(post("/api/auth/logout").session(mustChangePasswordSessionFor("hr")))
            .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"NewPassword123\"}")
                .session(mustChangePasswordSessionFor("hr")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void theAuthAllowlistIsNotAWildcardOverTheWholeAuthNamespace() throws Exception {
        // /api/auth/me is allowed by exact method+path. A sibling path under the same prefix is not — this fails if
        // anyone "simplifies" the allowlist to /api/auth/**.
        mvc.perform(get("/api/auth/me/../employees").session(mustChangePasswordSessionFor("hr")))
            .andExpect(status().is4xxClientError());
    }

    private long seedEmployeeForReconciliationAuthz(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, is_active) VALUES (:code, TRUE) RETURNING employee_id",
            Map.of("code", code), Long.class);
    }

    /** Same as {@link #sessionFor}, but pinned to a specific employeeId rather than the hardcoded 1. */

    /** Same idea, but for a specific role (hr/ceo/...) pinned to a specific employeeId — see {@link #sessionForEmployeeId}. */

    private int countTaxAllowanceRows(long employeeId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.employee_tax_allowance WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
        return count == null ? 0 : count;
    }

    private int countYtdSeedRows(long employeeId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.payroll_year_to_date_seed WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
        return count == null ? 0 : count;
    }

    private String taxAllowanceBody(long employeeId) {
        return """
            {"items":[{"employeeId":%d,"spouseAllowance":60000,"childAllowance":0,"parentCareAllowance":0,
            "disabledCareAllowance":0,"maternityAllowance":0,"lifeInsuranceAllowance":0,"healthInsuranceAllowance":0,
            "parentHealthInsuranceAllowance":0,"rmfAllowance":0,"ssfAllowance":0,"pensionInsuranceAllowance":0,
            "thaiEsgAllowance":0,"homeLoanInterestAllowance":0,"educationDonation":0,"generalDonation":0,
            "politicalDonation":0}]}
            """.formatted(employeeId);
    }

    private String ytdSeedBody(long employeeId) {
        return """
            {"items":[{"employeeId":%d,"taxableIncome":100000,"socialSecurity":5000,"withholdingTax":2000,
            "sourceNote":"authz test"}]}
            """.formatted(employeeId);
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 1L, true, LocalDate.now(), false, null, false));
        return session;
    }

    /** Same as #sessionFor, but with mustChangePassword = TRUE — i.e. exactly the principal
     * th.co.glr.hr.auth.AuthService#login builds for someone who has authenticated with a temporary password and
     * has not yet chosen their own. */
    private MockHttpSession mustChangePasswordSessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 1L, true, LocalDate.now(), true, null, false));
        return session;
    }
}
