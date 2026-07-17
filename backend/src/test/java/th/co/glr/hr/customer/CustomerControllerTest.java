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

// Audit gap #1: customer/contact/project CREATE endpoints were authenticated-only, so any
// role (incl. employee) could write customer rows straight through the repository. They are
// now gated to the sales role (the deal-entry flow); reads stay open for the customer pickers.
class CustomerControllerTest {
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final ContactRepository contacts = mock(ContactRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new CustomerController(customers, contacts, projects, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    // ── reads stay open to any authenticated user ─────────────────────────
    @Test
    void searchIsNotForbiddenForAnyAuthenticatedRole() throws Exception {
        when(customers.search(any())).thenReturn(List.of());
        mvc.perform(get("/api/customers").session(session("employee")))
            .andExpect(status().is2xxSuccessful());
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
