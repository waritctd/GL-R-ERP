package th.co.glr.hr.payroll;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Verifies the two new payroll endpoints added 2026-07-30 (detail-export preview, bulk payslip
 * ZIP) against the real {@code SecurityFilterChain} and real Postgres: HR and CEO reach both
 * (200), any other role is rejected (403), unauthenticated is rejected (401). Written wrong-way
 * round -- the assertions that matter are the roles that must NOT reach it -- mirroring {@link
 * PayrollSuggestedInputsAuthorizationIntegrationTest} exactly, per CLAUDE.md's "permission changes
 * must ship evidence" rule: both endpoints carry their own {@code @PreAuthorize}, and that rule
 * applies even when the annotation merely repeats an existing role set.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test")
@SpringBootTest
class PayrollDetailPreviewAndBulkPayslipAuthorizationIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private final MockMvc mvc;
    private final NamedParameterJdbcTemplate jdbc;

    @Autowired
    PayrollDetailPreviewAndBulkPayslipAuthorizationIntegrationTest(
        WebApplicationContext context,
        @Qualifier("springSecurityFilterChain") Filter securityFilterChain,
        NamedParameterJdbcTemplate jdbc
    ) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
        this.jdbc = jdbc;
    }

    private static final String PREVIEW_BODY = """
        {"payrollMonth":"2026-07-01","inputs":[]}
        """;

    // ---- POST /api/payroll/preview/export/payroll-detail ----

    @Test
    void hrCanReachDetailPreviewExport() throws Exception {
        mvc.perform(post("/api/payroll/preview/export/payroll-detail")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PREVIEW_BODY))
            .andExpect(status().isOk());
    }

    @Test
    void ceoCanReachDetailPreviewExport() throws Exception {
        mvc.perform(post("/api/payroll/preview/export/payroll-detail")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PREVIEW_BODY))
            .andExpect(status().isOk());
    }

    @Test
    void aPlainEmployeeCannotReachDetailPreviewExport() throws Exception {
        mvc.perform(post("/api/payroll/preview/export/payroll-detail")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PREVIEW_BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    void aSalesRoleCannotReachDetailPreviewExportEither() throws Exception {
        mvc.perform(post("/api/payroll/preview/export/payroll-detail")
                .session(sessionFor("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PREVIEW_BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    void anUnauthenticatedRequestToDetailPreviewExportIsRejectedWith401() throws Exception {
        mvc.perform(post("/api/payroll/preview/export/payroll-detail")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PREVIEW_BODY))
            .andExpect(status().isUnauthorized());
    }

    // ---- GET /api/payroll/{periodId}/payslips.zip ----

    @Test
    void hrCanReachBulkPayslipZip() throws Exception {
        long periodId = insertProcessedPeriod(LocalDate.of(2026, 3, 1));
        mvc.perform(get("/api/payroll/" + periodId + "/payslips.zip").session(sessionFor("hr")))
            .andExpect(status().isOk());
    }

    @Test
    void ceoCanReachBulkPayslipZip() throws Exception {
        // A distinct month from the HR case above -- hr.payroll_period has a UNIQUE(payroll_month)
        // index, and both tests share the same real Postgres schema/context.
        long periodId = insertProcessedPeriod(LocalDate.of(2026, 4, 1));
        mvc.perform(get("/api/payroll/" + periodId + "/payslips.zip").session(sessionFor("ceo")))
            .andExpect(status().isOk());
    }

    @Test
    void aPlainEmployeeCannotReachBulkPayslipZip() throws Exception {
        // The security gate must reject the role BEFORE the service/DB is ever touched -- a
        // nonexistent periodId still exercises exactly that, and proves the 403 isn't merely
        // coincidental with a 404 the service would otherwise throw.
        mvc.perform(get("/api/payroll/999999/payslips.zip").session(sessionFor("employee")))
            .andExpect(status().isForbidden());
    }

    @Test
    void aSalesRoleCannotReachBulkPayslipZipEither() throws Exception {
        mvc.perform(get("/api/payroll/999999/payslips.zip").session(sessionFor("sales")))
            .andExpect(status().isForbidden());
    }

    @Test
    void anUnauthenticatedRequestToBulkPayslipZipIsRejectedWith401() throws Exception {
        mvc.perform(get("/api/payroll/999999/payslips.zip"))
            .andExpect(status().isUnauthorized());
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 1L, true, LocalDate.now(), false, null, false));
        return session;
    }

    /** A bare PROCESSED period with no lines -- enough to prove HR/CEO reach the endpoint; the
     * per-line rendering/byte-identity behaviour is covered by PayrollServiceTest's unit tests. */
    private long insertProcessedPeriod(LocalDate month) {
        return jdbc.queryForObject("""
            INSERT INTO hr.payroll_period (payroll_month, period_start, period_end, pay_date, status)
            VALUES (:month, :start, :end, :payDate, 'PROCESSED')
            RETURNING period_id
            """,
            Map.of(
                "month", month,
                "start", month,
                "end", month.withDayOfMonth(month.lengthOfMonth()),
                "payDate", month.withDayOfMonth(26)),
            Long.class);
    }
}
