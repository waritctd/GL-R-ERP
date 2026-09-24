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
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.FxRateDto;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.FxResolver;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingDutyRateDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingItemDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
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
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ProductTypeOverrideRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ReturnPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.UnitBasis;
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
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PricingDecisionRepository decisions;
    private final PricingRequestRepository pricingRequests;
    private final PricingCostingRepository costings;
    private final TicketRepository tickets;
    private final FxRateRepository fxRates;
    private final NotificationRepository notifications;
    private final LandedCostCalculator landedCost;
    private final PricingFormulaEngine formulaEngine;

    public PricingDecisionService(PricingDecisionRepository decisions, PricingRequestRepository pricingRequests,
                                  PricingCostingRepository costings, TicketRepository tickets,
                                  FxRateRepository fxRates, NotificationRepository notifications,
                                  LandedCostCalculator landedCost, PricingFormulaEngine formulaEngine) {
        this.decisions = decisions;
        this.pricingRequests = pricingRequests;
        this.costings = costings;
        this.tickets = tickets;
        this.fxRates = fxRates;
        this.notifications = notifications;
        this.landedCost = landedCost;
        this.formulaEngine = formulaEngine;
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
        // Opus review minor #3 (2026-09-19): the SAME bound applyItemUpdates' marginPct branch
        // enforces (requireValidMargin) — a huge defaultMarginPct here would otherwise overflow
        // proposed_margin_pct/list_unit_price at INSERT time (insertItems below), as a raw 500
        // instead of a clean 400, before the CEO ever gets a chance to fix it through update().
        if (defaultMarginPct != null) {
            requireValidMargin(defaultMarginPct);
        }

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
            // V156: an UNCOSTABLE line has no effective cost at all (no freight lookup was
            // possible and no override exists yet), so it freezes as null and carries no proposed
            // price. It still becomes a decision item — that is the whole point: the CEO has to
            // see the line to resolve it, and approve() refuses while it stays this way.
            BigDecimal frozenPerRequestedUnit = item.effectiveTotalLandedCostThb() == null
                ? null
                : money4(item.effectiveTotalLandedCostThb().divide(item.requestedQuantity(), 8, RoundingMode.HALF_UP));
            BigDecimal proposedSellingPrice = defaultMarginPct != null && frozenPerRequestedUnit != null
                ? computeSellingPrice(frozenPerRequestedUnit, defaultMarginPct, fx.rateToThb(), currency)
                : null;
            // Owner ruling A (2026-09-19): the auto-calculated formula price becomes the NET
            // mode's starting list price (ราคาตั้ง) — a REAL stored value, not a UI placeholder,
            // so a CEO who picks NET and types nothing still has a computable, approvable net
            // (net = list x (1 - 0/100) = list). Rounded to money2, matching
            // pricing_decision_item.list_unit_price's NUMERIC(14,2) scale (V187) — see
            // computeNetUnitPrice's own "round to column scale before deriving" rule.
            BigDecimal listUnitPrice = proposedSellingPrice == null ? null : money2(proposedSellingPrice);
            writeItems.add(new WriteItem(item.pricingRequestItemId(), item.id(), item.requestedUnitBasis(),
                item.requestedQuantity(), item.normalizedQuantityPieces(), frozenPerPiece, frozenPerRequestedUnit,
                currency, defaultMarginPct, proposedSellingPrice, listUnitPrice));
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
        return stripPriceModeFieldsForNonCeo(requireDecision(decisionId), actor);
    }

    public List<PricingDecisionDto> list(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, RAW_DECISION_ROLES);
        requirePricingRequest(pricingRequestId);
        return decisions.findByPricingRequest(pricingRequestId).stream()
            .map(dto -> stripPriceModeFieldsForNonCeo(dto, actor))
            .toList();
    }

    @Transactional
    public PricingDecisionDto update(long decisionId, UpdatePricingDecisionRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        PricingDecisionDto decision = requireOpenDecisionForMutation(decisionId);

        boolean modeChanging = request.priceMode() != null && !request.priceMode().equals(decision.priceMode());
        if (request.priceMode() != null) {
            requireValidPriceMode(request.priceMode());
            // Phase 2 (owner rulings 2026-09-18/19): the mode picker only ever applies to a
            // new-form decision (Phase 1 / V185 PER_PIECE + sqm_per_piece items) — a legacy
            // decision keeps the margin/"ปรับราคาเอง" formula path untouched, so setting a mode on
            // one is refused rather than silently accepted and then never consulted.
            requireNewFormEligible(decision);
        }

        // Review finding #3 (2026-09-19): a call that touches NOTHING (no ceoNote, no priceMode
        // change, no items, or an items list whose every entry itself touches nothing — see
        // applyItemUpdates' own per-item no-op skip) must not bump updated_at, must not log
        // PRICING_DECISION_UPDATED, and must return the decision UNCHANGED — "saving with nothing
        // changed" was previously a silent-success no-op at the DB level that still looked like a
        // real save to the event trail and the caller.
        boolean headerTouched = request.ceoNote() != null || modeChanging;
        if (headerTouched) {
            decisions.updateDecisionHeader(decisionId, request.ceoNote(), request.priceMode());
        }

        String effectivePriceMode = request.priceMode() != null ? request.priceMode() : decision.priceMode();
        boolean itemsTouched = false;
        if (modeChanging) {
            // Review finding #2 (2026-09-19): switching the mode must not leave stale nets from
            // the OLD mode sitting in net_unit_price as if they were still valid under the NEW
            // one — recompute every item's net from ITS OWN stored inputs for the new mode,
            // ignoring whatever the old mode's inputs were, and going NULL where the item lacks
            // the new mode's required input (never silently freezing an old NET total under
            // DIRECT_NET, the review's own probe scenario).
            recomputeNetsForModeSwitch(decision, effectivePriceMode);
            itemsTouched = true;
        }
        if (request.items() != null && !request.items().isEmpty()) {
            itemsTouched = applyItemUpdates(decision, request.items(), effectivePriceMode) || itemsTouched;
        }

        if (headerTouched || itemsTouched) {
            addEvent(decision.pricingRequestId(), actor, PricingRequestEventKind.PRICING_DECISION_UPDATED,
                "CEO แก้ไขราคาขายที่เสนอ");
        }
        return requireDecision(decisionId);
    }

    /**
     * Review finding #2: recomputes EVERY item's {@code net_unit_price} for {@code newPriceMode}
     * from that item's own already-stored inputs — never reusing whatever the item happened to
     * compute under the PREVIOUS mode. An item that lacks the new mode's required input (e.g. no
     * {@code special_price_sqm} yet, right after switching TO {@code SPECIAL_SQM}) goes to NULL,
     * not to whatever stale figure the old mode left behind — {@link #computeNetUnitPriceOrNull}
     * is the null-instead-of-throw sibling of {@link #computeNetUnitPrice} that makes that safe to
     * do in bulk, across items that may each be in a different state of "filled in". The manual
     * "ปรับราคาเอง" override (owner ruling B) still applies as NET's effective list price here,
     * exactly as it does in {@link #applyItemUpdates} — a switch to NET right after setting an
     * override must not forget it.
     */
    private void recomputeNetsForModeSwitch(PricingDecisionDto decision, String newPriceMode) {
        List<ItemUpdate> updates = new ArrayList<>();
        for (PricingDecisionItemDto item : decision.items()) {
            BigDecimal effectiveListPrice = item.manualSellingPricePerRequestedUnit() != null
                ? item.manualSellingPricePerRequestedUnit() : item.listUnitPrice();
            BigDecimal net = computeNetUnitPriceOrNull(newPriceMode, effectiveListPrice, item.discountPct(),
                item.specialPriceSqm(), item.directNetPrice(), item.sqmPerPiece());
            updates.add(netOnlyUpdate(item.id(), net));
        }
        int rows = decisions.updateItems(decision.id(), updates);
        if (rows != updates.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "มติราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
    }

    private ItemUpdate netOnlyUpdate(long itemId, BigDecimal netUnitPrice) {
        return new ItemUpdate(itemId, null, null, null, null, null, false,
            null, false, null, false, null, false, null, false, netUnitPrice, true);
    }

    private static final Set<String> VALID_PRICE_MODES = Set.of(
        WastageCalculator.PRICE_MODE_NET, WastageCalculator.PRICE_MODE_SPECIAL_SQM,
        WastageCalculator.PRICE_MODE_DIRECT_NET);

    private void requireValidPriceMode(String priceMode) {
        if (!VALID_PRICE_MODES.contains(priceMode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "priceMode ต้องเป็น NET, SPECIAL_SQM หรือ DIRECT_NET");
        }
    }

    /**
     * Phase 2 eligibility (owner rulings 2026-09-18/19): the CEO price-mode picker is offered only
     * for a decision whose EVERY item comes from a Phase 1 (V185) new-form pricing-request item —
     * {@code requestedUnitBasis == PER_PIECE} and a non-null {@code sqmPerPiece}. A legacy decision
     * (any item missing either) keeps today's margin/"ปรับราคาเอง" behaviour, unchanged, forever —
     * this is the server-side backstop for the same rule the UI enforces by hiding/disabling the
     * mode picker (rule 6 of the phase's spec).
     */
    private void requireNewFormEligible(PricingDecisionDto decision) {
        if (!isNewFormEligible(decision)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "คำขอราคานี้เป็นรูปแบบเดิม (ไม่ได้ใช้แบบฟอร์มรายการแบบใหม่) ไม่สามารถเลือกวิธีกรอกราคาแบบ CEO ได้");
        }
    }

    private boolean isNewFormEligible(PricingDecisionDto decision) {
        return !decision.items().isEmpty() && decision.items().stream()
            .allMatch(item -> UnitBasis.PER_PIECE.equals(item.requestedUnitBasis()) && item.sqmPerPiece() != null);
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
        PricingDecisionDto recomputed = recomputeCostingInPlace(decision);
        addEvent(decision.pricingRequestId(), actor, PricingRequestEventKind.PRICING_DECISION_UPDATED,
            "CEO คำนวณต้นทุนใหม่");
        return recomputed;
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
        // Opus review minor #3 (2026-09-19): reject an overflowing override BEFORE it ever reaches
        // costings.applyOverride — probe was manualLandedCostPerUnitThb=1e15, which used to 500 on
        // the manual_landed_cost_per_unit_thb column write itself.
        if (manualCost != null && manualCost.compareTo(MAX_COST_18_4) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ต้นทุนที่ปรับเกินขอบเขตที่ระบบรองรับ (สูงสุด " + MAX_COST_18_4 + ")");
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
        // V156: CLEARING an override on an UNCOSTABLE line returns it to having no cost at all —
        // landedCostPerUnitThb is null there (the freight table could not be looked up), so the
        // derivation below must not run. The line goes back to needing the CEO's input, and
        // approve() blocks on it again, which is the correct end state rather than an NPE.
        BigDecimal effectiveTotal = effectivePerPiece == null
            ? null
            : money4(effectivePerPiece.multiply(costingItem.normalizedQuantityPieces()));
        BigDecimal frozenPerRequestedUnit = effectiveTotal == null
            ? null
            : money4(effectiveTotal.divide(item.requestedQuantity(), 8, RoundingMode.HALF_UP));
        BigDecimal sellingPrice = frozenPerRequestedUnit != null && item.proposedMarginPct() != null
            ? computeSellingPrice(frozenPerRequestedUnit, item.proposedMarginPct(), decision.fxRateUsed(), decision.currency())
            : null;
        ListAndNet listAndNet = deriveListAndNet(decision, item, sellingPrice);
        decisions.updateFrozenCosts(decisionId, List.of(
            new FrozenCostUpdate(item.id(), effectivePerPiece, frozenPerRequestedUnit, sellingPrice,
                listAndNet.listUnitPrice(), listAndNet.netUnitPrice())));

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

        // V156: a line whose freight could not be computed (no thickness or no origin country on
        // the catalogue row) now REACHES this screen with a null frozen cost, instead of aborting
        // costing at startReview. Here is where it must be resolved: the CEO either supplies the
        // landed cost (overrideItemCost) or fixes the price outright ("ปรับราคาเอง"). Approving
        // with neither would issue a quotation whose freight was silently omitted — the single
        // outcome the whole uncostable path exists to prevent. Checked BEFORE the margin gate
        // because a line with no cost has nothing for a margin to apply to.
        //
        // Opus review minor #1 (2026-09-19): "ปรับราคาเอง" clears this gate ONLY for the legacy
        // margin path and for a new-form NET-mode item — those are the two cases where the
        // override actually IS (or replaces) the price the item approves at (ruling B: for NET it
        // becomes the effective list price the discount applies to). For a new-form DIRECT_NET or
        // SPECIAL_SQM item, computeNetUnitPrice never even LOOKS at manualSellingPricePerRequestedUnit
        // — the approved price comes entirely from directNetPrice/specialPriceSqm — so letting the
        // override satisfy this gate let an item with ZERO real cost approve anyway (probe: an
        // uncosted DIRECT_NET item with directNetPrice=100 and an override of 300 approved at 100
        // with no cost backing it at all). Those two modes must clear this gate with a REAL cost.
        boolean overrideCanClearUncostedGate = decision.priceMode() == null
            || WastageCalculator.PRICE_MODE_NET.equals(decision.priceMode());
        List<Long> uncosted = decision.items().stream()
            .filter(item -> item.frozenLandedCostPerRequestedUnitThb() == null
                && !(overrideCanClearUncostedGate && item.manualSellingPricePerRequestedUnit() != null))
            .map(PricingDecisionItemDto::id)
            .toList();
        if (!uncosted.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ทุกรายการต้องมีต้นทุนก่อนอนุมัติ — คำนวณค่าขนส่งอัตโนมัติไม่ได้เพราะ Price Catalog "
                    + "ไม่มีความหนาหรือประเทศต้นทาง กรุณาระบุต้นทุนเอง หรือปรับราคาเอง — รายการที่ยังไม่มีต้นทุน: "
                    + uncosted);
        }

        // Phase 2 (owner rulings 2026-09-18/19): a new-form decision (non-null priceMode) is
        // driven entirely by the CEO's price-mode inputs — margin/"ปรับราคาเอง" are meaningless
        // for it, so the margin gate below is skipped, and this gate takes its place: every item
        // must carry a server-derived netUnitPrice (set the moment update() successfully resolves
        // one — see computeNetUnitPrice) before the decision may be approved.
        boolean newForm = decision.priceMode() != null;
        if (newForm) {
            List<Long> missingPrice = decision.items().stream()
                .filter(item -> item.netUnitPrice() == null)
                .map(PricingDecisionItemDto::id)
                .toList();
            if (!missingPrice.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "ทุกรายการต้องมีราคาตามวิธีกรอกราคาที่เลือกก่อนอนุมัติ — รายการที่ยังไม่มีราคา: " + missingPrice);
            }
        } else {
            // Phase 1 UI simplification: an item with an active "ปรับราคาเอง" override needs no
            // margin at all — its price is fixed directly, the formula (and therefore margin)
            // never drives it. Only a NON-overridden item without a margin blocks approval now.
            List<Long> missingMargin = decision.items().stream()
                .filter(item -> item.proposedMarginPct() == null && item.manualSellingPricePerRequestedUnit() == null)
                .map(PricingDecisionItemDto::id)
                .toList();
            if (!missingMargin.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "ทุกรายการต้องระบุ margin ก่อนอนุมัติ (หรือปรับราคาเอง) — รายการที่ยังไม่มี margin: " + missingMargin);
            }
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
            BigDecimal approvedSellingPrice;
            BigDecimal minimumSellingPrice;
            BigDecimal approvedMarginPct;
            if (newForm) {
                // Owner ruling: the CEO's own discount is PRE-approved — approved and minimum
                // freeze to the SAME net price (never a lower explicit floor, unlike the legacy
                // path below), so it can never trip the V155 per-line discount-approval gate on
                // the later customer quotation. No margin concept applies to a new-form line.
                approvedSellingPrice = item.netUnitPrice();
                minimumSellingPrice = item.netUnitPrice();
                approvedMarginPct = null;
            } else {
                approvedSellingPrice = item.manualSellingPricePerRequestedUnit() != null
                    ? item.manualSellingPricePerRequestedUnit()
                    : computeSellingPrice(item.frozenLandedCostPerRequestedUnitThb(), item.proposedMarginPct(),
                        decision.fxRateUsed(), decision.currency());
                minimumSellingPrice = item.minimumSellingPricePerRequestedUnit() != null
                    ? item.minimumSellingPricePerRequestedUnit()
                    : approvedSellingPrice;
                approvedMarginPct = item.proposedMarginPct();
            }
            approvedItems.add(new ApprovedItem(item.id(), approvedMarginPct, approvedSellingPrice,
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

    /** NUMERIC(14,2) upper bound — {@code list_unit_price}/{@code direct_net_price}. A value at or
     * above this overflows the column as a raw 500 instead of a clean 400 (review finding #4). */
    private static final BigDecimal MAX_PRICE_14_2 = new BigDecimal("999999999999.99");
    /** NUMERIC(12,2) upper bound — {@code special_price_sqm}. */
    private static final BigDecimal MAX_PRICE_12_2 = new BigDecimal("9999999999.99");
    /** NUMERIC(18,4) upper bound — {@code manual_landed_cost_per_unit_thb} (V141). Opus review
     * minor #3 (2026-09-19): an override this large (or a huge margin, see {@link
     * #MAX_MARGIN_PCT}) previously reached the DB as a raw numeric-overflow 500 instead of a clean
     * 400 — reproduced with an override of {@code 1e15}, which exceeds even this column's own
     * bound before the derived selling price is ever computed. */
    private static final BigDecimal MAX_COST_18_4 = new BigDecimal("99999999999999.9999");
    /** NUMERIC(9,6) upper bound — {@code proposed_margin_pct}/{@code approved_margin_pct} (V72). A
     * margin this large produces a derived selling price that overflows {@code list_unit_price}'s
     * NUMERIC(14,2) column long before it would overflow this one — {@link #deriveListAndNet}'s own
     * bound check is what actually catches that multiplicative case; this one exists so the RAW
     * margin itself never reaches the column as an overflowing 500 either. */
    private static final BigDecimal MAX_MARGIN_PCT = new BigDecimal("999.999999");

    /**
     * Returns whether anything was actually persisted for at least one item (review finding #3:
     * a caller that sends only untouched/no-op item entries must not count as a real change, so
     * {@link #update} can skip the event log and the "success" the frontend would otherwise toast
     * for a save that changed nothing).
     */
    private boolean applyItemUpdates(PricingDecisionDto decision, List<UpdatePricingDecisionItemRequest> requests,
                                     String effectivePriceMode) {
        Map<Long, PricingDecisionItemDto> byId = decision.items().stream()
            .collect(java.util.stream.Collectors.toMap(PricingDecisionItemDto::id, i -> i));
        List<ItemUpdate> updates = new ArrayList<>();
        for (UpdatePricingDecisionItemRequest req : requests) {
            PricingDecisionItemDto item = byId.get(req.pricingDecisionItemId());
            if (item == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "รายการที่ " + req.pricingDecisionItemId() + " ไม่ได้เป็นของมติราคานี้");
            }

            boolean touchesPriceOverride = req.sellingPriceOverride() != null || req.clearSellingPriceOverride();
            boolean touchesPriceModeValue = req.discountPct() != null || req.clearDiscountPct()
                || req.specialPriceSqm() != null || req.clearSpecialPriceSqm()
                || req.directNetPrice() != null || req.clearDirectNetPrice();
            boolean touchesAnything = touchesPriceOverride || touchesPriceModeValue
                || req.marginPct() != null || req.minimumSellingPrice() != null || req.decisionNote() != null;
            // Review finding #3: an item entry that touches NOTHING (the CEO clicked "save" on a
            // row they never edited) is a true no-op — skip it entirely rather than writing an
            // identical row and letting the caller believe something was saved.
            if (!touchesAnything) {
                continue;
            }

            // "ปรับราคาเอง" (Phase 1 UI simplification) — mirrors overrideItemCost's own check
            // ORDER exactly: reason (mandatory in BOTH directions) before the negative-amount
            // check, before anything else. Set and clear are mutually exclusive in one call.
            if (req.sellingPriceOverride() != null && req.clearSellingPriceOverride()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ระบุราคาที่ปรับพร้อมกับล้างค่าที่ปรับในคำขอเดียวกันไม่ได้");
            }
            if (touchesPriceOverride && (req.decisionNote() == null || req.decisionNote().isBlank())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ต้องระบุเหตุผลในการปรับราคาขาย ไม่ว่าจะปรับหรือยกเลิกการปรับก็ตาม");
            }
            if (req.sellingPriceOverride() != null && req.sellingPriceOverride().compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ราคาที่ปรับต้องไม่ติดลบ");
            }

            BigDecimal marginPct = req.marginPct();
            BigDecimal sellingPrice = null;
            boolean marginTouched = marginPct != null;
            if (marginTouched) {
                requireValidMargin(marginPct);
                // V156: a margin on an UNCOSTABLE line has nothing to apply to — the frozen cost
                // is null until the CEO supplies one. The margin is still stored (it becomes
                // meaningful the moment a cost override lands, which recomputes the price via
                // overrideItemCost); only the derived price stays null, and approve() blocks
                // until the cost exists.
                sellingPrice = item.frozenLandedCostPerRequestedUnitThb() == null
                    ? null
                    : computeSellingPrice(item.frozenLandedCostPerRequestedUnitThb(), marginPct,
                        decision.fxRateUsed(), decision.currency());
            }
            if (req.minimumSellingPrice() != null && req.minimumSellingPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ราคาขายขั้นต่ำต้องไม่ติดลบ");
            }

            // Owner correction (2026-09-19): a marginPct edit on the LEGACY path recomputes the
            // formula reference (sellingPrice) exactly like overrideItemCost/recomputeCostingInPlace
            // do — so list_unit_price (never a client input, see UpdatePricingDecisionItemRequest's
            // own Javadoc) must move with it here too, via the SAME deriveListAndNet helper those
            // two use, or a margin-only edit on a new-form-eligible decision would leave
            // list_unit_price stale. When marginPct was not part of this call, listUnitPriceInput
            // stays null/not-cleared, so the tri-state below leaves the column untouched.
            ListAndNet marginListAndNet = marginTouched ? deriveListAndNet(decision, item, sellingPrice) : null;
            BigDecimal listUnitPriceInput = marginTouched ? marginListAndNet.listUnitPrice() : null;
            boolean clearListUnitPrice = marginTouched && marginListAndNet.listUnitPrice() == null;

            // ── Phase 2 (owner rulings 2026-09-18/19): the CEO price-mode inputs ─────────────
            // Review finding #6: set + clear mutually exclusive, per field, same shape as the
            // legacy override check above.
            requireNotSetAndCleared(req.discountPct(), req.clearDiscountPct(), "ส่วนลด %");
            requireNotSetAndCleared(req.specialPriceSqm(), req.clearSpecialPriceSqm(), "ราคาพิเศษ บาท/ตร.ม.");
            requireNotSetAndCleared(req.directNetPrice(), req.clearDirectNetPrice(), "ราคาสุทธิต่อแผ่น");
            if (touchesPriceModeValue && effectivePriceMode == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "กรุณาเลือกวิธีกรอกราคา (priceMode) ก่อนกรอกราคาของรายการ");
            }
            // Review finding #4/#5: bound + round EVERY price-mode value to its column's scale
            // BEFORE it is stored or fed into the net derivation, so (a) an oversized value 400s
            // here instead of overflowing the NUMERIC column as a raw 500, and (b) the stored
            // input and the stored net can always be reproduced from each other — a >2dp value
            // (e.g. a discount of 12.345) rounds to what actually gets persisted (12.35), not a
            // silently different figure than what the net was derived from.
            BigDecimal discountPctInput = roundAndBoundPercent(req.discountPct());
            BigDecimal specialPriceSqmInput = roundAndBoundPrice(req.specialPriceSqm(), MAX_PRICE_12_2, "ราคาพิเศษ บาท/ตร.ม.");
            BigDecimal directNetPriceInput = roundAndBoundPrice(req.directNetPrice(), MAX_PRICE_14_2, "ราคาสุทธิต่อแผ่น");

            BigDecimal netUnitPrice = null;
            boolean netUnitPriceComputed = false;
            // Owner ruling B (2026-09-19): "ปรับราคาเอง" REPLACES the auto-calculated formula
            // price as NET's list price (ราคาตั้ง) when it is active — the discount then still
            // applies on top of it. A touch to the override, a price-mode value, OR marginPct
            // (which just moved list_unit_price itself, see above) means the net must be
            // re-derived from this item's fully-resolved inputs.
            if (effectivePriceMode != null && (touchesPriceModeValue || touchesPriceOverride || marginTouched)) {
                BigDecimal resolvedListUnitPrice = resolveTriState(
                    marginTouched, clearListUnitPrice, listUnitPriceInput, item.listUnitPrice());
                BigDecimal resolvedDiscountPct = resolveTriState(
                    req.discountPct() != null, req.clearDiscountPct(), discountPctInput, item.discountPct());
                BigDecimal resolvedSpecialPriceSqm = resolveTriState(
                    req.specialPriceSqm() != null, req.clearSpecialPriceSqm(), specialPriceSqmInput, item.specialPriceSqm());
                BigDecimal resolvedDirectNetPrice = resolveTriState(
                    req.directNetPrice() != null, req.clearDirectNetPrice(), directNetPriceInput, item.directNetPrice());
                BigDecimal resolvedManualOverride = req.clearSellingPriceOverride() ? null
                    : req.sellingPriceOverride() != null ? req.sellingPriceOverride()
                    : item.manualSellingPricePerRequestedUnit();
                BigDecimal effectiveListPrice = resolvedManualOverride != null ? resolvedManualOverride : resolvedListUnitPrice;
                netUnitPrice = computeNetUnitPrice(effectivePriceMode, effectiveListPrice, resolvedDiscountPct,
                    resolvedSpecialPriceSqm, resolvedDirectNetPrice, item.sqmPerPiece());
                netUnitPriceComputed = true;
            }
            updates.add(new ItemUpdate(item.id(), marginPct, sellingPrice,
                req.minimumSellingPrice(), req.decisionNote(),
                req.sellingPriceOverride(), req.clearSellingPriceOverride(),
                listUnitPriceInput, clearListUnitPrice,
                discountPctInput, req.clearDiscountPct(),
                specialPriceSqmInput, req.clearSpecialPriceSqm(),
                directNetPriceInput, req.clearDirectNetPrice(),
                netUnitPrice, netUnitPriceComputed));
        }
        if (updates.isEmpty()) {
            return false;
        }
        int rows = decisions.updateItems(decision.id(), updates);
        if (rows != updates.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "มติราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        return true;
    }

    private void requireNotSetAndCleared(BigDecimal value, boolean clear, String fieldNameTh) {
        if (value != null && clear) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ระบุ" + fieldNameTh + "พร้อมกับล้างค่าในคำขอเดียวกันไม่ได้");
        }
    }

    /** {@code touched} = an explicit set on THIS request; {@code cleared} = an explicit clear;
     * neither = leave the item's stored value as-is. The one place every price-mode field's
     * tri-state collapses to a single "what value does the derivation actually use" answer. */
    private BigDecimal resolveTriState(boolean touched, boolean cleared, BigDecimal newValue, BigDecimal storedValue) {
        if (cleared) {
            return null;
        }
        return touched ? newValue : storedValue;
    }

    private BigDecimal roundAndBoundPrice(BigDecimal value, BigDecimal max, String fieldNameTh) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, fieldNameTh + "ต้องไม่ติดลบ");
        }
        if (value.compareTo(max) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, fieldNameTh + "เกินขอบเขตที่ระบบรองรับ (สูงสุด " + max + ")");
        }
        return money2(value);
    }

    private BigDecimal roundAndBoundPercent(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(HUNDRED) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ส่วนลด % ต้องอยู่ระหว่าง 0-100");
        }
        return money2(value);
    }

    /**
     * Phase 2 (owner rulings 2026-09-18/19): derives the net price per requested unit (per แผ่น)
     * for whichever {@code priceMode} the decision uses — NEVER reimplements the arithmetic,
     * always routes through {@link WastageCalculator}, exactly as the direct-deal quotation editor
     * does for the identical three modes.
     *
     * <p>NET reuses {@link WastageCalculator#calculate} with a trivial single-piece, no-wastage,
     * no-box {@code Input} — {@code netUnitPrice} in that method's {@code Result} depends only on
     * {@code listPrice}/{@code discountPct} (never on the piece count), so this is the exact same
     * {@code round2(list × (1 − pct/100))} rounding the quotation editor pins, without duplicating
     * it as a second formula.
     */
    private BigDecimal computeNetUnitPrice(String priceMode, BigDecimal listUnitPrice, BigDecimal discountPct,
                                           BigDecimal specialPriceSqm, BigDecimal directNetPrice,
                                           BigDecimal sqmPerPiece) {
        if (WastageCalculator.PRICE_MODE_NET.equals(priceMode)) {
            if (listUnitPrice == null) {
                // Opus review minor #2 (2026-09-19): listUnitPrice is NEVER a field the CEO can
                // type any more (owner correction, same date) — it is null here only when the item
                // has no frozen cost yet AND no "ปรับราคาเอง" override (see deriveListAndNet's own
                // effectiveListPrice fallback). The old message ("กรุณาระบุราคา/หน่วย") pointed at
                // a field that no longer exists in the UI at all; point at the two REAL fixes
                // instead — supply a cost (ปรับต้นทุนเอง) or set the price directly (ปรับราคาตั้งเอง).
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "รายการนี้ยังไม่มีราคาตั้ง (ยังไม่มีต้นทุน) กรุณาระบุต้นทุนเอง (ปรับต้นทุนเอง) "
                        + "หรือปรับราคาตั้งเอง ก่อนกรอกส่วนลด");
            }
            BigDecimal discount = discountPct == null ? BigDecimal.ZERO : discountPct;
            WastageCalculator.Result result = WastageCalculator.calculate(new WastageCalculator.Input(
                null, WastageCalculator.QUANTITY_MODE_PIECES, null, 1,
                WastageCalculator.WASTAGE_MODE_NONE, null, null, listUnitPrice, discount));
            return result.netUnitPrice();
        }
        if (WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode)) {
            if (specialPriceSqm == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุราคาพิเศษ บาท/ตร.ม. (โหมดราคาพิเศษ)");
            }
            if (sqmPerPiece == null) {
                // Defence in depth: requireNewFormEligible already guarantees every item on a
                // mode-bearing decision has sqm_per_piece, but this guards the field individually
                // too, per the phase spec's explicit ask ("SPECIAL_SQM requires sqm_per_piece on
                // the item, Phase 1 makes it required, but guard anyway").
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "รายการนี้ไม่มี ตร.ม./แผ่น จึงคำนวณราคาพิเศษ บาท/ตร.ม. ไม่ได้");
            }
            try {
                return WastageCalculator.netPerPieceFromSpecialSqm(specialPriceSqm, sqmPerPiece);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ข้อมูลราคาไม่ถูกต้อง: " + e.getMessage());
            }
        }
        if (WastageCalculator.PRICE_MODE_DIRECT_NET.equals(priceMode)) {
            if (directNetPrice == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุราคาสุทธิต่อแผ่น (โหมดราคาสุทธิต่อแผ่น)");
            }
            return money2(directNetPrice);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "priceMode ต้องเป็น NET, SPECIAL_SQM หรือ DIRECT_NET");
    }

    /**
     * The null-instead-of-throw sibling of {@link #computeNetUnitPrice}, used ONLY by the
     * mode-switch recompute ({@link #recomputeNetsForModeSwitch}, review finding #2): a bulk
     * recompute across every item on the decision cannot afford to throw the moment ONE item
     * lacks the new mode's required input — that item's net should simply become NULL (the item
     * is not price-complete under the new mode yet, and {@code approve()}'s missing-price gate
     * catches it), while every OTHER item on the same decision still gets its own correct net.
     */
    private BigDecimal computeNetUnitPriceOrNull(String priceMode, BigDecimal listUnitPrice, BigDecimal discountPct,
                                                 BigDecimal specialPriceSqm, BigDecimal directNetPrice,
                                                 BigDecimal sqmPerPiece) {
        try {
            return computeNetUnitPrice(priceMode, listUnitPrice, discountPct, specialPriceSqm, directNetPrice, sqmPerPiece);
        } catch (ApiException e) {
            return null;
        }
    }

    private void requireValidMargin(BigDecimal marginPct) {
        if (marginPct.compareTo(MINUS_ONE) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "marginPct ต้องมากกว่า -1 (ราคาขายต้องไม่ติดลบ)");
        }
        // Opus review minor #3 (2026-09-19): a huge margin (the review's own probe) previously
        // produced a derived selling price that overflowed a NUMERIC column as a raw 500 — this
        // bounds the RAW margin itself; deriveListAndNet's own check catches the multiplicative
        // (cost × margin) overflow case even when this bound alone would not.
        if (marginPct.compareTo(MAX_MARGIN_PCT) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "marginPct เกินขอบเขตที่ระบบรองรับ (สูงสุด " + MAX_MARGIN_PCT + ")");
        }
    }

    /**
     * Selling price is always PER REQUESTED UNIT (design correction 1), computed fresh from the
     * frozen per-requested-unit cost and a margin fraction, converted through the decision's
     * pinned FX rate (design correction 6) — never taken verbatim from client input.
     *
     * <p>V109 engine wiring (V152), rounding changed by owner ruling 2026-09-19 (Phase 2 CEO
     * pricing): the formula is {@code SP = cost x (1+margin) x selling_buffer}, rounded HALF_UP to
     * 2dp — see {@link PricingFormulaEngine#sellingPrice}. No longer rounds UP to the nearest
     * {@code selling_price_round_up_to} (that column is now dead — see that method's own Javadoc).
     * Previously this was a bare {@code cost x (1+margin)}, with no buffer and no rounding rule at
     * all; {@code selling_buffer} is a deliberate COST BUFFER (not VAT — VAT stays untouched, added
     * separately at quotation time). The rounding happens in THB (landed cost is always THB, V72's
     * own comment), BEFORE any FX conversion to a non-THB decision currency.
     */
    private BigDecimal computeSellingPrice(BigDecimal costPerRequestedUnitThb, BigDecimal marginPct,
                                           BigDecimal fxRateUsed, String currency) {
        PricingFormulaConfigDto formulaConfig = formulaEngine.requireCurrentConfig();
        BigDecimal sellingPriceThb = formulaEngine.sellingPrice(costPerRequestedUnitThb, marginPct,
            formulaConfig.sellingBuffer());
        BigDecimal price = "THB".equals(currency)
            ? sellingPriceThb
            : sellingPriceThb.divide(fxRateUsed, 8, RoundingMode.HALF_UP);
        return money4(price);
    }

    /**
     * V152 (V109 engine wiring), owner ruling 2026-08-16: {@code product_type} has no source in
     * deal data today, so {@link LandedCostCalculator} defaults every item to TILE (30% duty). This
     * is the CEO's per-item escape hatch — e.g. โมเสคแก้ว, which must be taxed at 10%, not TILE's
     * 30% — reachable from the pricing-decision review screen (mirrors {@link #overrideItemCost}'s
     * shape: CEO-only, DRAFT-decision-only, then immediately recomputes so the CEO sees the new
     * duty applied without a separate manual "recalculate" click).
     *
     * <p>{@code productType == null} clears the override, reverting to the TILE default.
     * Non-null must name a product_type the CURRENT pricing_formula_config actually prices — never
     * silently accepted and then failing later at the freight/duty lookup.
     */
    @Transactional
    public PricingDecisionDto overrideItemProductType(long decisionId, long itemId, ProductTypeOverrideRequest request,
                                                       UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        PricingDecisionDto decision = requireOpenDecisionForMutation(decisionId);
        PricingDecisionItemDto item = decision.items().stream()
            .filter(i -> i.id() == itemId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "รายการที่ " + itemId + " ไม่ได้เป็นของมติราคานี้"));

        String productType = request.productType() == null || request.productType().isBlank()
            ? null : request.productType().trim();
        if (productType != null) {
            PricingFormulaConfigDto formulaConfig = formulaEngine.requireCurrentConfig();
            boolean known = formulaConfig.dutyRates().stream()
                .anyMatch(rate -> rate.productType().equals(productType));
            if (!known) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "ไม่พบประเภทสินค้า '" + productType + "' ในสูตรคำนวณราคาปัจจุบัน");
            }
        }
        pricingRequests.updateItemProductTypeOverride(item.pricingRequestItemId(), productType);

        PricingDecisionDto recomputed = recomputeCostingInPlace(decision);
        addEvent(decision.pricingRequestId(), actor, PricingRequestEventKind.PRICING_DECISION_UPDATED,
            productType != null
                ? "CEO ปรับประเภทสินค้ารายการที่ " + itemId + " เป็น " + productType + " (สำหรับคำนวณอากรขาเข้า)"
                : "CEO ล้างการปรับประเภทสินค้ารายการที่ " + itemId + " (กลับไปใช้ค่าเริ่มต้น)");
        return recomputed;
    }

    /**
     * Shared by {@link #recalculateCost} and {@link #overrideItemProductType}: recomputes the
     * bound costing IN PLACE (same {@code pricing_costing_id}, preserving any existing per-line
     * cost override — {@link PricingCostingRepository#replaceItemsPreservingOverrides}) and
     * re-derives every decision item's frozen cost + proposed selling price from the (possibly
     * still-overridden) effective cost.
     */
    private PricingDecisionDto recomputeCostingInPlace(PricingDecisionDto decision) {
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
            // V156: same as startReview — a recalculation that leaves a line uncostable refreezes
            // it as null rather than dividing by nothing.
            BigDecimal frozenPerRequestedUnit = costingItem.effectiveTotalLandedCostThb() == null
                ? null
                : money4(costingItem.effectiveTotalLandedCostThb().divide(item.requestedQuantity(), 8, RoundingMode.HALF_UP));
            BigDecimal sellingPrice = item.proposedMarginPct() != null && frozenPerRequestedUnit != null
                ? computeSellingPrice(frozenPerRequestedUnit, item.proposedMarginPct(), decision.fxRateUsed(), decision.currency())
                : null;
            ListAndNet listAndNet = deriveListAndNet(decision, item, sellingPrice);
            updates.add(new FrozenCostUpdate(item.id(), frozenPerPiece, frozenPerRequestedUnit, sellingPrice,
                listAndNet.listUnitPrice(), listAndNet.netUnitPrice()));
        }
        decisions.updateFrozenCosts(decision.id(), updates);
        return requireDecision(decision.id());
    }

    /** Small carrier for {@link #deriveListAndNet}'s two derived values — not persisted as its own
     * DTO, just a same-call-site pairing so the two callers below don't each hand-roll a
     * two-element return. */
    private record ListAndNet(BigDecimal listUnitPrice, BigDecimal netUnitPrice) {}

    /**
     * Owner correction (2026-09-19), fixing a gap the review's Blocker #1 fix left open: whenever
     * the formula reference ({@code proposedSellingPricePerRequestedUnit}, i.e. {@code
     * sellingPrice} here) is recomputed by a cost-driven path — {@link #overrideItemCost} directly,
     * or {@link #recomputeCostingInPlace} (shared by {@link #recalculateCost} and
     * {@link #overrideItemProductType}) — Phase 2's {@code list_unit_price} must move WITH it, and
     * the item's {@code net_unit_price} must be re-derived from the FRESH list price for the
     * decision's current mode. Ruling A: the CEO never types {@code list_unit_price} — it is always
     * {@code money2(sellingPrice)} (or null, when the line just became/stayed uncostable) — so
     * freezing it once at {@code startReview} and never refreshing it (the original design) meant
     * an item that starts uncostable could become costable via {@code overrideItemCost} yet stay
     * permanently unapprovable under NET mode, since {@code list_unit_price} never left null. This
     * is the single place that gap is closed, reused by both cost-recompute call sites so neither
     * can drift from the other.
     *
     * <p>Ruling B still applies exactly as {@link #recomputeNetsForModeSwitch}/
     * {@link #applyItemUpdates} apply it: an active "ปรับราคาเอง" override REPLACES the fresh list
     * price as NET's effective list price — a cost recompute must not silently re-surface the
     * formula price underneath an override the CEO deliberately set. {@link
     * #computeNetUnitPriceOrNull} (not the throwing sibling) is used because this call cannot
     * afford to blow up mid cost-recompute over one item lacking its mode's other inputs (e.g. a
     * SPECIAL_SQM item that has never had {@code special_price_sqm} typed yet) — that item's net
     * simply stays/becomes null, exactly like every other bulk recompute in this class. For a
     * legacy decision ({@code decision.priceMode() == null}), {@code net_unit_price} always comes
     * back null (there is no mode to compute a net under) — matching today's untouched behaviour
     * for the margin/"ปรับราคาเอง" path. */
    private ListAndNet deriveListAndNet(PricingDecisionDto decision, PricingDecisionItemDto item,
                                        BigDecimal sellingPrice) {
        BigDecimal listUnitPrice = sellingPrice == null ? null : money2(sellingPrice);
        // Opus review minor #3 (2026-09-19): cost x margin x buffer can overflow list_unit_price's
        // NUMERIC(14,2) column even when the cost and margin inputs each individually passed their
        // OWN bound (overrideItemCost's manualCost bound, requireValidMargin's margin bound) --
        // this is the one place that catches the MULTIPLICATIVE case, shared by every caller
        // (overrideItemCost, recomputeCostingInPlace, and applyItemUpdates' legacy marginPct
        // branch) so none of them can 500 on a numeric-overflow INSERT/UPDATE instead of 400ing
        // here first. @Transactional on every caller means this throw rolls back cleanly -- no
        // partial write (e.g. overrideItemCost's already-applied costings.applyOverride) survives.
        if (listUnitPrice != null && listUnitPrice.compareTo(MAX_PRICE_14_2) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ราคาที่คำนวณได้จากต้นทุนและ margin เกินขอบเขตที่ระบบรองรับ (สูงสุด " + MAX_PRICE_14_2
                    + ") กรุณาตรวจสอบต้นทุนหรือ margin ที่ปรับ");
        }
        BigDecimal effectiveListPrice = item.manualSellingPricePerRequestedUnit() != null
            ? item.manualSellingPricePerRequestedUnit() : listUnitPrice;
        BigDecimal netUnitPrice = computeNetUnitPriceOrNull(decision.priceMode(), effectiveListPrice,
            item.discountPct(), item.specialPriceSqm(), item.directNetPrice(), item.sqmPerPiece());
        return new ListAndNet(listUnitPrice, netUnitPrice);
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

    /** sales.pricing_decision_item's Phase 2 price columns are NUMERIC(14,2)/(12,2), mirroring
     * sales.quotation_item's own price columns (V49/V168) — 2dp, not this class's usual 4dp
     * money4 (used only for the pre-existing THB landed-cost/margin-formula figures). */
    private BigDecimal money2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Phase 2, CEO-only field stripping (owner rulings 2026-09-18/19): {@code get}/{@code list}
     * are the only entry points {@link #RAW_DECISION_ROLES} shares between {@code ceo} and
     * {@code import} — every mutation on this aggregate is already {@link #CEO_ROLES}-gated, so a
     * caller that reaches one of those already IS the ceo and needs no stripping. This nulls the
     * price-mode header and every per-item price-mode field for anyone who is not {@code ceo}
     * (import, in practice, since that is the only other role {@code RAW_DECISION_ROLES} admits) —
     * never touches cost/margin/formula fields, which import has always been entitled to see.
     */
    private PricingDecisionDto stripPriceModeFieldsForNonCeo(PricingDecisionDto decision, UserPrincipal actor) {
        if (CEO_ROLES.contains(actor.role())) {
            return decision;
        }
        List<PricingDecisionItemDto> strippedItems = decision.items().stream()
            .map(item -> new PricingDecisionItemDto(
                item.id(), item.pricingDecisionId(), item.pricingRequestItemId(), item.pricingCostingItemId(),
                item.brand(), item.model(), item.productDescription(), item.factoryName(),
                item.requestedUnitBasis(), item.requestedQuantity(), item.normalizedQuantityPieces(),
                item.frozenLandedCostPerPieceThb(), item.frozenLandedCostPerRequestedUnitThb(), item.currency(),
                item.proposedMarginPct(), item.approvedMarginPct(), item.proposedSellingPricePerRequestedUnit(),
                item.approvedSellingPricePerRequestedUnit(), item.minimumSellingPricePerRequestedUnit(),
                item.decisionNote(), item.createdAt(), item.updatedAt(), item.manualSellingPricePerRequestedUnit(),
                item.effectiveSellingPricePerRequestedUnit(), item.sqmPerPiece(),
                null, null, null, null, null))
            .toList();
        return new PricingDecisionDto(
            decision.id(), decision.decisionCode(), decision.pricingRequestId(), decision.pricingCostingId(),
            decision.decisionVersionNo(), decision.status(), decision.defaultMarginPct(), decision.currency(),
            decision.fxRateUsed(), decision.fxSource(), decision.fxEffectiveDate(), decision.ceoNote(),
            decision.returnReason(), decision.createdBy(), decision.createdAt(), decision.updatedAt(),
            decision.approvedBy(), decision.approvedAt(), decision.returnedAt(), strippedItems, null);
    }

    private String firstText(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return fallback;
    }
}
