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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.DealEntryAccess;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerRepository customers;
    private final ContactRepository  contacts;
    private final ProjectRepository  projects;
    private final CustomerService    customerService;
    private final SessionContext     sessions;
    // Deal-ENTRY authz only (see DealEntryAccess's own Javadoc) -- the three create() endpoints
    // below use this; nothing else on this controller does.
    private final EmployeeAuthRepository employeeAuth;

    public CustomerController(CustomerRepository customers,
                              ContactRepository contacts,
                              ProjectRepository projects,
                              CustomerService customerService,
                              SessionContext sessions,
                              EmployeeAuthRepository employeeAuth) {
        this.customers       = customers;
        this.contacts        = contacts;
        this.projects        = projects;
        this.customerService = customerService;
        this.sessions        = sessions;
        this.employeeAuth    = employeeAuth;
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
        // Customer/contact/project creation is the deal-entry flow (TicketCreateModal /
        // /quotations/new). Previously any role (incl. employee) could write; then narrowed to
        // sales-only, mirroring the old TicketService.create. Owner ruling 2026-09-10 widened this
        // to sales/sales_manager/canCreateQuotation-grant — see DealEntryAccess's own Javadoc. The
        // three reads below are gated the same way (CustomerService).
        DealEntryAccess.requireCanEnterDeal(sessions.requireUser(session), employeeAuth);
        // branch has a DB default ('สำนักงานใหญ่') but an explicit NULL bypasses it and
        // violates NOT NULL; coalesce so a create that omits branch succeeds (mirrors the mock).
        return Map.of("customer", customers.create(req.name(), req.taxId(), req.address(), branchOrDefault(req.branch()), req.phone()));
    }

    /**
     * Owner feedback F7 (2026-09-10): "for the customer information you also have to have a field
     * for เลขที่ผู้เสียภาษี and โทร." — the columns and the printed lines already existed; what was
     * missing was any way to CORRECT them. The deal card now edits the selected customer's tax id
     * / phone in place, so the next quotation for that customer is already right.
     *
     * <p><strong>Stated authz change</strong> (CLAUDE.md's sales-flow relaxation — a sales API
     * contract change, declared, not smuggled in): this is a NEW write endpoint on the customer
     * master, gated by {@link DealEntryAccess#requireCanEnterDeal} — exactly the gate
     * {@link #create} uses, no wider. sales / sales_manager / a live {@code canCreateQuotation}
     * grant may write; import, account, employee, hr, warehouse and an ungranted qc get 403. Real-DB
     * evidence: {@code DealEntryAccessIntegrationTest#...UpdateCustomer...}.
     *
     * <p>PATCH semantics on a PUT verb (the shape the frontend asked for): a field the body omits
     * or sends as {@code null} is left alone; a field it sends is written, blank included, so a
     * wrong tax id can be cleared. {@code name} and {@code branch} are {@code NOT NULL} columns —
     * a blank for either is a 400 rather than a constraint violation. Nothing here rewrites an
     * already-issued document: {@code sales.quotation} freezes {@code customer_name}/
     * {@code customer_tax_id}/{@code customer_address}/{@code customer_phone} at save time.
     */
    @PutMapping("/{customerId}")
    Map<String, CustomerDto> update(@PathVariable long customerId,
                                    @Valid @RequestBody UpdateCustomerRequest req,
                                    HttpSession session) {
        DealEntryAccess.requireCanEnterDeal(sessions.requireUser(session), employeeAuth);
        requireNotBlankIfPresent(req.name(), "กรุณาระบุชื่อลูกค้า");
        requireNotBlankIfPresent(req.branch(), "กรุณาระบุสาขา");
        CustomerDto updated = customers
            .update(customerId, req.name(), req.taxId(), req.address(), req.branch(), req.phone())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบลูกค้ารายนี้"));
        return Map.of("customer", updated);
    }

    /** A field the body OMITS is null and simply not applied; a field it sends as an empty string
     * is a deliberate clear — which the two NOT NULL columns cannot accept. */
    private static void requireNotBlankIfPresent(String value, String message) {
        if (value != null && value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
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
        DealEntryAccess.requireCanEnterDeal(sessions.requireUser(session), employeeAuth);
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
        DealEntryAccess.requireCanEnterDeal(sessions.requireUser(session), employeeAuth);
        return Map.of("project", projects.create(customerId, req.name()));
    }

    record CreateCustomerRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 20)  String taxId,
        @Size(max = 2000) String address,
        @Size(max = 100) String branch,
        @Size(max = 50)  String phone
    ) {}

    /** Every field optional — see {@link #update}: null means "leave it alone". The @Size caps
     * mirror {@code CreateCustomerRequest}'s, which mirror V16's own column widths. */
    record UpdateCustomerRequest(
        @Size(max = 200) String name,
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
