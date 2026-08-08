package th.co.glr.hr.customer;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerRepository customers;
    private final ContactRepository  contacts;
    private final ProjectRepository  projects;
    private final CustomerService    customerService;
    private final SessionContext     sessions;

    public CustomerController(CustomerRepository customers,
                              ContactRepository contacts,
                              ProjectRepository projects,
                              CustomerService customerService,
                              SessionContext sessions) {
        this.customers       = customers;
        this.contacts        = contacts;
        this.projects        = projects;
        this.customerService = customerService;
        this.sessions        = sessions;
    }

    // P0 fix: this used to be sessions.requireUser(session) and nothing else — authenticated but
    // not authorized, so every role (employee/warehouse/qc included) could read the customer
    // master (taxId/address/phone). Gated in CustomerService (requireRole, matching
    // TicketService/DepositNoticeService/CustomerQuotationService's own pattern) to the derived
    // sales/CRM audience — see that class's javadoc for the caller audit. Unlike CatalogController
    // (#205/#388), there was no recorded owner ruling to preserve for the read side.
    @GetMapping
    Map<String, List<CustomerDto>> search(@RequestParam(required = false) String search, HttpSession session) {
        UserPrincipal actor = sessions.requireUser(session);
        return Map.of("customers", customerService.search(search, actor));
    }

    @PostMapping
    Map<String, CustomerDto> create(@Valid @RequestBody CreateCustomerRequest req, HttpSession session) {
        // Customer/contact/project creation is the sales deal-entry flow (TicketCreateModal);
        // gate it to the sales role, mirroring TicketService.create. Previously any role (incl.
        // employee) could write. The three reads below are now gated too (CustomerService,
        // requireRole) — they used to be open to any authenticated user, but that was an
        // oversight, not a recorded product decision (unlike CatalogController's #205/#388).
        sessions.requireAnyRole(sessions.requireUser(session), "sales");
        // branch has a DB default ('สำนักงานใหญ่') but an explicit NULL bypasses it and
        // violates NOT NULL; coalesce so a create that omits branch succeeds (mirrors the mock).
        return Map.of("customer", customers.create(req.name(), req.taxId(), req.address(), branchOrDefault(req.branch()), req.phone()));
    }

    private static final String DEFAULT_BRANCH = "สำนักงานใหญ่";

    private static String branchOrDefault(String branch) {
        return (branch == null || branch.isBlank()) ? DEFAULT_BRANCH : branch;
    }

    // P0 fix — same gate as search() above; see CustomerService's javadoc for the derived audience.
    @GetMapping("/{customerId}/contacts")
    Map<String, List<ContactDto>> listContacts(@PathVariable long customerId, HttpSession session) {
        UserPrincipal actor = sessions.requireUser(session);
        return Map.of("contacts", customerService.listContacts(customerId, actor));
    }

    @PostMapping("/{customerId}/contacts")
    Map<String, ContactDto> createContact(@PathVariable long customerId,
                                          @Valid @RequestBody CreateContactRequest req,
                                          HttpSession session) {
        sessions.requireAnyRole(sessions.requireUser(session), "sales");
        return Map.of("contact", contacts.create(customerId,
            req.firstName(), req.lastName(), req.position(), req.email(), req.phone()));
    }

    // P0 fix — same gate as search() above; see CustomerService's javadoc for the derived audience.
    @GetMapping("/{customerId}/projects")
    Map<String, List<ProjectDto>> listProjects(@PathVariable long customerId, HttpSession session) {
        UserPrincipal actor = sessions.requireUser(session);
        return Map.of("projects", customerService.listProjects(customerId, actor));
    }

    @PostMapping("/{customerId}/projects")
    Map<String, ProjectDto> createProject(@PathVariable long customerId,
                                          @Valid @RequestBody CreateProjectRequest req,
                                          HttpSession session) {
        sessions.requireAnyRole(sessions.requireUser(session), "sales");
        return Map.of("project", projects.create(customerId, req.name()));
    }

    record CreateCustomerRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 20)  String taxId,
        @Size(max = 2000) String address,
        @Size(max = 100) String branch,
        @Size(max = 50)  String phone
    ) {}

    record CreateContactRequest(
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @Size(max = 100) String position,
        @Email @Size(max = 200) String email,
        @Size(max = 50)  String phone
    ) {}

    record CreateProjectRequest(@NotBlank @Size(max = 200) String name) {}
}
