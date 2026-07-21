package th.co.glr.hr.pricingcosting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteItemDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.FxRateDto;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigDto;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository.PricingCostingWriteItem;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

@Service
public class PricingCostingService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final Set<String> IMPORT_ROLES = Set.of("import");
    private static final Set<String> RAW_COSTING_ROLES = Set.of("import", "ceo");
    // Step 3 (CEO Selling Price Decision, "submitted costing is immutable... actually true"):
    // READY_FOR_CEO_REVIEW is deliberately EXCLUDED here, unlike before — Import used to be able
    // to silently reopen a SUBMITTED costing any time the request sat at READY_FOR_CEO_REVIEW,
    // which made a "submitted costing is immutable" claim false. COSTING_REVISION_REQUIRED is
    // the one replacement: it is only reachable via PricingDecisionService.returnToImport (the
    // CEO's own action), so createDraft can no longer bypass the CEO. See
    // PricingRequestStatus's ALLOWED map for the corresponding state-machine change.
    private static final Set<String> COSTING_CREATE_STATUSES = Set.of(
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.COSTING_IN_PROGRESS,
        PricingRequestStatus.COSTING_REVISION_REQUIRED);

    private final PricingCostingRepository costings;
    private final PricingRequestRepository pricingRequests;
    private final FactoryQuoteRepository factoryQuotes;
    private final TicketRepository tickets;
    private final FxRateRepository fxRates;
    private final PriceCalcConfigRepository priceConfigs;
    private final FactoryConfigRepository factoryConfigs;
    private final NotificationRepository notifications;

    public PricingCostingService(PricingCostingRepository costings, PricingRequestRepository pricingRequests,
                                 FactoryQuoteRepository factoryQuotes, TicketRepository tickets,
                                 FxRateRepository fxRates, PriceCalcConfigRepository priceConfigs,
                                 FactoryConfigRepository factoryConfigs, NotificationRepository notifications) {
        this.costings = costings;
        this.pricingRequests = pricingRequests;
        this.factoryQuotes = factoryQuotes;
        this.tickets = tickets;
        this.fxRates = fxRates;
        this.priceConfigs = priceConfigs;
        this.factoryConfigs = factoryConfigs;
        this.notifications = notifications;
    }

    @Transactional
    public PricingCostingDto createDraft(long pricingRequestId, CreateCostingRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        if (!COSTING_CREATE_STATUSES.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request is not ready for costing");
        }
        requireActiveDeal(summary.ticketId());
        resolveSources(summary);
        String clientRequestId = validateClientRequestId(request.clientRequestId());
        PricingCostingRepository.CreateDraftResult created =
            costings.createDraft(pricingRequestId, request.note(), clientRequestId, actor.id());
        long costingId = created.costingId();
        if (!created.created()) {
            PricingCostingDto existing = requireCosting(costingId);
            if (existing.pricingRequestId() != pricingRequestId) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "clientRequestId has already been used for another pricing request");
            }
            return existing;
        }
        if (!PricingRequestStatus.COSTING_IN_PROGRESS.equals(summary.status())) {
            int transitioned = pricingRequests.transition(summary.id(), summary.status(),
                PricingRequestStatus.COSTING_IN_PROGRESS, null, null);
            if (transitioned == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "Pricing request was changed by another user");
            }
        }
        addEvent(summary, actor, PricingRequestEventKind.PRICING_COSTING_STARTED, summary.status(),
            PricingRequestStatus.COSTING_IN_PROGRESS, "Costing draft created");
        notifyCeo(summary, PricingRequestEventKind.PRICING_COSTING_STARTED,
            "ใบขอราคา " + summary.requestCode() + " เริ่มร่างต้นทุน");
        return requireCosting(costingId);
    }

    public List<PricingCostingDto> list(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, RAW_COSTING_ROLES);
        requirePricingRequest(pricingRequestId);
        return costings.findByPricingRequest(pricingRequestId);
    }

    public PricingCostingDto get(long costingId, UserPrincipal actor) {
        requireRole(actor, RAW_COSTING_ROLES);
        return requireCosting(costingId);
    }

    @Transactional
    public PricingCostingDto recalculate(long costingId, RecalculateCostingRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        PricingCostingDto costing = requireCosting(costingId);
        if (PricingCostingStatus.SUBMITTED.equals(costing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Submitted costing is immutable");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(costing.pricingRequestId());
        if (!PricingRequestStatus.COSTING_IN_PROGRESS.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request must be COSTING_IN_PROGRESS before recalculation");
        }
        requireActiveDeal(summary.ticketId());
        CalculationResult result = calculate(summary);
        costings.replaceItems(costingId, result.items());
        int rows = costings.markCalculated(costingId, result.total(), request.note());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Costing cannot be recalculated in its current status");
        }
        addEvent(summary, actor, PricingRequestEventKind.PRICING_COSTING_CALCULATED, summary.status(), summary.status(),
            "Costing recalculated");
        if (PricingCostingStatus.DRAFT.equals(costing.status())) {
            notifyCeo(summary, PricingRequestEventKind.PRICING_COSTING_CALCULATED,
                "ใบขอราคา " + summary.requestCode() + " คำนวณต้นทุนแล้ว");
        }
        return requireCosting(costingId);
    }

    @Transactional
    public PricingCostingDto submit(long costingId, SubmitCostingRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        PricingCostingDto costing = requireCosting(costingId);
        if (costing.stale()) {
            throw new ApiException(HttpStatus.CONFLICT, "Costing is stale and must be recalculated before submit");
        }
        if (!PricingCostingStatus.CALCULATED.equals(costing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a calculated costing can be submitted");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(costing.pricingRequestId());
        if (!PricingRequestStatus.COSTING_IN_PROGRESS.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request must be COSTING_IN_PROGRESS before CEO submission");
        }
        requireActiveDeal(summary.ticketId());
        CalculationResult result = calculate(summary);
        costings.replaceItems(costingId, result.items());
        int rows = costings.submit(costingId, actor.id(), result.total(), request.note());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Costing was changed by another user");
        }
        int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.COSTING_IN_PROGRESS,
            PricingRequestStatus.READY_FOR_CEO_REVIEW, null, null);
        if (transitioned == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Pricing request was changed by another user");
        }
        addEvent(summary, actor, PricingRequestEventKind.PRICING_COSTING_SUBMITTED,
            PricingRequestStatus.COSTING_IN_PROGRESS, PricingRequestStatus.READY_FOR_CEO_REVIEW,
            "Costing submitted to CEO");
        notifyCeo(summary, PricingRequestEventKind.PRICING_COSTING_SUBMITTED,
            "ใบขอราคา " + summary.requestCode() + " ส่งต้นทุนให้ CEO แล้ว");
        return requireCosting(costingId);
    }

    /**
     * Finding B (financial-integrity review, commit 3): the price quoted by the factory and the
     * quantity requested by Sales can each be expressed in a different unit basis (the review's
     * worked example: factory quotes 1,000 THB/box, 20 pieces/box, Sales requests 10 boxes — the
     * pre-fix code computed {@code landedPerUnit * requestedQty} treating requestedQty as if it
     * were already in pieces, silently producing 1000/20*10 = 500 instead of 1000*10 = 10,000).
     * This method now normalizes BOTH the price and the requested quantity onto a common basis
     * (physical pieces) before multiplying: {@link #pricePerPiece} converts the raw factory price
     * to a per-piece THB figure using the quote's own unit basis, {@link #quantityToPieces}
     * converts requestedQty to a piece count using the request's own unit basis — the two bases
     * do not have to match, and each is looked up independently. freight/insurance/inland are
     * config values expressed per sqm of product, so they are converted to per-piece using the
     * line's sqm-per-piece conversion factor the same way regardless of either unit basis.
     */
    private CalculationResult calculate(PricingRequestSummaryDto summary) {
        List<ResolvedSource> sources = resolveSources(summary);
        List<PricingCostingWriteItem> writeItems = new ArrayList<>();
        BigDecimal total = ZERO;
        Instant calculatedAt = Instant.now();
        for (ResolvedSource source : sources) {
            FactoryConfigDto factoryConfig = factoryConfigs.findByName(source.quote().factoryName())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ไม่พบ factory config สำหรับโรงงาน: " + source.quote().factoryName()));
            String country = firstText(factoryConfig.country(), null);
            if (country == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "factory config ของ " + source.quote().factoryName() + " ไม่มีประเทศ");
            }
            PriceCalcConfigDto config = priceConfigs.findCurrentByCountry(country)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ไม่พบ price config สำหรับประเทศ: " + country));
            FxSnapshot fx = resolveFx(source.quoteItem().currency());

            BigDecimal sqmPerPiece = resolveSqmPerPiece(source.quoteItem(), source.requestItem());
            BigDecimal piecesPerBox = source.quoteItem().piecesPerBox();
            BigDecimal linearMPerUnit = source.quoteItem().linearMPerUnit();

            BigDecimal rawThb = source.quoteItem().rawUnitPrice().multiply(fx.rate());
            BigDecimal goodsCost = money4(pricePerPiece(rawThb, source.quoteItem().unitBasis(),
                sqmPerPiece, piecesPerBox, linearMPerUnit, source.requestItem()));

            String requestedUnitBasis = source.requestItem().requestedUnitBasis();
            BigDecimal qtyPieces = quantityToPieces(source.requestItem().requestedQty(), requestedUnitBasis,
                sqmPerPiece, piecesPerBox, linearMPerUnit, source.requestItem());

            BigDecimal freight = money4(config.freightPerSqm().multiply(sqmPerPiece));
            BigDecimal insurance = money4(config.insurancePerSqm().multiply(sqmPerPiece));
            BigDecimal cif = money4(goodsCost.add(freight).add(insurance));
            BigDecimal duty = money4(cif.multiply(config.importDutyPct()));
            BigDecimal inland = money4(config.inlandFactoryToPortPerSqm()
                .add(config.inlandPortToWarehousePerSqm()).multiply(sqmPerPiece));
            BigDecimal landedPerUnit = money4(cif.add(duty).add(inland));
            BigDecimal lineTotal = money4(landedPerUnit.multiply(qtyPieces));
            total = total.add(lineTotal);
            String snapshot = "{\"formula\":\"goods+freight+insurance+duty+inland\",\"calculatedAt\":\""
                + calculatedAt + "\",\"requestedUnitBasis\":\"" + requestedUnitBasis
                + "\",\"normalizedQuantityPieces\":\"" + qtyPieces + "\"}";
            writeItems.add(new PricingCostingWriteItem(
                source.requestItem().id(),
                source.quote().id(),
                source.quoteItem().id(),
                source.quote().revisionNo(),
                source.quote().factoryId(),
                source.quote().factoryName(),
                source.quote().supplierQuoteRef(),
                source.quoteItem().rawUnitPrice(),
                source.quoteItem().currency(),
                source.quoteItem().quotedUnit(),
                source.quoteItem().unitBasis(),
                source.requestItem().requestedQty(),
                source.requestItem().requestedUnit(),
                requestedUnitBasis,
                qtyPieces,
                linearMPerUnit,
                sqmPerPiece,
                piecesPerBox,
                fx.rate(),
                fx.source(),
                fx.effectiveDate(),
                fx.fetchedAt(),
                config.configId(),
                config.version(),
                goodsCost,
                freight,
                insurance,
                duty,
                inland,
                ZERO,
                cif,
                landedPerUnit,
                lineTotal,
                snapshot
            ));
        }
        return new CalculationResult(writeItems, money4(total));
    }

    private List<ResolvedSource> resolveSources(PricingRequestSummaryDto summary) {
        List<ResolvedSource> result = new ArrayList<>();
        for (PricingRequestItemDto item : pricingRequests.findItems(summary.id())) {
            String factoryName = firstText(item.resolvedFactoryName(), item.factory());
            if (factoryName == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Pricing request item " + item.id() + " has no resolved factory");
            }
            FactoryQuoteDto quote = factoryQuotes.findCurrentByFactory(summary.id(), factoryName)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No current factory quote for " + factoryName));
            if (!FactoryQuoteStatus.READY_FOR_COSTING.equals(quote.status())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Factory quote for " + factoryName + " is not ready for costing");
            }
            FactoryQuoteItemDto quoteItem = quote.items().stream()
                .filter(candidate -> candidate.pricingRequestItemId() == item.id())
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Factory quote for " + factoryName + " does not cover item " + item.id()));
            if (quoteItem.rawUnitPrice() == null || quoteItem.currency() == null
                    || quoteItem.quotedUnit() == null || quoteItem.unitBasis() == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Factory quote item " + quoteItem.id() + " is missing raw price, currency or unit");
            }
            result.add(new ResolvedSource(item, quote, quoteItem));
        }
        return result;
    }

    /**
     * Delegates to {@link th.co.glr.hr.pricing.FxResolver#resolve} (Step 3 extraction — see that
     * class's Javadoc). Behaviour unchanged from before the extraction.
     */
    private FxSnapshot resolveFx(String currencyValue) {
        FxRateDto rate = th.co.glr.hr.pricing.FxResolver.resolve(fxRates, currencyValue);
        return new FxSnapshot(rate.rateToThb(), rate.source(), rate.effectiveDate(), rate.fetchedAt());
    }

    private String validateClientRequestId(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(clientRequestId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId must be a valid UUID");
        }
    }

    private PricingRequestSummaryDto requirePricingRequest(long pricingRequestId) {
        return pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pricing request not found"));
    }

    private PricingCostingDto requireCosting(long costingId) {
        return costings.find(costingId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Costing not found"));
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

    private void notifyCeo(PricingRequestSummaryDto summary, String type, String message) {
        notifications.notifyByRoleForPricingRequest("ceo", summary.id(), type, message);
    }

    /**
     * The sqm-per-piece physical conversion factor for a line, needed unconditionally: freight/
     * insurance/inland are always priced per sqm of product (see {@link #calculate}), regardless
     * of whether either side's unit basis is PER_SQM. Prefers the factory quote item's own
     * {@code sqmPerUnit} (what Import entered when recording the response); falls back to the
     * pricing-request item's requestedQtySqm/requestedQty ratio only when the quote item did not
     * provide one. Unlike the pre-fix code, this does NOT silently default to 1 when neither
     * source has data — that was itself an unsafe assumption (freight/insurance/inland would be
     * costed as if every piece were exactly 1 sqm, which is very rarely true) — it 422s instead.
     */
    private BigDecimal resolveSqmPerPiece(FactoryQuoteItemDto quoteItem, PricingRequestItemDto requestItem) {
        BigDecimal fromQuote = quoteItem.sqmPerUnit();
        if (fromQuote != null && fromQuote.compareTo(ZERO) > 0) {
            return fromQuote;
        }
        if (requestItem.requestedQtySqm() != null && requestItem.requestedQty() != null
                && requestItem.requestedQty().compareTo(ZERO) > 0) {
            BigDecimal fromRequest = requestItem.requestedQtySqm().divide(requestItem.requestedQty(), 8, RoundingMode.HALF_UP);
            if (fromRequest.compareTo(ZERO) > 0) {
                return fromRequest;
            }
        }
        throw missingFactor(requestItem, "sqmPerUnit");
    }

    /**
     * Converts a factory-quoted price (already in THB) to a per-PIECE THB figure, using
     * whichever unit basis the QUOTE itself was expressed in — independent of the basis the
     * requested quantity is expressed in (see {@link #quantityToPieces}).
     */
    private BigDecimal pricePerPiece(BigDecimal rawThb, String quoteUnitBasis, BigDecimal sqmPerPiece,
                                     BigDecimal piecesPerBox, BigDecimal linearMPerUnit,
                                     PricingRequestItemDto requestItem) {
        return switch (quoteUnitBasis) {
            case UnitBasis.PER_PIECE -> rawThb;
            case UnitBasis.PER_BOX -> rawThb.divide(requireFactor(piecesPerBox, requestItem, "piecesPerBox"),
                8, RoundingMode.HALF_UP);
            case UnitBasis.PER_SQM -> rawThb.multiply(requireFactor(sqmPerPiece, requestItem, "sqmPerUnit"));
            case UnitBasis.PER_LINEAR_M -> rawThb.multiply(requireFactor(linearMPerUnit, requestItem, "linearMPerUnit"));
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Unsupported factory quote unit basis '" + quoteUnitBasis + "'");
        };
    }

    /**
     * Converts a requested quantity to a PIECE count, using whichever unit basis the REQUEST
     * itself was expressed in ({@code sales.pricing_request_item.requested_unit_basis}, V68) —
     * independent of the basis the factory's price was quoted in (see {@link #pricePerPiece}).
     * This is the direct fix for Finding B: the pre-fix code multiplied a per-piece landed cost
     * by requestedQty without ever converting it, so a PER_BOX request against a PER_BOX quote
     * silently under-costed by a factor of piecesPerBox (see the worked example in this class's
     * {@link #calculate} javadoc).
     */
    private BigDecimal quantityToPieces(BigDecimal requestedQty, String requestedUnitBasis, BigDecimal sqmPerPiece,
                                        BigDecimal piecesPerBox, BigDecimal linearMPerUnit,
                                        PricingRequestItemDto requestItem) {
        return switch (requestedUnitBasis) {
            case UnitBasis.PER_PIECE -> requestedQty;
            case UnitBasis.PER_BOX -> requestedQty.multiply(requireFactor(piecesPerBox, requestItem, "piecesPerBox"));
            case UnitBasis.PER_SQM -> requestedQty.divide(requireFactor(sqmPerPiece, requestItem, "sqmPerUnit"),
                8, RoundingMode.HALF_UP);
            case UnitBasis.PER_LINEAR_M -> requestedQty.divide(requireFactor(linearMPerUnit, requestItem, "linearMPerUnit"),
                8, RoundingMode.HALF_UP);
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Unsupported requested unit basis '" + requestedUnitBasis + "'");
        };
    }

    private BigDecimal requireFactor(BigDecimal value, PricingRequestItemDto requestItem, String factorName) {
        if (value == null || value.compareTo(ZERO) <= 0) {
            throw missingFactor(requestItem, factorName);
        }
        return value;
    }

    /** Names both the item and the missing factor, per the financial-integrity review's requirement. */
    private ApiException missingFactor(PricingRequestItemDto requestItem, String factorName) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
            "Pricing request item " + requestItem.id()
                + " is missing the " + factorName + " conversion factor needed to normalize its price/quantity");
    }

    private BigDecimal money4(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private String firstText(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return fallback != null && !fallback.isBlank() ? fallback.trim() : null;
    }

    private record ResolvedSource(PricingRequestItemDto requestItem, FactoryQuoteDto quote, FactoryQuoteItemDto quoteItem) {}
    private record FxSnapshot(BigDecimal rate, String source, LocalDate effectiveDate, Instant fetchedAt) {}
    private record CalculationResult(List<PricingCostingWriteItem> items, BigDecimal total) {}
}
