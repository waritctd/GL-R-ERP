package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerController;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.CustomerService;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real-DB authz evidence for the deal-entry widening (owner ruling 2026-09-10 — see
 * {@code docs/sales/quotation-v2-plan.md}'s inline-deal-creation addendum and {@link
 * DealEntryAccess}'s own Javadoc): {@code TicketService.create}, {@code
 * CustomerController.create/createContact/createProject}, and {@code CustomerService}'s three
 * reads widened from sales-only to sales / sales_manager / a live {@code canCreateQuotation}
 * grant.
 *
 * <p>Runs the REAL {@code TicketService} and REAL {@code CustomerController} over REAL
 * repositories on REAL Postgres — CLAUDE.md's "Permission changes must ship evidence": a mocked
 * repository (see {@code CustomerControllerTest}) cannot prove the grant survives into the actual
 * SQL/service call. Every denial case is written wrong-way-round: it asks whether a caller who
 * should NOT reach the action can reach it.
 */
class DealEntryAccessIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketService ticketService;
    private MockMvc customerMvc;
    private EmployeeAuthRepository employeeAuth;

    private long projectOwnerCustomerId;
    private long projectId;

    private long qcUserId;
    private long salesManagerId;
    private long importUserId;
    private long accountUserId;
    private long employeeUserId;

    @BeforeEach
    void wireRealCollaboratorsAndSeed() {
        TicketRepository tickets = new TicketRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ContactRepository contacts = new ContactRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        employeeAuth = new EmployeeAuthRepository(jdbc);
        CustomerService customerService = new CustomerService(customers, contacts, projects, employeeAuth);

        // pricingRequests (dead-deal cascade only) is never reached by create() -- null is safe,
        // exactly as DealQuotationIntegrationTest's own wiring already does.
        ticketService = new TicketService(tickets, notifications, new ObjectMapper(), customers,
            new QuotationRenderer(), (PricingRequestService) null, employeeAuth);

        JsonMapper jsonMapper = JsonMapper.builder()
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .build();
        customerMvc = MockMvcBuilders
            .standaloneSetup(new CustomerController(customers, contacts, projects, customerService,
                new SessionContext(), employeeAuth))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        qcUserId = createEmployee(employees, "QC เดี๋ยว", "qc-dea@glr.co.th", "QC", "ฝ่าย QC");
        salesManagerId = createEmployee(employees, "ผจก.ขาย เดี๋ยว", "sm-dea@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "นำเข้า เดี๋ยว", "import-dea@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        accountUserId = createEmployee(employees, "บัญชี เดี๋ยว", "account-dea@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        employeeUserId = createEmployee(employees, "พนักงาน เดี๋ยว", "employee-dea@glr.co.th", "OTHER", "ฝ่ายอื่น");

        // A project to attach ticket-create calls to (V50 requires one) -- created directly via
        // repository, not through the gate under test.
        var customer = customers.create("บริษัท DealEntryAccess ทดสอบ", null, null, "สำนักงานใหญ่", null);
        projectOwnerCustomerId = customer.id();
        ProjectDto project = projects.create(projectOwnerCustomerId, "โครงการทดสอบสิทธิ์ deal-entry");
        projectId = project.id();
    }

    // ── ticket create ────────────────────────────────────────────────────────────────────

    @Test
    void qcWithoutGrant_cannotCreateTicket() {
        assertForbidden(() -> ticketService.create(ticketRequest(), actor(qcUserId, "qc")));
    }

    @Test
    void qcWithGrant_canCreateTicket() {
        grantQuotationCapability(qcUserId);
        var dto = ticketService.create(ticketRequest(), actor(qcUserId, "qc"));
        assertThat(dto.summary().id()).isPositive();
    }

    @Test
    void salesManager_canCreateTicket() {
        var dto = ticketService.create(ticketRequest(), actor(salesManagerId, "sales_manager"));
        assertThat(dto.summary().id()).isPositive();
    }

    @Test
    void importAccountAndEmployeeRoles_cannotCreateTicket() {
        assertForbidden(() -> ticketService.create(ticketRequest(), actor(importUserId, "import")));
        assertForbidden(() -> ticketService.create(ticketRequest(), actor(accountUserId, "account")));
        assertForbidden(() -> ticketService.create(ticketRequest(), actor(employeeUserId, "employee")));
    }

    // ── customer / project create + customer search (HTTP layer, real Postgres) ────────────

    @Test
    void qcWithoutGrant_cannotCreateCustomerOrProjectOrSearch() throws Exception {
        customerMvc.perform(post("/api/customers").session(session(qcUserId, "qc"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ACME QC\"}"))
            .andExpect(status().isForbidden());
        customerMvc.perform(post("/api/customers/{id}/projects", projectOwnerCustomerId).session(session(qcUserId, "qc"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"โครงการ QC\"}"))
            .andExpect(status().isForbidden());
        customerMvc.perform(get("/api/customers").session(session(qcUserId, "qc")))
            .andExpect(status().isForbidden());
    }

    @Test
    void qcWithGrant_canCreateCustomerAndProjectAndSearch() throws Exception {
        grantQuotationCapability(qcUserId);
        customerMvc.perform(post("/api/customers").session(session(qcUserId, "qc"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ACME QC Granted\"}"))
            .andExpect(status().is2xxSuccessful());
        customerMvc.perform(post("/api/customers/{id}/projects", projectOwnerCustomerId).session(session(qcUserId, "qc"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"โครงการ QC Granted\"}"))
            .andExpect(status().is2xxSuccessful());
        customerMvc.perform(get("/api/customers").session(session(qcUserId, "qc")))
            .andExpect(status().isOk());
    }

    @Test
    void salesManager_canCreateCustomerAndProjectAndSearch() throws Exception {
        customerMvc.perform(post("/api/customers").session(session(salesManagerId, "sales_manager"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ACME SM\"}"))
            .andExpect(status().is2xxSuccessful());
        customerMvc.perform(post("/api/customers/{id}/projects", projectOwnerCustomerId).session(session(salesManagerId, "sales_manager"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"โครงการ SM\"}"))
            .andExpect(status().is2xxSuccessful());
        customerMvc.perform(get("/api/customers").session(session(salesManagerId, "sales_manager")))
            .andExpect(status().isOk());
    }

    @Test
    void importAccountAndEmployeeRoles_cannotCreateCustomerOrProject() throws Exception {
        for (long id : List.of(importUserId, accountUserId, employeeUserId)) {
            String role = id == importUserId ? "import" : id == accountUserId ? "account" : "employee";
            customerMvc.perform(post("/api/customers").session(session(id, role))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ACME " + role + "\"}"))
                .andExpect(status().isForbidden());
            customerMvc.perform(post("/api/customers/{id}/projects", projectOwnerCustomerId).session(session(id, role))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"โครงการ " + role + "\"}"))
                .andExpect(status().isForbidden());
        }
        // import/account ARE in TicketAccessPolicy.VIEWER_ROLES (unaffected by this widening --
        // that read gate was already open to them before this change), so search stays 200 for
        // them; only plain employee is denied there.
        customerMvc.perform(get("/api/customers").session(session(employeeUserId, "employee")))
            .andExpect(status().isForbidden());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private void grantQuotationCapability(long employeeId) {
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id",
            Map.of("id", employeeId));
    }

    private CreateTicketRequest ticketRequest() {
        return new CreateTicketRequest("ดีลทดสอบ deal-entry", "NORMAL", "ลูกค้าทดสอบ deal-entry", null,
            projectId, null, null, null, List.of(), LocalDate.now().plusDays(7));
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }

    private MockHttpSession session(long employeeId, String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor(employeeId, role));
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
