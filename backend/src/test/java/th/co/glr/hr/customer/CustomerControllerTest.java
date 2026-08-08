package th.co.glr.hr.customer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

// Audit gap #1 (writes): customer/contact/project CREATE endpoints were authenticated-only, so
// any role (incl. employee) could write customer rows straight through the repository. Gated to
// the sales role (the deal-entry flow) below.
//
// P0 fix (reads): the three GETs were ALSO authenticated-only — sessions.requireUser and nothing
// else — so any role, including employee/warehouse/qc (no sales access anywhere else in the
// system), could read the customer master (taxId/address/phone) and every contact's name/email/
// phone. Unlike CatalogController's GET /api/catalog and /api/catalog/prices (#205, #388 ruling),
// there was never a recorded product-owner decision that these reads should stay open — the old
// docstring here just asserted it. Reads are now gated in CustomerService to the audience derived
// from every real caller (TicketCreateModal's picker — sales only; DepositNoticePage's customer
// search — the full canViewTickets audience): sales/import/ceo/account/sales_manager, the same
// set as canViewCatalog/canViewTickets/TicketAccessPolicy.VIEWER_ROLES. This class unit-tests that
// DECISION with a mocked repository (cheap, fast); CustomerReadAuthzIntegrationTest proves the
// ENFORCEMENT survives into the real SQL on real Postgres — Mockito cannot reach that part.
class CustomerControllerTest {
    private static final List<String> VIEWER_ROLES =
        List.of("sales", "import", "ceo", "account", "sales_manager");
    private static final List<String> NON_VIEWER_ROLES =
        List.of("employee", "warehouse", "qc", "hr");

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final ContactRepository contacts = mock(ContactRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final CustomerService customerService = new CustomerService(customers, contacts, projects);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new CustomerController(customers, contacts, projects, customerService, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    // ── reads: gated to the derived sales/CRM audience ────────────────────
    @Test
    void searchIsForbiddenForNonViewerRoles() throws Exception {
        for (String role : NON_VIEWER_ROLES) {
            mvc.perform(get("/api/customers").session(session(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void searchIsAllowedForEveryViewerRole() throws Exception {
        when(customers.search(any())).thenReturn(List.of());
        for (String role : VIEWER_ROLES) {
            mvc.perform(get("/api/customers").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void listContactsIsForbiddenForNonViewerRoles() throws Exception {
        for (String role : NON_VIEWER_ROLES) {
            mvc.perform(get("/api/customers/1/contacts").session(session(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void listContactsIsAllowedForEveryViewerRole() throws Exception {
        when(contacts.findByCustomer(anyLong())).thenReturn(List.of());
        for (String role : VIEWER_ROLES) {
            mvc.perform(get("/api/customers/1/contacts").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void listProjectsIsForbiddenForNonViewerRoles() throws Exception {
        for (String role : NON_VIEWER_ROLES) {
            mvc.perform(get("/api/customers/1/projects").session(session(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void listProjectsIsAllowedForEveryViewerRole() throws Exception {
        when(projects.findByCustomer(anyLong())).thenReturn(List.of());
        for (String role : VIEWER_ROLES) {
            mvc.perform(get("/api/customers/1/projects").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void anonymousCallerIsUnauthorizedOnAllThreeReads() throws Exception {
        mvc.perform(get("/api/customers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customers/1/contacts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customers/1/projects")).andExpect(status().isUnauthorized());
    }

    // ── create customer: sales only ───────────────────────────────────────
    @Test
    void employeeCannotCreateCustomer() throws Exception {
        mvc.perform(post("/api/customers").session(session("employee"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ACME\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesManagerCannotCreateCustomer() throws Exception {
        // sales_manager is read+comment oversight only — never a write role.
        mvc.perform(post("/api/customers").session(session("sales_manager"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ACME\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesCanCreateCustomer() throws Exception {
        when(customers.create(any(), any(), any(), any(), any()))
            .thenReturn(new CustomerDto(1L, "ACME", null, null, "สำนักงานใหญ่", null));
        mvc.perform(post("/api/customers").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ACME\"}"))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void createCustomerWithoutBranchDefaultsToHeadOffice() throws Exception {
        // branch is NOT NULL with a DB default; an explicit null bypasses the default and
        // used to 500. The controller now coalesces a blank branch to 'สำนักงานใหญ่'.
        when(customers.create(any(), any(), any(), any(), any()))
            .thenReturn(new CustomerDto(1L, "ACME", null, null, "สำนักงานใหญ่", null));
        mvc.perform(post("/api/customers").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ACME\"}"))
            .andExpect(status().is2xxSuccessful());
        verify(customers).create(eq("ACME"), any(), any(), eq("สำนักงานใหญ่"), any());
    }

    // ── create contact / project: sales only ──────────────────────────────
    @Test
    void employeeCannotCreateContact() throws Exception {
        mvc.perform(post("/api/customers/1/contacts").session(session("employee"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"A\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesCanCreateContact() throws Exception {
        when(contacts.create(anyLong(), any(), any(), any(), any(), any()))
            .thenReturn(new ContactDto(1L, 1L, "A", null, null, null, null));
        mvc.perform(post("/api/customers/1/contacts").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"A\"}"))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void employeeCannotCreateProject() throws Exception {
        mvc.perform(post("/api/customers/1/projects").session(session("employee"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"P1\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesCanCreateProject() throws Exception {
        when(projects.create(anyLong(), any())).thenReturn(new ProjectDto(1L, 1L, "P1"));
        mvc.perform(post("/api/customers/1/projects").session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"P1\"}"))
            .andExpect(status().is2xxSuccessful());
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
