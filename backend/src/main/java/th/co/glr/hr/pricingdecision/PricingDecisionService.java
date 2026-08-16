package th.co.glr.hr.pricingdecision;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingItemDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesViewDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository.ApprovedItem;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository.CreateDecisionResult;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository.FrozenCostUpdate;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository.ItemUpdate;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository.WriteItem;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.CostOverrideRequest;
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
 * <p>V141 ("CEO owns costing"): {@link #startReview} now COMPUTES the costing itself (via {@link
 * LandedCostCalculator}), in the same transaction that creates the decision — Import no longer
 * submits one ({@code th.co.glr.hr.pricingcosting.PricingCostingService} is read-only). The CEO
 * may {@link #recalculateCost} the bound costing in place, or {@link #overrideItemCost} a single
 * line — both preserve any existing override; {@link #approve} refuses while any line's override
 * is stale (its FX rate or calc-config version moved since the override was entered).
 *
 * <pre>
 * READY_FOR_CEO_REVIEW -&gt; (CEO starts, computes cost) CEO_REVIEWING
 *     |-- approve -&gt; APPROVED_FOR_QUOTATION
 *     `-- return  -&gt; AWAITING_FACTORY_RESPONSE  (Import re-marks a factory quote ready; CEO
 *                      opens review again and the cost is recomputed from scratch)
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
    private final LandedCostCalculator landedCost;

    public PricingDecisionService(PricingDecisionRepository decisions, PricingRequestRepository pricingRequests,
                                  PricingCostingRepository costings, TicketRepository tickets,
                                  FxRateRepository fxRates, NotificationRepository notifications,
                                  LandedCostCalculator landedCost) {
        this.decisions = decisions;
        this.pricingRequests = pricingRequests;
        this.costings = costings;
        this.tickets = tickets;
        this.fxRates = fxRates;
        this.notifications = notifications;
        this.landedCost = landedCost;
    }

    @Transactional
    public PricingDecisionDto startReview(long pricingRequestId, StartPricingDecisionRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        if (!PricingRequestStatus.READY_FOR_CEO_REVIEW.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคานี้ยังไม่พร้อมส่งให้ CEO พิจารณา");
        }
        requireActiveDeal(summary.ticketId());
        String clientRequestId = validateUuid(request.clientRequestId());
        decisions.lockPricingRequest(pricingRequestId);

        // Re-read UNDER the lock — the check above is racy. Without this, the costing INSERT
        // below (createComputed) would run for the LOSER of a concurrent double-submit before it
        // discovers it lost, leaving an orphan sales.pricing_costing row. The explicit
        // clientRequestId replay lookup MUST come before the status re-check (not after): a
        // genuine retry of an already-succeeded call finds the pricing request has already moved
        // to CEO_REVIEWING, and a bare status re-check would wrongly 409 an idempotent retry
        // instead of returning its result — exactly the ordering approve() already uses for its
        // own clientRequestId check, below.
        summary = requirePricingRequest(pricingRequestId);
        if (clientRequestId != null) {
            Optional<PricingDecisionDto> replay = decisions.findByClientRequestId(actor.id(), clientRequestId);
            if (replay.isPresent()) {
                if (replay.get().pricingRequestId() != pricingRequestId) {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "clientRequestId นี้ถูกใช้ไปแล้วกับคำขอราคาอื่น");
                }
                return replay.get();
            }
        }
        if (!PricingRequestStatus.READY_FOR_CEO_REVIEW.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคานี้ยังไม่พร้อมส่งให้ CEO พิจารณา");
        }

        // V141 ("CEO owns costing"): the cost is computed HERE, once, deterministically, from
        // whichever factory quote is current right now — Import no longer submits a costing of
        // its own. 422s (via LandedCostCalculator.resolveSources) if any request item's factory
        // quote is not READY_FOR_COSTING — should not happen, since
        // FactoryQuoteService.markReadyForCosting only advances the pricing request to
        // READY_FOR_CEO_REVIEW once every item resolves, but THIS check, not that one, is the
        // authoritative gate for whether a costing can actually be computed.
        LandedCostCalculator.CalculationResult calc = landedCost.calculate(summary);
        long costingId = costings.createComputed(pricingRequestId, request.ceoNote(), actor.id(), calc.total());
        costings.replaceItemsPreservingOverrides(costingId, calc.items());
        PricingCostingDto submittedCosting = requireCosting(costingId);

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
                    "clientRequestId นี้ถูกใช้ไปแล้วกับคำขอราคาอื่น");
            }
            return existing;
        }

        List<WriteItem> writeItems = new ArrayList<>();
        for (PricingCostingItemDto item : submittedCosting.items()) {
            // Frozen from the EFFECTIVE cost, not the raw computed one, so an override flows into
            // price = cost x margin exactly like the computed figure would have (see
            // PricingCostingItemDto.effectiveLandedCostPerUnitThb/effectiveTotalLandedCostThb).
            BigDecimal frozenPerPiece = item.effectiveLandedCostPerUnitThb();
            BigDecimal frozenPerRequestedUnit = money4(
                item.effectiveTotalLandedCostThb().divide(item.requestedQuantity(), 8, RoundingMode.HALF_UP));
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
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
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
            updates.add(new ItemUpdate(item.id(), margin, sellingPrice, null, null, null, false));
        }
        decisions.updateItems(decisionId, updates);
        addEvent(decision.pricingRequestId(), actor, PricingRequestEventKind.PRICING_DECISION_UPDATED,
            "CEO คำนวณราคาขายใหม่");
        return requireDecision(decisionId);
    }

    /**
     * V141 ("CEO owns costing"): recomputes the bound costing IN PLACE (same {@code
     * pricing_costing_id} — a new FX rate or a factory-quote change since {@code startReview}
     * would otherwise leave the decision frozen on a stale price), preserving any existing
     * per-line override ({@link PricingCostingRepository#replaceItemsPreservingOverrides}), then
     * re-derives every decision item's frozen cost + proposed selling price from the (possibly
     * still-overridden) effective cost. DRAFT decisions only — {@link #requireOpenDecisionForMutation}
     * enforces that, same as every other CEO-editing action here.
     */
    @Transactional
    public PricingDecisionDto recalculateCost(long decisionId, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        PricingDecisionDto decision = requireOpenDecisionForMutation(decisionId);
        PricingRequestSummaryDto summary = requirePricingRequest(decision.pricingRequestId());
        LandedCostCalculator.CalculationResult calc = landedCost.calculate(summary);
        costings.replaceItemsPreservingOverrides(decision.pricingCostingId(), calc.items());
        PricingCostingDto refreshed = requireCosting(decision.pricingCostingId());
        Map<Long, PricingCostingItemDto> costingItemsByRequestItem = refreshed.items().stream()
            .collect(java.util.stream.Collectors.toMap(PricingCostingItemDto::pricingRequestItemId, i -> i));

        List<FrozenCostUpdate> updates = new ArrayList<>();
        for (PricingDecisionItemDto item : decision.items()) {
            PricingCostingItemDto costingItem = costingItemsByRequestItem.get(item.pricingRequestItemId());
            if (costingItem == null) {
                continue;
            }
            BigDecimal frozenPerPiece = costingItem.effectiveLandedCostPerUnitThb();
            BigDecimal frozenPerRequestedUnit = money4(
                costingItem.effectiveTotalLandedCostThb().divide(item.requestedQuantity(), 8, RoundingMode.HALF_UP));
            BigDecimal sellingPrice = item.proposedMarginPct() != null
                ? computeSellingPrice(frozenPerRequestedUnit, item.proposedMarginPct(), decision.fxRateUsed(), decision.currency())
                : null;
            updates.add(new FrozenCostUpdate(item.id(), frozenPerPiece, frozenPerRequestedUnit, sellingPrice));
        }
        decisions.updateFrozenCosts(decisionId, updates);
        addEvent(decision.pricingRequestId(), actor, PricingRequestEventKind.PRICING_DECISION_UPDATED,
            "CEO คำนวณต้นทุนใหม่");
        return requireDecision(decisionId);
    }

    /**
     * V141 ("CEO owns costing"): the only genuinely new behaviour — a per-line manual cost
     * override sitting BESIDE the computed figure, which is never destroyed. {@code itemId} is
     * the {@code pricing_decision_item} id (a sub-resource of this decision), resolved here to
     * its bound {@code pricing_costing_item_id}. {@code reason} is mandatory in BOTH directions —
     * clearing (manualLandedCostPerUnitThb == null) is money-affecting too, mirroring {@link
     * #returnToImport}'s own mandatory-reason check. Writing a value stamps {@code
     * override_fx_rate}/{@code override_calc_config_version} from THIS item's CURRENT computed
     * values — re-confirming the same value after a recalculate re-stamps them to whatever is
     * current then, which is how staleness clears (the CEO's escape hatch). DRAFT decisions only.
     */
    @Transactional
    public PricingDecisionDto overrideItemCost(long decisionId, long itemId, CostOverrideRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุเหตุผลในการปรับต้นทุน ไม่ว่าจะปรับหรือยกเลิกการปรับก็ตาม");
        }
        BigDecimal manualCost = request.manualLandedCostPerUnitThb();
        if (manualCost != null && manualCost.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้นทุนที่ปรับต้องไม่ติดลบ");
        }
        PricingDecisionDto decision = requireOpenDecisionForMutation(decisionId);
        PricingDecisionItemDto item = decision.items().stream()
            .filter(i -> i.id() == itemId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "รายการที่ " + itemId + " ไม่ได้เป็นของมติราคานี้"));
        PricingCostingDto costing = requireCosting(decision.pricingCostingId());
        PricingCostingItemDto costingItem = costing.items().stream()
            .filter(i -> i.id() == item.pricingCostingItemId())
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ไม่พบรายการต้นทุนที่ผูกกับมติราคานี้"));

        BigDecimal roundedManualCost = manualCost == null ? null : money4(manualCost);
        if (roundedManualCost != null) {
            costings.applyOverride(costingItem.id(), roundedManualCost, request.reason(), actor.id(),
                costingItem.fxRate(), costingItem.calculationConfigVersion());
        } else {
            costings.clearOverride(costingItem.id(), actor.id());
        }

        // landed_cost_per_unit_thb/normalized_quantity_pieces are untouched by an override write,
        // so re-deriving the effective figures from the ALREADY-fetched costingItem (rather than
        // re-querying) is safe and matches PricingCostingRepository#mapItem's own formula exactly.
        BigDecimal effectivePerPiece = roundedManualCost != null ? roundedManualCost : costingItem.landedCostPerUnitThb();
        BigDecimal effectiveTotal = money4(effectivePerPiece.multiply(costingItem.normalizedQuantityPieces()));
        BigDecimal frozenPerRequestedUnit = money4(effectiveTotal.divide(item.requestedQuantity(), 8, RoundingMode.HALF_UP));
        BigDecimal sellingPrice = item.proposedMarginPct() != null
            ? computeSellingPrice(frozenPerRequestedUnit, item.proposedMarginPct(), decision.fxRateUsed(), decision.currency())
            : null;
        decisions.updateFrozenCosts(decisionId, List.of(
            new FrozenCostUpdate(item.id(), effectivePerPiece, frozenPerRequestedUnit, sellingPrice)));

        addEvent(decision.pricingRequestId(), actor, PricingRequestEventKind.PRICING_COSTING_ITEM_COST_OVERRIDDEN,
            (roundedManualCost != null ? "CEO ปรับต้นทุนรายการที่ " : "CEO ล้างการปรับต้นทุนรายการที่ ")
                + item.id() + ": " + request.reason());
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
                        "clientRequestId นี้ถูกใช้ไปแล้วกับมติราคาอื่น");
                }
                return replay.get();
            }
        }
        PricingDecisionDto decision = requireDecision(decisionId);
        if (!PricingDecisionStatus.DRAFT.equals(decision.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "มติราคานี้ไม่ได้อยู่ในสถานะที่รออนุมัติ");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(decision.pricingRequestId());
        if (!PricingRequestStatus.CEO_REVIEWING.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคานี้ไม่ได้อยู่ระหว่างการพิจารณาของ CEO");
        }
        requireActiveDeal(summary.ticketId());

        // V141: refuse while ANY line of the bound costing carries a stale override (its FX rate
        // or calc-config version moved since the CEO entered the manual value) — approving one
        // would freeze a selling price built on a cost the CEO never actually confirmed against
        // current conditions. recalculateCost (re-derives every line, preserving overrides) or
        // re-confirming the SAME override value (which re-stamps its provenance, clearing
        // staleness) are the two ways past this.
        PricingCostingDto costing = requireCosting(decision.pricingCostingId());
        Map<Long, Long> decisionItemIdByCostingItemId = decision.items().stream()
            .collect(java.util.stream.Collectors.toMap(PricingDecisionItemDto::pricingCostingItemId, PricingDecisionItemDto::id));
        List<Long> staleItemIds = costing.items().stream()
            .filter(PricingCostingItemDto::overrideStale)
            .map(costingItem -> decisionItemIdByCostingItemId.getOrDefault(costingItem.id(), costingItem.id()))
            .toList();
        if (!staleItemIds.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ไม่สามารถอนุมัติได้ เนื่องจากมีรายการที่ปรับต้นทุนเองล้าสมัย "
                    + "(อัตราแลกเปลี่ยนหรือค่าคำนวณเปลี่ยนไปหลังปรับ) กรุณาคำนวณต้นทุนใหม่หรือยืนยันค่าที่ปรับอีกครั้งก่อนอนุมัติ "
                    + "— รายการที่ล้าสมัย: " + staleItemIds);
        }

        // Phase 1 UI simplification: an item with an active "ปรับราคาเอง" override needs no
        // margin at all — its price is fixed directly, the formula (and therefore margin) never
        // drives it. Only a NON-overridden item without a margin blocks approval now.
        List<Long> missingMargin = decision.items().stream()
            .filter(item -> item.proposedMarginPct() == null && item.manualSellingPricePerRequestedUnit() == null)
            .map(PricingDecisionItemDto::id)
            .toList();
        if (!missingMargin.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ทุกรายการต้องระบุ margin ก่อนอนุมัติ (หรือปรับราคาเอง) — รายการที่ยังไม่มี margin: " + missingMargin);
        }

        // Design correction 7: never trust a stored/client-supplied selling price at approval —
        // always recompute fresh from the frozen cost and the margin being frozen in. The ONE
        // deliberate exception is an active "ปรับราคาเอง" override (Phase 1 UI simplification):
        // there the CEO's own fixed value freezes in verbatim and the formula is not consulted at
        // all for that line, exactly as overrideItemCost already does for the cost side.
        //
        // ราคาขั้นต่ำ ("ราคาขั้นต่ำ") is no longer a CEO input in the UI (Phase 1 UI
        // simplification, owner ruling 2026-08-16) — the per-item text field is gone. Left unset,
        // minimum_selling_price_per_requested_unit would stay NULL forever, and
        // CustomerQuotationService's three below-minimum 422 guards are each explicitly
        // null-guarded (`item.minimumSellingPricePerRequestedUnit() != null && ...`), so a NULL
        // minimum does not merely fail open on ONE check — it silently disarms all three,
        // un-guarding every future discount on this request. Auto-populating it here with the
        // approved selling price itself closes that hole by construction: the CEO types nothing,
        // and — because floor == price — today's "any discount refused" outcome (Discount Policy
        // B's zero-width case) holds for every new decision without any special-casing downstream.
        // An explicitly-set LOWER floor is honoured, not overwritten, if one is already on the row
        // (still settable through PUT /pricing-decisions/{id}, which is unchanged) — only a still-
        // NULL minimum falls back to the approved price. See PricingDecisionCostOverrideValidation-
        // IntegrationTest's sibling in PricingDecisionMinimumPriceAutoPopulationIntegrationTest for
        // the wrong-way-round proof that a discounted quotation line is still refused.
        List<ApprovedItem> approvedItems = new ArrayList<>();
        for (PricingDecisionItemDto item : decision.items()) {
            BigDecimal approvedSellingPrice = item.manualSellingPricePerRequestedUnit() != null
                ? item.manualSellingPricePerRequestedUnit()
                : computeSellingPrice(item.frozenLandedCostPerRequestedUnitThb(), item.proposedMarginPct(),
                    decision.fxRateUsed(), decision.currency());
            BigDecimal minimumSellingPrice = item.minimumSellingPricePerRequestedUnit() != null
                ? item.minimumSellingPricePerRequestedUnit()
                : approvedSellingPrice;
            approvedItems.add(new ApprovedItem(item.id(), item.proposedMarginPct(), approvedSellingPrice,
                minimumSellingPrice));
        }
        decisions.approveItems(decisionId, approvedItems);

        int approvedRows = decisions.approve(decisionId, actor.id(), request.ceoNote(), approveClientRequestId);
        if (approvedRows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "มติราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.CEO_REVIEWING,
            PricingRequestStatus.APPROVED_FOR_QUOTATION, null, null);
        if (transitioned == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        addEvent(summary, actor, PricingRequestEventKind.PRICING_DECISION_APPROVED,
            PricingRequestStatus.CEO_REVIEWING, PricingRequestStatus.APPROVED_FOR_QUOTATION,
            "CEO อนุมัติราคาขายแล้ว");
        notifications.notifyEmployeeForPricingRequest(summary.requestedById(), summary.id(),
            PricingRequestEventKind.PRICING_DECISION_APPROVED,
            "คำขอราคา " + summary.requestCode() + " ได้รับอนุมัติราคาขายแล้ว");
        // V141: notify Import too — approval means the deal is moving on to quotation, useful
        // context for whoever has been renegotiating with the factory. Mirrors returnToImport's
        // own targeting (assignedImportId, else role broadcast "import").
        if (summary.assignedImportId() != null) {
            notifications.notifyEmployeeForPricingRequest(summary.assignedImportId(), summary.id(),
                PricingRequestEventKind.PRICING_DECISION_APPROVED,
                "คำขอราคา " + summary.requestCode() + " ได้รับอนุมัติราคาขายแล้ว");
        } else {
            notifications.notifyByRoleForPricingRequest("import", summary.id(),
                PricingRequestEventKind.PRICING_DECISION_APPROVED,
                "คำขอราคา " + summary.requestCode() + " ได้รับอนุมัติราคาขายแล้ว");
        }
        return requireDecision(decisionId);
    }

    @Transactional
    public PricingDecisionDto returnToImport(long decisionId, ReturnPricingDecisionRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        if (request.returnReason() == null || request.returnReason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุเหตุผลการตีกลับ");
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
            throw new ApiException(HttpStatus.CONFLICT, "มติราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        // V141: sends the request to AWAITING_FACTORY_RESPONSE, not a dedicated "revise the
        // costing" status — there is no standalone costing draft any more for Import to revise.
        // Import's only remaining job is to renegotiate/re-mark the factory quote(s) ready; the
        // CEO's next startReview recomputes the cost from scratch.
        int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.CEO_REVIEWING,
            PricingRequestStatus.AWAITING_FACTORY_RESPONSE, null, null);
        if (transitioned == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        addEvent(summary, actor, PricingRequestEventKind.PRICING_DECISION_RETURNED,
            PricingRequestStatus.CEO_REVIEWING, PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
            request.returnReason());
        if (summary.assignedImportId() != null) {
            notifications.notifyEmployeeForPricingRequest(summary.assignedImportId(), summary.id(),
                PricingRequestEventKind.PRICING_DECISION_RETURNED,
                "คำขอราคา " + summary.requestCode() + " ถูก CEO ตีกลับให้แก้ไขต้นทุน");
        } else {
            notifications.notifyByRoleForPricingRequest("import", summary.id(),
                PricingRequestEventKind.PRICING_DECISION_RETURNED,
                "คำขอราคา " + summary.requestCode() + " ถูก CEO ตีกลับให้แก้ไขต้นทุน");
        }
        return requireDecision(decisionId);
    }

    /** Design correction 2: the only entry point sales/sales_manager may use. */
    public PricingDecisionSalesViewDto salesView(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, SALES_VIEW_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        if ("sales".equals(actor.role()) && summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return decisions.findApprovedSalesView(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ยังไม่มีมติราคาที่ได้รับอนุมัติ"));
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
                    "รายการที่ " + req.pricingDecisionItemId() + " ไม่ได้เป็นของมติราคานี้");
            }
            // "ปรับราคาเอง" (Phase 1 UI simplification) — mirrors overrideItemCost's own check
            // ORDER exactly: reason (mandatory in BOTH directions) before the negative-amount
            // check, before anything else. Set and clear are mutually exclusive in one call.
            if (req.sellingPriceOverride() != null && req.clearSellingPriceOverride()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ระบุราคาที่ปรับพร้อมกับล้างค่าที่ปรับในคำขอเดียวกันไม่ได้");
            }
            boolean touchesPriceOverride = req.sellingPriceOverride() != null || req.clearSellingPriceOverride();
            if (touchesPriceOverride && (req.decisionNote() == null || req.decisionNote().isBlank())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ต้องระบุเหตุผลในการปรับราคาขาย ไม่ว่าจะปรับหรือยกเลิกการปรับก็ตาม");
            }
            if (req.sellingPriceOverride() != null && req.sellingPriceOverride().compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ราคาที่ปรับต้องไม่ติดลบ");
            }

            BigDecimal marginPct = req.marginPct();
            BigDecimal sellingPrice = null;
            if (marginPct != null) {
                requireValidMargin(marginPct);
                sellingPrice = computeSellingPrice(item.frozenLandedCostPerRequestedUnitThb(), marginPct,
                    decision.fxRateUsed(), decision.currency());
            }
            if (req.minimumSellingPrice() != null && req.minimumSellingPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ราคาขายขั้นต่ำต้องไม่ติดลบ");
            }
            updates.add(new ItemUpdate(item.id(), marginPct, sellingPrice,
                req.minimumSellingPrice(), req.decisionNote(),
                req.sellingPriceOverride(), req.clearSellingPriceOverride()));
        }
        int rows = decisions.updateItems(decision.id(), updates);
        if (rows != updates.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "มติราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
    }

    private void requireValidMargin(BigDecimal marginPct) {
        if (marginPct.compareTo(MINUS_ONE) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "marginPct ต้องมากกว่า -1 (ราคาขายต้องไม่ติดลบ)");
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

    private PricingCostingDto requireCosting(long costingId) {
        return costings.find(costingId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบการคำนวณต้นทุนนี้"));
    }

    private PricingDecisionDto requireOpenDecisionForMutation(long decisionId) {
        PricingDecisionDto decision = requireDecision(decisionId);
        if (!PricingDecisionStatus.DRAFT.equals(decision.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "มติราคานี้ไม่ได้อยู่ในสถานะที่แก้ไขได้");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(decision.pricingRequestId());
        if (!PricingRequestStatus.CEO_REVIEWING.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคานี้ไม่ได้อยู่ระหว่างการพิจารณาของ CEO");
        }
        requireActiveDeal(summary.ticketId());
        return decision;
    }

    private PricingDecisionDto requireDecision(long decisionId) {
        return decisions.find(decisionId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบมติราคานี้"));
    }

    private PricingRequestSummaryDto requirePricingRequest(long pricingRequestId) {
        return pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
    }

    private void requireActiveDeal(long ticketId) {
        TicketSummaryDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"))
            .summary();
        if (!DealLifecycle.ACTIVE.equals(ticket.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลต้นทางต้องอยู่ในสถานะ ACTIVE");
        }
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId ต้องเป็น UUID ที่ถูกต้อง");
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
