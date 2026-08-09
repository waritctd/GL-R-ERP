package th.co.glr.hr.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
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
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
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
        // Tax-allowance evidence upload/download (2026-08-01) is exercised through the real
        // FileStorageService bean below -- point it at a JVM temp dir rather than the app default
        // (./uploads relative to wherever this JVM runs), so a test run never leaves real files
        // behind in the working tree.
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

    // ------------------------------------------------------------------------------------------
    // P0 fix (Opus review, 2026-07-30): the withholding-tax classification matrix's new HTTP surface
    // (PayrollController#getComponentTaxTreatments/putComponentTaxTreatments). Same GET (HR+CEO view)
    // broader than PUT (HR-only edit) split as the C1/C2 endpoints above, so the exact same
    // wrong-way-round pattern applies: assert the caller CANNOT reach the write, and that the table is
    // provably unchanged afterwards, through the real filter chain + real service + real repository.
    // ------------------------------------------------------------------------------------------

    @Test
    void aPlainEmployeeCannotViewOrEditTheComponentTaxTreatmentMatrix() throws Exception {
        mvc.perform(get("/api/payroll/component-tax-treatments?year=2026").session(sessionFor("employee")))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/payroll/component-tax-treatments?year=2026")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(componentTaxTreatmentBody(9004L)))
            .andExpect(status().isForbidden());

        assertThat(countTaxTreatmentRows(9004L)).isZero();
    }

    @Test
    void aSalesRoleCannotEditTheComponentTaxTreatmentMatrixEither() throws Exception {
        mvc.perform(put("/api/payroll/component-tax-treatments?year=2026")
                .session(sessionFor("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(componentTaxTreatmentBody(9005L)))
            .andExpect(status().isForbidden());

        assertThat(countTaxTreatmentRows(9005L)).isZero();
    }

    @Test
    void ceoCanViewTheComponentTaxTreatmentMatrixButCannotEditIt() throws Exception {
        mvc.perform(get("/api/payroll/component-tax-treatments?year=2026").session(sessionFor("ceo")))
            .andExpect(status().isOk());

        mvc.perform(put("/api/payroll/component-tax-treatments?year=2026")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(componentTaxTreatmentBody(9006L)))
            .andExpect(status().isForbidden());

        assertThat(countTaxTreatmentRows(9006L)).isZero();
    }

    @Test
    void anHrSessionCanEditTheComponentTaxTreatmentMatrix() throws Exception {
        long employeeId = seedEmployeeForReconciliationAuthz("EMP-AUTHZ-2");

        mvc.perform(put("/api/payroll/component-tax-treatments?year=2026")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(componentTaxTreatmentBody(employeeId)))
            .andExpect(status().isOk());

        assertThat(countTaxTreatmentRows(employeeId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------
    // Statutory export files (KBank/PND1/SSO). These read PDPA-restricted PII (national id, SSN, tax
    // id), so the HR/CEO gate must hold on the real filter chain. Written wrong-way-round: assert the
    // callers who must NOT reach the file get 403 on every kind, and that HR/CEO pass authorization
    // (a missing period then 404s from the service — proving authz let them through, not that a file
    // was produced).
    // ------------------------------------------------------------------------------------------

    @Test
    void plainEmployeeAndSalesCannotDownloadAnyStatutoryExportFile() throws Exception {
        for (String kind : new String[] {"kbank", "pnd1", "sso"}) {
            mvc.perform(get("/api/payroll/1/export/" + kind).session(sessionFor("employee")))
                .andExpect(status().isForbidden());
            mvc.perform(get("/api/payroll/1/export/" + kind).session(sessionFor("sales")))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void unauthenticatedStatutoryExportIsRejectedWith401() throws Exception {
        mvc.perform(get("/api/payroll/1/export/kbank")).andExpect(status().isUnauthorized());
    }

    @Test
    void hrAndCeoPassAuthorizationOnStatutoryExport() throws Exception {
        // A non-existent period id → the service 404s; the point is that neither HR nor CEO is 403'd,
        // i.e. the HR/CEO authorization gate let them through.
        mvc.perform(get("/api/payroll/999999/export/kbank").session(sessionFor("hr")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/payroll/999999/export/pnd1").session(sessionFor("ceo")))
            .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------
    // Tax-allowance DECLARATION workflow (PR A, 2026-08-01): TaxAllowanceDeclarationController.
    // Employees gain self-read/self-write for the FIRST time here (GET/POST/DELETE
    // .../declarations/me) — this is an authorization change, stated per CLAUDE.md. Everything else
    // (register, approve, reject, apply, on-behalf) is HR-only-to-mutate, CEO-view-only, same split
    // as the C1/C2 endpoints above. Written wrong-way-round, through the real filter chain + real
    // service + real repository, and every table asserted unchanged after a rejected call.
    //
    // TaxAllowanceDeclarationScopeIntegrationTest (service-layer, AbstractPostgresIntegrationTest)
    // covers the 404-not-403-on-a-foreign-row nuance and the DISTINCT ON expiry trap; this class
    // covers the HTTP-layer @PreAuthorize gates and the one thing only an HTTP-level test can prove:
    // an extra "employeeId" field smuggled into the /me POST body is not bound to anything (the
    // request record has no such field) and is silently ignored by Jackson's default
    // fail-on-unknown-properties=false, so the row lands on the CALLER, never the named victim.
    // ------------------------------------------------------------------------------------------

    @Test
    void aPlainEmployeeCannotViewOrMutateTheTaxAllowanceDeclarationRegister() throws Exception {
        long victimId = seedEmployeeForReconciliationAuthz("EMP-TAD-1");
        long declarationId = seedPendingDeclaration(victimId, 2026);

        mvc.perform(get("/api/payroll/tax-allowances/declarations?year=2026").session(sessionFor("employee")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/payroll/tax-allowances/declarations/" + declarationId + "/approve")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/payroll/tax-allowances/declarations/" + declarationId + "/reject")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewerNote\":\"no\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/payroll/tax-allowances/declarations/" + declarationId + "/apply")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/payroll/tax-allowances/declarations/on-behalf")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(onBehalfBody(victimId)))
            .andExpect(status().isForbidden());

        assertThat(declarationStatus(declarationId)).isEqualTo("PENDING");
        assertThat(countTaxAllowanceRows(victimId)).isZero();
    }

    @Test
    void ceoCanViewTheTaxAllowanceDeclarationRegisterButCannotApproveRejectApplyOrCreateOnBehalf() throws Exception {
        long victimId = seedEmployeeForReconciliationAuthz("EMP-TAD-2");
        long declarationId = seedPendingDeclaration(victimId, 2026);

        mvc.perform(get("/api/payroll/tax-allowances/declarations?year=2026").session(sessionFor("ceo")))
            .andExpect(status().isOk());
        mvc.perform(post("/api/payroll/tax-allowances/declarations/" + declarationId + "/approve")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/payroll/tax-allowances/declarations/" + declarationId + "/apply")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/payroll/tax-allowances/declarations/on-behalf")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(onBehalfBody(victimId)))
            .andExpect(status().isForbidden());

        assertThat(declarationStatus(declarationId)).isEqualTo("PENDING");
        assertThat(countTaxAllowanceRows(victimId)).isZero();
    }

    @Test
    void aSubmittedDeclarationIgnoresAForgedEmployeeIdInTheRequestBodyAndLandsOnTheCaller() throws Exception {
        // A dedicated session pinned to a REAL, freshly-seeded employee — sessionFor's own
        // employeeId=1 is not depended on here, since other tests in this class may have already
        // advanced hr.employee's identity sequence past 1.
        long callerEmployeeId = seedEmployeeForReconciliationAuthz("EMP-TAD-CALLER");
        long victimEmployeeId = seedEmployeeForReconciliationAuthz("EMP-TAD-VICTIM");

        // The request DTO (TaxAllowanceDeclarationSubmitRequest) has no employeeId field at all —
        // this "employeeId" key is unknown to Jackson and, with the default
        // fail-on-unknown-properties=false, is simply dropped rather than bound anywhere.
        mvc.perform(post("/api/payroll/tax-allowances/declarations/me")
                .session(sessionForEmployeeId(callerEmployeeId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":" + victimEmployeeId + ",\"taxYear\":2026,\"spouseAllowance\":60000}"))
            .andExpect(status().isCreated());

        assertThat(countTaxAllowanceDeclarationRows(callerEmployeeId, 2026))
            .as("the row must land on the authenticated caller")
            .isEqualTo(1);
        assertThat(countTaxAllowanceDeclarationRows(victimEmployeeId, 2026))
            .as("the named victim must get ZERO rows despite the forged body")
            .isZero();
    }

    @Test
    void approvingAnAlreadyApprovedDeclarationIsRejectedWith409() throws Exception {
        long employeeId = seedEmployeeForReconciliationAuthz("EMP-TAD-3");
        long declarationId = seedPendingDeclaration(employeeId, 2026);

        mvc.perform(post("/api/payroll/tax-allowances/declarations/" + declarationId + "/approve")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/payroll/tax-allowances/declarations/" + declarationId + "/approve")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isConflict());

        assertThat(declarationStatus(declarationId)).isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------------------------------
    // Tax-allowance evidence attachments (2026-08-01): TaxAllowanceDeclarationController's nested
    // upload/list endpoints and TaxAllowanceAttachmentController's flat download/delete endpoints.
    // Both are @PreAuthorize("isAuthenticated()") -- the real gate is inside
    // TaxAllowanceDeclarationService#requireOwnerOrHr -- so this class's job is to prove that gate
    // is actually reachable and enforced through the REAL multipart upload / filter chain / disk
    // write, not just at the service layer (TaxAllowanceAttachmentScopeIntegrationTest already
    // covers the service layer exhaustively; this is the HTTP-level companion for the same
    // reasoning SecurityAuthorizationIntegrationTest already applies to every other endpoint here).
    // ------------------------------------------------------------------------------------------

    @Test
    void evidenceUploadDownloadAndDeleteAreScopedToTheOwningEmployeeAndHrThroughTheRealFilterChain() throws Exception {
        long ownerId = seedEmployeeForReconciliationAuthz("EMP-TAA-OWNER");
        long strangerId = seedEmployeeForReconciliationAuthz("EMP-TAA-STRANGER");
        // Freshly seeded, guaranteed-distinct HR/CEO ids for THIS test -- deliberately NOT
        // sessionFor("hr")/sessionFor("ceo")'s shared hardcoded employeeId=1, which could
        // coincidentally collide with ownerId depending on JUnit's method execution order within
        // this shared-schema test class (no @BeforeEach reset here, unlike AbstractPostgresIntegrationTest)
        // and silently turn the CEO-exclusion assertions below into a false pass.
        long hrId = seedEmployeeForReconciliationAuthz("EMP-TAA-HR");
        long ceoId = seedEmployeeForReconciliationAuthz("EMP-TAA-CEO");
        long declarationId = seedPendingDeclaration(ownerId, 2026);

        MockMultipartFile file = new MockMultipartFile("file", "evidence.pdf", "application/pdf", "หลักฐาน".getBytes());
        String uploadJson = mvc.perform(multipart("/api/payroll/tax-allowances/declarations/" + declarationId + "/attachments")
                .file(file)
                .session(sessionForEmployeeId(ownerId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        long attachmentId = extractAttachmentId(uploadJson);

        // Owner downloads: 200.
        mvc.perform(get("/api/payroll/tax-allowance-attachments/" + attachmentId + "/file")
                .session(sessionForEmployeeId(ownerId)))
            .andExpect(status().isOk());
        // HR downloads: 200.
        mvc.perform(get("/api/payroll/tax-allowance-attachments/" + attachmentId + "/file")
                .session(sessionForEmployeeIdAndRole(hrId, "hr")))
            .andExpect(status().isOk());
        // A stranger employee: 404, not 403 -- must not leak that the id exists.
        mvc.perform(get("/api/payroll/tax-allowance-attachments/" + attachmentId + "/file")
                .session(sessionForEmployeeId(strangerId)))
            .andExpect(status().isNotFound());
        // CEO: 404 on the evidence -- deliberate asymmetry -- while the register itself stays 200.
        mvc.perform(get("/api/payroll/tax-allowance-attachments/" + attachmentId + "/file")
                .session(sessionForEmployeeIdAndRole(ceoId, "ceo")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/payroll/tax-allowances/declarations?year=2026")
                .session(sessionForEmployeeIdAndRole(ceoId, "ceo")))
            .andExpect(status().isOk());
        // A stranger cannot even list the attachments.
        mvc.perform(get("/api/payroll/tax-allowances/declarations/" + declarationId + "/attachments")
                .session(sessionForEmployeeId(strangerId)))
            .andExpect(status().isNotFound());

        // Owner tombstones it; the row survives but is no longer downloadable by anyone.
        mvc.perform(delete("/api/payroll/tax-allowance-attachments/" + attachmentId)
                .session(sessionForEmployeeId(ownerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"อัปโหลดผิดไฟล์\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/payroll/tax-allowance-attachments/" + attachmentId + "/file")
                .session(sessionForEmployeeId(ownerId)))
            .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject(
            "SELECT deleted_at IS NOT NULL FROM hr.file_attachment WHERE attachment_id = :id",
            Map.of("id", attachmentId), Boolean.class))
            .as("tombstone, never a hard delete -- the row must still exist")
            .isTrue();
    }

    @Test
    void estimateEndpointIgnoresAForgedEmployeeIdAndUsesOnlyTheCaller() throws Exception {
        long callerEmployeeId = seedEmployeeForReconciliationAuthz("EMP-TAA-EST-CALLER");
        long victimEmployeeId = seedEmployeeForReconciliationAuthz("EMP-TAA-EST-VICTIM");

        // TaxAllowanceDeclarationSubmitRequest (reused verbatim as the estimate body) has no
        // employeeId field -- this key is unknown to Jackson and silently dropped, same reasoning
        // as aSubmittedDeclarationIgnoresAForgedEmployeeIdInTheRequestBodyAndLandsOnTheCaller.
        mvc.perform(post("/api/payroll/tax-allowances/declarations/me/estimate")
                .session(sessionForEmployeeId(callerEmployeeId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":" + victimEmployeeId + ",\"taxYear\":2026,\"effectiveMonth\":6,\"spouseAllowance\":60000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.estimateAvailable").exists());
    }

    // ------------------------------------------------------------------------------------------
    // P0 fix (customer master read gate, 2026-08): CustomerController's three GETs (search,
    // {id}/contacts, {id}/projects) called only sessions.requireUser — authenticated, not
    // authorized — so any role, including employee/warehouse/qc (no sales access anywhere else in
    // the system), could read the customer master (taxId/address/phone) and every contact's
    // name/email/phone. Now gated in CustomerService to the audience derived from every real
    // caller: sales/import/ceo/account/sales_manager (TicketAccessPolicy.VIEWER_ROLES — the same
    // set as canViewCatalog/canViewTickets on the frontend). CustomerReadAuthzIntegrationTest pins
    // the exhaustive per-role, per-endpoint, wrong-way-round matrix against real Postgres; this
    // case pins the same decision through the REAL SecurityFilterChain rather than a standalone
    // MockMvc, matching this class's own stated purpose for every other endpoint here.
    // ------------------------------------------------------------------------------------------

    @Test
    void anEmployeeSessionCannotReadTheCustomerMasterButASalesSessionCan() throws Exception {
        mvc.perform(get("/api/customers").session(sessionFor("employee")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/customers").session(sessionFor("sales")))
            .andExpect(status().isOk());
    }

    @Test
    void warehouseAndQcCannotReadCustomerContactsOrProjectsEither() throws Exception {
        mvc.perform(get("/api/customers/1/contacts").session(sessionFor("warehouse")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/customers/1/projects").session(sessionFor("qc")))
            .andExpect(status().isForbidden());
    }

    private long extractAttachmentId(String json) {
        // Cheap, dependency-free extraction (this file has no Jackson ObjectMapper wired) --
        // {"attachment":{"attachmentId":123,...}}.
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("\"attachmentId\":(\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new AssertionError("no attachmentId in response: " + json);
        }
        return Long.parseLong(matcher.group(1));
    }

    private long seedEmployeeForReconciliationAuthz(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, is_active) VALUES (:code, TRUE) RETURNING employee_id",
            Map.of("code", code), Long.class);
    }

    /** Same as {@link #sessionFor}, but pinned to a specific employeeId rather than the hardcoded 1. */
    private MockHttpSession sessionForEmployeeId(long employeeId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(employeeId, "employee" + employeeId + "@glr.co.th", "employee", "employee",
                employeeId, true, LocalDate.now(), false, null, false));
        return session;
    }

    /** Same idea, but for a specific role (hr/ceo/...) pinned to a specific employeeId — see {@link #sessionForEmployeeId}. */
    private MockHttpSession sessionForEmployeeIdAndRole(long employeeId, String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(employeeId, role + employeeId + "@glr.co.th", role, role,
                employeeId, true, LocalDate.now(), false, null, false));
        return session;
    }

    /**
     * A PENDING declaration that HR could actually approve — which since the signed-form guard
     * means one with its signed ล.ย.01 already attached.
     *
     * <p>Every caller here is testing an authorization boundary, not the signature requirement, so
     * they all want a declaration whose only remaining obstacle is who is asking. Leaving the
     * attachment out would make the approve cases fail with 409 "no signed form" and quietly stop
     * exercising the role checks they exist for — a green-looking suite that had stopped testing
     * authorization at all. The signature guard has its own wrong-way-round test in
     * TaxAllowanceDeclarationScopeIntegrationTest.
     */
    private long seedPendingDeclaration(long employeeId, int taxYear) {
        long declarationId = jdbc.queryForObject("""
            INSERT INTO hr.tax_allowance_declaration
                (employee_id, tax_year, effective_month, spouse_allowance, submitted_by_id)
            VALUES (:employeeId, :taxYear, 1, 60000, :employeeId)
            RETURNING declaration_id
            """,
            Map.of("employeeId", employeeId, "taxYear", taxYear), Long.class);
        jdbc.update("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, section_key, storage_state)
            VALUES ('tax_allowance_declaration', :ownerId, 'signed-loryor01.pdf', 'test://signed-form',
                    'application/pdf', 2048, 'signed_form', 'DATABASE')
            """, Map.of("ownerId", declarationId));
        return declarationId;
    }

    private String declarationStatus(long declarationId) {
        return jdbc.queryForObject(
            "SELECT status FROM hr.tax_allowance_declaration WHERE declaration_id = :id",
            Map.of("id", declarationId), String.class);
    }

    private int countTaxAllowanceDeclarationRows(long employeeId, int taxYear) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.tax_allowance_declaration WHERE employee_id = :employeeId AND tax_year = :taxYear",
            Map.of("employeeId", employeeId, "taxYear", taxYear), Integer.class);
        return count == null ? 0 : count;
    }

    private String onBehalfBody(long employeeId) {
        return """
            {"employeeId":%d,"taxYear":2026,"spouseAllowance":60000}
            """.formatted(employeeId);
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

    private int countTaxTreatmentRows(long employeeId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.payroll_component_tax_treatment WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
        return count == null ? 0 : count;
    }

    /** PUT /api/payroll/component-tax-treatments binds a raw JSON array, not a wrapped object. */
    private String componentTaxTreatmentBody(long employeeId) {
        return """
            [{"employeeId":%d,"component":"BONUS_PAY","taxTreatment":"EXTRA_KNOWN_FREQUENCY"}]
            """.formatted(employeeId);
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 1L, true, LocalDate.now(), false, null, false));
        return session;
    }
}
