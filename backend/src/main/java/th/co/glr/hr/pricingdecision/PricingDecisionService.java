package th.co.glr.hr.pricingdecision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
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
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.FxRateDto;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.FxResolver;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingItemDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingStatus;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesViewDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository.ApprovedItem;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository.CreateDecisionResult;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository.ItemUpdate;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository.WriteItem;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.RecalculatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ReturnPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Step 3 of the sales pricing redesign: CEO Selling Price Decision. Turns a frozen SUBMITTED
 * costing into an approved, customer-facing selling price.
 *
 * <pre>
 * READY_FOR_CEO_REVIEW -&gt; (CEO starts) CEO_REVIEWING
 *     |-- approve -&gt; APPROVED_FOR_QUOTATION
 *     `-- return  -&gt; COSTING_REVISION_REQUIRED  (Import recalculates / new costing version, resubmits)
 * </pre>
 *
 * <p>Deliberately does NOT create a customer quotation, touch legacy {@code sales.ticket_item}
 * price fields, or change the deal stage — see the class-level scope note in the branch handoff.
 */
@Service
public class PricingDecisionService {
    private static final Set<String> CEO_ROLES = Set.of("ceo");
    /** Design correction 2: the ONLY roles that may ever see cost/margin. Sales/sales_manager
     * must go through {@link #salesView}, which never touches this set's data. */
    private static final Set<String> RAW_DECISION_ROLES = Set.of("import", "ceo");
    private static final Set<String> SALES_VIEW_ROLES = Set.of("sales", "sales_manager", "ceo", "import");
    private static final BigDecimal MINUS_ONE = BigDecimal.valueOf(-1);

    private final PricingDecisionRepository decisions;
    private final PricingRequestRepository pricingRequests;
    private final PricingCostingRepository costings;
    private final TicketRepository tickets;
    private final FxRateRepository fxRates;
    private final NotificationRepository notifications;

    public PricingDecisionService(PricingDecisionRepository decisions, PricingRequestRepository pricingRequests,
                                  PricingCostingRepository costings, TicketRepository tickets,
                                  FxRateRepository fxRates, NotificationRepository notifications) {
        this.decisions = decisions;
        this.pricingRequests = pricingRequests;
        this.costings = costings;
        this.tickets = tickets;
        this.fxRates = fxRates;
        this.notifications = notifications;
    }

    @Transactional
    public PricingDecisionDto startReview(long pricingRequestId, StartPricingDecisionRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        if (!PricingRequestStatus.READY_FOR_CEO_REVIEW.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request is not ready for CEO review");
        }
        requireActiveDeal(summary.ticketId());
        String clientRequestId = validateUuid(request.clientRequestId());
        decisions.lockPricingRequest(pricingRequestId);

        PricingCostingDto submittedCosting = requireLatestSubmittedCosting(pricingRequestId);
        String currency = firstText(request.currency(), firstText(summary.targetCurrency(), "THB")).toUpperCase();
        FxRateDto fx = FxResolver.resolve(fxRates, currency);
        BigDecimal defaultMarginPct = request.defaultMarginPct();

        CreateDecisionResult created = decisions.createDraft(pricingRequestId, submittedCosting.id(), defaultMarginPct,
            currency, fx.rateToThb(), fx.source(), fx.effectiveDate(), request.ceoNote(), clientRequestId, actor.id());
        long decisionId = created.decisionId();
        if (!created.created()) {
            PricingDecisionDto existing = requireDecision(decisionId);
            if (existing.pricingRequestId() != pricingRequestId) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "clientRequestId has already been used for another pricing request");
            }
            return existing;
        }

        List<WriteItem> writeItems = new ArrayList<>();
        for (PricingCostingItemDto item : submittedCosting.items()) {
            BigDecimal frozenPerPiece = item.landedCostPerUnitThb();
            BigDecimal frozenPerRequestedUnit = money4(
                item.totalLandedCostThb().divide(item.requestedQuantity(), 8, RoundingMode.HALF_UP));
            BigDecimal proposedSellingPrice = defaultMarginPct != null
                ? computeSellingPrice(frozenPerRequestedUnit, defaultMarginPct, fx.rateToThb(), currency)
                : null;
            writeItems.add(new WriteItem(item.pricingRequestItemId(), item.id(), item.requestedUnitBasis(),
                item.requestedQuantity(), item.normalizedQuantityPieces(), frozenPerPiece, frozenPerRequestedUnit,
                currency, defaultMarginPct, proposedSellingPrice));
        }
        decisions.insertItems(decisionId, writeItems);

        int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.READY_FOR_CEO_REVIEW,
            PricingRequestStatus.CEO_REVIEWING, null, null);
        if (transitioned == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request was changed by another user");
        }
        addEvent(summary, actor, PricingRequestEventKind.PRICING_DECISION_STARTED,
            PricingRequestStatus.READY_FOR_CEO_REVIEW, PricingRequestStatus.CEO_REVIEWING,
            "CEO เริ่มพิจารณาราคาขาย");
        return requireDecision(decisionId);
    }

    public PricingDecisionDto get(long decisionId, UserPrincipal actor) {
        requireRole(actor, RAW_DECISION_ROLES);
        return requireDecision(decisionId);
    }

    public List<PricingDecisionDto> list(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, RAW_DECISION_ROLES);
        requirePricingRequest(pricingRequestId);
        return decisions.findByPricingRequest(pricingRequestId);
    }

    @Transactional
    public PricingDecisionDto update(long decisionId, UpdatePricingDecisionRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        PricingDecisionDto decision = requireOpenDecisionForMutation(decisionId);
        decisions.updateDecisionNote(decisionId, request.ceoNote());
        if (request.items() != null && !request.items().isEmpty()) {
            applyItemUpdates(decision, request.items());
        }
        addEvent(decision.pricingRequestId(), actor, PricingRequestEventKind.PRICING_DECISION_UPDATED,
            "CEO แก้ไขราคาขายที่เสนอ");
        return requireDecision(decisionId);
    }

    @Transactional
    public PricingDecisionDto recalculate(long decisionId, RecalculatePricingDecisionRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        PricingDecisionDto decision = requireOpenDecisionForMutation(decisionId);
        BigDecimal bulkMargin = request.defaultMarginPct();
        if (bulkMargin != null) {
            requireValidMargin(bulkMargin);
            decisions.updateDefaultMargin(decisionId, bulkMargin);
        }
        List<ItemUpdate> updates = new ArrayList<>();
        for (PricingDecisionItemDto item : decision.items()) {
            BigDecimal margin = bulkMargin != null ? bulkMargin : item.proposedMarginPct();
            if (margin == null) {
                continue;
            }
            BigDecimal sellingPrice = computeSellingPrice(item.frozenLandedCostPerRequestedUnitThb(), margin,
                decision.fxRateUsed(), decision.currency());
            updates.add(new ItemUpdate(item.id(), margin, sellingPrice, null, null, null));
        }
        decisions.updateItems(decisionId, updates);
        addEvent(decision.pricingRequestId(), actor, PricingRequestEventKind.PRICING_DECISION_UPDATED,
            "CEO คำนวณราคาขายใหม่");
        return requireDecision(decisionId);
    }

    @Transactional
    public PricingDecisionDto approve(long decisionId, ApprovePricingDecisionRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        PricingDecisionDto preview = requireDecision(decisionId);
        decisions.lockPricingRequest(preview.pricingRequestId());
        String approveClientRequestId = validateUuid(request.clientRequestId());
        if (approveClientRequestId != null) {
            Optional<PricingDecisionDto> replay = decisions.findByApproveClientRequestId(actor.id(), approveClientRequestId);
            if (replay.isPresent()) {
                if (replay.get().id() != decisionId) {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "clientRequestId has already been used for another pricing decision");
                }
                return replay.get();
            }
        }
        PricingDecisionDto decision = requireDecision(decisionId);
        if (!PricingDecisionStatus.DRAFT.equals(decision.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Decision is not open for approval");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(decision.pricingRequestId());
        if (!PricingRequestStatus.CEO_REVIEWING.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request is not under CEO review");
        }
        requireActiveDeal(summary.ticketId());

        List<Long> missingMargin = new ArrayList<>();
        List<Long> missingMinimum = new ArrayList<>();
        for (PricingDecisionItemDto item : decision.items()) {
            if (item.proposedMarginPct() == null) {
                missingMargin.add(item.id());
            }
            if (item.minimumSellingPricePerRequestedUnit() == null) {
                missingMinimum.add(item.id());
            }
        }
        if (!missingMargin.isEmpty() || !missingMinimum.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Every item needs a margin and a minimum selling price before approval — missing margin: "
                    + missingMargin + ", missing minimum selling price: " + missingMinimum);
        }

        // Design correction 7: never trust a stored/client-supplied selling price at approval —
        // always recompute fresh from the frozen cost and the margin being frozen in.
        List<ApprovedItem> approvedItems = new ArrayList<>();
        for (PricingDecisionItemDto item : decision.items()) {
            BigDecimal approvedSellingPrice = computeSellingPrice(item.frozenLandedCostPerRequestedUnitThb(),
                item.proposedMarginPct(), decision.fxRateUsed(), decision.currency());
            approvedItems.add(new ApprovedItem(item.id(), item.proposedMarginPct(), approvedSellingPrice));
        }
        decisions.approveItems(decisionId, approvedItems);

        int approvedRows = decisions.approve(decisionId, actor.id(), request.ceoNote(), approveClientRequestId);
        if (approvedRows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Decision was changed by another user");
        }
        int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.CEO_REVIEWING,
            PricingRequestStatus.APPROVED_FOR_QUOTATION, null, null);
        if (transitioned == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request was changed by another user");
        }
        addEvent(summary, actor, PricingRequestEventKind.PRICING_DECISION_APPROVED,
            PricingRequestStatus.CEO_REVIEWING, PricingRequestStatus.APPROVED_FOR_QUOTATION,
            "CEO อนุมัติราคาขายแล้ว");
        notifications.notifyEmployeeForPricingRequest(summary.requestedById(), summary.id(),
            PricingRequestEventKind.PRICING_DECISION_APPROVED,
            "ใบขอราคา " + summary.requestCode() + " ได้รับอนุมัติราคาขายแล้ว");
        return requireDecision(decisionId);
    }

    @Transactional
    public PricingDecisionDto returnToImport(long decisionId, ReturnPricingDecisionRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        if (request.returnReason() == null || request.returnReason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "returnReason is required");
        }
        // Same lock-then-re-read discipline as approve(): return and approve are the two
        // mutually-exclusive terminal exits from DRAFT, so both must serialize against each
        // other (a CEO returning in one tab while approving in another must not let both win).
        PricingDecisionDto preview = requireDecision(decisionId);
        decisions.lockPricingRequest(preview.pricingRequestId());
        PricingDecisionDto decision = requireOpenDecisionForMutation(decisionId);
        PricingRequestSummaryDto summary = requirePricingRequest(decision.pricingRequestId());

        int returnedRows = decisions.returnToImport(decisionId, request.returnReason());
        if (returnedRows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Decision was changed by another user");
        }
        int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.CEO_REVIEWING,
            PricingRequestStatus.COSTING_REVISION_REQUIRED, null, null);
        if (transitioned == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request was changed by another user");
        }
        addEvent(summary, actor, PricingRequestEventKind.PRICING_DECISION_RETURNED,
            PricingRequestStatus.CEO_REVIEWING, PricingRequestStatus.COSTING_REVISION_REQUIRED,
            request.returnReason());
        if (summary.assignedImportId() != null) {
            notifications.notifyEmployeeForPricingRequest(summary.assignedImportId(), summary.id(),
                PricingRequestEventKind.PRICING_DECISION_RETURNED,
                "ใบขอราคา " + summary.requestCode() + " ถูก CEO ตีกลับให้แก้ไขต้นทุน");
        } else {
            notifications.notifyByRoleForPricingRequest("import", summary.id(),
                PricingRequestEventKind.PRICING_DECISION_RETURNED,
                "ใบขอราคา " + summary.requestCode() + " ถูก CEO ตีกลับให้แก้ไขต้นทุน");
        }
        return requireDecision(decisionId);
    }

    /** Design correction 2: the only entry point sales/sales_manager may use. */
    public PricingDecisionSalesViewDto salesView(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, SALES_VIEW_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        if ("sales".equals(actor.role()) && summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return decisions.findApprovedSalesView(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No approved pricing decision yet"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────

    private void applyItemUpdates(PricingDecisionDto decision, List<UpdatePricingDecisionItemRequest> requests) {
        Map<Long, PricingDecisionItemDto> byId = decision.items().stream()
            .collect(java.util.stream.Collectors.toMap(PricingDecisionItemDto::id, i -> i));
        List<ItemUpdate> updates = new ArrayList<>();
        for (UpdatePricingDecisionItemRequest req : requests) {
            PricingDecisionItemDto item = byId.get(req.pricingDecisionItemId());
            if (item == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Item " + req.pricingDecisionItemId() + " does not belong to this decision");
            }
            BigDecimal marginPct = req.marginPct();
            BigDecimal sellingPrice = null;
            if (marginPct != null) {
                requireValidMargin(marginPct);
                sellingPrice = computeSellingPrice(item.frozenLandedCostPerRequestedUnitThb(), marginPct,
                    decision.fxRateUsed(), decision.currency());
            }
            if (req.discountCeilingPct() != null
                    && (req.discountCeilingPct().compareTo(BigDecimal.ZERO) < 0
                        || req.discountCeilingPct().compareTo(BigDecimal.ONE) > 0)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "discountCeilingPct must be between 0 and 1");
            }
            if (req.minimumSellingPrice() != null && req.minimumSellingPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "minimumSellingPrice must not be negative");
            }
            updates.add(new ItemUpdate(item.id(), marginPct, sellingPrice, req.discountCeilingPct(),
                req.minimumSellingPrice(), req.decisionNote()));
        }
        int rows = decisions.updateItems(decision.id(), updates);
        if (rows != updates.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "Decision was changed by another user");
        }
    }

    private void requireValidMargin(BigDecimal marginPct) {
        if (marginPct.compareTo(MINUS_ONE) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "marginPct must be greater than -1 (selling price cannot be negative)");
        }
    }

    /** Selling price is always PER REQUESTED UNIT (design correction 1), computed fresh from the
     * frozen per-requested-unit cost and a margin fraction, converted through the decision's
     * pinned FX rate (design correction 6) — never taken verbatim from client input. */
    private BigDecimal computeSellingPrice(BigDecimal costPerRequestedUnitThb, BigDecimal marginPct,
                                           BigDecimal fxRateUsed, String currency) {
        BigDecimal sellingPriceThb = costPerRequestedUnitThb.multiply(BigDecimal.ONE.add(marginPct));
        BigDecimal price = "THB".equals(currency)
            ? sellingPriceThb
            : sellingPriceThb.divide(fxRateUsed, 8, RoundingMode.HALF_UP);
        return money4(price);
    }

    private PricingCostingDto requireLatestSubmittedCosting(long pricingRequestId) {
        List<PricingCostingDto> all = costings.findByPricingRequest(pricingRequestId);
        PricingCostingDto latest = null;
        for (PricingCostingDto costing : all) {
            if (PricingCostingStatus.SUBMITTED.equals(costing.status())) {
                latest = costing;
            }
        }
        if (latest == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request has no submitted costing");
        }
        return latest;
    }

    private PricingDecisionDto requireOpenDecisionForMutation(long decisionId) {
        PricingDecisionDto decision = requireDecision(decisionId);
        if (!PricingDecisionStatus.DRAFT.equals(decision.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Decision is not open for editing");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(decision.pricingRequestId());
        if (!PricingRequestStatus.CEO_REVIEWING.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request is not under CEO review");
        }
        requireActiveDeal(summary.ticketId());
        return decision;
    }

    private PricingDecisionDto requireDecision(long decisionId) {
        return decisions.find(decisionId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pricing decision not found"));
    }

    private PricingRequestSummaryDto requirePricingRequest(long pricingRequestId) {
        return pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pricing request not found"));
    }

    private void requireActiveDeal(long ticketId) {
        TicketSummaryDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"))
            .summary();
        if (!DealLifecycle.ACTIVE.equals(ticket.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "Parent deal must be ACTIVE");
        }
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void addEvent(PricingRequestSummaryDto summary, UserPrincipal actor, String kind,
                          String fromStatus, String toStatus, String message) {
        pricingRequests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(), kind, fromStatus, toStatus,
            message, null);
    }

    /** Non-transitioning event helper (update/recalculate don't move the pricing_request status). */
    private void addEvent(long pricingRequestId, UserPrincipal actor, String kind, String message) {
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
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

    private BigDecimal money4(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private String firstText(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return fallback;
    }
}
