package th.co.glr.hr.pricingcosting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteItemDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;
import th.co.glr.hr.pricing.FxRateDto;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingClearanceFeeDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingDutyRateDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFreightRateDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository.PricingCostingWriteItem;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.UnitBasis;

/**
 * V109 ENGINE SWAP (owner-authorised, 2026-08-16): computes each pricing-request item's landed
 * cost using the CEO's real selling-price formula (see {@link PricingFormulaEngine}'s class
 * Javadoc for the formula itself), read from {@code sales.pricing_formula_config}. Previously this
 * class read {@code sales.price_calc_config} (V26) — that table is untouched by this change (still
 * reachable via its own CRUD, {@code PriceCalcConfigController}) but is no longer read by the
 * pricing engine; see this branch's commit message for the "does anything else still read it"
 * audit.
 *
 * <h2>"Factory shipment" — how a pricing request maps to one</h2>
 * V109's formula defines {@code F} (freight) and {@code S} (clearance fee) as "flat THB, per
 * factory shipment", and {@code Q} as "that factory shipment's total sqm" — quantities that must
 * be shared across every item the shipment actually contains, not recomputed per item (doing the
 * latter would multiply-count a fixed shipping/customs cost once per item instead of splitting it
 * fairly across them). This codebase already has exactly the right grouping for "which items ship
 * together, from which factory, within this pricing request": {@code sales.factory_quote} (its
 * CURRENT revision) — unique per {@code (pricing_request_id, factory_name)} (see
 * {@code uq_factory_quote_current_factory}, V61) and already the unit {@link #resolveSources}
 * groups quote items under. A pricing request maps to ONE-OR-MORE factory shipments: one per
 * distinct factory in play (a request sourcing items from two factories produces two independent
 * shipments, each separately costed); several {@link PricingRequestItemDto}s sourced from the SAME
 * factory within the same request share ONE shipment. See {@link #costShipment} for the grouping
 * and per-item allocation this implies.
 *
 * <p>Freight additionally varies by {@code thickness_mm} (one of {@code
 * sales.pricing_freight_rate}'s own lookup keys, alongside origin country and quantity) — a
 * physical driver distinct from the shipment/factory grouping, since a shipment can in principle
 * carry more than one thickness. {@code F} is therefore looked up and allocated at the finer grain
 * of (shipment × thickness); {@code S} (clearance fee — the schema keys it on {@code qty_sqm}
 * alone, nothing else) stays at the whole-shipment grain. In the overwhelmingly common case (one
 * factory quote = one product spec = one thickness) both grains coincide with the whole shipment,
 * and the allocation described below collapses to "the item gets the whole flat amount",
 * unchanged from what a single-item shipment would produce on its own.
 *
 * <p>{@code C} (goods cost), {@code i} (insurance) and {@code T} (duty) stay genuinely per-item:
 * different products in the same shipment can have different factory prices, and — via the CEO's
 * per-item {@code product_type} override — different duty rates. This class therefore returns one
 * landed-cost row per {@link PricingRequestItemDto}, never one blended shipment-wide figure applied
 * uniformly to every item it contains.
 */
@Component
public class LandedCostCalculator {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final FactoryQuoteRepository factoryQuotes;
    private final PricingRequestRepository pricingRequests;
    private final FxRateRepository fxRates;
    private final FactoryConfigRepository factoryConfigs;
    private final CatalogRepository catalog;
    private final PricingFormulaEngine formulaEngine;

    public LandedCostCalculator(FactoryQuoteRepository factoryQuotes,
                                PricingRequestRepository pricingRequests,
                                FxRateRepository fxRates,
                                FactoryConfigRepository factoryConfigs,
                                CatalogRepository catalog,
                                PricingFormulaEngine formulaEngine) {
        this.factoryQuotes = factoryQuotes;
        this.pricingRequests = pricingRequests;
        this.fxRates = fxRates;
        this.factoryConfigs = factoryConfigs;
        this.catalog = catalog;
        this.formulaEngine = formulaEngine;
    }

    public CalculationResult calculate(PricingRequestSummaryDto summary) {
        PricingFormulaConfigDto formulaConfig = formulaEngine.requireCurrentConfig();
        List<ResolvedSource> sources = resolveSources(summary);

        Map<Long, List<ResolvedSource>> byShipment = new LinkedHashMap<>();
        for (ResolvedSource source : sources) {
            byShipment.computeIfAbsent(source.quote().id(), key -> new ArrayList<>()).add(source);
        }

        List<PricingCostingWriteItem> writeItems = new ArrayList<>();
        Instant calculatedAt = Instant.now();
        for (List<ResolvedSource> shipment : byShipment.values()) {
            writeItems.addAll(costShipment(shipment, formulaConfig, calculatedAt));
        }

        BigDecimal total = ZERO;
        for (PricingCostingWriteItem item : writeItems) {
            total = total.add(item.totalLandedCostThb());
        }
        return new CalculationResult(writeItems, money4(total));
    }

    /** See the class Javadoc for the grouping/allocation rules this implements. */
    private List<PricingCostingWriteItem> costShipment(List<ResolvedSource> shipment,
                                                        PricingFormulaConfigDto formulaConfig, Instant calculatedAt) {
        FactoryQuoteDto quote = shipment.get(0).quote();
        String factoryName = quote.factoryName();
        FactoryConfigDto factoryConfig = factoryConfigs.findByName(factoryName)
            .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ไม่พบ factory config สำหรับโรงงาน: " + factoryName));
        String originCountry = firstText(factoryConfig.country(), null);
        if (originCountry == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "factory config ของ " + factoryName + " ไม่มีประเทศ");
        }
        String shipmentLabel = "ใบเสนอราคาโรงงาน " + factoryName + " (" + quote.quoteCode() + ")";

        List<ItemPhysicals> items = new ArrayList<>();
        for (ResolvedSource source : shipment) {
            items.add(resolveItemPhysicals(source, formulaConfig));
        }

        // F: grouped by thickness within the shipment (see class Javadoc).
        Map<BigDecimal, BigDecimal> qtySqmByThickness = new LinkedHashMap<>();
        for (ItemPhysicals item : items) {
            qtySqmByThickness.merge(item.thicknessMm(), item.qtySqm(), BigDecimal::add);
        }
        Map<BigDecimal, BigDecimal> freightAmountByThickness = new LinkedHashMap<>();
        for (Map.Entry<BigDecimal, BigDecimal> entry : qtySqmByThickness.entrySet()) {
            BigDecimal thicknessMm = entry.getKey();
            BigDecimal thicknessGroupQtySqm = entry.getValue();
            PricingFreightRateDto freightRate = formulaEngine.selectFreightRate(formulaConfig.freightRates(),
                originCountry, thicknessMm, thicknessGroupQtySqm, shipmentLabel + " หนา " + thicknessMm + " มม.");
            freightAmountByThickness.put(thicknessMm, freightRate.amountThb());
        }

        // S: whole shipment, regardless of thickness.
        BigDecimal shipmentQtySqm = ZERO;
        for (BigDecimal thicknessGroupQtySqm : qtySqmByThickness.values()) {
            shipmentQtySqm = shipmentQtySqm.add(thicknessGroupQtySqm);
        }
        PricingClearanceFeeDto clearanceFee = formulaEngine.selectClearanceFee(
            formulaConfig.clearanceFees(), shipmentQtySqm, shipmentLabel);

        List<PricingCostingWriteItem> writeItems = new ArrayList<>();
        for (ItemPhysicals item : items) {
            BigDecimal thicknessGroupQtySqm = qtySqmByThickness.get(item.thicknessMm());
            BigDecimal freightAmountForThickness = freightAmountByThickness.get(item.thicknessMm());
            // F/S allocation is inherently a TOTAL-for-the-item quantity (a share of a flat,
            // shipment-level amount, proportional to the item's own share of the group's sqm —
            // see class Javadoc) — computed once here, in totals, then carried through the
            // formula's own C/i/F/T/S/TC/UC pipeline (also totals, per V109's own definition: TC
            // is dimensionally a total, only UC=TC/Q converts it to a per-sqm rate).
            BigDecimal freightShareTotal = allocate(freightAmountForThickness, item.qtySqm(), thicknessGroupQtySqm);
            BigDecimal clearanceShareTotal = allocate(clearanceFee.amountThb(), item.qtySqm(), shipmentQtySqm);

            PricingDutyRateDto dutyRate = formulaEngine.selectDutyRate(
                formulaConfig.dutyRates(), item.productType(), itemLabel(item.source().requestItem()));

            BigDecimal cifTotal = money4(item.goodsCostThb().add(item.insuranceThb()).add(freightShareTotal));
            BigDecimal dutyAmountTotal = formulaEngine.dutyAmount(cifTotal, dutyRate.dutyPct());
            BigDecimal totalLandedCostThb = formulaEngine.totalLandedCost(
                cifTotal, dutyAmountTotal, formulaConfig.costBuffer(), clearanceShareTotal);
            BigDecimal unitCostPerSqm = formulaEngine.unitCostPerSqm(totalLandedCostThb, item.qtySqm());
            BigDecimal landedCostPerUnitThb = money4(unitCostPerSqm.multiply(item.sqmPerPiece()));
            // Re-derive the total from the ROUNDED per-piece figure (not unitCostPerSqm/qtySqm
            // directly) so total_landed_cost_thb = landed_cost_per_unit_thb x
            // normalized_quantity_pieces holds EXACTLY — the invariant
            // PricingCostingRepository#mapItem's overrideStale/effective* derivations, and
            // PricingDecisionService's frozenPerRequestedUnit computation, both rely on.
            BigDecimal lineTotal = money4(landedCostPerUnitThb.multiply(item.qtyPieces()));

            // "Breakdown" columns (goods/freight/insurance/duty/cif/clearance) are stored PER
            // PIECE, matching this schema's pre-V109 convention (goods_cost_thb etc. were always
            // per-piece — only landed_cost_per_unit_thb/total_landed_cost_thb name their own
            // basis) — derived here by dividing each already-computed TOTAL by qtyPieces, which
            // is exactly consistent with the per-piece pipeline above by linearity (division
            // distributes over the additions/multiplications V109's formula is built from).
            BigDecimal freightPerPiece = money4(freightShareTotal.divide(item.qtyPieces(), 8, RoundingMode.HALF_UP));
            BigDecimal insurancePerPiece = money4(item.insuranceThb().divide(item.qtyPieces(), 8, RoundingMode.HALF_UP));
            BigDecimal dutyPerPiece = money4(dutyAmountTotal.divide(item.qtyPieces(), 8, RoundingMode.HALF_UP));
            BigDecimal cifPerPiece = money4(cifTotal.divide(item.qtyPieces(), 8, RoundingMode.HALF_UP));
            BigDecimal clearancePerPiece = money4(clearanceShareTotal.divide(item.qtyPieces(), 8, RoundingMode.HALF_UP));

            String snapshot = buildSnapshot(item, originCountry, freightAmountForThickness, freightShareTotal,
                thicknessGroupQtySqm, clearanceFee.amountThb(), clearanceShareTotal, shipmentQtySqm,
                dutyRate.dutyPct(), formulaConfig, calculatedAt);

            ResolvedSource source = item.source();
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
                source.requestItem().requestedUnitBasis(),
                item.qtyPieces(),
                source.quoteItem().linearMPerUnit(),
                item.sqmPerPiece(),
                source.quoteItem().piecesPerBox(),
                item.fx().rate(),
                item.fx().source(),
                item.fx().effectiveDate(),
                item.fx().fetchedAt(),
                // V152: repointed from sales.price_calc_config to sales.pricing_formula_config —
                // see V152's migration header for why this column pair is reused, not replaced.
                formulaConfig.formulaConfigId(),
                formulaConfig.version(),
                item.goodsCostPerPiece(),
                freightPerPiece,
                insurancePerPiece,
                dutyPerPiece,
                ZERO, // inland_transport_cost_thb -- V109 has no inland-transport term at all
                ZERO, // other_cost_thb -- clearance fee now has its own dedicated column
                cifPerPiece,
                landedCostPerUnitThb,
                lineTotal,
                clearancePerPiece,
                item.productType(),
                snapshot
            ));
        }
        return writeItems;
    }

    private ItemPhysicals resolveItemPhysicals(ResolvedSource source, PricingFormulaConfigDto formulaConfig) {
        BigDecimal sqmPerPiece = resolveSqmPerPiece(source.quoteItem(), source.requestItem());
        BigDecimal piecesPerBox = source.quoteItem().piecesPerBox();
        BigDecimal linearMPerUnit = source.quoteItem().linearMPerUnit();
        FxSnapshot fx = resolveFx(source.quoteItem().currency());

        BigDecimal rawThb = source.quoteItem().rawUnitPrice().multiply(fx.rate());
        BigDecimal goodsCostPerPiece = money4(pricePerPiece(rawThb, source.quoteItem().unitBasis(),
            sqmPerPiece, piecesPerBox, linearMPerUnit, source.requestItem()));
        BigDecimal qtyPieces = quantityToPieces(source.requestItem().requestedQty(),
            source.requestItem().requestedUnitBasis(), sqmPerPiece, piecesPerBox, linearMPerUnit, source.requestItem());
        BigDecimal qtySqm = sqmPerPiece.multiply(qtyPieces);
        // C = P x E, goods cost in THB, for this item's FULL requested quantity (a TOTAL, not a
        // per-piece figure) — see PricingFormulaEngine's class Javadoc for why C must be a total
        // (TC = (C+i+F)x(1+T)xcost_buffer + S is dimensionally a total, divided by Q at the very
        // end to recover a per-sqm unit cost).
        BigDecimal goodsCostThb = money4(goodsCostPerPiece.multiply(qtyPieces));
        BigDecimal insuranceThb = formulaEngine.insurance(goodsCostThb, formulaConfig);
        BigDecimal thicknessMm = resolveThicknessMm(source.requestItem());
        String productType = resolveProductType(source.requestItem());
        return new ItemPhysicals(source, sqmPerPiece, qtyPieces, qtySqm, goodsCostPerPiece, goodsCostThb,
            insuranceThb, thicknessMm, productType, fx);
    }

    /**
     * Thickness comes ONLY from the catalog link — {@code price_catalog.product_prices.thickness_mm}
     * via {@code pricing_request_item.catalog_price_id} (the submit-time snapshot, V61), falling
     * back to the live {@code product_id} (both columns point at the SAME target,
     * {@code price_catalog.product_prices.price_id}, V68) when the snapshot has not run yet —
     * never guessed, never defaulted. An item with no resolvable catalog link at all is a
     * legitimate case (Import may submit a free-text line with no catalog match, owner ruling
     * 2026-08-11 — {@code PricingRequestService#submit}'s own comment), and a matched catalog row
     * can still have a NULL {@code thickness_mm}. Either way this fails costing LOUDLY here,
     * naming the item, so Import can fix the data — it never silently prices as if thickness did
     * not matter (V109's freight table cannot be looked up at all without one).
     */
    private BigDecimal resolveThicknessMm(PricingRequestItemDto requestItem) {
        Long priceId = requestItem.catalogPriceId() != null ? requestItem.catalogPriceId() : requestItem.productId();
        BigDecimal thicknessMm = priceId == null ? null : catalog.findThicknessMm(priceId).orElse(null);
        if (thicknessMm == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                itemLabel(requestItem) + " ไม่มีความหนา (thickness_mm) จาก Price Catalog "
                    + "— ไม่สามารถคำนวณค่าขนส่งได้ กรุณาเชื่อมรายการนี้กับสินค้าใน Price Catalog ที่มีความหนาระบุไว้");
        }
        return thicknessMm;
    }

    /** Owner ruling 2026-08-16: {@code product_type} has no source in deal data today, so every
     * item defaults to TILE; the CEO may override this per item
     * ({@code PricingDecisionService#overrideItemProductType}) — e.g. โมเสคแก้ว, which must NOT be
     * taxed at TILE's 30% when 10% is correct. */
    private String resolveProductType(PricingRequestItemDto requestItem) {
        String override = requestItem.productTypeOverride();
        return override != null && !override.isBlank() ? override.trim() : PricingFormulaEngine.DEFAULT_PRODUCT_TYPE;
    }

    private String itemLabel(PricingRequestItemDto requestItem) {
        String brandModel = firstText(join(requestItem.brand(), requestItem.model()), null);
        String description = firstText(brandModel, requestItem.productDescription());
        return "รายการที่ " + requestItem.id() + (description != null ? " (" + description + ")" : "");
    }

    private String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a.trim() + " " + b.trim();
    }

    /**
     * item's proportional share of a shipment/sub-group-flat amount, by sqm — the literal reading
     * of "a shipment-flat cost divided across the items that share it". A single-item group has
     * ratio 1 by construction (the item's own qty IS the group's total), so this collapses to "the
     * item gets the whole flat amount" exactly when nothing is actually being shared — the common
     * case, and the only case most existing tests exercise.
     */
    private BigDecimal allocate(BigDecimal flatAmountThb, BigDecimal itemQtySqm, BigDecimal groupQtySqm) {
        BigDecimal ratio = itemQtySqm.divide(groupQtySqm, 12, RoundingMode.HALF_UP);
        return money4(flatAmountThb.multiply(ratio));
    }

    private String buildSnapshot(ItemPhysicals item, String originCountry, BigDecimal freightAmountForThickness,
                                 BigDecimal freightShare, BigDecimal thicknessGroupQtySqm, BigDecimal clearanceAmount,
                                 BigDecimal clearanceShare, BigDecimal shipmentQtySqm, BigDecimal dutyPct,
                                 PricingFormulaConfigDto formulaConfig, Instant calculatedAt) {
        // Every value quoted as a JSON STRING (never a bare number) — mirrors the pre-V109
        // snapshot's own convention (it quoted requestedUnitBasis/normalizedQuantityPieces the
        // same way) specifically to avoid any BigDecimal-formatting edge case producing a token
        // the jsonb column would reject at insert time.
        return "{\"formula\":\"V109\""
            + ",\"calculatedAt\":\"" + calculatedAt + "\""
            + ",\"formulaConfigId\":\"" + formulaConfig.formulaConfigId() + "\""
            + ",\"formulaConfigVersion\":\"" + formulaConfig.version() + "\""
            + ",\"originCountry\":\"" + originCountry + "\""
            + ",\"thicknessMm\":\"" + item.thicknessMm() + "\""
            + ",\"productType\":\"" + item.productType() + "\""
            + ",\"itemQtySqm\":\"" + item.qtySqm() + "\""
            + ",\"freightThicknessGroupQtySqm\":\"" + thicknessGroupQtySqm + "\""
            + ",\"freightAmountThbForGroup\":\"" + freightAmountForThickness + "\""
            + ",\"freightShareThb\":\"" + freightShare + "\""
            + ",\"shipmentQtySqm\":\"" + shipmentQtySqm + "\""
            + ",\"clearanceAmountThbForShipment\":\"" + clearanceAmount + "\""
            + ",\"clearanceShareThb\":\"" + clearanceShare + "\""
            + ",\"dutyPct\":\"" + dutyPct + "\""
            + ",\"costBuffer\":\"" + formulaConfig.costBuffer() + "\""
            + ",\"requestedUnitBasis\":\"" + item.source().requestItem().requestedUnitBasis() + "\""
            + ",\"normalizedQuantityPieces\":\"" + item.qtyPieces() + "\"}";
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
     * insurance are always priced against sqm-derived quantities (see {@link #costShipment}),
     * regardless of whether either side's unit basis is PER_SQM. Prefers the factory quote item's
     * own {@code sqmPerUnit} (what Import entered when recording the response); falls back to the
     * pricing-request item's requestedQtySqm/requestedQty ratio only when the quote item did not
     * provide one. Does NOT silently default to 1 when neither source has data — that would cost
     * every piece as if it were exactly 1 sqm, very rarely true — it 422s instead.
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

    /** Per-item resolution pass, before shipment-level F/S lookup/allocation — see
     * {@link #costShipment}. {@code goodsCostPerPiece} and {@code goodsCostThb} (== C, the
     * TOTAL for this item's full requested quantity) are both kept: the formula pipeline
     * (C/i/F/T/S/TC/UC) runs in totals, but the stored "breakdown" columns report per-piece
     * (see {@link #costShipment}'s own comment) — keeping the direct per-piece value here avoids
     * re-deriving it by dividing the total back down, which would compound a second rounding. */
    private record ItemPhysicals(ResolvedSource source, BigDecimal sqmPerPiece, BigDecimal qtyPieces,
                                 BigDecimal qtySqm, BigDecimal goodsCostPerPiece, BigDecimal goodsCostThb,
                                 BigDecimal insuranceThb, BigDecimal thicknessMm, String productType, FxSnapshot fx) {}
}
