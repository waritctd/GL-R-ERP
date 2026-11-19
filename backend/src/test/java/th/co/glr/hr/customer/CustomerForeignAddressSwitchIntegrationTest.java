package th.co.glr.hr.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.dealquotation.DealQuotationRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Real-DB evidence for the frontend fix: CustomerDetailsFields.jsx now lets a rep switch a customer that
 * already has a Thai STRUCTURED address to a free-text one, by sending PUT /api/customers/{id} with ONLY
 * {"address": "<text>"} — no structured keys at all. */
class CustomerForeignAddressSwitchIntegrationTest extends AbstractPostgresIntegrationTest {

    private MockMvc mvc() {
        CustomerRepository customers = new CustomerRepository(jdbc);
        ContactRepository contacts = new ContactRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeAuthRepository employeeAuth = new EmployeeAuthRepository(jdbc);
        CustomerService customerService = new CustomerService(customers, contacts, projects, employeeAuth,
            new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)));
        return MockMvcBuilders
            .standaloneSetup(new CustomerController(customers, contacts, projects, customerService,
                new SessionContext(), employeeAuth))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    private MockHttpSession salesSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, "rep@glr.co.th", "Test Rep", "sales", 1L, true, LocalDate.now(), false, null, false));
        return session;
    }

    /** Shaped like prod customer_id 4326: a Bangkok structured address standing in for a foreign (Vietnamese)
     * customer whose rep had no real Thai address to give. */
    private long seedStructuredCustomer() {
        CustomerRepository customers = new CustomerRepository(jdbc);
        return customers.createStructured("DONG NGO GROUP INVESTMENT AND DEVELOPMENT JSC", null,
            "สำนักงานใหญ่", null, "---Vietnam---", "10", "1039", "103901", null).id();
    }

    @Test
    void switchingToFreeTextAddressClearsEveryStructuredColumn() throws Exception {
        long id = seedStructuredCustomer();
        CustomerDto before = new CustomerRepository(jdbc).findById(id).orElseThrow();
        assertThat(before.provinceCode()).isEqualTo("10");
        assertThat(before.districtCode()).isEqualTo("1039");
        assertThat(before.subdistrictCode()).isEqualTo("103901");

        String freeText = "No. 12 Le Loi Street, District 1, Ho Chi Minh City, Vietnam";
        mvc().perform(put("/api/customers/{id}", id).session(salesSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\":\"" + freeText + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customer.address").value(freeText))
            .andExpect(jsonPath("$.customer.provinceCode").doesNotExist())
            .andExpect(jsonPath("$.customer.districtCode").doesNotExist())
            .andExpect(jsonPath("$.customer.subdistrictCode").doesNotExist())
            .andExpect(jsonPath("$.customer.postalCode").doesNotExist());

        CustomerDto after = new CustomerRepository(jdbc).findById(id).orElseThrow();
        assertThat(after.address()).isEqualTo(freeText);
        assertThat(after.provinceCode()).isNull();
        assertThat(after.districtCode()).isNull();
        assertThat(after.subdistrictCode()).isNull();
        assertThat(after.postalCode()).isNull();
        assertThat(after.provinceNameTh()).isNull();
        assertThat(after.districtNameTh()).isNull();
        assertThat(after.subdistrictNameTh()).isNull();
        // The old structured display string is preserved as legacyAddress (CustomerRepository.update's own
        // COALESCE(legacy_address, address)).
        assertThat(after.legacyAddress()).isEqualTo(before.address());
    }
}
