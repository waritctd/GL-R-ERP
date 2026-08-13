package th.co.glr.hr.pricingcosting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteItemDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;
import th.co.glr.hr.pricing.FxRateDto;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigDto;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRepository.PricingCostingWriteItem;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.UnitBasis;

/**
 * Extracted verbatim from {@link PricingCostingService} — no behaviour change. Fully
 * deterministic and requires zero human input: everything it produces is derived from the
 * factory quote, {@code sales.factory_config} (country), {@code sales.price_calc_config}, and
 * the FX rate. Extracted into its own bean so a later step can call it at CEO-review time from
 * {@code th.co.glr.hr.pricingdecision.PricingDecisionService} as well.
 */
@Component
public class LandedCostCalculator {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final FactoryQuoteRepository factoryQuotes;
    private final PricingRequestRepository pricingRequests;
    private final FxRateRepository fxRates;
    private final PriceCalcConfigRepository priceConfigs;
    private final FactoryConfigRepository factoryConfigs;

    public LandedCostCalculator(FactoryQuoteRepository factoryQuotes,
                                PricingRequestRepository pricingRequests,
                                FxRateRepository fxRates,
                                PriceCalcConfigRepository priceConfigs,
                                FactoryConfigRepository factoryConfigs) {
        this.factoryQuotes = factoryQuotes;
        this.pricingRequests = pricingRequests;
        this.fxRates = fxRates;
        this.priceConfigs = priceConfigs;
        this.factoryConfigs = factoryConfigs;
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
    public CalculationResult calculate(PricingRequestSummaryDto summary) {
        List<ResolvedSource> sources = resolveSources(summary);
        List<PricingCostingWriteItem> writeItems = new ArrayList<>();
        BigDecimal total = ZERO;
        Instant calculatedAt = Instant.now();
        for (ResolvedSource source : sources) {
            FactoryConfigDto factoryConfig = factoryConfigs.findByName(source.quote().factoryName())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "ไม่พบ factory config สำหรับโรงงาน: " + source.quote().factoryName()));
            String country = firstText(factoryConfig.country(), null);
            if (country == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "factory config ของ " + source.quote().factoryName() + " ไม่มีประเทศ");
            }
            PriceCalcConfigDto config = priceConfigs.findCurrentByCountry(country)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
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

    public List<ResolvedSource> resolveSources(PricingRequestSummaryDto summary) {
        List<ResolvedSource> result = new ArrayList<>();
        for (PricingRequestItemDto item : pricingRequests.findItems(summary.id())) {
            String factoryName = firstText(item.resolvedFactoryName(), item.factory());
            if (factoryName == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "รายการที่ " + item.id() + " ในคำขอราคายังไม่ได้ระบุโรงงาน");
            }
            FactoryQuoteDto quote = factoryQuotes.findCurrentByFactory(summary.id(), factoryName)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "ยังไม่มีใบเสนอราคาโรงงานสำหรับ " + factoryName));
            if (!FactoryQuoteStatus.READY_FOR_COSTING.equals(quote.status())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "ใบเสนอราคาของโรงงาน " + factoryName + " ยังไม่พร้อมสำหรับการคำนวณต้นทุน");
            }
            FactoryQuoteItemDto quoteItem = quote.items().stream()
                .filter(candidate -> candidate.pricingRequestItemId() == item.id())
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "ใบเสนอราคาของโรงงาน " + factoryName + " ไม่ครอบคลุมรายการที่ " + item.id()));
            if (quoteItem.rawUnitPrice() == null || quoteItem.currency() == null
                    || quoteItem.quotedUnit() == null || quoteItem.unitBasis() == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "รายการที่ " + quoteItem.id() + " ในใบเสนอราคาโรงงานยังไม่มีราคา สกุลเงิน หรือหน่วยนับ");
            }
            result.add(new ResolvedSource(item, quote, quoteItem));
        }
        return result;
    }

    /**
     * True when {@link #resolveSources} would succeed — the single definition of "ready to cost".
     * {@code FactoryQuoteService.markReadyForCosting} calls this to decide whether the LAST
     * outstanding factory quote just became ready (and therefore whether the pricing request
     * should auto-advance to {@code READY_FOR_CEO_REVIEW}); {@link #calculate} 422s via the same
     * {@link #resolveSources} check if it is ever called when this would return false. Sharing
     * one predicate for both is deliberate — "we said ready" and "the calculator can run" must
     * never be able to drift apart.
     */
    public boolean isFullyResolvable(PricingRequestSummaryDto summary) {
        try {
            resolveSources(summary);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    /**
     * Delegates to {@link th.co.glr.hr.pricing.FxResolver#resolve} (Step 3 extraction — see that
     * class's Javadoc). Behaviour unchanged from before the extraction.
     */
    private FxSnapshot resolveFx(String currencyValue) {
        FxRateDto rate = th.co.glr.hr.pricing.FxResolver.resolve(fxRates, currencyValue);
        return new FxSnapshot(rate.rateToThb(), rate.source(), rate.effectiveDate(), rate.fetchedAt());
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
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ไม่รองรับหน่วยนับของใบเสนอราคาโรงงาน '" + quoteUnitBasis + "'");
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
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ไม่รองรับหน่วยนับที่ขอ '" + requestedUnitBasis + "'");
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
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
            "รายการที่ " + requestItem.id()
                + " ในคำขอราคายังไม่มีค่าแปลงหน่วย " + factorName + " ที่จำเป็นสำหรับคำนวณราคา/จำนวน");
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

    public record ResolvedSource(PricingRequestItemDto requestItem, FactoryQuoteDto quote, FactoryQuoteItemDto quoteItem) {}
    public record FxSnapshot(BigDecimal rate, String source, LocalDate effectiveDate, Instant fetchedAt) {}
    public record CalculationResult(List<PricingCostingWriteItem> items, BigDecimal total) {}
}
