package th.co.glr.hr.customer;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.DealEntryAccess;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationRepository;
import th.co.glr.hr.ticket.TicketAccessPolicy;

/**
 * Role gate for {@code CustomerController}'s three reads ({@code GET /api/customers},
 * {@code /{id}/contacts}, {@code /{id}/projects}). Authenticated is not enough: {@code employee},
 * {@code warehouse} and {@code qc} (the roles {@code DivisionAccessPolicy} hands ordinary staff,
 * with no sales access anywhere else in the system) must not read the customer master
 * ({@code taxId}, {@code address}, {@code phone}) or any contact's name/email/phone.
 *
 * <p>There is no recorded product-owner ruling for this audience. It is
 * <strong>derived</strong> from every real caller, not assumed:
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
 * hand-copied a fourth time: see {@link TicketAccessPolicy}'s own javadoc for why (drift between
 * hand-written copies of this exact set caused an earlier bug; {@code
 * DepositNoticeService} still carries the one un-migrated copy). No non-UI (backend-internal)
 * caller needs a wider audience than this either — {@code PricingRequestService} and {@code
 * DepositNoticeService}/{@code CustomerQuotationService}/{@code TicketService} all reach customer
 * data by injecting {@code CustomerRepository}/{@code ContactRepository} directly, never through
 * this controller, and each already enforces its own (equal-or-narrower) role gate.
 *
 * <p>Per {@link DealEntryAccess}, these three reads ALSO admit any employee
 * holding the live {@code canCreateQuotation} grant — the inline-deal-creation typeahead
 * ({@code /quotations/new}'s customer/project picker) would otherwise 403 for exactly the people
 * the grant was created to unblock. {@code sales_manager} is already inside
 * {@link TicketAccessPolicy#VIEWER_ROLES}, so it needs no separate widening here.
 */
@Service
public class CustomerService {
    // Alias, not a fourth hand-written copy — see class javadoc and TicketAccessPolicy's own.
    private static final Set<String> VIEWER_ROLES = TicketAccessPolicy.VIEWER_ROLES;

    private final CustomerRepository customers;
    private final ContactRepository  contacts;
    private final ProjectRepository  projects;
    // Deal-ENTRY grant read only (see DealEntryAccess's own Javadoc) -- ORed alongside
    // VIEWER_ROLES below, never replacing it (import/ceo/account must stay reachable too).
    private final EmployeeAuthRepository employeeAuth;
    // Only used by #updateContact (see its Javadoc); the reads above stay as narrow as before.
    private final DealQuotationRepository dealQuotations;

    public CustomerService(CustomerRepository customers, ContactRepository contacts, ProjectRepository projects,
                           EmployeeAuthRepository employeeAuth, DealQuotationRepository dealQuotations) {
        this.customers      = customers;
        this.contacts       = contacts;
        this.projects       = projects;
        this.employeeAuth   = employeeAuth;
        this.dealQuotations = dealQuotations;
    }

    public List<CustomerDto> search(String q, UserPrincipal actor) {
        requireReadAccess(actor);
        return customers.search(q);
    }

    public List<ContactDto> listContacts(long customerId, UserPrincipal actor) {
        requireReadAccess(actor);
        return contacts.findByCustomer(customerId);
    }

    public List<ProjectDto> listProjects(long customerId, UserPrincipal actor) {
        requireReadAccess(actor);
        return projects.findByCustomer(customerId);
    }

    private void requireReadAccess(UserPrincipal actor) {
        if (VIEWER_ROLES.contains(actor.role()) || DealEntryAccess.canEnterDeal(actor, employeeAuth)) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }

    /**
     * Wraps {@link ContactRepository#update} and {@link DealQuotationRepository#refreshDraftContactSnapshot}
     * in ONE transaction, so a contact correction and the DRAFT snapshots it feeds either both land
     * or neither does. {@link CustomerController#updateContact} goes through this rather than
     * {@code contacts.update(...)} directly.
     *
     * <p>A DRAFT quotation's ผู้สั่งซื้อ phone/email snapshot refreshes the moment the CONTACT
     * itself is corrected, not only on that DRAFT's own next save
     * ({@code DealQuotationRepository#updateHeader}'s comment) — see
     * {@code DealQuotationRepository#refreshDraftContactSnapshot}'s own Javadoc for the full
     * reasoning and why an already-submitted document is untouched either way.
     *
     * <p>{@code refreshName} is derived here, not left to the caller to remember: only an edit that
     * actually supplies a new {@code firstName}/{@code lastName} re-snapshots the printed name —
     * a phone/email-only PATCH (the common case, and the one the owner reported) never touches it.
     */
    @Transactional
    public ContactDto updateContact(long customerId, long contactId, String firstName, String lastName,
                                    String position, String email, String phone) {
        ContactDto updated = contacts.update(customerId, contactId, firstName, lastName, position, email, phone)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบผู้สั่งซื้อรายนี้"));
        dealQuotations.refreshDraftContactSnapshot(contactId, firstName != null || lastName != null);
        return updated;
    }
}
