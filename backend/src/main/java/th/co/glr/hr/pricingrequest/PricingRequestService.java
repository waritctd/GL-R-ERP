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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteCarryForward;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestAttachmentDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CancelPricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CustomerChangeRevisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestAttachmentRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestRequest;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Workflow + authz for the PricingRequest aggregate: createDraft, get, listForTicket,
 * list (the Import queue), updateDraft, submit, pickup, cancel, plus the internal {@link #cancelOpenForTicket} cascade
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
    /**
     * Statuses in which Sales may upload/delete a Pricing Request attachment (V69, review
     * remediation COMMIT 4). A DRAFT is the rep's own scratchpad. Once past it, the request has
     * moved into Import/CEO territory and its attachment set should stop changing out from under
     * whatever email draft or costing review is already in flight.
     *
     * <p>V140 narrowed this from {DRAFT, MORE_INFO_REQUIRED} to {DRAFT} — a consequence of
     * removing the ขอข้อมูลเพิ่มเติม round-trip, not a separate decision. That was the one state
     * where Sales was expected to add material AFTER submitting; with it gone, Sales attaches
     * while drafting or creates a revision.
     */
    private static final Set<String> ATTACHMENT_EDITABLE_STATUSES =
        Set.of(PricingRequestStatus.DRAFT);

    private final PricingRequestRepository requests;
    private final TicketRepository tickets;
    private final NotificationRepository notifications;
    private final ObjectMapper objectMapper;
    private final ContactRepository contacts;
    private final FileStorageService fileStorage;
    private final FactoryQuoteCarryForward factoryQuoteCarryForward;

    public PricingRequestService(PricingRequestRepository requests, TicketRepository tickets,
                                 NotificationRepository notifications, ObjectMapper objectMapper,
                                 ContactRepository contacts, FileStorageService fileStorage,
                                 FactoryQuoteCarryForward factoryQuoteCarryForward) {
        this.requests      = requests;
        this.tickets       = tickets;
        this.notifications = notifications;
        this.objectMapper  = objectMapper;
        this.contacts      = contacts;
        this.fileStorage   = fileStorage;
        this.factoryQuoteCarryForward = factoryQuoteCarryForward;
    }

    @Transactional
    public PricingRequestDetailDto createDraft(long ticketId, CreatePricingRequestRequest request, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto ticket = requireTicket(ticketId);
        if (ticket.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
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
        long id = requests.create(ticketId, requestCode, request, actor.id());
        if (id == 0L) {
            existing = existingForClientRequest(actor.id(), clientRequestId);
            if (existing != null) {
                return detail(requireSameTicket(existing, ticketId).id());
            }
            throw new ApiException(HttpStatus.CONFLICT, "clientRequestId นี้ถูกใช้ไปแล้ว");
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
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับสถานะ '" + status + "'");
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
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if (!PricingRequestStatus.DRAFT.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ต้องเป็นคำขอราคาที่อยู่ในสถานะ 'DRAFT' เท่านั้น (สถานะปัจจุบัน: '" + summary.status() + "')");
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "recipientType ต้องไม่เว้นว่าง");
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
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
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
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if (!PricingRequestStatus.DRAFT.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ต้องเป็นคำขอราคาที่อยู่ในสถานะ 'DRAFT' เท่านั้น (สถานะปัจจุบัน: '" + summary.status() + "')");
        }
        TicketSummaryDto ticket = requireTicket(summary.ticketId());
        requireActive(ticket);

        List<PricingRequestItemDto> items = requests.findItems(id);
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องมีรายการสินค้าอย่างน้อย 1 รายการก่อนส่งคำขอราคา");
        }
        List<Long> unresolvableCatalogItems = requests.findUnresolvableCatalogItemIds(id);
        if (!unresolvableCatalogItems.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ไม่พบสินค้าใน price catalog ที่ active สำหรับรายการ: " + unresolvableCatalogItems);
        }
        requests.snapshotCatalogSelections(id);
        items = requests.findItems(id);
        // REMOVED 2026-08-11 (owner request): "Finding A" (financial-integrity review, commit 3)
        // used to require a fully-populated catalog snapshot on EVERY line here, 422-ing with
        // "ต้องเลือกสินค้าจาก Price Catalog ที่ active ก่อนส่งคำขอราคา" otherwise. That made the
        // catalogue mandatory, which blocked the case a คำขอราคา exists to serve: asking Import to
        // price a product that is NOT in the catalogue yet. A free-text line may now be submitted
        // with a null catalog snapshot, and Import quotes it from scratch.
        //
        // What still protects the money path, so this is a narrowing rather than a hole:
        //   - findUnresolvableCatalogItemIds above (kept) still rejects a DANGLING reference — an
        //     item whose product_id points at a missing or non-ACTIVE price. That is a data fault,
        //     not a new product, and is a different thing from having no reference at all.
        //   - FactoryQuoteService.groupByFactory 422s ("ยังไม่ได้ระบุโรงงาน") if a line reaches the
        //     factory-email step with neither a resolved nor a free-text factory, so a line with no
        //     routable factory is still stopped — just later, and only when it actually matters.
        //   - Downstream reads already tolerate a null snapshot: FactoryQuoteService and
        //     PricingCostingService both resolve the factory via
        //     firstText(resolvedFactoryName, factory), and resolvedFactoryId is collected with
        //     filter(nonNull)...orElse(null).
        // The trade-off is real and deliberate: an item submitted this way carries NO preliminary
        // base price, so Import receives it un-anchored.
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
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        requests.addEvent(id, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_SUBMITTED, PricingRequestStatus.DRAFT,
            PricingRequestStatus.SUBMITTED, null, null);
        if (carryFactoryQuotesForwardOnSubmit(summary, actor)) {
            return detail(id);
        }
        notifications.notifyByRoleForPricingRequest("import", summary.id(), "PRICING_REQUEST_SUBMITTED",
            "คำขอราคา " + summary.requestCode() + " รอการรับเรื่อง");
        notifyCeo(summary, PricingRequestEventKind.PRICING_REQUEST_SUBMITTED,
            "คำขอราคา " + summary.requestCode() + " ถูกส่งเข้าสู่ Pricing workflow");
        return detail(id);
    }

    /**
     * Reissue-through-CEO-chain (owner ruling 2026-08-13): a customer-change revision that changes
     * only the commercial terms — same products, same requested quantities — reuses its parent's
     * factory quotes and goes straight to the CEO instead of round-tripping through Import for
     * prices nobody disputed.
     *
     * <p>Runs at SUBMIT time, not at revision-creation time, and that timing is load-bearing:
     * {@code sales.factory_quote_item.pricing_request_item_id} is {@code ON DELETE RESTRICT}, while
     * {@code updateDraft} replaces a draft's items with a DELETE + re-INSERT. Copying quotes onto a
     * still-DRAFT child would therefore make the child's own items undeletable and turn the next
     * {@code updateDraft} into a raw constraint violation. After submit the items are frozen, so
     * there is nothing to collide with.
     *
     * <p>Deliberately fails OPEN, in the sense that every "no" lands the request on the ordinary
     * Import path rather than raising: the shortcut is an optimisation, and refusing to take it is
     * always safe. It is also placed AFTER the submit event above, so the audit trail reads
     * submitted-then-advanced rather than inventing a submit that never happened.
     *
     * @return true when the request was advanced to READY_FOR_CEO_REVIEW.
     */
    private boolean carryFactoryQuotesForwardOnSubmit(PricingRequestSummaryDto summary, UserPrincipal actor) {
        PricingRequestSummaryDto submitted = requests.findSummary(summary.id()).orElse(null);
        if (submitted == null || !factoryQuoteCarryForward.carryForwardOnSubmit(submitted, actor.id())) {
            return false;
        }
        // The advance itself. SUBMITTED -> READY_FOR_CEO_REVIEW is declared in
        // PricingRequestStatus.ALLOWED specifically for this path. A compare-and-set miss here is
        // not a user-facing error: the request is validly SUBMITTED either way, so fall back to
        // the Import path rather than failing a submit that already succeeded.
        int transitioned = requests.transition(summary.id(), PricingRequestStatus.SUBMITTED,
            PricingRequestStatus.READY_FOR_CEO_REVIEW, null, null);
        if (transitioned == 0) {
            return false;
        }
        // Same event kind FactoryQuoteService.markReadyForCosting emits for the same transition,
        // so "became ready for CEO review" reads as one consistent trail regardless of which path
        // produced it.
        requests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_COSTING_SUBMITTED, PricingRequestStatus.SUBMITTED,
            PricingRequestStatus.READY_FOR_CEO_REVIEW,
            "Factory quotes carried forward unchanged from the superseded pricing request "
                + "— advanced for CEO review without a new Import round-trip", null);
        notifyCeo(summary, PricingRequestEventKind.PRICING_COSTING_SUBMITTED,
            "คำขอราคา " + summary.requestCode() + " พร้อมให้ CEO พิจารณาราคาแล้ว (ใช้ราคาโรงงานเดิม)");
        return true;
    }

    @Transactional
    public PricingRequestDetailDto pickup(long id, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        PricingRequestSummaryDto summary = requireViewable(id, actor);
        if (!PricingRequestStatus.SUBMITTED.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "รับเรื่องได้เฉพาะคำขอราคาที่ถูกยื่นแล้วเท่านั้น");
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
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคานี้ถูกรับเรื่องไปแล้วโดยผู้ใช้อื่น");
        }
        requests.addEvent(id, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_PICKED_UP, PricingRequestStatus.SUBMITTED,
            PricingRequestStatus.IMPORT_REVIEWING, null, null);
        notifications.notifyEmployeeForPricingRequest(summary.requestedById(), summary.id(), "PICKED_UP",
            "คำขอราคา " + summary.requestCode() + " ถูกรับเรื่องแล้ว");
        notifyCeo(summary, PricingRequestEventKind.PRICING_REQUEST_PICKED_UP,
            "คำขอราคา " + summary.requestCode() + " ถูกรับเรื่องโดย Import");
        return detail(id);
    }

    @Transactional
    public PricingRequestDetailDto cancel(long id, CancelPricingRequestRequest request, UserPrincipal actor) {
        PricingRequestSummaryDto summary = requireViewable(id, actor);
        // Deliberately NO requireTicket/requireActive here (unlike updateDraft): a
        // request on a dead deal
        // (ON_HOLD/DORMANT/etc.) must still be cancellable — that is the one
        // mutation that should always be available on a stalled deal, not blocked
        // by it. Do not add a lifecycle gate to this method.
        // Mirrors TicketService.cancel: ownership is the gate, not a role set —
        // extended here with an explicit CEO override (unlike TicketService.cancel,
        // which currently has none) so a manager can unwind an abandoned draft
        // without needing the original sales rep's session.
        if (!"ceo".equals(actor.role()) && summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if (!PricingRequestStatus.canTransition(summary.status(), PricingRequestStatus.CANCELLED)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ไม่สามารถยกเลิกคำขอราคาที่อยู่ในสถานะ '" + summary.status() + "' ได้");
        }
        int rows = requests.transition(id, summary.status(), PricingRequestStatus.CANCELLED, null, actor.id());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        requests.cancelOpenChildrenForDeadRequest(id, request.reason(), actor.id());
        // Cancel cutoff (owner ruling 2026-08-13): this call USED to be dead defensive code, and
        // the comment here said so — canTransition only reached cancel() from
        // DRAFT/SUBMITTED/IMPORT_REVIEWING/AWAITING_FACTORY_RESPONSE, none of which can co-exist
        // with an open pricing_decision (that needs READY_FOR_CEO_REVIEW+) or a quotation (that
        // needs APPROVED_FOR_QUOTATION). The widened cutoff makes it live on the main path: a
        // cancel from CEO_REVIEWING retires a DRAFT decision, and one from APPROVED_FOR_QUOTATION
        // retires an APPROVED decision plus any DRAFT quotation built from it. The prediction the
        // old comment made — "a future widening of that map cannot silently reintroduce design
        // correction 1's bug" — is exactly what is being cashed in here.
        requests.supersedeOpenPricingDecisionAndQuotation(id);
        String metadataJson = toReasonMetadataJson(request.reason());
        requests.addEvent(id, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_CANCELLED, summary.status(), PricingRequestStatus.CANCELLED,
            request.reason(), metadataJson);
        notifyCeo(summary, PricingRequestEventKind.PRICING_REQUEST_CANCELLED,
            "คำขอราคา " + summary.requestCode() + " ถูกยกเลิก");
        return detail(id);
    }

    @Transactional
    public PricingRequestDetailDto createCustomerChangeRevision(long id, CustomerChangeRevisionRequest request,
                                                                UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        PricingRequestSummaryDto parent = requireViewable(id, actor);
        if (parent.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        // Reissue-through-CEO-chain (owner ruling 2026-08-13): the state machine is now the ONLY
        // authority on which statuses a customer-change revision may start from, replacing the
        // hand-maintained {DRAFT, CANCELLED, SUPERSEDED} denylist that used to live here. That
        // denylist and PricingRequestStatus.ALLOWED had drifted apart in both directions, and
        // PricingRequestRepository.supersedeForCustomerRevision consulted neither. Deriving the
        // gate from canTransition means this 409 and the repository's IllegalStateException can
        // never disagree about what is legal.
        //
        // QUOTATION_ACCEPTED gets its own message because it is the case that CHANGED: it used to
        // be reachable and is now refused. Once the customer has accepted, changing the deal is an
        // order amendment, not a quotation revision.
        if (PricingRequestStatus.QUOTATION_ACCEPTED.equals(parent.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ลูกค้ายอมรับใบเสนอราคาแล้ว จึงแก้ไขผ่าน revision ของคำขอราคาไม่ได้ "
                    + "— การเปลี่ยนแปลงหลังลูกค้ายอมรับต้องทำเป็นการแก้ไขคำสั่งซื้อ");
        }
        if (!PricingRequestStatus.canTransition(parent.status(), PricingRequestStatus.SUPERSEDED)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "สร้าง revision จากการเปลี่ยนแปลงของลูกค้าได้เฉพาะจากคำขอราคาที่ยื่นและยังดำเนินการอยู่เท่านั้น");
        }
        TicketSummaryDto ticket = requireTicket(parent.ticketId());
        requireActive(ticket);
        validateClientRequestId(request.clientRequestId());
        PricingRequestSummaryDto existing = existingForClientRequest(actor.id(), request.clientRequestId());
        if (existing != null) {
            if (existing.parentPricingRequestId() == null || existing.parentPricingRequestId() != id) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "clientRequestId นี้ถูกใช้ไปแล้วกับคำขอราคาอื่น");
            }
            return detail(existing.id());
        }
        validateRecipient(request.recipientType());
        validateRecipientIdentifiable(request.recipientContactId(), request.recipientLabel());
        validateRecipientContactBelongsToCustomer(request.recipientContactId(), ticket);
        validateCurrency(request.targetCurrency());
        validateItems(request.items());
        validateSourceItemsBelongToTicket(parent.ticketId(), request.items());

        long newId = requests.createCustomerChangeRevision(parent, request, actor.id());
        if (newId == 0L) {
            existing = existingForClientRequest(actor.id(), request.clientRequestId());
            if (existing != null) {
                return detail(existing.id());
            }
            throw new ApiException(HttpStatus.CONFLICT, "clientRequestId นี้ถูกใช้ไปแล้ว");
        }
        int superseded = requests.supersedeForCustomerRevision(parent.id(), parent.status(), newId);
        if (superseded == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        requests.cancelOpenStep2Children(parent.id(), "Customer change revision created", actor.id());
        // Step 5 (V75, design correction 1): also supersede any DRAFT/APPROVED pricing_decision
        // left over from Step 3 — cancelOpenStep2Children above predates it and does not touch it.
        //
        // Reissue-through-CEO-chain (owner ruling 2026-08-13): this deliberately no longer
        // supersedes the parent's QUOTATION. It used to (the method was
        // supersedeOpenPricingDecisionAndQuotation, and it flipped an ISSUED quotation to
        // SUPERSEDED the instant a revision was created), which meant the customer was left with
        // no live offer for the whole time the new chain ran — and if the CEO ultimately refused
        // the new price there was nothing to fall back to. The owner's ruling is that the already
        // issued quotation STAYS ISSUED and valid while the replacement chain runs, and is
        // superseded only when the replacement quotation is actually issued
        // (CustomerQuotationService#issue -> supersedeSupersededChainQuotations).
        //
        // The DECISION half stays eager on purpose: it is internal pricing state, not the
        // customer-facing offer, and the issued quotation carries its own frozen price snapshot
        // (sales.quotation_item), so superseding the decision cannot change what the customer was
        // quoted.
        requests.supersedeOpenPricingDecision(parent.id());
        requests.addEvent(parent.id(), parent.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_REVISED, parent.status(), PricingRequestStatus.SUPERSEDED,
            request.revisionReason(), toRevisionMetadataJson(newId));
        requests.addEvent(newId, parent.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_CREATED, null, PricingRequestStatus.DRAFT,
            request.revisionReason(), toRevisionMetadataJson(parent.id()));
        notifyCeo(parent, PricingRequestEventKind.PRICING_REQUEST_REVISED,
            "คำขอราคา " + parent.requestCode() + " มี customer-change revision ใหม่");
        return detail(newId);
    }

    // --- Pricing Request attachments (V69, review remediation COMMIT 4) ---
    //
    // Sales may optionally attach supporting files to the Pricing Request while it is DRAFT
    // (see ATTACHMENT_EDITABLE_STATUSES, narrowed to DRAFT alone by V140); zero attachments
    // remains valid (no gate anywhere requires at least
    // one). Import can mark which of those to include when it sends the factory email —
    // FactoryQuoteService.attemptSend reads that flag fresh at actual-send time, not here.
    //
    // Every method below reuses requireViewable, which already carries the pricing-request
    // ownership rule (a "sales" actor may only reach a request on a ticket they created,
    // regardless of status — see requireViewable's own Javadoc) — this is what makes a second
    // sales rep's attempt to read/upload/delete another rep's attachments 404/403 without any
    // attachment-specific ownership check needing to be re-derived here.

    @Transactional
    public PricingRequestAttachmentDto uploadAttachment(long id, MultipartFile file, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        PricingRequestSummaryDto summary = requireViewable(id, actor);
        if (summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if (!ATTACHMENT_EDITABLE_STATUSES.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "แนบไฟล์ได้เฉพาะเมื่อคำขอราคายังเป็นแบบร่างเท่านั้น");
        }
        TicketSummaryDto ticket = requireTicket(summary.ticketId());
        requireActive(ticket);
        FileStorageService.StoredFile stored = fileStorage.store("pricing-request", id, file, Set.of());
        // The disk copy above is NOT part of this transaction. saveAttachment and addEvent below
        // can both still fail, rolling this method back and leaving the file on disk with no
        // sales.pricing_request_attachment row referencing it. deleteOnRollback ties it to the
        // transaction's outcome and is a no-op when the method commits. The store deliberately
        // stays BELOW the authorization/status gates above (see deleteOnRollback's Javadoc):
        // hoisting it out of the transaction would mean storing before authorizing.
        fileStorage.deleteOnRollback(stored);
        PricingRequestAttachmentDto attachment = requests.saveAttachment(id, stored.fileName(), stored.filePath(),
            stored.mimeType(), stored.fileSize(), actor.id());
        requests.addEvent(id, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_UPDATED, summary.status(), summary.status(),
            "Attachment uploaded: " + attachment.fileName(), null);
        return attachment;
    }

    public List<PricingRequestAttachmentDto> listAttachments(long id, UserPrincipal actor) {
        requireViewable(id, actor);
        return requests.findAttachments(id);
    }

    /** Resolves and authorizes an attachment id back to its parent request in one step. */
    private PricingRequestAttachmentDto requireViewableAttachment(long attachmentId, UserPrincipal actor) {
        PricingRequestAttachmentDto attachment = requests.findAttachment(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์แนบของคำขอราคานี้"));
        requireViewable(attachment.pricingRequestId(), actor);
        return attachment;
    }

    public PricingRequestAttachmentDto getAttachment(long attachmentId, UserPrincipal actor) {
        return requireViewableAttachment(attachmentId, actor);
    }

    public String attachmentFilePath(long attachmentId, UserPrincipal actor) {
        requireViewableAttachment(attachmentId, actor);
        String path = requests.findAttachmentFilePath(attachmentId);
        if (path == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์แนบของคำขอราคานี้");
        }
        return path;
    }

    @Transactional
    public void deleteAttachment(long attachmentId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        PricingRequestAttachmentDto attachment = requests.findAttachment(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์แนบของคำขอราคานี้"));
        PricingRequestSummaryDto summary = requireViewable(attachment.pricingRequestId(), actor);
        if (summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if (!ATTACHMENT_EDITABLE_STATUSES.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ลบไฟล์แนบได้เฉพาะเมื่อคำขอราคายังเป็นแบบร่างเท่านั้น");
        }
        String path = requests.findAttachmentFilePath(attachmentId);
        requests.deleteAttachment(attachmentId);
        // Deferred to commit, not done here: this method is @Transactional, and a disk delete is
        // not. Removing the bytes inline meant a rollback restored the
        // sales.pricing_request_attachment row while the file it points at stayed gone — a document
        // the pricing request still lists and nobody can ever download again. Identical to the
        // hazard PR #719 fixed in AttachmentController#delete, and the mirror of the
        // deleteOnRollback guard PR #708 added to uploadAttachment just above.
        //
        // deleteOnCommit also replaces the swallowed-IOException block that used to live here: it
        // is best-effort and cannot throw either (it logs a WARN instead), so metadata removal
        // stays authoritative and a file that is already gone still does not fail the workflow.
        fileStorage.deleteOnCommit(path);
    }

    /**
     * Import-only. Deliberately no {@code ATTACHMENT_EDITABLE_STATUSES}-style status gate here:
     * {@code requireViewable} already makes a DRAFT request invisible to import entirely (see its
     * Javadoc — draft privacy), so import can only ever reach an attachment on a request that has
     * already been submitted; there is no separate "too early" state to guard against for this
     * role the way there is for Sales's own upload/delete.
     */
    @Transactional
    public PricingRequestAttachmentDto setAttachmentIncludeInFactoryEmail(
        long attachmentId, UpdatePricingRequestAttachmentRequest request, UserPrincipal actor
    ) {
        requireRole(actor, IMPORT_ROLES);
        PricingRequestAttachmentDto attachment = requireViewableAttachment(attachmentId, actor);
        int rows = requests.setIncludeInFactoryEmail(attachmentId, Boolean.TRUE.equals(request.includeInFactoryEmail()));
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ไม่สามารถอัปเดตไฟล์แนบของคำขอราคานี้ได้");
        }
        return requests.findAttachment(attachment.id()).orElseThrow();
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
     * <p>Bypasses {@link PricingRequestStatus#canTransition} on purpose, via {@link
     * PricingRequestRepository#cancelForDeadDeal} (review remediation COMMIT 5) rather than
     * {@link PricingRequestRepository#transition}, which now asserts the canonical map and would
     * reject this cascade for a request sitting in {@code READY_FOR_CEO_REVIEW} — a status from
     * which a live user action may not cancel directly, but a dead deal must still be able to kill
     * ANY open pricing request, in ANY open status. The normal restriction exists to protect a
     * live workflow, which no longer exists once the deal itself is terminal.
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
                int rows = requests.cancelForDeadDeal(id, summary.status(), actor.id());
                if (rows == 1) {
                    // Cancel cutoff (owner ruling 2026-08-13): the DEAD-request cascade, same as
                    // cancel() above. This path needed it even more than cancel() did —
                    // cancelForDeadDeal bypasses canTransition entirely and cancels from ANY open
                    // status, so its children have always been reachable at READY_FOR_COSTING (a
                    // factory quote) and SUBMITTED (a costing), and the old narrow predicates
                    // missed both. Switching this call is a bug fix independent of the cutoff.
                    requests.cancelOpenChildrenForDeadRequest(id, reason, actor.id());
                    // Step 5 (V75, review follow-up): same fix as cancel()/createCustomerChangeRevision
                    // — a deal reaching a terminal lifecycle must also close out an open decision/
                    // quotation on this pricing request, not just its factory-quote/costing children.
                    requests.supersedeOpenPricingDecisionAndQuotation(id);
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "เหตุผลการยกเลิกไม่ถูกต้อง");
        }
    }

    private String toDeadDealMetadataJson(String reason) {
        try {
            return objectMapper.writeValueAsString(Map.of("reason", reason, "cause", "DEAL_TERMINAL"));
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เหตุผลการยกเลิกไม่ถูกต้อง");
        }
    }

    private String toRevisionMetadataJson(long relatedPricingRequestId) {
        try {
            return objectMapper.writeValueAsString(Map.of("relatedPricingRequestId", relatedPricingRequestId));
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ข้อมูล revision ไม่ถูกต้อง");
        }
    }

    private PricingRequestDetailDto detail(long id) {
        PricingRequestSummaryDto summary = requests.findSummary(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
        // A DRAFT is the rep's private scratchpad (see this class's Javadoc) — only
        // the owning sales rep and managerial oversight (ceo/sales_manager) may see
        // it. import/account must not, even though they can see every other status.
        // Respond 404, NOT 403: a 403 here would confirm to a non-owner that a
        // pricing request with this id exists in SOME status, letting them probe
        // ids to enumerate other reps' in-flight drafts. 404 is indistinguishable
        // from "no such id", which is what we want a non-owner to see.
        if (PricingRequestStatus.DRAFT.equals(summary.status()) && !canSeeDraft(actor, summary)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้");
        }
        if ("sales".equals(actor.role()) && summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return summary;
    }

    /** Who may see a request while it is still in DRAFT status — see requireViewable. */
    private boolean canSeeDraft(UserPrincipal actor, PricingRequestSummaryDto summary) {
        return summary.ticketCreatedById() == actor.id()
            || "ceo".equals(actor.role())
            || "sales_manager".equals(actor.role());
    }

    private void notifyCeo(PricingRequestSummaryDto summary, String type, String message) {
        notifications.notifyByRoleForPricingRequest("ceo", summary.id(), type, message);
    }

    private TicketSummaryDto requireTicket(long ticketId) {
        return tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"))
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
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private void validateRecipient(String recipientType) {
        if (!PricingRequestRecipient.isValid(recipientType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับประเภทผู้รับ '" + recipientType + "'");
        }
    }

    private void validateItems(List<PricingRequestItemRequest> items) {
        for (int i = 0; i < items.size(); i++) {
            PricingRequestItemRequest item = items.get(i);
            if (!QuantityType.isValid(item.quantityType())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับประเภทจำนวน '" + item.quantityType() + "'");
            }
            if (!UnitBasis.isValid(item.requestedUnitBasis())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ไม่รองรับ requestedUnitBasis '" + item.requestedUnitBasis() + "'");
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId ต้องเป็น UUID");
        }
        try {
            return UUID.fromString(clientRequestId).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId ต้องเป็น UUID");
        }
    }

    private PricingRequestSummaryDto existingForClientRequest(long requestedBy, String clientRequestId) {
        var existing = requests.findByClientRequestId(requestedBy, clientRequestId);
        return existing == null ? null : existing.orElse(null);
    }

    private PricingRequestSummaryDto requireSameTicket(PricingRequestSummaryDto existing, long ticketId) {
        if (existing.ticketId() != ticketId) {
            throw new ApiException(HttpStatus.CONFLICT,
                "clientRequestId นี้ถูกใช้ไปแล้วกับดีลอื่น");
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "targetCurrency ต้องเป็นรหัสสกุลเงิน 3 ตัวอักษร");
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
                    "sourceTicketItemId " + sourceTicketItemId + " ไม่ได้เป็นของดีล " + ticketId);
            }
        }
    }
}
