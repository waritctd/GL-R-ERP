package th.co.glr.hr.pricingrequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
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
        Set.of("sales", "import", "ceo", "account", "sales_manager");

    private final PricingRequestRepository requests;
    private final TicketRepository tickets;
    private final NotificationRepository notifications;
    private final ObjectMapper objectMapper;

    public PricingRequestService(PricingRequestRepository requests, TicketRepository tickets,
                                 NotificationRepository notifications, ObjectMapper objectMapper) {
        this.requests      = requests;
        this.tickets       = tickets;
        this.notifications = notifications;
        this.objectMapper  = objectMapper;
    }

    @Transactional
    public PricingRequestDetailDto createDraft(long ticketId, CreatePricingRequestRequest request, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto ticket = requireTicket(ticketId);
        requireActive(ticket);
        if (ticket.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        // Validate BEFORE persisting — an unvalidated value hits a CHECK
        // constraint in the repository and fails closed (500), same reasoning
        // as TicketService.create's Priority guard.
        validateRecipient(request.recipientType());
        validateItems(request.items());
        validateCurrency(request.targetCurrency());
        validateRecipientIdentifiable(request.recipientContactId(), request.recipientLabel());
        validateSourceItemsBelongToTicket(ticketId, request.items());

        String requestCode = requests.nextRequestCode();
        long id = requests.create(ticketId, requestCode, request, actor.id());
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
        return requests.findByTicket(ticketId);
    }

    public List<PricingRequestSummaryDto> list(String status, Long assignedImportId,
                                               boolean activeDealsOnly, UserPrincipal actor) {
        requireRole(actor, VIEWER_ROLES);
        if (status != null && !PricingRequestStatus.isValid(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown status '" + status + "'");
        }
        Long createdByFilter = "sales".equals(actor.role()) ? actor.id() : null;
        return requests.findSummaries(status, assignedImportId, createdByFilter, activeDealsOnly);
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
        if (request.recipientType() != null) {
            validateRecipient(request.recipientType());
        }
        if (request.items() != null) {
            validateItems(request.items());
            validateSourceItemsBelongToTicket(summary.ticketId(), request.items());
        }
        if (request.targetCurrency() != null) {
            validateCurrency(request.targetCurrency());
        }
        // recipientContactId/recipientLabel are a joint invariant (at least one
        // must identify a recipient) — only re-check it when the caller is
        // actually touching one of the two fields; a partial edit of an
        // unrelated field (e.g. requiredDate) must not be forced to resupply
        // the recipient. updateDraft's COALESCE semantics mean a field left
        // null keeps its current value, so merge onto the existing summary
        // before validating.
        if (request.recipientContactId() != null || request.recipientLabel() != null) {
            Long effectiveContactId = request.recipientContactId() != null
                ? request.recipientContactId() : summary.recipientContactId();
            String effectiveLabel = request.recipientLabel() != null
                ? request.recipientLabel() : summary.recipientLabel();
            validateRecipientIdentifiable(effectiveContactId, effectiveLabel);
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
        if (summary.requiredDate() != null && summary.requiredDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ต้องการต้องไม่ใช่วันที่ผ่านมาแล้ว");
        }
        Set<Long> seenSourceItemIds = new HashSet<>();
        for (PricingRequestItemDto item : items) {
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
        notifications.notifyByRole("import", summary.ticketId(), "SUBMITTED",
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
     * @return the number of pricing requests actually cancelled.
     */
    @Transactional
    public int cancelOpenForTicket(long ticketId, String reason, UserPrincipal actor) {
        List<Long> openIds = requests.findOpenIdsForTicket(ticketId);
        String metadataJson = toDeadDealMetadataJson(reason);
        int cancelledCount = 0;
        for (Long id : openIds) {
            // Read each request's own current status to pass as the compare-and-set
            // `expected` value — findOpenIdsForTicket only guarantees status <>
            // CANCELLED, not which of the open statuses each row is actually in.
            PricingRequestSummaryDto summary = requests.findSummary(id).orElse(null);
            if (summary == null) {
                continue;
            }
            int rows = requests.transition(id, summary.status(), PricingRequestStatus.CANCELLED, null, actor.id());
            if (rows == 0) {
                // Raced: something else changed this row between findOpenIdsForTicket
                // and this transition (e.g. the owning rep cancelled it concurrently).
                // Skip it rather than failing the whole deal-terminal cascade. This
                // runs inside the caller's transaction (REQUIRED propagation joins
                // markLost/cancel), so throwing here would roll back the deal's own
                // lost/cancel too — losing a legitimate deal state change over a
                // request someone else has already resolved.
                continue;
            }
            requests.addEvent(id, ticketId, actor.id(), actor.name(),
                PricingRequestEventKind.PRICING_REQUEST_CANCELLED, summary.status(), PricingRequestStatus.CANCELLED,
                reason, metadataJson);
            cancelledCount++;
        }
        return cancelledCount;
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
        if ("sales".equals(actor.role()) && summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return summary;
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
        for (PricingRequestItemRequest item : items) {
            if (!QuantityType.isValid(item.quantityType())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown quantity type '" + item.quantityType() + "'");
            }
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
