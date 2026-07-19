package th.co.glr.hr.pricingrequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CancelPricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.RequestMoreInformationRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.RespondMoreInformationRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestRequest;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Workflow + authz for the PricingRequest aggregate: createDraft, get, listForTicket,
 * list (the Import queue), updateDraft, submit, pickup, requestInformation,
 * respondInformation, cancel, plus the internal {@link #cancelOpenForTicket} cascade
 * invoked by {@code TicketService} when a deal reaches a terminal lifecycle state.
 *
 * <p>Reads {@link TicketRepository} for deal ownership/lifecycle/scoping context
 * only — this class never writes through it. Writing to {@code sales.ticket} /
 * {@code sales.ticket_event} from here would be exactly the coupling
 * {@link PricingRequestRepository}'s class-level Javadoc warns against.
 */
@Service
public class PricingRequestService {
    private static final Logger log = LoggerFactory.getLogger(PricingRequestService.class);

    // Duplicated from th.co.glr.hr.ticket.TicketService's role sets on purpose:
    // TicketService keeps its own copies private, and this is a distinct
    // aggregate's authz, not a side door into ticket workflow rules. Keep the
    // two lists in sync by inspection, not by sharing a mutable reference.
    private static final Set<String> SALES_ROLES  = Set.of("sales");
    private static final Set<String> IMPORT_ROLES = Set.of("import");
    // Mirrors TicketService.VIEWER_ROLES: who may read a pricing request at all.
    // sales_manager stays read-only oversight here too — never add it to
    // SALES_ROLES/IMPORT_ROLES.
    private static final Set<String> VIEWER_ROLES =
        Set.of("sales", "import", "ceo", "sales_manager");
    /** Bounded retry count for {@link #cancelOpenForTicket}'s per-row compare-and-set. */
    private static final int CANCEL_MAX_ATTEMPTS = 3;

    private final PricingRequestRepository requests;
    private final TicketRepository tickets;
    private final NotificationRepository notifications;
    private final ObjectMapper objectMapper;
    private final ContactRepository contacts;

    public PricingRequestService(PricingRequestRepository requests, TicketRepository tickets,
                                 NotificationRepository notifications, ObjectMapper objectMapper,
                                 ContactRepository contacts) {
        this.requests      = requests;
        this.tickets       = tickets;
        this.notifications = notifications;
        this.objectMapper  = objectMapper;
        this.contacts      = contacts;
    }

    @Transactional
    public PricingRequestDetailDto createDraft(long ticketId, CreatePricingRequestRequest request, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto ticket = requireTicket(ticketId);
        if (ticket.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        String clientRequestId = validateClientRequestId(request.clientRequestId());
        PricingRequestSummaryDto existing = existingForClientRequest(actor.id(), clientRequestId);
        if (existing != null) {
            return detail(requireSameTicket(existing, ticketId).id());
        }
        requireActive(ticket);
        // Validate BEFORE persisting — an unvalidated value hits a CHECK
        // constraint in the repository and fails closed (500), same reasoning
        // as TicketService.create's Priority guard.
        validateRecipient(request.recipientType());
        validateItems(request.items());
        validateCurrency(request.targetCurrency());
        validateRecipientIdentifiable(request.recipientContactId(), request.recipientLabel());
        validateRecipientContactBelongsToCustomer(request.recipientContactId(), ticket);
        validateSourceItemsBelongToTicket(ticketId, request.items());

        String requestCode = requests.nextRequestCode();
        long id;
        try {
            id = requests.create(ticketId, requestCode, request, actor.id());
        } catch (DataIntegrityViolationException e) {
            existing = existingForClientRequest(actor.id(), clientRequestId);
            if (existing != null) {
                return detail(requireSameTicket(existing, ticketId).id());
            }
            throw e;
        }
        requests.addEvent(id, ticketId, actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_CREATED, null, PricingRequestStatus.DRAFT, null, null);
        // Deliberately no notification, no ticket status change, no sales_stage
        // change, no ticket_item write — a draft is the rep's private scratchpad
        // until submit().
        return detail(id);
    }

    public PricingRequestDetailDto get(long id, UserPrincipal actor) {
        requireViewable(id, actor);
        return detail(id);
    }

    public List<PricingRequestSummaryDto> listForTicket(long ticketId, UserPrincipal actor) {
        requireRole(actor, VIEWER_ROLES);
        TicketSummaryDto ticket = requireTicket(ticketId);
        if ("sales".equals(actor.role()) && ticket.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        // Separate read path from findSummaries/list — must apply the same DRAFT
        // privacy rule so a request that's still a draft never leaks through the
        // per-ticket view either (e.g. to import/account before the rep submits it).
        return requests.findByTicket(ticketId).stream()
            .filter(summary -> !PricingRequestStatus.DRAFT.equals(summary.status()) || canSeeDraft(actor, summary))
            .toList();
    }

    public List<PricingRequestSummaryDto> list(String status, Long assignedImportId,
                                               boolean activeDealsOnly, UserPrincipal actor) {
        requireRole(actor, VIEWER_ROLES);
        if (status != null && !PricingRequestStatus.isValid(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown status '" + status + "'");
        }
        Long createdByFilter = "sales".equals(actor.role()) ? actor.id() : null;
        boolean draftOversight = "ceo".equals(actor.role()) || "sales_manager".equals(actor.role());
        return requests.findSummaries(status, assignedImportId, createdByFilter, activeDealsOnly,
            draftOversight, actor.id());
    }

    @Transactional
    public PricingRequestDetailDto updateDraft(long id, UpdatePricingRequestRequest request, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        PricingRequestSummaryDto summary = requireViewable(id, actor);
        if (summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (!PricingRequestStatus.DRAFT.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Expected status 'DRAFT' but pricing request is '" + summary.status() + "'");
        }
        TicketSummaryDto ticket = requireTicket(summary.ticketId());
        requireActive(ticket);

        // PricingRequestRepository.updateDraft is now a FULL REPLACEMENT of
        // the draft's editable fields (COALESCE dropped — see its Javadoc):
        // the request represents the complete new state, not a sparse patch,
        // so every editable field is validated unconditionally here, the same
        // way createDraft validates its (also complete) payload — there is no
        // more "only re-check what the caller touched". recipient_type is
        // additionally NOT NULL in the DB, so a blank/missing one must be
        // rejected here as a 400, before it can reach the repository and fail
        // as a raw constraint violation (500).
        if (request.recipientType() == null || request.recipientType().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "recipientType must not be blank");
        }
        validateRecipient(request.recipientType());
        validateRecipientIdentifiable(request.recipientContactId(), request.recipientLabel());
        validateRecipientContactBelongsToCustomer(request.recipientContactId(), ticket);
        validateCurrency(request.targetCurrency());
        if (request.items() != null) {
            validateItems(request.items());
            validateSourceItemsBelongToTicket(summary.ticketId(), request.items());
        }

        boolean updated = requests.updateDraft(id, request);
        if (!updated) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request was changed by another user");
        }
        requests.addEvent(id, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_UPDATED, PricingRequestStatus.DRAFT, PricingRequestStatus.DRAFT,
            null, null);
        return detail(id);
    }

    @Transactional
    public PricingRequestDetailDto submit(long id, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        PricingRequestSummaryDto summary = requireViewable(id, actor);
        if (summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (!PricingRequestStatus.DRAFT.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Expected status 'DRAFT' but pricing request is '" + summary.status() + "'");
        }
        TicketSummaryDto ticket = requireTicket(summary.ticketId());
        requireActive(ticket);

        List<PricingRequestItemDto> items = requests.findItems(id);
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องมีรายการสินค้าอย่างน้อย 1 รายการก่อนส่งคำขอราคา");
        }
        if (summary.recipientContactId() == null
                && (summary.recipientLabel() == null || summary.recipientLabel().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุผู้รับคำขอราคา");
        }
        // Re-check against the PERSISTED recipientContactId, not a request
        // payload — submit() takes no body, so this is the only re-validation
        // point. Same reasoning as the item-identity recheck below: a draft
        // created before this rule existed (or before its recipient was
        // last touched) must not be submittable while pointing at another
        // customer's contact.
        validateRecipientContactBelongsToCustomer(summary.recipientContactId(), ticket);
        if (summary.requiredDate() != null && summary.requiredDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ต้องการต้องไม่ใช่วันที่ผ่านมาแล้ว");
        }
        // Re-check item identity against the PERSISTED items, not the
        // createDraft/updateDraft payload that produced them — validateItems
        // only runs before a write, so a draft created before that rule
        // existed (or one whose items were never touched again) must still
        // be blocked here, at the one point before a request becomes visible
        // to Import.
        Set<Long> seenSourceItemIds = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            PricingRequestItemDto item = items.get(i);
            if (!isProductIdentified(item.sourceTicketItemId(), item.productId(), item.model(), item.productDescription())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, identityErrorMessage(i));
            }
            if (item.sourceTicketItemId() != null && !seenSourceItemIds.add(item.sourceTicketItemId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "มีรายการอ้างอิงสินค้าเดิมซ้ำกัน");
            }
        }

        int rows = requests.transition(id, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null);
        if (rows == 0) {
            // Compare-and-set miss: someone else changed this row between the
            // requireViewable() read above and this call. Don't re-query to
            // build a nicer message — see PricingRequestRepository.transition's
            // Javadoc for why.
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request was updated by another user");
        }
        requests.addEvent(id, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_SUBMITTED, PricingRequestStatus.DRAFT,
            PricingRequestStatus.SUBMITTED, null, null);
        // NotificationRepository.notifyByRole hardcodes link = "/tickets/{ticketId}"
        // (there is no pricing-request-specific route yet), so this deep-links to
        // the deal page rather than the request itself. Known limitation — a
        // request-specific deep link is a follow-up, not part of this branch.
        // Type is "PRICING_REQUEST_SUBMITTED", NOT "SUBMITTED" — the latter is
        // also TicketEventKind.SUBMITTED, which would make a pricing-request
        // notification indistinguishable from a ticket-submitted one in
        // hr.notification.type (no DB CHECK constraint on that column, so a new
        // value here is safe).
        notifications.notifyByRole("import", summary.ticketId(), "PRICING_REQUEST_SUBMITTED",
            "ใบขอราคา " + summary.requestCode() + " รอการรับเรื่อง");
        return detail(id);
    }

    @Transactional
    public PricingRequestDetailDto pickup(long id, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        PricingRequestSummaryDto summary = requireViewable(id, actor);
        if (!PricingRequestStatus.SUBMITTED.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a submitted pricing request can be picked up");
        }
        TicketSummaryDto ticket = requireTicket(summary.ticketId());
        requireActive(ticket);
        // CRITICAL: this assigns the Import employee to the PRICING REQUEST only,
        // never to sales.ticket.assigned_to. TicketRepository.addEventInternal sets
        // assigned_to as a side-effect of any PICKED_UP event ON THE TICKET; this
        // flow deliberately never routes through TicketRepository's write methods
        // (see that class's and PricingRequestRepository's class-level Javadoc), so
        // two pricing requests on the same deal can be picked up by two different
        // Import employees without either stealing the other's — or the whole
        // deal's — assignment.
        int rows = requests.transition(id, PricingRequestStatus.SUBMITTED, PricingRequestStatus.IMPORT_REVIEWING,
            actor.id(), null);
        if (rows == 0) {
            // Compare-and-set miss: someone else already picked this up between
            // requireViewable()'s read and here.
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request was already picked up");
        }
        requests.addEvent(id, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_PICKED_UP, PricingRequestStatus.SUBMITTED,
            PricingRequestStatus.IMPORT_REVIEWING, null, null);
        notifications.notifyEmployee(summary.requestedById(), summary.ticketId(), "PICKED_UP",
            "ใบขอราคา " + summary.requestCode() + " ถูกรับเรื่องแล้ว");
        return detail(id);
    }

    @Transactional
    public PricingRequestDetailDto requestInformation(long id, RequestMoreInformationRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        PricingRequestSummaryDto summary = requireViewable(id, actor);
        // Only the Import employee already assigned to THIS request may ask for
        // more information. An unassigned Import user must call pickup() first —
        // which is also what makes them the assignee checked here.
        if (summary.assignedImportId() == null || summary.assignedImportId() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (!PricingRequestStatus.IMPORT_REVIEWING.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Expected status 'IMPORT_REVIEWING' but pricing request is '" + summary.status() + "'");
        }
        TicketSummaryDto ticket = requireTicket(summary.ticketId());
        requireActive(ticket);
        int rows = requests.transition(id, PricingRequestStatus.IMPORT_REVIEWING, PricingRequestStatus.MORE_INFO_REQUIRED,
            null, null);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request was updated by another user");
        }
        requests.addEvent(id, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.MORE_INFO_REQUESTED, PricingRequestStatus.IMPORT_REVIEWING,
            PricingRequestStatus.MORE_INFO_REQUIRED, request.message(), toDueDateMetadataJson(request.dueDate()));
        notifications.notifyEmployee(summary.requestedById(), summary.ticketId(), "MORE_INFO_REQUIRED",
            "ใบขอราคา " + summary.requestCode() + " ต้องการข้อมูลเพิ่มเติม");
        return detail(id);
    }

    @Transactional
    public PricingRequestDetailDto respondInformation(long id, RespondMoreInformationRequest request, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        PricingRequestSummaryDto summary = requireViewable(id, actor);
        if (summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (!PricingRequestStatus.MORE_INFO_REQUIRED.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Expected status 'MORE_INFO_REQUIRED' but pricing request is '" + summary.status() + "'");
        }
        TicketSummaryDto ticket = requireTicket(summary.ticketId());
        requireActive(ticket);
        // Goes back to IMPORT_REVIEWING, NOT SUBMITTED — Import already owns this
        // request. The repository's transition() COALESCE guards mean
        // assigned_import_id and picked_up_at survive this round trip unchanged;
        // this method does not (and must not) re-supply them.
        int rows = requests.transition(id, PricingRequestStatus.MORE_INFO_REQUIRED, PricingRequestStatus.IMPORT_REVIEWING,
            null, null);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request was updated by another user");
        }
        requests.addEvent(id, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.MORE_INFO_RESPONDED, PricingRequestStatus.MORE_INFO_REQUIRED,
            PricingRequestStatus.IMPORT_REVIEWING, request.response(), null);
        // Guard against a null assignee: NotificationRepository.notifyEmployee takes
        // a primitive long, not a Long, so this cannot be skipped by a null check
        // inside the call itself. Should not happen once a request has been through
        // pickup(), but a defensive check here costs nothing.
        if (summary.assignedImportId() != null) {
            notifications.notifyEmployee(summary.assignedImportId(), summary.ticketId(), "MORE_INFO_RESPONDED",
                "ใบขอราคา " + summary.requestCode() + " ได้รับข้อมูลเพิ่มเติมแล้ว");
        }
        return detail(id);
    }

    @Transactional
    public PricingRequestDetailDto cancel(long id, CancelPricingRequestRequest request, UserPrincipal actor) {
        PricingRequestSummaryDto summary = requireViewable(id, actor);
        // Deliberately NO requireTicket/requireActive here (unlike updateDraft/
        // requestInformation/respondInformation): a request on a dead deal
        // (ON_HOLD/DORMANT/etc.) must still be cancellable — that is the one
        // mutation that should always be available on a stalled deal, not blocked
        // by it. Do not add a lifecycle gate to this method.
        // Mirrors TicketService.cancel: ownership is the gate, not a role set —
        // extended here with an explicit CEO override (unlike TicketService.cancel,
        // which currently has none) so a manager can unwind an abandoned draft
        // without needing the original sales rep's session.
        if (!"ceo".equals(actor.role()) && summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (!PricingRequestStatus.canTransition(summary.status(), PricingRequestStatus.CANCELLED)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Cannot cancel pricing request in status '" + summary.status() + "'");
        }
        int rows = requests.transition(id, summary.status(), PricingRequestStatus.CANCELLED, null, actor.id());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request was updated by another user");
        }
        String metadataJson = toReasonMetadataJson(request.reason());
        requests.addEvent(id, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_CANCELLED, summary.status(), PricingRequestStatus.CANCELLED,
            request.reason(), metadataJson);
        return detail(id);
    }

    /**
     * Internal cascade: when a deal reaches a terminal lifecycle state (lost or
     * cancelled — see {@code TicketService.markLost}/{@code cancel}), any pricing
     * requests still open on it can never be priced, so they are cancelled here
     * too rather than left stranded in the Import queue forever.
     *
     * <p><strong>No role check on purpose.</strong> This is not a user-facing
     * endpoint — it is invoked by an already-authorised ticket action (the caller
     * has already passed {@code TicketService}'s own gate for markLost/cancel), and
     * re-deriving a pricing-request-specific role check here would either reject a
     * legitimate cascade (the triggering actor may be sales, not import — pricing
     * requests are normally only cancellable by import/CEO-adjacent flows) or
     * require threading a bypass flag through. Do NOT add a controller endpoint for
     * this method.
     *
     * <p>Bypasses {@link PricingRequestStatus#canTransition} on purpose: the normal
     * state machine forbids IMPORT_REVIEWING → CANCELLED directly (only DRAFT,
     * SUBMITTED and MORE_INFO_REQUIRED may cancel per {@code cancel()}'s check), but
     * a dead deal must be able to kill a request in ANY open status, including
     * IMPORT_REVIEWING — the normal restriction exists to protect a live workflow,
     * which no longer exists once the deal itself is terminal.
     *
     * <p>Each row's compare-and-set is retried up to {@value #CANCEL_MAX_ATTEMPTS}
     * times against a freshly-read status before being given up on — a single
     * raced miss (something else changed the row between the read and the
     * transition) is common enough under concurrent access that giving up on the
     * first attempt would routinely leave a request stranded open on a dead deal.
     * A row already found CANCELLED (by whatever raced it) counts as settled, not
     * abandoned — the outcome we wanted already holds. Exhausting every attempt
     * without settling is logged as a warning (this method has no caller that
     * inspects the return value today, so the log is the only operator-visible
     * signal) and the id is reported back in {@link CancelOpenForTicketResult
     * #abandonedIds()} so a future caller can act on it without a plain count
     * silently swallowing the distinction between "nothing was open" and
     * "gave up on some".
     *
     * @return cancelled/abandoned counts — never throws for a row that could not
     *         be settled, so the caller's own deal-terminal transaction still commits.
     */
    @Transactional
    public CancelOpenForTicketResult cancelOpenForTicket(long ticketId, String reason, UserPrincipal actor) {
        // Deliberately NO requireTicket/requireActive here: this method exists
        // BECAUSE the deal just left ACTIVE (see the class Javadoc above) — a
        // lifecycle gate would make the one caller that needs this cascade fail
        // every time it runs. Do not add one.
        List<Long> openIds = requests.findOpenIdsForTicket(ticketId);
        String metadataJson = toDeadDealMetadataJson(reason);
        int cancelledCount = 0;
        List<Long> abandonedIds = new ArrayList<>();
        for (Long id : openIds) {
            boolean settled = false;
            for (int attempt = 1; attempt <= CANCEL_MAX_ATTEMPTS; attempt++) {
                // Read each request's own current status fresh on every attempt to pass
                // as the compare-and-set `expected` value — findOpenIdsForTicket only
                // guarantees status <> CANCELLED at the time it ran, not which of the
                // open statuses each row is still in by the time we get here.
                PricingRequestSummaryDto summary = requests.findSummary(id).orElse(null);
                if (summary == null) {
                    // Row is gone entirely — nothing left to cancel.
                    settled = true;
                    break;
                }
                if (PricingRequestStatus.CANCELLED.equals(summary.status())) {
                    // Already cancelled by whatever raced us — the wanted end state
                    // already holds. Not a new cancellation of ours, so it does not
                    // add to cancelledCount, but it is settled, not abandoned.
                    settled = true;
                    break;
                }
                int rows = requests.transition(id, summary.status(), PricingRequestStatus.CANCELLED, null, actor.id());
                if (rows == 1) {
                    requests.addEvent(id, ticketId, actor.id(), actor.name(),
                        PricingRequestEventKind.PRICING_REQUEST_CANCELLED, summary.status(), PricingRequestStatus.CANCELLED,
                        reason, metadataJson);
                    cancelledCount++;
                    settled = true;
                    break;
                }
                // Raced: something else changed this row between the read above and
                // this transition (e.g. the owning rep cancelled it concurrently).
                // Retry with a fresh read rather than giving up on the first miss —
                // this runs inside the caller's transaction (REQUIRED propagation
                // joins markLost/cancel), so throwing here would roll back the deal's
                // own lost/cancel too, which must never happen over a pricing request
                // someone else is concurrently touching.
                log.warn("cancelOpenForTicket: transition raced on pricing request {} (ticket {}, attempt {}/{}); retrying",
                    id, ticketId, attempt, CANCEL_MAX_ATTEMPTS);
            }
            if (!settled) {
                abandonedIds.add(id);
                log.warn("cancelOpenForTicket: gave up cancelling pricing request {} for ticket {} after {} attempts (reason={}) — it remains open",
                    id, ticketId, CANCEL_MAX_ATTEMPTS, reason);
            }
        }
        return new CancelOpenForTicketResult(cancelledCount, abandonedIds);
    }

    /**
     * Result of {@link #cancelOpenForTicket}: distinguishes "nothing was open" /
     * "cancelled everything that was open" from "gave up on N requests after
     * retrying" — a plain {@code int} count cannot express that distinction, which
     * matters because an abandoned row is left open on an otherwise-dead deal.
     */
    public record CancelOpenForTicketResult(int cancelledCount, List<Long> abandonedIds) {
        public CancelOpenForTicketResult {
            abandonedIds = List.copyOf(abandonedIds);
        }

        public int abandonedCount() {
            return abandonedIds.size();
        }

        public boolean hasAbandoned() {
            return !abandonedIds.isEmpty();
        }
    }

    // --- private helpers ---

    private String toReasonMetadataJson(String reason) {
        try {
            return objectMapper.writeValueAsString(Map.of("reason", reason));
        } catch (JsonProcessingException e) {
            // reason is @NotBlank String — a plain string can never actually fail
            // Jackson serialisation, but the checked exception must still be
            // handled rather than escaping as an unhandled 500.
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid cancel reason");
        }
    }

    private String toDueDateMetadataJson(LocalDate dueDate) {
        // Unlike cancel's reason (always present, @NotBlank), dueDate is optional —
        // omit metadata entirely rather than serialising a JSON null, matching
        // addEvent's null-metadata convention (COALESCE(...,'{}'::jsonb) on read).
        if (dueDate == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of("dueDate", dueDate));
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid due date");
        }
    }

    private String toDeadDealMetadataJson(String reason) {
        try {
            return objectMapper.writeValueAsString(Map.of("reason", reason, "cause", "DEAL_TERMINAL"));
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid cancel reason");
        }
    }

    private PricingRequestDetailDto detail(long id) {
        PricingRequestSummaryDto summary = requests.findSummary(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pricing request not found"));
        return new PricingRequestDetailDto(summary, requests.findItems(id), requests.findEvents(id));
    }

    /**
     * The one read-access rule for a single pricing request: viewer role
     * required, and sales reps only see requests on tickets they created.
     * Every read path (get, updateDraft, submit, cancel) must go through this.
     */
    private PricingRequestSummaryDto requireViewable(long id, UserPrincipal actor) {
        requireRole(actor, VIEWER_ROLES);
        PricingRequestSummaryDto summary = requests.findSummary(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pricing request not found"));
        // A DRAFT is the rep's private scratchpad (see this class's Javadoc) — only
        // the owning sales rep and managerial oversight (ceo/sales_manager) may see
        // it. import/account must not, even though they can see every other status.
        // Respond 404, NOT 403: a 403 here would confirm to a non-owner that a
        // pricing request with this id exists in SOME status, letting them probe
        // ids to enumerate other reps' in-flight drafts. 404 is indistinguishable
        // from "no such id", which is what we want a non-owner to see.
        if (PricingRequestStatus.DRAFT.equals(summary.status()) && !canSeeDraft(actor, summary)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Pricing request not found");
        }
        if ("sales".equals(actor.role()) && summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return summary;
    }

    /** Who may see a request while it is still in DRAFT status — see requireViewable. */
    private boolean canSeeDraft(UserPrincipal actor, PricingRequestSummaryDto summary) {
        return summary.ticketCreatedById() == actor.id()
            || "ceo".equals(actor.role())
            || "sales_manager".equals(actor.role());
    }

    private TicketSummaryDto requireTicket(long ticketId) {
        return tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"))
            .summary();
    }

    private void requireActive(TicketSummaryDto ticket) {
        if (!DealLifecycle.ACTIVE.equals(ticket.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลไม่ได้อยู่ในสถานะ ACTIVE (" + ticket.lifecycle() + ") จึงสร้าง/แก้ไขคำขอราคาไม่ได้");
        }
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void validateRecipient(String recipientType) {
        if (!PricingRequestRecipient.isValid(recipientType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown recipient type '" + recipientType + "'");
        }
    }

    private void validateItems(List<PricingRequestItemRequest> items) {
        for (int i = 0; i < items.size(); i++) {
            PricingRequestItemRequest item = items.get(i);
            if (!QuantityType.isValid(item.quantityType())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown quantity type '" + item.quantityType() + "'");
            }
            if (!isProductIdentified(item.sourceTicketItemId(), item.productId(), item.model(), item.productDescription())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, identityErrorMessage(i));
            }
        }
    }

    /**
     * Shared identity predicate for product identity:
     * an item must actually name a product somehow — an existing deal line,
     * a catalog reference, a model name, or a dedicated product description.
     * Brand alone is deliberately NOT sufficient (a brand with no model does
     * not identify a product), so this checks the other four fields only.
     *
     * <p>Called from both {@link #validateItems} (the payload-shaped
     * {@link PricingRequestItemRequest}, pre-persist) and {@link #submit}
     * (the persisted {@link PricingRequestItemDto}) — the two are different
     * record types with no shared interface, so callers extract the four
     * relevant fields themselves rather than this method taking either DTO.
     */
    private static boolean isProductIdentified(Long sourceTicketItemId, Long productId, String model, String productDescription) {
        return sourceTicketItemId != null || productId != null || hasText(model) || hasText(productDescription);
    }

    private String validateClientRequestId(String clientRequestId) {
        if (!hasText(clientRequestId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId must be a UUID");
        }
        try {
            return UUID.fromString(clientRequestId).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId must be a UUID");
        }
    }

    private PricingRequestSummaryDto existingForClientRequest(long requestedBy, String clientRequestId) {
        var existing = requests.findByClientRequestId(requestedBy, clientRequestId);
        return existing == null ? null : existing.orElse(null);
    }

    private PricingRequestSummaryDto requireSameTicket(PricingRequestSummaryDto existing, long ticketId) {
        if (existing.ticketId() != ticketId) {
            throw new ApiException(HttpStatus.CONFLICT,
                "clientRequestId has already been used for a different ticket");
        }
        return existing;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String identityErrorMessage(int zeroBasedIndex) {
        return "รายการที่ " + (zeroBasedIndex + 1)
            + ": ต้องระบุสินค้าที่ต้องการเสนอราคา (เลือกจากรายการในดีล หรือระบุรุ่น/รายละเอียด)";
    }

    /**
     * Part 2 of the review-remediation plan: a {@code recipientContactId}
     * must belong to the SAME customer as the deal itself — otherwise a
     * pricing request on Customer A's deal could name Customer B's contact,
     * which would later put the wrong recipient on a quotation.
     *
     * <p>Skips the comparison when {@code ticket.customerId()} is null (an
     * older deal with no customer link) rather than throwing — there is no
     * customer to compare against, so this is "nothing to check", NOT "any
     * contact is fine". A contact id that does not resolve to any row at all
     * is treated the same as a mismatch: both are a 400, since neither could
     * possibly belong to the deal's customer.
     */
    private void validateRecipientContactBelongsToCustomer(Long recipientContactId, TicketSummaryDto ticket) {
        if (recipientContactId == null || ticket.customerId() == null) {
            return;
        }
        ContactDto contact = contacts.findById(recipientContactId).orElse(null);
        if (contact == null || contact.customerId() != ticket.customerId()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ผู้รับที่เลือกไม่ได้อยู่ในลูกค้าของดีลนี้");
        }
    }

    private void validateCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return;
        }
        if (currency.trim().length() != 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "targetCurrency must be a 3-letter currency code");
        }
    }

    private void validateRecipientIdentifiable(Long recipientContactId, String recipientLabel) {
        if (recipientContactId == null && (recipientLabel == null || recipientLabel.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ต้องระบุผู้รับคำขอราคา (recipientContactId หรือ recipientLabel)");
        }
    }

    private void validateSourceItemsBelongToTicket(long ticketId, List<PricingRequestItemRequest> items) {
        List<Long> validItemIds = requests.findItemIdsForTicket(ticketId);
        for (PricingRequestItemRequest item : items) {
            Long sourceTicketItemId = item.sourceTicketItemId();
            if (sourceTicketItemId != null && !validItemIds.contains(sourceTicketItemId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "sourceTicketItemId " + sourceTicketItemId + " does not belong to ticket " + ticketId);
            }
        }
    }
}
