package th.co.glr.hr.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.Filter;
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

    // ------------------------------------------------------------------------------------------
    // Reconciliation additions (2026-07-21, C1/C2): the four new payroll endpoints. GET is broader
    // (HR + CEO view) than PUT (HR-only edit), mirroring the existing GET /api/payroll split. These
    // are written wrong-way-round: assert the caller CANNOT reach the edit, and that the table is
    // provably unchanged afterwards, through the real filter chain + real service + real repository.
    // ------------------------------------------------------------------------------------------

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
    void aSalesRoleCannotEditStoredTaxAllowancesOrYtdSeedEither() throws Exception {
        mvc.perform(put("/api/payroll/tax-allowances?year=2026")
                .session(sessionFor("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(taxAllowanceBody(9002L)))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/payroll/ytd-seed?year=2026")
                .session(sessionFor("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ytdSeedBody(9002L)))
            .andExpect(status().isForbidden());

        assertThat(countTaxAllowanceRows(9002L)).isZero();
        assertThat(countYtdSeedRows(9002L)).isZero();
    }

    @Test
    void ceoCanViewStoredTaxAllowancesAndYtdSeedButCannotEditEither() throws Exception {
        // View: allowed (200), same as the existing GET /api/payroll CEO allowance.
        mvc.perform(get("/api/payroll/tax-allowances?year=2026").session(sessionFor("ceo")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/payroll/ytd-seed?year=2026").session(sessionFor("ceo")))
            .andExpect(status().isOk());

        // Edit: rejected. CEO has VIEW, not EDIT, on these HR-owned standing declarations.
        mvc.perform(put("/api/payroll/tax-allowances?year=2026")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(taxAllowanceBody(9003L)))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/payroll/ytd-seed?year=2026")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ytdSeedBody(9003L)))
            .andExpect(status().isForbidden());

        assertThat(countTaxAllowanceRows(9003L)).isZero();
        assertThat(countYtdSeedRows(9003L)).isZero();
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

    private long seedEmployeeForReconciliationAuthz(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, is_active) VALUES (:code, TRUE) RETURNING employee_id",
            Map.of("code", code), Long.class);
    }

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
}
