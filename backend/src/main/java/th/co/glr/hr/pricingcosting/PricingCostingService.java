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
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

@Service
public class PricingCostingService {
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final Set<String> IMPORT_ROLES = Set.of("import");
    private static final Set<String> RAW_COSTING_ROLES = Set.of("import", "ceo");
    private static final Set<String> COSTING_CREATE_STATUSES = Set.of(
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.COSTING_IN_PROGRESS,
        PricingRequestStatus.READY_FOR_CEO_REVIEW);

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
            BigDecimal sqmPerUnit = source.quoteItem().sqmPerUnit() != null
                ? source.quoteItem().sqmPerUnit()
                : sqmPerUnit(source.requestItem());
            BigDecimal goodsCost = goodsCostForUnit(source.quoteItem(), fx, sqmPerUnit);
            goodsCost = money4(goodsCost);
            BigDecimal freight = money4(config.freightPerSqm().multiply(sqmPerUnit));
            BigDecimal insurance = money4(config.insurancePerSqm().multiply(sqmPerUnit));
            BigDecimal cif = money4(goodsCost.add(freight).add(insurance));
            BigDecimal duty = money4(cif.multiply(config.importDutyPct()));
            BigDecimal inland = money4(config.inlandFactoryToPortPerSqm()
                .add(config.inlandPortToWarehousePerSqm()).multiply(sqmPerUnit));
            BigDecimal landedPerUnit = money4(cif.add(duty).add(inland));
            BigDecimal lineTotal = money4(landedPerUnit.multiply(source.requestItem().requestedQty()));
            total = total.add(lineTotal);
            String snapshot = "{\"formula\":\"goods+freight+insurance+duty+inland\",\"calculatedAt\":\""
                + calculatedAt + "\"}";
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
                sqmPerUnit,
                source.quoteItem().piecesPerBox(),
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

    private FxSnapshot resolveFx(String currencyValue) {
        String currency = firstText(currencyValue, "THB").toUpperCase();
        if ("THB".equals(currency)) {
            return fxRates.findByCurrency("THB")
                .map(rate -> new FxSnapshot(rate.rateToThb(), rate.source(), rate.effectiveDate(), rate.fetchedAt()))
                .orElseGet(() -> new FxSnapshot(ONE, "THB", LocalDate.now(), null));
        }
        FxRateDto rate = fxRates.findByCurrency(currency)
            .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ไม่พบอัตราแลกเปลี่ยนสำหรับสกุลเงิน " + currency));
        if (!"BOT".equalsIgnoreCase(rate.source()) || rate.fetchedAt() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "อัตราแลกเปลี่ยน " + currency + " ต้องมาจาก BOT ก่อนคำนวณต้นทุน");
        }
        if (rate.effectiveDate() == null || rate.effectiveDate().isBefore(LocalDate.now().minusDays(7))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "อัตราแลกเปลี่ยน BOT สำหรับ " + currency + " เก่าเกินไป");
        }
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

    private BigDecimal sqmPerUnit(PricingRequestItemDto item) {
        if (item.requestedQtySqm() != null && item.requestedQty() != null
                && item.requestedQty().compareTo(ZERO) > 0) {
            return item.requestedQtySqm().divide(item.requestedQty(), 8, RoundingMode.HALF_UP);
        }
        return ONE;
    }

    private BigDecimal goodsCostForUnit(FactoryQuoteItemDto quoteItem, FxSnapshot fx, BigDecimal sqmPerUnit) {
        BigDecimal rawThb = quoteItem.rawUnitPrice().multiply(fx.rate());
        return switch (quoteItem.unitBasis()) {
            case "PER_SQM" -> rawThb.multiply(requirePositive(sqmPerUnit, "PER_SQM requires sqmPerUnit"));
            case "PER_PIECE" -> rawThb;
            case "PER_BOX" -> rawThb.divide(requirePositive(quoteItem.piecesPerBox(), "PER_BOX requires piecesPerBox"),
                8, RoundingMode.HALF_UP);
            case "PER_LINEAR_M" -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PER_LINEAR_M costing requires a configured linear-metre conversion");
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Unsupported factory quote unit basis '" + quoteItem.unitBasis() + "'");
        };
    }

    private BigDecimal requirePositive(BigDecimal value, String message) {
        if (value == null || value.compareTo(ZERO) <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
        }
        return value;
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
