package th.co.glr.hr.customer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * P0 fix: {@code CustomerController}'s three GETs ({@code /api/customers}, {@code
 * /{id}/contacts}, {@code /{id}/projects}) called only {@code sessions.requireUser} — authenticated,
 * not authorized — so any logged-in role, including {@code employee}, {@code warehouse} and
 * {@code qc} (no sales access anywhere else in the system), could read the customer master
 * ({@code taxId}, {@code address}, {@code phone}) and every contact's name/email/phone.
 *
 * <p>This runs the REAL {@code CustomerController} over the REAL {@code CustomerService} and REAL
 * repositories on REAL Postgres, per CLAUDE.md's "Permission changes must ship evidence": a
 * Mockito-mocked repository (see {@code CustomerControllerTest}, which unit-tests the same
 * decision cheaply) passes happily while the SQL does something else — this is the part only a
 * real database can prove. {@code AttendanceScopeIntegrationTest} is the cited reference shape;
 * {@code CatalogPricingReadAuthzIntegrationTest} is the direct sibling in this same domain.
 *
 * <p>Every denial case is written <strong>wrong way round</strong>: it asks whether a caller who
 * should not reach the data can reach it, asserts 403, AND asserts the response body carries none
 * of the seeded row's data (not just "not 2xx" — a body that leaked data alongside a non-200 would
 * still be a real bug). The permitted cases assert a real seeded row comes back through the real
 * SQL, so a removed guard fails loudly instead of quietly passing against an empty table.
 *
 * <p>The derived audience — {@code sales, import, ceo, account, sales_manager} — is {@link
 * th.co.glr.hr.ticket.TicketAccessPolicy#VIEWER_ROLES}, exactly the same set as {@code
 * canViewCatalog}/{@code canViewTickets} on the frontend. See {@code CustomerService}'s javadoc for
 * the caller audit this was derived from (TicketCreateModal's picker — sales only reaches it;
 * DepositNoticePage's customer search — reachable by the full audience).
 */
class CustomerReadAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final List<String> VIEWER_ROLES =
        List.of("sales", "import", "ceo", "account", "sales_manager");

    /**
     * Everyone else {@link th.co.glr.hr.auth.ApplicationRoles} recognises. {@code warehouse} and
     * {@code qc} are named explicitly below too, per the audit that opened this fix.
     */
    private static final List<String> NON_VIEWER_ROLES = List.of("employee", "warehouse", "qc", "hr");

    private static final String SEEDED_TAX_ID   = "AUTHZ-CUST-TAXID-1";
    private static final String SEEDED_EMAIL    = "authz-contact-1@example.com";
    private static final String SEEDED_PROJECT  = "โครงการทดสอบสิทธิ์ AUTHZ-1";

    private MockMvc mvc;
    private long customerId;

    @BeforeEach
    void wireRealCollaboratorsAndSeedACustomer() {
        SessionContext sessions = new SessionContext();
        CustomerRepository customers = new CustomerRepository(jdbc);
        ContactRepository contacts = new ContactRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        CustomerService customerService = new CustomerService(customers, contacts, projects);

        // Real HTTP responses go through Boot's Jackson-3-backed JacksonJsonHttpMessageConverter
        // (JacksonAutoConfiguration), so the MockMvc layer is wired with the same converter type.
        // Jackson 3 handles java.time natively (no module registration needed, unlike the old
        // Jackson 2 ObjectMapper this replaced). accept-empty-string-as-null-object mirrors the one
        // Jackson property this app sets (src/main/resources/application.yml).
        JsonMapper jsonMapper = JsonMapper.builder()
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .build();

        mvc = MockMvcBuilders
            .standaloneSetup(new CustomerController(customers, contacts, projects, customerService, sessions))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        CustomerDto customer = customers.create("บริษัท ทดสอบสิทธิ์ จำกัด", SEEDED_TAX_ID,
            "999 ถนนทดสอบสิทธิ์", "สำนักงานใหญ่", "02-000-9999");
        customerId = customer.id();
        contacts.create(customerId, "สมชาย", "ทดสอบสิทธิ์", "ผู้จัดการ", SEEDED_EMAIL, "081-000-0001");
        projects.create(customerId, SEEDED_PROJECT);
    }

    // ── wrong-way-round: excluded roles get 403 AND zero rows, never just a status code ──────

    @Test
    void nonViewerRolesCannotSearchCustomers() throws Exception {
        for (String role : NON_VIEWER_ROLES) {
            mvc.perform(get("/api/customers").session(sessionFor(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.customers").doesNotExist());
        }
    }

    @Test
    void nonViewerRolesCannotListContacts() throws Exception {
        for (String role : NON_VIEWER_ROLES) {
            mvc.perform(get("/api/customers/{id}/contacts", customerId).session(sessionFor(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.contacts").doesNotExist());
        }
    }

    @Test
    void nonViewerRolesCannotListProjects() throws Exception {
        for (String role : NON_VIEWER_ROLES) {
            mvc.perform(get("/api/customers/{id}/projects", customerId).session(sessionFor(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.projects").doesNotExist());
        }
    }

    /**
     * Named explicitly per the audit that opened this fix: warehouse/qc are the two roles the
     * audit specifically called out as having "no sales access anywhere else in the system", so
     * they get their own case rather than being folded silently into the loop above.
     */
    @Test
    void warehouseAndQcSpecificallyGetZeroRowsOnAllThreeReads() throws Exception {
        for (String role : List.of("warehouse", "qc")) {
            mvc.perform(get("/api/customers").session(sessionFor(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.customers").doesNotExist());
            mvc.perform(get("/api/customers/{id}/contacts", customerId).session(sessionFor(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.contacts").doesNotExist());
            mvc.perform(get("/api/customers/{id}/projects", customerId).session(sessionFor(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.projects").doesNotExist());
        }
    }

    // ── the gate isn't "deny everything": every derived-audience role gets 200 + a REAL row ──

    @Test
    void everyViewerRoleCanSearchCustomersAndSeesTheRealRow() throws Exception {
        for (String role : VIEWER_ROLES) {
            mvc.perform(get("/api/customers").param("search", "ทดสอบสิทธิ์").session(sessionFor(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customers[0].taxId").value(SEEDED_TAX_ID));
        }
    }

    @Test
    void everyViewerRoleCanListContactsAndSeesTheRealRow() throws Exception {
        for (String role : VIEWER_ROLES) {
            mvc.perform(get("/api/customers/{id}/contacts", customerId).session(sessionFor(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contacts[0].email").value(SEEDED_EMAIL));
        }
    }

    @Test
    void everyViewerRoleCanListProjectsAndSeesTheRealRow() throws Exception {
        for (String role : VIEWER_ROLES) {
            mvc.perform(get("/api/customers/{id}/projects", customerId).session(sessionFor(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].name").value(SEEDED_PROJECT));
        }
    }

    /** Role-gated is not the same as authenticated: an anonymous caller still gets 401, not 403. */
    @Test
    void anonymousCallerIsRejectedWith401OnEveryRead() throws Exception {
        mvc.perform(get("/api/customers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customers/{id}/contacts", customerId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customers/{id}/projects", customerId)).andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private static MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test " + role, role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
