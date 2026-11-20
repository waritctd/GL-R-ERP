package th.co.glr.hr.customer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.dealquotation.DealQuotationRepository;

// Audit gap #1 (writes): customer/contact/project CREATE endpoints were authenticated-only, so any role (incl.
// employee) could write customer rows straight through the repository. Gated to the sales role (the deal-entry
// flow) below.
class CustomerControllerTest {

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final ContactRepository contacts = mock(ContactRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    // Unstubbed -> canCreateQuotation() defaults to false (Mockito), so a role reaches an endpoint here only via
    // its OWN role (sales/sales_manager) or an explicit per-test stub — never a phantom grant.
    private final EmployeeAuthRepository employeeAuth = mock(EmployeeAuthRepository.class);
    // Unstubbed -> refreshDraftContactSnapshot returns 0 (Mockito default for int); nothing here reads it.
    private final DealQuotationRepository dealQuotations = mock(DealQuotationRepository.class);
    private final CustomerService customerService = new CustomerService(customers, contacts, projects, employeeAuth, dealQuotations);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new CustomerController(customers, contacts, projects, customerService,
            new SessionContext(), employeeAuth))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    // ── create customer: sales only ───────────────────────────────────────
    @Test
    void employeeCannotCreateCustomer() throws Exception {
        mvc.perform(post("/api/customers").session(session("employee"))
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

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
