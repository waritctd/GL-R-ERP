package th.co.glr.hr.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

import java.time.LocalDate;

/**
 * Real-DB authz evidence for the designer-directory REVERSAL (owner ask relayed 2026-09-26, task
 * "designer-add-from-ui" — see {@link DesignerController}'s own class Javadoc and
 * {@code sales.designer}'s own V173 header): {@code sales.designer} used to be enforced read-only
 * by construction (no write mapping at all); {@code POST /api/designers} is now a new write
 * endpoint gated by {@link th.co.glr.hr.auth.DealEntryAccess#requireCanEnterDeal} -- the SAME gate
 * {@code CustomerController#create} uses, no wider.
 *
 * <p>Runs the REAL {@link DesignerController} over the REAL {@link DesignerRepository} on REAL
 * Postgres — CLAUDE.md's "Permission changes must ship evidence": a mocked repository (see
 * {@code DesignerControllerTest}) cannot prove the grant survives into the actual SQL/service call.
 * Every denial case is written wrong-way-round: it asks whether a caller who should NOT be able to
 * create a designer row can, asserts 403, AND re-reads the table to prove NO row was inserted — a
 * 403 that had already written would still be a real bug.
 */
class DesignerCreateAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private MockMvc designerMvc;
    private EmployeeAuthRepository employeeAuth;

    private long qcUserId;
    private long salesManagerId;
    private long importUserId;
    private long accountUserId;
    private long employeeUserId;

    @BeforeEach
    void wireRealCollaboratorsAndSeed() {
        DesignerRepository designers = new DesignerRepository(jdbc);
        employeeAuth = new EmployeeAuthRepository(jdbc);

        designerMvc = MockMvcBuilders
            .standaloneSetup(new DesignerController(designers, new SessionContext(), employeeAuth))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        qcUserId = createEmployee(employees, "QC ทดสอบ ผู้ออกแบบ", "qc-designer@glr.co.th", "QC", "ฝ่าย QC");
        salesManagerId = createEmployee(employees, "ผจก.ขาย ทดสอบ ผู้ออกแบบ", "sm-designer@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "นำเข้า ทดสอบ ผู้ออกแบบ", "import-designer@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        accountUserId = createEmployee(employees, "บัญชี ทดสอบ ผู้ออกแบบ", "account-designer@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        employeeUserId = createEmployee(employees, "พนักงาน ทดสอบ ผู้ออกแบบ", "employee-designer@glr.co.th", "OTHER", "ฝ่ายอื่น");
    }

    // ── wrong-way-round: ineligible roles must NOT be able to create, and must NOT insert a row ──

    @Test
    void importAccountEmployeeAndUngrantedQc_cannotCreateADesigner_andNoRowIsInserted() throws Exception {
        for (long actorId : List.of(importUserId, accountUserId, employeeUserId, qcUserId)) {
            String role = actorId == importUserId ? "import"
                : actorId == accountUserId ? "account"
                : actorId == employeeUserId ? "employee" : "qc";
            String code = "WRNG-" + role;
            designerMvc.perform(post("/api/designers").session(session(actorId, role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + code + "\",\"name\":\"Should Not Be Created (" + role + ")\"}"))
                .andExpect(status().isForbidden());
            assertThat(designerRowExists(code)).as("%s must not have inserted a designer row", role).isFalse();
        }
    }

    // ── sales / sales_manager / a granted qc CAN create, and the row is actually in the table ────

    @Test
    void salesCanCreateADesigner_andTheRowIsActuallyInTheTable() throws Exception {
        designerMvc.perform(post("/api/designers").session(session(employeeUserId, "sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"IT-SALES-1\",\"name\":\"Integration Test Sales Designer\"}"))
            .andExpect(status().is2xxSuccessful());
        assertThat(designerRowExists("IT-SALES-1")).isTrue();
        assertThat(designerNameOf("IT-SALES-1")).isEqualTo("Integration Test Sales Designer");
        assertThat(designerActiveOf("IT-SALES-1")).isTrue();
        assertThat(designerSourceSheetOf("IT-SALES-1")).isEqualTo("UI");
    }

    @Test
    void salesManagerCanCreateADesigner() throws Exception {
        designerMvc.perform(post("/api/designers").session(session(salesManagerId, "sales_manager"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"IT-SM-1\",\"name\":\"Integration Test SM Designer\"}"))
            .andExpect(status().is2xxSuccessful());
        assertThat(designerRowExists("IT-SM-1")).isTrue();
    }

    @Test
    void aGrantedQcCanCreateADesigner_butAnUngrantedOneCannot() throws Exception {
        designerMvc.perform(post("/api/designers").session(session(qcUserId, "qc"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"IT-QC-UNGRANTED\",\"name\":\"Should Not Be Created\"}"))
            .andExpect(status().isForbidden());
        assertThat(designerRowExists("IT-QC-UNGRANTED")).isFalse();

        grantQuotationCapability(qcUserId);
        designerMvc.perform(post("/api/designers").session(session(qcUserId, "qc"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"IT-QC-GRANTED\",\"name\":\"Integration Test Granted QC Designer\"}"))
            .andExpect(status().is2xxSuccessful());
        assertThat(designerRowExists("IT-QC-GRANTED")).isTrue();
    }

    // ── duplicate code ───────────────────────────────────────────────────────────────────────

    @Test
    void duplicateCodeIs409_andTheOriginalRowIsUnchanged() throws Exception {
        designerMvc.perform(post("/api/designers").session(session(employeeUserId, "sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"IT-DUP-1\",\"name\":\"Original Name\"}"))
            .andExpect(status().is2xxSuccessful());

        designerMvc.perform(post("/api/designers").session(session(employeeUserId, "sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"IT-DUP-1\",\"name\":\"Attempted Overwrite\"}"))
            .andExpect(status().isConflict());

        assertThat(designerNameOf("IT-DUP-1")).as("the original row must survive a duplicate-code attempt")
            .isEqualTo("Original Name");
    }

    // ── validation ───────────────────────────────────────────────────────────────────────────

    @Test
    void blankCodeOrNameIsRefused() throws Exception {
        designerMvc.perform(post("/api/designers").session(session(employeeUserId, "sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"\",\"name\":\"Some Name\"}"))
            .andExpect(status().isBadRequest());
        designerMvc.perform(post("/api/designers").session(session(employeeUserId, "sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"IT-BLANK-NAME\",\"name\":\"\"}"))
            .andExpect(status().isBadRequest());
        assertThat(designerRowExists("IT-BLANK-NAME")).isFalse();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private void grantQuotationCapability(long employeeId) {
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id",
            Map.of("id", employeeId));
    }

    private boolean designerRowExists(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sales.designer WHERE code = :code",
            Map.of("code", code), Integer.class);
        return count != null && count > 0;
    }

    private String designerNameOf(String code) {
        return jdbc.queryForObject("SELECT name FROM sales.designer WHERE code = :code",
            Map.of("code", code), String.class);
    }

    private boolean designerActiveOf(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM sales.designer WHERE code = :code",
            Map.of("code", code), Boolean.class));
    }

    private String designerSourceSheetOf(String code) {
        return jdbc.queryForObject("SELECT source_sheet FROM sales.designer WHERE code = :code",
            Map.of("code", code), String.class);
    }

    private MockHttpSession session(long employeeId, String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
                true, LocalDate.now(), false, null, false));
        return session;
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }
}
