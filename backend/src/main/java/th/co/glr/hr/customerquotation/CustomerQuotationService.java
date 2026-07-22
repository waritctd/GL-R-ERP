package th.co.glr.hr.customerquotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository.InsertDraftParams;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository.ItemUpdate;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository.NewItem;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CancelCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateRevisionRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.RecordQuotationOutcomeRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationItemRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesViewDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.RelatedDocumentType;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Step 4 of the sales pricing redesign: Customer Quotation Generation and Issuance. Turns the
 * current APPROVED {@code sales.pricing_decision} into a draft, editable-within-limits, then
 * issued customer quotation — extending the EXISTING {@code sales.quotation}/
 * {@code sales.quotation_item} aggregate and reusing {@link QuotationRenderer} and
 * {@link TicketService}'s own stage-advance, per the owner's decision recorded in the branch
 * handoff. Deliberately never reads or writes legacy {@code sales.ticket_item} price columns —
 * the spine of every price on a Step 4 quotation is
 * {@code pricing_decision_item.approved_selling_price_per_requested_unit}.
 */
@Service
public class CustomerQuotationService {
    private static final Set<String> SALES_ROLES = Set.of("sales");
    // Read-only for ceo/import/sales_manager — mirrors RAW_DECISION_ROLES/RAW_QUOTE_ROLES'
    // {import, ceo} precedent, plus sales_manager's documented read-only oversight role
    // (TicketService's own VIEWER_ROLES comment). account is deliberately excluded: "account
    // role: no quotation editing" in the task brief, and Step 3 already excludes account from
    // every raw-pricing-adjacent view on this same chain (accountRole_cannotReach... test) —
    // there is no positive grant for account to read a customer quotation either, so it stays
    // forbidden end-to-end, not just for writes.
    private static final Set<String> VIEW_ROLES = Set.of("sales", "sales_manager", "ceo", "import");
    private static final BigDecimal VAT_RATE = new BigDecimal("0.07");

    private final CustomerQuotationRepository quotations;
    private final PricingRequestRepository pricingRequests;
    private final PricingDecisionRepository decisions;
    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final CustomerRepository customers;
    private final QuotationRenderer renderer;
    private final NotificationRepository notifications;

    public CustomerQuotationService(CustomerQuotationRepository quotations, PricingRequestRepository pricingRequests,
                                    PricingDecisionRepository decisions, TicketRepository tickets,
                                    TicketService ticketService, CustomerRepository customers,
                                    QuotationRenderer renderer, NotificationRepository notifications) {
        this.quotations = quotations;
        this.pricingRequests = pricingRequests;
        this.decisions = decisions;
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.customers = customers;
        this.renderer = renderer;
        this.notifications = notifications;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Create (rule 6: never moves the deal stage or the pricing request status)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public CustomerQuotationDto create(long pricingRequestId, CreateCustomerQuotationRequest request, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        requireOwner(summary, actor);
        requireActiveDeal(summary.ticketId());
        String clientRequestId = validateUuid(request.clientRequestId());
        quotations.lockPricingRequest(pricingRequestId);
        if (clientRequestId != null) {
            Optional<Long> replay = quotations.findIdByClientRequestId(actor.id(), clientRequestId);
            if (replay.isPresent()) {
                return requireQuotation(replay.get());
            }
        }
        // Input gate (spine of Step 4): only from APPROVED_FOR_QUOTATION with a current
        // APPROVED pricing_decision.
        if (!PricingRequestStatus.APPROVED_FOR_QUOTATION.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบขอราคาต้องอยู่ในสถานะ 'อนุมัติราคาขายแล้ว' ก่อนจึงจะออกใบเสนอราคาลูกค้าได้ (ปัจจุบัน: " + summary.status() + ")");
        }
        PricingDecisionSalesViewDto salesView = decisions.findApprovedSalesView(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ยังไม่มีราคาขายที่ CEO อนุมัติสำหรับใบขอราคานี้"));

        List<NewItem> items = new ArrayList<>();
        for (PricingDecisionSalesItemDto item : salesView.items()) {
            items.add(buildItem(item, BigDecimal.ZERO));
        }
        BigDecimal subtotal = sumOf(items, NewItem::lineSubtotal);

        TicketSummaryDto ticket = requireTicketSummary(summary.ticketId());
        CustomerDto customer = ticket.customerId() != null ? customers.findById(ticket.customerId()).orElse(null) : null;

        long id = quotations.insertDraft(new InsertDraftParams(
            summary.ticketId(), pricingRequestId, salesView.pricingDecisionId(), summary.recipientType(),
            summary.recipientLabel(), actor.id(), clientRequestId, blankToNull(request.paymentTerms()),
            blankToNull(request.leadTime()), blankToNull(request.deliveryTerms()), request.validityDate(),
            blankToNull(request.customerNotes()), null, 1, subtotal, salesView.currency(),
            ticket.customerName(), customer != null ? customer.address() : null,
            customer != null ? customer.taxId() : null, customer != null ? customer.phone() : null,
            ticket.projectName(), items));

        addPricingRequestEvent(summary, actor, PricingRequestEventKind.CUSTOMER_QUOTATION_CREATED,
            "สร้างร่างใบเสนอราคาลูกค้า");
        return requireQuotation(id);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Read
    // ─────────────────────────────────────────────────────────────────────────────────────

    public CustomerQuotationDto get(long quotationId, UserPrincipal actor) {
        CustomerQuotationDto quotation = requireQuotation(quotationId);
        requireViewAccess(quotation, actor);
        return quotation;
    }

    public List<CustomerQuotationDto> listForPricingRequest(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, VIEW_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        if ("sales".equals(actor.role())) {
            requireOwner(summary, actor);
        }
        return quotations.findByPricingRequest(pricingRequestId);
    }

    /**
     * Rule 12: a GET-like recompute of the CURRENT persisted state — performs zero writes. Every
     * mutation (create/update) already server-recomputes and persists totals, so "preview" is the
     * same read as {@link #get}; this method exists as its own endpoint only so the frontend has
     * an explicit, unambiguous "no side effect" call to make before offering PDF/XLSX download.
     */
    public CustomerQuotationDto preview(long quotationId, UserPrincipal actor) {
        return get(quotationId, actor);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Update (draft-only; rules 4/5: only customer-facing description/notes/discount are
    // editable — no cost/FX/margin field exists anywhere on this aggregate to edit)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public CustomerQuotationDto update(long quotationId, UpdateCustomerQuotationRequest request, UserPrincipal actor) {
        CustomerQuotationDto quotation = requireQuotation(quotationId);
        requireEditAccess(quotation, actor);
        requireDraft(quotation);

        quotations.updateHeader(quotationId, blankToNull(request.paymentTerms()), blankToNull(request.leadTime()),
            blankToNull(request.deliveryTerms()), request.validityDate(), blankToNull(request.customerNotes()));

        if (request.items() != null && !request.items().isEmpty()) {
            applyItemUpdates(quotation, request.items());
        }
        quotations.recalculateTotal(quotationId);

        PricingRequestSummaryDto summary = requirePricingRequest(quotation.pricingRequestId());
        addPricingRequestEvent(summary, actor, PricingRequestEventKind.CUSTOMER_QUOTATION_UPDATED,
            "แก้ไขใบเสนอราคาลูกค้า " + quotation.number());
        return requireQuotation(quotationId);
    }

    private void applyItemUpdates(CustomerQuotationDto quotation, List<UpdateCustomerQuotationItemRequest> requests) {
        Map<Long, CustomerQuotationItemDto> byId = new HashMap<>();
        for (CustomerQuotationItemDto item : quotation.items()) {
            byId.put(item.id(), item);
        }
        List<ItemUpdate> updates = new ArrayList<>();
        for (UpdateCustomerQuotationItemRequest req : requests) {
            CustomerQuotationItemDto current = byId.get(req.quotationItemId());
            if (current == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "รายการ " + req.quotationItemId() + " ไม่ได้อยู่ในใบเสนอราคานี้");
            }
            BigDecimal discount = req.salesDiscount() != null ? req.salesDiscount() : current.salesDiscount();
            if (discount == null) {
                discount = BigDecimal.ZERO;
            }
            if (discount.compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ส่วนลดต้องไม่ติดลบ");
            }
            BigDecimal finalUnitPrice = money4(current.approvedUnitPrice().subtract(discount));
            if (finalUnitPrice.compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ส่วนลดต้องไม่เกินราคาที่อนุมัติ");
            }
            // Discount Policy B (controlled, owner's decision): Sales may discount down to, but
            // never below, the CEO-approved minimum selling price. No auto-escalation in this
            // step — a below-minimum request is a hard 422, full stop.
            if (current.minimumSellingPricePerRequestedUnit() != null
                    && finalUnitPrice.compareTo(current.minimumSellingPricePerRequestedUnit()) < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ราคาหลังหักส่วนลดของรายการ " + req.quotationItemId() + " ต่ำกว่าราคาขั้นต่ำที่ CEO อนุมัติ ("
                        + current.minimumSellingPricePerRequestedUnit() + " " + "ต่อหน่วย)");
            }
            BigDecimal lineSubtotal = money2(finalUnitPrice.multiply(current.requestedQuantity()));
            BigDecimal vat = money2(lineSubtotal.multiply(VAT_RATE));
            BigDecimal lineTotal = lineSubtotal.add(vat);
            String description = req.description() != null ? req.description() : current.description();
            String itemNotes = req.itemNotes() != null ? req.itemNotes() : current.itemNotes();
            updates.add(new ItemUpdate(current.id(), description, itemNotes, discount, finalUnitPrice,
                lineSubtotal, vat, lineTotal));
        }
        int rows = quotations.updateItems(quotation.id(), updates);
        if (rows != updates.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาถูกเปลี่ยนแปลงโดยผู้ใช้อื่น หรือไม่ได้อยู่ในสถานะร่างแล้ว");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Issue (rules 7/8/13/14)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public CustomerQuotationDto issue(long quotationId, IssueCustomerQuotationRequest request, UserPrincipal actor) {
        CustomerQuotationDto preview = requireQuotation(quotationId);
        requireEditAccess(preview, actor);
        quotations.lockPricingRequest(preview.pricingRequestId());
        String issueClientRequestId = validateUuid(request.clientRequestId());
        if (issueClientRequestId != null) {
            Optional<Long> replay = quotations.findIdByIssueClientRequestId(actor.id(), issueClientRequestId);
            if (replay.isPresent()) {
                if (replay.get() != quotationId) {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "clientRequestId ถูกใช้ไปแล้วกับใบเสนอราคาอื่น");
                }
                return requireQuotation(replay.get());
            }
        }
        CustomerQuotationDto quotation = requireQuotation(quotationId);
        if (!isOpenForIssue(quotation.docStatus())) {
            if (QuotationStatus.ISSUED.equals(quotation.docStatus())) {
                // Idempotent replay without a clientRequestId (or a fresh one never seen
                // before) against an already-ISSUED quotation is a clean conflict, not a
                // silent second "success" — mirrors PricingDecisionService.approve()'s
                // "Decision is not open for approval" 409.
                throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคานี้ออกไปแล้ว");
            }
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะที่ออกได้ (" + quotation.docStatus() + ")");
        }
        for (CustomerQuotationItemDto item : quotation.items()) {
            if (item.minimumSellingPricePerRequestedUnit() != null
                    && item.finalUnitPrice().compareTo(item.minimumSellingPricePerRequestedUnit()) < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ไม่สามารถออกใบเสนอราคาได้ — รายการ " + item.id() + " ต่ำกว่าราคาขั้นต่ำ");
            }
        }

        int issuedRows = quotations.issue(quotationId, issueClientRequestId);
        if (issuedRows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาถูกเปลี่ยนแปลงโดยผู้ใช้อื่น");
        }

        PricingRequestSummaryDto summary = requirePricingRequest(quotation.pricingRequestId());
        // Only the FIRST issue moves the pricing request — a revision's re-issue is a no-op
        // transition (the request is already QUOTATION_ISSUED); see PricingRequestStatus's own
        // Javadoc on QUOTATION_ISSUED.
        if (PricingRequestStatus.APPROVED_FOR_QUOTATION.equals(summary.status())) {
            int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.APPROVED_FOR_QUOTATION,
                PricingRequestStatus.QUOTATION_ISSUED, null, null);
            if (transitioned == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "ใบขอราคาถูกเปลี่ยนแปลงโดยผู้ใช้อื่น");
            }
        }

        // Rule 7: reuse the EXISTING stage transition generateQuotation already performs — not
        // a second path. Rule 6 (drafts never move the stage) holds because this is the only
        // call site in this whole service.
        ticketService.advanceStageForCustomerQuotationIssue(summary.ticketId(), quotation.recipientType(), actor);

        tickets.addEventWithDocument(summary.ticketId(), actor.id(), actor.name(), TicketEventKind.QUOTATION_ISSUED,
            null, null, "ออกใบเสนอราคาลูกค้า " + quotation.number() + " (revision " + quotation.quotationRevisionNo() + ")",
            RelatedDocumentType.QUOTATION, quotationId);
        addPricingRequestEvent(summary, actor, PricingRequestEventKind.CUSTOMER_QUOTATION_ISSUED,
            "ออกใบเสนอราคาลูกค้า " + quotation.number());
        // "Customer notification" — this system has no customer user account to notify
        // in-app, so the closest equivalent is a CEO-visibility notification documenting the
        // issuance (CEO read-only on the final document, per the task brief). Documented
        // explicitly in the branch handoff's Known Risks, not silently substituted.
        notifications.notifyByRoleForPricingRequest("ceo", summary.id(),
            PricingRequestEventKind.CUSTOMER_QUOTATION_ISSUED,
            "ใบเสนอราคาลูกค้า " + quotation.number() + " ถูกออกแล้ว");
        return requireQuotation(quotationId);
    }

    private boolean isOpenForIssue(String docStatus) {
        return QuotationStatus.DRAFT.equals(docStatus) || QuotationStatus.READY_TO_ISSUE.equals(docStatus);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Cancel (draft-only — an ISSUED quotation cannot be cancelled, only revised)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public CustomerQuotationDto cancel(long quotationId, CancelCustomerQuotationRequest request, UserPrincipal actor) {
        CustomerQuotationDto quotation = requireQuotation(quotationId);
        requireEditAccess(quotation, actor);
        int rows = quotations.cancel(quotationId);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงยกเลิกไม่ได้");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(quotation.pricingRequestId());
        addPricingRequestEvent(summary, actor, PricingRequestEventKind.CUSTOMER_QUOTATION_CANCELLED,
            "ยกเลิกร่างใบเสนอราคาลูกค้า " + quotation.number()
                + (request.reason() != null && !request.reason().isBlank() ? " — " + request.reason().trim() : ""));
        return requireQuotation(quotationId);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Revision (rule 9: corrections after issue create a new revision; old ones stay readable)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public CustomerQuotationDto createRevision(long quotationId, CreateRevisionRequest request, UserPrincipal actor) {
        CustomerQuotationDto source = requireQuotation(quotationId);
        requireEditAccess(source, actor);
        // Step 5 (design correction 3, "compatibility fix on createRevision's guard"):
        // recordOutcome writes ISSUED -> REVISION_REQUESTED immediately when Sales records what
        // the customer said, BEFORE they pick commercial-only vs cost-affecting — so a
        // commercial-only correction must be reachable from REVISION_REQUESTED too, not only the
        // original ISSUED. Widened to accept exactly these two predecessors, nothing else (see
        // createRevision_widenedGuard_* tests for the full negative-space proof: DRAFT/CANCELLED/
        // SUPERSEDED/EXPIRED/ACCEPTED/REJECTED must all still be rejected).
        if (!QuotationStatus.ISSUED.equals(source.docStatus()) && !QuotationStatus.REVISION_REQUESTED.equals(source.docStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "แก้ไข revision ใหม่ได้จากใบเสนอราคาที่ออกแล้วเท่านั้น");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(source.pricingRequestId());
        requireActiveDeal(summary.ticketId());
        String clientRequestId = validateUuid(request.clientRequestId());
        quotations.lockPricingRequest(source.pricingRequestId());
        if (clientRequestId != null) {
            Optional<Long> replay = quotations.findIdByClientRequestId(actor.id(), clientRequestId);
            if (replay.isPresent()) {
                return requireQuotation(replay.get());
            }
        }
        // Re-source fresh from the (single, immutable-once-approved) approved decision — there
        // is only ever one APPROVED decision per pricing request (V72's own partial unique
        // index), so this is a re-read of the same prices, not a second decision to reconcile.
        PricingDecisionSalesViewDto salesView = decisions.findApprovedSalesView(summary.id())
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ยังไม่มีราคาขายที่ CEO อนุมัติ"));

        List<NewItem> items = new ArrayList<>();
        for (PricingDecisionSalesItemDto item : salesView.items()) {
            // Preserve the prior revision's own discount per line where the same
            // pricing_request_item still exists, so a correction defaults to "same price,
            // edited description/notes" rather than silently resetting every discount to zero.
            BigDecimal priorDiscount = source.items().stream()
                .filter(i -> i.pricingRequestItemId() == item.pricingRequestItemId())
                .map(CustomerQuotationItemDto::salesDiscount)
                .findFirst().orElse(BigDecimal.ZERO);
            items.add(buildItem(item, priorDiscount));
        }
        BigDecimal subtotal = sumOf(items, NewItem::lineSubtotal);

        TicketSummaryDto ticket = requireTicketSummary(summary.ticketId());
        CustomerDto customer = ticket.customerId() != null ? customers.findById(ticket.customerId()).orElse(null) : null;

        long newId = quotations.insertDraft(new InsertDraftParams(
            summary.ticketId(), summary.id(), salesView.pricingDecisionId(), summary.recipientType(),
            summary.recipientLabel(), actor.id(), clientRequestId, source.paymentTerms(), source.leadTime(),
            source.deliveryTerms(), source.validityDate(), source.customerNotes(), source.id(),
            source.quotationRevisionNo() + 1, subtotal, salesView.currency(), ticket.customerName(),
            customer != null ? customer.address() : null, customer != null ? customer.taxId() : null,
            customer != null ? customer.phone() : null, ticket.projectName(), items));

        // Supersede the prior ISSUED revision only after the new draft exists, so a failure
        // partway through never leaves a pricing request with zero readable current quotation.
        quotations.supersede(source.id());

        addPricingRequestEvent(summary, actor, PricingRequestEventKind.CUSTOMER_QUOTATION_REVISED,
            "สร้างใบเสนอราคาลูกค้า revision " + (source.quotationRevisionNo() + 1)
                + (request.reason() != null && !request.reason().isBlank() ? " — " + request.reason().trim() : ""));
        return requireQuotation(newId);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Step 5: Customer Decision and Commercial Revisions.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** The only outcomes a client may record — EXPIRED is sweep-only, rejected explicitly below. */
    private static final Set<String> RECORDABLE_OUTCOMES =
        Set.of(QuotationStatus.ACCEPTED, QuotationStatus.REJECTED, QuotationStatus.REVISION_REQUESTED);

    /**
     * Records what the customer said about an ISSUED quotation. Role-gated SALES_ROLES only,
     * owner-scoped (ticket owner, via {@link #requireEditAccess}), only from ISSUED. Idempotent on
     * {@code clientRequestId} (this service's usual lock-then-replay-check pattern, mirroring
     * {@link #issue}). On ACCEPTED, also transitions the pricing request
     * {@code QUOTATION_ISSUED -> QUOTATION_ACCEPTED}. Emits exactly one
     * {@code sales.pricing_request_event} and one CEO-visibility notification per outcome —
     * REJECTED/REVISION_REQUESTED deliberately do NOT change the pricing request's own status
     * (design correction 2); Sales decides what happens next (a new revision via
     * {@link #createRevision}/{@code PricingRequestService.createCustomerChangeRevision}, or a
     * separate ticket-level lost-deal action outside this method's scope).
     */
    @Transactional
    public CustomerQuotationDto recordOutcome(long quotationId, RecordQuotationOutcomeRequest request, UserPrincipal actor) {
        if (QuotationStatus.EXPIRED.equals(request.outcome())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "EXPIRED ไม่สามารถบันทึกผ่าน API นี้ได้ — ระบบตั้งเป็นอัตโนมัติเท่านั้น");
        }
        if (request.outcome() == null || !RECORDABLE_OUTCOMES.contains(request.outcome())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "outcome ไม่ถูกต้อง");
        }
        CustomerQuotationDto quotation = requireQuotation(quotationId);
        requireEditAccess(quotation, actor);
        quotations.lockPricingRequest(quotation.pricingRequestId());
        String outcomeClientRequestId = validateUuid(request.clientRequestId());
        if (outcomeClientRequestId != null) {
            Optional<Long> replay = quotations.findIdByOutcomeClientRequestId(actor.id(), outcomeClientRequestId);
            if (replay.isPresent()) {
                return requireQuotation(replay.get());
            }
        }
        CustomerQuotationDto fresh = requireQuotation(quotationId);
        if (!QuotationStatus.ISSUED.equals(fresh.docStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "บันทึกผลได้เฉพาะใบเสนอราคาที่ออกแล้วเท่านั้น (ปัจจุบัน: " + fresh.docStatus() + ")");
        }
        int rows = quotations.recordOutcome(quotationId, request.outcome(), blankToNull(request.customerNote()),
            actor.id(), outcomeClientRequestId);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาถูกเปลี่ยนแปลงโดยผู้ใช้อื่น");
        }

        PricingRequestSummaryDto summary = requirePricingRequest(fresh.pricingRequestId());
        String eventKind = outcomeEventKind(request.outcome());
        addPricingRequestEvent(summary, actor, eventKind,
            "บันทึกผลใบเสนอราคาลูกค้า " + fresh.number() + ": " + request.outcome()
                + (request.customerNote() != null && !request.customerNote().isBlank()
                    ? " — " + request.customerNote().trim() : ""));
        notifications.notifyByRoleForPricingRequest("ceo", summary.id(), eventKind,
            "ใบเสนอราคาลูกค้า " + fresh.number() + " " + outcomeLabel(request.outcome()));

        if (QuotationStatus.ACCEPTED.equals(request.outcome())
                && PricingRequestStatus.QUOTATION_ISSUED.equals(summary.status())) {
            int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.QUOTATION_ISSUED,
                PricingRequestStatus.QUOTATION_ACCEPTED, null, null);
            if (transitioned == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "ใบขอราคาถูกเปลี่ยนแปลงโดยผู้ใช้อื่น");
            }
        }
        return requireQuotation(quotationId);
    }

    private String outcomeEventKind(String outcome) {
        return switch (outcome) {
            case QuotationStatus.ACCEPTED -> PricingRequestEventKind.CUSTOMER_QUOTATION_ACCEPTED;
            case QuotationStatus.REJECTED -> PricingRequestEventKind.CUSTOMER_QUOTATION_REJECTED;
            case QuotationStatus.REVISION_REQUESTED -> PricingRequestEventKind.CUSTOMER_QUOTATION_REVISION_REQUESTED;
            default -> throw new IllegalStateException("Unreachable — validated by RECORDABLE_OUTCOMES");
        };
    }

    private String outcomeLabel(String outcome) {
        return switch (outcome) {
            case QuotationStatus.ACCEPTED -> "ลูกค้ายอมรับแล้ว";
            case QuotationStatus.REJECTED -> "ถูกลูกค้าปฏิเสธ";
            case QuotationStatus.REVISION_REQUESTED -> "ลูกค้าขอแก้ไข";
            default -> "มีการอัปเดตผล";
        };
    }

    /**
     * Automatic expiry sweep — see {@code QuotationExpiryWorker} for the {@code @Scheduled}
     * trigger. A single guarded UPDATE (no outbox/claim/retry machinery — this isn't calling an
     * external system), scoped to Step 4/5 quotations only (see
     * {@link CustomerQuotationRepository#expireOverdueQuotations}'s own Javadoc). Emits one event
     * + one CEO-visibility notification per quotation flipped, and NEVER changes the pricing
     * request's own status (design correction 2). Returns the number of quotations expired, for
     * the worker/tests to assert against without a second query.
     */
    @Transactional
    public int expireOverdueQuotations() {
        List<CustomerQuotationRepository.ExpiredQuotationRow> expired = quotations.expireOverdueQuotations();
        for (CustomerQuotationRepository.ExpiredQuotationRow row : expired) {
            pricingRequests.findSummary(row.pricingRequestId()).ifPresent(summary -> {
                pricingRequests.addEvent(summary.id(), summary.ticketId(), null, "System (quotation expiry sweep)",
                    PricingRequestEventKind.CUSTOMER_QUOTATION_EXPIRED, summary.status(), summary.status(),
                    "ใบเสนอราคาลูกค้า " + row.number() + " หมดอายุ", null);
                notifications.notifyByRoleForPricingRequest("ceo", summary.id(),
                    PricingRequestEventKind.CUSTOMER_QUOTATION_EXPIRED, "ใบเสนอราคาลูกค้า " + row.number() + " หมดอายุ");
            });
        }
        return expired.size();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Rendering — reuses QuotationRenderer AS-IS (not modified): a Step 4 quotation's own
    // snapshot already populates the exact legacy columns
    // (brand/qty/raw_unit/unit_price/customer_name/...) TicketRepository.findQuotationItemsByQuotationId
    // and QuotationRenderer.toXlsx/toPdf already read, so PDF and XLSX derive from the SAME
    // stored snapshot without a second rendering implementation (rule 11).
    // ─────────────────────────────────────────────────────────────────────────────────────

    public byte[] renderPdf(long quotationId, UserPrincipal actor) {
        var ctx = loadRenderContext(quotationId, actor);
        return renderer.toPdf(ctx.ticket(), ctx.quotation(), ctx.customer());
    }

    public byte[] renderXlsx(long quotationId, UserPrincipal actor) {
        var ctx = loadRenderContext(quotationId, actor);
        return renderer.toXlsx(ctx.ticket(), ctx.quotation(), ctx.customer());
    }

    private record RenderContext(TicketDto ticket, th.co.glr.hr.ticket.QuotationDto quotation, CustomerDto customer) {}

    // Rule 12: this is a pure read — no status/state is ever written here, so downloading a
    // preview (before issue) can never issue.
    private RenderContext loadRenderContext(long quotationId, UserPrincipal actor) {
        CustomerQuotationDto quotation = requireQuotation(quotationId);
        requireViewAccess(quotation, actor);
        TicketSummaryDto liveTicketSummary = requireTicketSummary(quotation.ticketId());
        List<TicketItemDto> snapshotItems = tickets.findQuotationItemsByQuotationId(quotationId, quotation.ticketId());
        // Rule 8 (issued quotations are immutable): render against the FROZEN customer/project
        // header captured at creation time (sales.quotation.customer_name/project_name), not the
        // ticket's current live values — a later customer-record edit must never change how an
        // already-issued quotation renders. Mirrors TicketService.loadQuotationContext's own
        // legacy-flow substitution exactly (see TicketSummaryDto.withCustomerAndProject).
        TicketRepository.QuotationHeaderSnapshot header = tickets.findQuotationHeaderSnapshot(quotationId)
            .orElse(new TicketRepository.QuotationHeaderSnapshot(null, null, null, null, null));
        String frozenCustomerName = header.customerName() != null ? header.customerName() : liveTicketSummary.customerName();
        String frozenProjectName = header.projectName() != null ? header.projectName() : liveTicketSummary.projectName();
        TicketSummaryDto ticketSummary = liveTicketSummary.withCustomerAndProject(frozenCustomerName, frozenProjectName);
        TicketDto ticket = new TicketDto(ticketSummary, snapshotItems, List.of(), null, List.of());
        CustomerDto customer = new CustomerDto(
            liveTicketSummary.customerId() != null ? liveTicketSummary.customerId() : 0L,
            frozenCustomerName, header.customerTaxId(), header.customerAddress(), null, header.customerPhone());
        th.co.glr.hr.ticket.QuotationDto legacyDto = new th.co.glr.hr.ticket.QuotationDto(
            quotation.id(), quotation.ticketId(), quotation.number(), quotation.issuedById(),
            quotation.issuedByName(), quotation.issuedAt(), null, quotation.subtotalAmount(), quotation.currency(),
            quotation.quotationVersion(), quotation.docStatus(), quotation.recipientType(), quotation.recipientLabel(),
            quotation.paymentTerms(), quotation.leadTime(), quotation.deliveryTerms(), quotation.validityDate(),
            quotation.sentAt(), quotation.acceptedAt(), quotation.rejectedAt(), quotation.parentQuotationId());
        return new RenderContext(ticket, legacyDto, customer);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private NewItem buildItem(PricingDecisionSalesItemDto item, BigDecimal salesDiscount) {
        BigDecimal approvedUnitPrice = item.approvedSellingPricePerRequestedUnit();
        BigDecimal discount = salesDiscount == null ? BigDecimal.ZERO : salesDiscount;
        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ส่วนลดต้องไม่ติดลบ");
        }
        BigDecimal finalUnitPrice = money4(approvedUnitPrice.subtract(discount));
        if (finalUnitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ส่วนลดต้องไม่เกินราคาที่อนุมัติ");
        }
        // Discount Policy B, enforced at creation too (a client cannot pre-seed a
        // below-minimum discount by any path — there is currently no create-time discount
        // input, so this branch is unreachable via create() today but stays as defense in
        // depth since buildItem is shared with createRevision, which DOES carry forward a
        // prior discount).
        if (item.minimumSellingPricePerRequestedUnit() != null
                && finalUnitPrice.compareTo(item.minimumSellingPricePerRequestedUnit()) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ราคาหลังหักส่วนลดของรายการ " + item.pricingRequestItemId() + " ต่ำกว่าราคาขั้นต่ำที่ CEO อนุมัติ");
        }
        // Unit basis (highest financial risk on this task): finalUnitPrice is per requested
        // unit, requestedQuantity is in the SAME requested-unit basis (both come straight off
        // pricing_decision_item, which is itself already normalized in Step 3) — never mixed
        // with a physical-piece quantity.
        BigDecimal lineSubtotal = money2(finalUnitPrice.multiply(item.requestedQuantity()));
        BigDecimal vat = money2(lineSubtotal.multiply(VAT_RATE));
        BigDecimal lineTotal = lineSubtotal.add(vat);
        String description = firstText(item.productDescription(),
            (firstText(item.brand(), "") + " " + firstText(item.model(), "")).trim());
        return new NewItem(item.pricingRequestItemId(), item.pricingDecisionItemId(), description,
            item.requestedUnitBasis(), item.requestedQuantity(), approvedUnitPrice, discount, finalUnitPrice,
            lineSubtotal, vat, lineTotal, item.brand(), unitLabel(item.requestedUnitBasis()));
    }

    private String unitLabel(String unitBasis) {
        if (unitBasis == null) return null;
        return switch (unitBasis) {
            case UnitBasis.PER_SQM -> "ตร.ม.";
            case UnitBasis.PER_PIECE -> "แผ่น";
            case UnitBasis.PER_BOX -> "กล่อง";
            case UnitBasis.PER_LINEAR_M -> "เมตร";
            default -> unitBasis;
        };
    }

    private BigDecimal sumOf(List<NewItem> items, java.util.function.Function<NewItem, BigDecimal> fn) {
        return items.stream().map(fn).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void requireOwner(PricingRequestSummaryDto summary, UserPrincipal actor) {
        if ("sales".equals(actor.role()) && summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void requireViewAccess(CustomerQuotationDto quotation, UserPrincipal actor) {
        requireRole(actor, VIEW_ROLES);
        if ("sales".equals(actor.role())) {
            PricingRequestSummaryDto summary = requirePricingRequest(quotation.pricingRequestId());
            requireOwner(summary, actor);
        }
    }

    private void requireEditAccess(CustomerQuotationDto quotation, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(quotation.pricingRequestId());
        requireOwner(summary, actor);
    }

    private void requireDraft(CustomerQuotationDto quotation) {
        if (!isOpenForIssue(quotation.docStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "แก้ไขได้เฉพาะใบเสนอราคาที่ยังเป็นร่างเท่านั้น (ปัจจุบัน: "
                + quotation.docStatus() + ")");
        }
    }

    private void requireActiveDeal(long ticketId) {
        TicketSummaryDto ticket = requireTicketSummary(ticketId);
        if (!DealLifecycle.ACTIVE.equals(ticket.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "Parent deal must be ACTIVE");
        }
    }

    private TicketSummaryDto requireTicketSummary(long ticketId) {
        return tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"))
            .summary();
    }

    private CustomerQuotationDto requireQuotation(long quotationId) {
        return quotations.findById(quotationId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Customer quotation not found"));
    }

    private PricingRequestSummaryDto requirePricingRequest(long pricingRequestId) {
        return pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pricing request not found"));
    }

    private void addPricingRequestEvent(PricingRequestSummaryDto summary, UserPrincipal actor, String kind, String message) {
        pricingRequests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(), kind,
            summary.status(), summary.status(), message, null);
    }

    private String validateUuid(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(clientRequestId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId must be a valid UUID");
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String firstText(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return fallback;
    }

    private BigDecimal money4(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal money2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
