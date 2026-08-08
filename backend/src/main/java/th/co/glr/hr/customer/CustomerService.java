package th.co.glr.hr.customer;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.ticket.TicketAccessPolicy;

/**
 * P0 fix: {@code CustomerController}'s three reads ({@code GET /api/customers},
 * {@code /{id}/contacts}, {@code /{id}/projects}) used to call only {@code sessions.requireUser} —
 * authenticated, not authorized — so every logged-in role, including {@code employee},
 * {@code warehouse} and {@code qc} (the roles {@code DivisionAccessPolicy} hands ordinary staff,
 * with no sales access anywhere else in the system), could read the customer master
 * ({@code taxId}, {@code address}, {@code phone}) and every contact's name/email/phone.
 *
 * <p>Unlike {@code CatalogController}'s reads, there was no recorded product-owner ruling for this
 * one — the {@code :26-28} comment on the controller justified only the write gate. The audience
 * below is <strong>derived</strong> from every real caller, not assumed:
 *
 * <ul>
 *   <li>{@code TicketCreateModal}'s customer/contact/project picker — the deal-creation flow,
 *       reachable only via the "สร้างดีลใหม่" button on {@code TicketListPage}, which renders only
 *       for {@code canCreateTickets} ({@code ['sales']}).</li>
 *   <li>{@code DepositNoticePage}'s customer search — fires unconditionally on mount for whoever
 *       reaches {@code /tickets/:ticketId/deposit}, gated by {@code canViewTickets}
 *       ({@code ['sales','import','ceo','account','sales_manager']}).</li>
 * </ul>
 *
 * <p>The union of both is exactly {@link TicketAccessPolicy#VIEWER_ROLES} — the same audience as
 * {@code canViewCatalog} and {@code canViewTickets} on the frontend. Aliased here rather than
 * hand-copied a fourth time: see {@link TicketAccessPolicy}'s own javadoc for why issue #389 made
 * that the rule (drift between hand-written copies of this exact set is how #389 happened; {@code
 * DepositNoticeService} still carries the one un-migrated copy). No non-UI (backend-internal)
 * caller needs a wider audience than this either — {@code PricingRequestService} and {@code
 * DepositNoticeService}/{@code CustomerQuotationService}/{@code TicketService} all reach customer
 * data by injecting {@code CustomerRepository}/{@code ContactRepository} directly, never through
 * this controller, and each already enforces its own (equal-or-narrower) role gate.
 */
@Service
public class CustomerService {
    // Alias, not a fourth hand-written copy — see class javadoc and TicketAccessPolicy's own.
    private static final Set<String> VIEWER_ROLES = TicketAccessPolicy.VIEWER_ROLES;

    private final CustomerRepository customers;
    private final ContactRepository  contacts;
    private final ProjectRepository  projects;

    public CustomerService(CustomerRepository customers, ContactRepository contacts, ProjectRepository projects) {
        this.customers = customers;
        this.contacts  = contacts;
        this.projects  = projects;
    }

    public List<CustomerDto> search(String q, UserPrincipal actor) {
        requireRole(actor, VIEWER_ROLES);
        return customers.search(q);
    }

    public List<ContactDto> listContacts(long customerId, UserPrincipal actor) {
        requireRole(actor, VIEWER_ROLES);
        return contacts.findByCustomer(customerId);
    }

    public List<ProjectDto> listProjects(long customerId, UserPrincipal actor) {
        requireRole(actor, VIEWER_ROLES);
        return projects.findByCustomer(customerId);
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }
}
