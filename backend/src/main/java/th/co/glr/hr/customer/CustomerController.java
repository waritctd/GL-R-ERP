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

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerRepository customers;
    private final ContactRepository  contacts;
    private final ProjectRepository  projects;
    private final SessionContext     sessions;

    public CustomerController(CustomerRepository customers,
                              ContactRepository contacts,
                              ProjectRepository projects,
                              SessionContext sessions) {
        this.customers = customers;
        this.contacts  = contacts;
        this.projects  = projects;
        this.sessions  = sessions;
    }

    @GetMapping
    Map<String, List<CustomerDto>> search(@RequestParam(required = false) String search, HttpSession session) {
        sessions.requireUser(session);
        return Map.of("customers", customers.search(search));
    }

    @PostMapping
    Map<String, CustomerDto> create(@Valid @RequestBody CreateCustomerRequest req, HttpSession session) {
        // Customer/contact/project creation is the sales deal-entry flow (TicketCreateModal);
        // gate it to the sales role, mirroring TicketService.create. Reads stay open to any
        // authenticated user (customer pickers). Previously any role (incl. employee) could write.
        sessions.requireAnyRole(sessions.requireUser(session), "sales");
        // branch has a DB default ('สำนักงานใหญ่') but an explicit NULL bypasses it and
        // violates NOT NULL; coalesce so a create that omits branch succeeds (mirrors the mock).
        return Map.of("customer", customers.create(req.name(), req.taxId(), req.address(), branchOrDefault(req.branch()), req.phone()));
    }

    private static final String DEFAULT_BRANCH = "สำนักงานใหญ่";

    private static String branchOrDefault(String branch) {
        return (branch == null || branch.isBlank()) ? DEFAULT_BRANCH : branch;
    }

    @GetMapping("/{customerId}/contacts")
    Map<String, List<ContactDto>> listContacts(@PathVariable long customerId, HttpSession session) {
        sessions.requireUser(session);
        return Map.of("contacts", contacts.findByCustomer(customerId));
    }

    @PostMapping("/{customerId}/contacts")
    Map<String, ContactDto> createContact(@PathVariable long customerId,
                                          @Valid @RequestBody CreateContactRequest req,
                                          HttpSession session) {
        sessions.requireAnyRole(sessions.requireUser(session), "sales");
        return Map.of("contact", contacts.create(customerId,
            req.firstName(), req.lastName(), req.position(), req.email(), req.phone()));
    }

    @GetMapping("/{customerId}/projects")
    Map<String, List<ProjectDto>> listProjects(@PathVariable long customerId, HttpSession session) {
        sessions.requireUser(session);
        return Map.of("projects", projects.findByCustomer(customerId));
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
