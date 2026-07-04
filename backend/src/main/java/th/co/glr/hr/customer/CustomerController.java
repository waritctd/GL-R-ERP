package th.co.glr.hr.customer;

import jakarta.servlet.http.HttpSession;
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
    Map<String, CustomerDto> create(@RequestBody CreateCustomerRequest req, HttpSession session) {
        sessions.requireUser(session);
        return Map.of("customer", customers.create(req.name(), req.taxId(), req.address(), req.branch(), req.phone()));
    }

    @GetMapping("/{customerId}/contacts")
    Map<String, List<ContactDto>> listContacts(@PathVariable long customerId, HttpSession session) {
        sessions.requireUser(session);
        return Map.of("contacts", contacts.findByCustomer(customerId));
    }

    @PostMapping("/{customerId}/contacts")
    Map<String, ContactDto> createContact(@PathVariable long customerId,
                                          @RequestBody CreateContactRequest req,
                                          HttpSession session) {
        sessions.requireUser(session);
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
                                          @RequestBody CreateProjectRequest req,
                                          HttpSession session) {
        sessions.requireUser(session);
        return Map.of("project", projects.create(customerId, req.name()));
    }

    record CreateCustomerRequest(String name, String taxId, String address, String branch, String phone) {}
    record CreateContactRequest(String firstName, String lastName, String position, String email, String phone) {}
    record CreateProjectRequest(String name) {}
}
