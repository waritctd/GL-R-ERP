package th.co.glr.hr.pricingcosting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
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
 * <p>Freight additionally varies by {@code origin_country_code} and {@code thickness_mm} (V151 +
 * V109's own lookup keys, alongside quantity) — physical drivers distinct from the shipment/
 * factory grouping, since a shipment can in principle carry more than one product spec. {@code F}
 * is therefore looked up and allocated at the finer grain of (shipment × origin country ×
 * thickness); {@code S} (clearance fee — the schema keys it on {@code qty_sqm} alone, nothing
 * else) stays at the whole-shipment grain. In the overwhelmingly common case (one factory quote =
 * one product spec = one country = one thickness) both grains coincide with the whole shipment,
 * and the allocation described below collapses to "the item gets the whole flat amount",
 * unchanged from what a single-item shipment would produce on its own.
 *
 * <p><b>Origin country (V151, "one canonical country, so the freight lookup can join"):</b>
 * resolved PER ITEM from the catalog link — {@code price_catalog.factories.country} via {@code
 * price_catalog.product_prices.factory_id} — the SAME ISO 3166-1 alpha-2 source {@code
 * sales.pricing_freight_rate.origin_country_code} is normalised against, and the SAME catalog link
 * {@link #resolveThicknessMm} already uses. Deliberately NOT {@code sales.factory_config.country}
 * (the RFQ-email-routing table's free-text field, a different table V151 never touched) — reading
 * that here would silently reintroduce the exact free-text/ISO-code mismatch V151's migration
 * fixed (its own words: "every freight lookup returned nothing, for all nine factories").
 *
 * <p>{@code C} (goods cost), {@code i} (insurance) and {@code T} (duty) stay genuinely per-item:
 * different products in the same shipment can have different factory prices, and — via the CEO's
 * per-item {@code product_type} override — different duty rates. This class therefore returns one
 * landed-cost row per {@link PricingRequestItemDto}, never one blended shipment-wide figure applied
 * uniformly to every item it contains.
 *
 * <h2>P1a/P1b fixes (2026-09, authorised sales-pricing-workflow changes): aggregate the two
 * problem-sources below into one 422 each, and stop re-fetching the same rows per item.</h2>
 * <b>P1a — aggregate, don't abort on the first problem — but NOT every problem in this class; see
 * the correction below.</b> {@link #resolveSources} and {@link #resolveItemPhysicals} used to
 * throw on the FIRST missing factory/quote/price/conversion-factor they met, so a CEO with three
 * bad lines fixed one, re-ran, and met the next — one round trip per defect instead of one round
 * trip total. Both now collect every problem across every item (the former within its own loop;
 * the latter via a single accumulating pass in {@link #calculate} across ALL shipments, not just
 * one) and throw exactly ONE 422 listing all of them. {@link #missingFactor} additionally now
 * names the item by its human label (never the bare id) and the missing factor in Thai alongside
 * its technical identifier, plus where it is entered ({@code sales.factory_quote_item}), so the
 * CEO knows what to fix and who to ask without a second guess. {@link #isFullyResolvable}'s
 * semantics are unchanged by this — it still wraps only {@link #resolveSources}, exactly as
 * before.
 *
 * <p><b>F2 correction — what this class does NOT aggregate, and a masking change that came free
 * with the restructure.</b> An earlier version of this Javadoc's headline claimed the calculator
 * "reports every problem at once". That overstates what shipped: aggregation covers exactly
 * {@link #resolveSources} and the physicals-resolution pass in {@link #calculate} described
 * above — nothing else. Four 422 clusters inside {@link #costShipment} still abort on the FIRST
 * problem, unaggregated: {@code factoryConfigs.findByName}, and {@code
 * PricingFormulaEngine#selectFreightRate}/{@code #selectClearanceFee}/{@code #selectDutyRate}.
 * Extending aggregation to those four is a separate, larger change, tracked as a known limitation
 * in this branch's PR body rather than done here.
 *
 * <p>The restructure also changed which error wins when more than one kind of problem exists in
 * the same request. Before the restructure, the old per-shipment loop called {@code costShipment}
 * — factory-config check included — immediately for each shipment in turn, so a factory-config
 * problem in an EARLIER shipment could surface and 422 before a physicals problem in a LATER one
 * was ever reached. Now, because {@link #calculate} resolves physicals for EVERY shipment before
 * ANY {@link #costShipment} call runs (P1a.4 below), the ordering is reversed unconditionally: a
 * physicals problem in ANY shipment always surfaces before a factory-config or formula-lookup
 * problem in ANY shipment, even one that used to win the race. A physicals problem therefore now
 * masks a factory-config problem that used to surface first; both are still real defects the CEO
 * must fix, just not reported in the same order release-to-release.
 *
 * <p><b>P1b — the same rows were being re-fetched once per item.</b> {@link #resolveSources} used
 * to call {@code FactoryQuoteRepository#findCurrentByFactory} once per pricing-request item (a
 * 4-query round trip each — the quote row plus {@code findItems}/{@code findAttachments}/{@code
 * findLatestDispatch}), so N items from the same factory re-fetched the identical quote N times;
 * it now memoizes that lookup by factory name in a call-local map. {@link #resolveItemPhysicals}
 * used to call {@code CatalogRepository#findThicknessMm} (deleted, F1)/the old per-row
 * origin-country lookup (also deleted) once per item (2 more round trips each); {@link #calculate}
 * now prefetches both, for every source's price id, in {@link CatalogRepository#findPricingKeys}
 * — ONE batched round trip per field, regardless of item count. Neither change alters what
 * resolves or does not: a null/absent value still becomes an {@code uncostableReason} (V156) or a
 * thrown 422 exactly as before — see each method's own Javadoc.
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

        // P1b.2: one batched catalog round trip per field, for every source's price id, instead of
        // the 2 x N (findThicknessMm + the old findOriginCountryCode) round trips
        // resolveItemPhysicals used to make below. See CatalogRepository#findPricingKeys.
        Map<Long, CatalogRepository.CatalogPricingKey> catalogKeys = prefetchCatalogKeys(sources);

        // P1a.4: resolve EVERY item's physicals up front, in one accumulating pass across ALL
        // shipments/factories in this request — not per shipment, and not aborting on the first
        // problem. costShipment below receives the already-resolved list for its shipment and
        // never re-resolves it (also removes the duplicated work the old per-shipment call did).
        List<ItemPhysicals> allPhysicals = new ArrayList<>();
        Set<String> problems = new LinkedHashSet<>();
        for (ResolvedSource source : sources) {
            try {
                allPhysicals.add(resolveItemPhysicals(source, formulaConfig, catalogKeys));
            } catch (ApiException e) {
                // F4 hardening: every throw reachable on this path is a 422 today (see
                // resolveItemPhysicals and its callees), so this rethrow is currently inert — but
                // folding a problem into the aggregated bullet list below is only safe for a 422.
                // A future nested call that 409s or 500s must propagate as itself, not be silently
                // downgraded into a "costing problem" bullet the CEO would misread as a 422.
                if (e.getStatus() != HttpStatus.UNPROCESSABLE_CONTENT) {
                    throw e;
                }
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw aggregateProblems(problems);
        }

        Map<Long, List<ItemPhysicals>> byShipment = new LinkedHashMap<>();
        for (ItemPhysicals physicals : allPhysicals) {
            byShipment.computeIfAbsent(physicals.source().quote().id(), key -> new ArrayList<>()).add(physicals);
        }

        List<PricingCostingWriteItem> writeItems = new ArrayList<>();
        Instant calculatedAt = Instant.now();
        for (List<ItemPhysicals> shipment : byShipment.values()) {
            writeItems.addAll(costShipment(shipment, formulaConfig, calculatedAt));
        }

        // V156: an uncostable line contributes NOTHING to the costing total — it has no computed
        // cost to contribute. The total therefore covers only the lines that could be costed, and
        // is knowingly incomplete until the CEO supplies the missing ones; approve() is what
        // refuses to let an incomplete costing become an approved price.
        BigDecimal total = ZERO;
        for (PricingCostingWriteItem item : writeItems) {
            if (item.totalLandedCostThb() != null) {
                total = total.add(item.totalLandedCostThb());
            }
        }
        return new CalculationResult(writeItems, money4(total));
    }

    /**
     * Every problem collected during {@link #resolveSources} or the physicals-resolution pass in
     * {@link #calculate}, as ONE 422 — never the first problem alone. A heading line then one
     * bullet per problem (a {@link Set} so an identical message from more than one item, e.g. "no
     * factory quote yet" affecting every item sourced from the same unresolved factory, collapses
     * to a single bullet instead of repeating itself once per affected item).
     */
    private ApiException aggregateProblems(Set<String> problems) {
        StringBuilder message = new StringBuilder("ไม่สามารถคำนวณต้นทุนได้ เนื่องจากพบปัญหาดังนี้:");
        for (String problem : problems) {
            message.append("\n- ").append(problem);
        }
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, message.toString());
    }

    /** Every distinct catalog price id referenced by {@code sources}, resolved in ONE batched
     * call — see {@link CatalogRepository#findPricingKeys} (P1b.2). */
    private Map<Long, CatalogRepository.CatalogPricingKey> prefetchCatalogKeys(List<ResolvedSource> sources) {
        Set<Long> priceIds = new LinkedHashSet<>();
        for (ResolvedSource source : sources) {
            Long priceId = catalogPriceId(source.requestItem());
            if (priceId != null) {
                priceIds.add(priceId);
            }
        }
        return catalog.findPricingKeys(priceIds);
    }

    /** The catalog link a pricing-request item resolves through — the submit-time snapshot when
     * one was taken, falling back to the live product id (see {@link #resolveThicknessMm}'s
     * Javadoc for why both point at the same {@code price_catalog.product_prices.price_id}). */
    private Long catalogPriceId(PricingRequestItemDto requestItem) {
        return requestItem.catalogPriceId() != null ? requestItem.catalogPriceId() : requestItem.productId();
    }

    /**
     * See the class Javadoc for the grouping/allocation rules this implements. {@code allItems} is
     * ALREADY resolved (P1a.4) — by {@link #calculate}'s own accumulating pass, across every
     * shipment in the request, not just this one — so this method never calls {@link
     * #resolveItemPhysicals} itself any more; it only groups/allocates/costs what it is handed.
     */
    private List<PricingCostingWriteItem> costShipment(List<ItemPhysicals> allItems,
                                                        PricingFormulaConfigDto formulaConfig, Instant calculatedAt) {
        FactoryQuoteDto quote = allItems.get(0).source().quote();
        String factoryName = quote.factoryName();
        // Existence check only, now — V151 moved the freight lookup's origin-country input to the
        // catalog link (see resolveOriginCountryCode / the class Javadoc's V151 section), so this
        // RFQ-settings row's own (free-text, never-normalised) country is no longer read. The
        // check itself is unchanged: the factory this quote is FOR must still be a configured RFQ
        // factory.
        factoryConfigs.findByName(factoryName)
            .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ไม่พบ factory config สำหรับโรงงาน: " + factoryName));
        String shipmentLabel = "ใบเสนอราคาโรงงาน " + factoryName + " (" + quote.quoteCode() + ")";

        // V156: an item whose catalogue row carries no freight-lookup key (thickness or origin
        // country) cannot be costed at all. It is set ASIDE here rather than aborting the whole
        // shipment, and is written below with NULL costs and a stated reason.
        //
        // Excluding it from BOTH the freight grouping and the clearance allocation is deliberate.
        // It has no freight band to join, and letting it dilute the clearance denominator would
        // push part of a real, already-incurred cost onto a line nobody can price — the cost would
        // simply vanish from the quotation. The costable lines therefore absorb the entire
        // clearance fee, which OVER-allocates to them. That is the safe direction: it over-costs
        // rather than under-costs, and it is corrected the moment the CEO supplies the missing
        // line's own cost and the costing is recalculated.
        List<ItemPhysicals> items = new ArrayList<>();
        List<ItemPhysicals> uncostable = new ArrayList<>();
        for (ItemPhysicals item : allItems) {
            (uncostableReason(item) == null ? items : uncostable).add(item);
        }
        if (items.isEmpty()) {
            // Every line in this shipment is uncostable — there is no freight band and no
            // clearance denominator to compute at all. Emit the placeholders and stop, rather
            // than dividing by a zero shipment quantity.
            List<PricingCostingWriteItem> onlyUncostable = new ArrayList<>();
            for (ItemPhysicals item : uncostable) {
                onlyUncostable.add(uncostableWriteItem(item, formulaConfig));
            }
            return onlyUncostable;
        }

        // F: grouped by (origin country, thickness) within the shipment (see class Javadoc) —
        // both are per-item catalog-sourced physical drivers of the freight-table lookup key.
        Map<FreightGroupKey, BigDecimal> qtySqmByGroup = new LinkedHashMap<>();
        for (ItemPhysicals item : items) {
            qtySqmByGroup.merge(
                new FreightGroupKey(item.originCountryCode(), item.thicknessMm()), item.qtySqm(), BigDecimal::add);
        }
        Map<FreightGroupKey, BigDecimal> freightAmountByGroup = new LinkedHashMap<>();
        for (Map.Entry<FreightGroupKey, BigDecimal> entry : qtySqmByGroup.entrySet()) {
            FreightGroupKey key = entry.getKey();
            BigDecimal groupQtySqm = entry.getValue();
            PricingFreightRateDto freightRate = formulaEngine.selectFreightRate(formulaConfig.freightRates(),
                key.originCountryCode(), key.thicknessMm(), groupQtySqm,
                shipmentLabel + " ต้นทาง " + key.originCountryCode() + " หนา " + key.thicknessMm() + " มม.");
            freightAmountByGroup.put(key, freightRate.amountThb());
        }

        // S: whole shipment, regardless of country or thickness.
        BigDecimal shipmentQtySqm = ZERO;
        for (BigDecimal groupQtySqm : qtySqmByGroup.values()) {
            shipmentQtySqm = shipmentQtySqm.add(groupQtySqm);
        }
        PricingClearanceFeeDto clearanceFee = formulaEngine.selectClearanceFee(
            formulaConfig.clearanceFees(), shipmentQtySqm, shipmentLabel);

        List<PricingCostingWriteItem> writeItems = new ArrayList<>();
        for (ItemPhysicals item : items) {
            FreightGroupKey key = new FreightGroupKey(item.originCountryCode(), item.thicknessMm());
            BigDecimal freightGroupQtySqm = qtySqmByGroup.get(key);
            BigDecimal freightAmountForGroup = freightAmountByGroup.get(key);
            // F/S allocation is inherently a TOTAL-for-the-item quantity (a share of a flat,
            // shipment-level amount, proportional to the item's own share of the group's sqm —
            // see class Javadoc) — computed once here, in totals, then carried through the
            // formula's own C/i/F/T/S/TC/UC pipeline (also totals, per V109's own definition: TC
            // is dimensionally a total, only UC=TC/Q converts it to a per-sqm rate).
            BigDecimal freightShareTotal = allocate(freightAmountForGroup, item.qtySqm(), freightGroupQtySqm);
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

            String snapshot = buildSnapshot(item, freightAmountForGroup, freightShareTotal,
                freightGroupQtySqm, clearanceFee.amountThb(), clearanceShareTotal, shipmentQtySqm,
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
                snapshot,
                null            // uncostable_reason — this line costed cleanly
            ));
        }
        for (ItemPhysicals item : uncostable) {
            writeItems.add(uncostableWriteItem(item, formulaConfig));
        }
        return writeItems;
    }

    /**
     * A costing row for an item that cannot be costed (V156): every freight-dependent figure is
     * NULL and {@code uncostableReason} says why, enforced as an XOR by
     * {@code chk_pricing_costing_item_uncostable_xor}.
     *
     * <p>The non-freight facts are still recorded — goods cost, insurance, FX snapshot, quantities,
     * the catalogue link. They are computable without a thickness, and keeping them means the CEO
     * sees a real line with a real supplier price to judge the missing cost against, rather than an
     * empty placeholder.
     */
    private PricingCostingWriteItem uncostableWriteItem(ItemPhysicals item, PricingFormulaConfigDto formulaConfig) {
        ResolvedSource source = item.source();
        BigDecimal insurancePerPiece = money4(
            item.insuranceThb().divide(item.qtyPieces(), 8, RoundingMode.HALF_UP));
        return new PricingCostingWriteItem(
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
            formulaConfig.formulaConfigId(),
            formulaConfig.version(),
            item.goodsCostPerPiece(),
            null,               // freight_cost_thb — the freight table cannot be looked up
            insurancePerPiece,
            null,               // import_duty_thb — duty applies to CIF, which needs freight
            ZERO,
            ZERO,
            null,               // cif_cost_thb
            null,               // landed_cost_per_unit_thb
            null,               // total_landed_cost_thb
            null,               // clearance_fee_thb — allocated across costable lines only
            item.productType(),
            null,               // calculation_snapshot — no calculation happened
            uncostableReason(item)
        );
    }

    private ItemPhysicals resolveItemPhysicals(ResolvedSource source, PricingFormulaConfigDto formulaConfig,
                                               Map<Long, CatalogRepository.CatalogPricingKey> catalogKeys) {
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
        BigDecimal thicknessMm = resolveThicknessMm(source.requestItem(), catalogKeys);
        String originCountryCode = resolveOriginCountryCode(source.requestItem(), catalogKeys);
        String productType = resolveProductType(source.requestItem());
        return new ItemPhysicals(source, sqmPerPiece, qtyPieces, qtySqm, goodsCostPerPiece, goodsCostThb,
            insuranceThb, thicknessMm, originCountryCode, productType, fx);
    }

    /**
     * Thickness comes ONLY from the catalog link — {@code price_catalog.product_prices.thickness_mm}
     * via {@code pricing_request_item.catalog_price_id} (the submit-time snapshot, V61), falling
     * back to the live {@code product_id} (both columns point at the SAME target,
     * {@code price_catalog.product_prices.price_id}, V68) when the snapshot has not run yet —
     * never guessed, never defaulted. An item with no resolvable catalog link at all is a
     * legitimate case (Import may submit a free-text line with no catalog match, owner ruling
     * 2026-08-11 — {@code PricingRequestService#submit}'s own comment), and a matched catalog row
     * can still have a NULL {@code thickness_mm} — 41.7% of the production catalogue does, and for
     * some products (Bode's whole range, REFIN's OUT2.0) NO source carries it, so it cannot be
     * fixed by better data alone.
     *
     * <p><b>Returns null rather than throwing (V156).</b> It used to throw 422 here, which aborted
     * {@code PricingDecisionService#startReview} before a single costing row was written — and
     * therefore before the CEO could reach the very screen that owns the manual cost override
     * ({@code manual_landed_cost_per_unit_thb}, V141) that resolves this. The capability existed
     * and the route to it was blocked. A null now marks the item UNCOSTABLE, it persists with a
     * stated reason, and {@code approve()} is what refuses to let it through un-resolved. Nothing
     * is ever priced on a guessed thickness — the guarantee moved, it did not weaken.
     *
     * <p><b>P1b.2:</b> reads {@code catalogKeys}, a map {@link #calculate} prefetches ONCE per
     * request via {@link CatalogRepository#findPricingKeys} (batched over every source's price
     * id), rather than calling {@code CatalogRepository#findThicknessMm} (F1: deleted — its only
     * caller was its own test, repointed at {@code findPricingKeys}) here per item. Resolution
     * semantics are byte-identical to that deleted single-row method (same view, same
     * ACTIVE-version filter) — only the round-trip count changed.
     */
    private BigDecimal resolveThicknessMm(PricingRequestItemDto requestItem,
                                          Map<Long, CatalogRepository.CatalogPricingKey> catalogKeys) {
        Long priceId = catalogPriceId(requestItem);
        if (priceId == null) {
            return null;
        }
        CatalogRepository.CatalogPricingKey key = catalogKeys.get(priceId);
        return key == null ? null : key.thicknessMm();
    }

    /**
     * Origin country comes ONLY from the catalog link — {@code price_catalog.factories.country}
     * via {@code price_catalog.product_prices.factory_id} — resolved through the SAME {@code
     * catalog_price_id}/{@code product_id} link {@link #resolveThicknessMm} uses (see class
     * Javadoc's V151 section for why: this is the canonical ISO 3166-1 alpha-2 source {@code
     * sales.pricing_freight_rate.origin_country_code} is normalised against; {@code
     * sales.factory_config.country}, a different table's free-text field, is never read for
     * pricing). An item with no resolvable catalog link fails costing LOUDLY here, naming the
     * item, for the same reason a missing thickness does.
     *
     * <p><b>P1b.2:</b> reads the SAME prefetched {@code catalogKeys} map {@link
     * #resolveThicknessMm} does — see that method's own P1b.2 note. Resolution semantics are
     * byte-identical to the single-row lookup this replaced (the base {@code product_prices}/
     * {@code factories} join, deliberately with NO active-version filter — see {@link
     * CatalogRepository#findPricingKeys}'s Javadoc for why that must stay a SEPARATE query from
     * thickness's, not a shared one over the ACTIVE-filtered view).
     */
    private String resolveOriginCountryCode(PricingRequestItemDto requestItem,
                                            Map<Long, CatalogRepository.CatalogPricingKey> catalogKeys) {
        Long priceId = catalogPriceId(requestItem);
        if (priceId == null) {
            return null;
        }
        CatalogRepository.CatalogPricingKey key = catalogKeys.get(priceId);
        return key == null ? null : key.originCountryCode();
    }

    /**
     * Why this item cannot be costed, in the CEO's own language, or null when it can. Both inputs
     * are freight-table lookup keys ({@code sales.pricing_freight_rate}), so missing either one
     * makes the lookup impossible rather than merely inaccurate — see V156.
     */
    private String uncostableReason(ItemPhysicals item) {
        boolean noThickness = item.thicknessMm() == null;
        boolean noCountry = item.originCountryCode() == null;
        if (!noThickness && !noCountry) {
            return null;
        }
        String missing = noThickness && noCountry ? "ความหนา (thickness_mm) และประเทศต้นทาง"
            : noThickness ? "ความหนา (thickness_mm)"
            : "ประเทศต้นทาง (origin country)";
        return itemLabel(item.source().requestItem()) + " ไม่มี" + missing + " จาก Price Catalog "
            + "— คำนวณค่าขนส่งอัตโนมัติไม่ได้ กรุณาระบุต้นทุนเอง หรือเชื่อมรายการนี้กับสินค้าใน Price Catalog "
            + "ที่มีข้อมูลครบ";
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

    private String buildSnapshot(ItemPhysicals item, BigDecimal freightAmountForGroup,
                                 BigDecimal freightShare, BigDecimal freightGroupQtySqm, BigDecimal clearanceAmount,
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
            + ",\"originCountryCode\":\"" + item.originCountryCode() + "\""
            + ",\"thicknessMm\":\"" + item.thicknessMm() + "\""
            + ",\"productType\":\"" + item.productType() + "\""
            + ",\"itemQtySqm\":\"" + item.qtySqm() + "\""
            + ",\"freightGroupQtySqm\":\"" + freightGroupQtySqm + "\""
            + ",\"freightAmountThbForGroup\":\"" + freightAmountForGroup + "\""
            + ",\"freightShareThb\":\"" + freightShare + "\""
            + ",\"shipmentQtySqm\":\"" + shipmentQtySqm + "\""
            + ",\"clearanceAmountThbForShipment\":\"" + clearanceAmount + "\""
            + ",\"clearanceShareThb\":\"" + clearanceShare + "\""
            + ",\"dutyPct\":\"" + dutyPct + "\""
            + ",\"costBuffer\":\"" + formulaConfig.costBuffer() + "\""
            + ",\"requestedUnitBasis\":\"" + item.source().requestItem().requestedUnitBasis() + "\""
            + ",\"normalizedQuantityPieces\":\"" + item.qtyPieces() + "\"}";
    }

    /**
     * P1a.1: accumulates every problem across every item and throws ONE 422 listing all of them,
     * rather than exiting on the first — see the class Javadoc. P1b.1: memoizes the
     * factory-quote lookup by factory name in a map LOCAL to this call (never cached across
     * calls) — {@code FactoryQuoteRepository#findCurrentByFactory} is a 4-query round trip (the
     * quote row plus {@code findItems}/{@code findAttachments}/{@code findLatestDispatch}), so N
     * items sourced from the SAME factory used to repeat all 4 N times; now it runs once per
     * DISTINCT factory in the request.
     */
    public List<ResolvedSource> resolveSources(PricingRequestSummaryDto summary) {
        List<ResolvedSource> result = new ArrayList<>();
        Set<String> problems = new LinkedHashSet<>();
        Map<String, Optional<FactoryQuoteDto>> quotesByFactory = new HashMap<>();
        for (PricingRequestItemDto item : pricingRequests.findItems(summary.id())) {
            String factoryName = firstText(item.resolvedFactoryName(), item.factory());
            if (factoryName == null) {
                problems.add(itemLabel(item) + " ในคำขอราคายังไม่ได้ระบุโรงงาน");
                continue;
            }
            Optional<FactoryQuoteDto> maybeQuote = quotesByFactory.computeIfAbsent(
                factoryName, name -> factoryQuotes.findCurrentByFactory(summary.id(), name));
            if (maybeQuote.isEmpty()) {
                problems.add("ยังไม่มีใบเสนอราคาโรงงานสำหรับ " + factoryName);
                continue;
            }
            FactoryQuoteDto quote = maybeQuote.get();
            if (!FactoryQuoteStatus.READY_FOR_COSTING.equals(quote.status())) {
                problems.add("ใบเสนอราคาของโรงงาน " + factoryName + " ยังไม่พร้อมสำหรับการคำนวณต้นทุน");
                continue;
            }
            Optional<FactoryQuoteItemDto> maybeQuoteItem = quote.items().stream()
                .filter(candidate -> candidate.pricingRequestItemId() == item.id())
                .findFirst();
            if (maybeQuoteItem.isEmpty()) {
                problems.add("ใบเสนอราคาของโรงงาน " + factoryName + " ไม่ครอบคลุม" + itemLabel(item));
                continue;
            }
            FactoryQuoteItemDto quoteItem = maybeQuoteItem.get();
            if (quoteItem.rawUnitPrice() == null || quoteItem.currency() == null
                    || quoteItem.quotedUnit() == null || quoteItem.unitBasis() == null) {
                // F5: this used to name quoteItem.id() (sales.factory_quote_item) here; it now
                // names the pricing-request-item id via itemLabel(item) instead, matching every
                // other message in this class. Consistency improvement, but a real change: anyone
                // looking an id up from THIS message now lands on sales.pricing_request_item, not
                // sales.factory_quote_item as before.
                problems.add(itemLabel(item) + " ในใบเสนอราคาโรงงานยังไม่มีราคา สกุลเงิน หรือหน่วยนับ");
                continue;
            }
            result.add(new ResolvedSource(item, quote, quoteItem));
        }
        if (!problems.isEmpty()) {
            throw aggregateProblems(problems);
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

    /**
     * Thai label for a physical conversion-factor field, alongside its own technical identifier
     * so the message stays greppable back to the code/schema for an engineer while still reading
     * naturally for the CEO. Falls back to the bare identifier for any factor name not in this
     * map (defensive only — every caller today passes one of the three below).
     */
    private String factorLabelTh(String factorName) {
        return switch (factorName) {
            case "sqmPerUnit" -> "ตร.ม. ต่อหน่วย (sqmPerUnit)";
            case "piecesPerBox" -> "จำนวนแผ่นต่อกล่อง (piecesPerBox)";
            case "linearMPerUnit" -> "ความยาว (เมตร) ต่อหน่วย (linearMPerUnit)";
            default -> factorName;
        };
    }

    /**
     * Names the item by its human label (never the bare id), the missing factor in Thai alongside
     * its technical identifier ({@link #factorLabelTh}), and where it is entered — the factory
     * quote item Import recorded ({@code sales.factory_quote_item}) — so the CEO knows both what
     * to fix and who to ask. Per the financial-integrity review's requirement.
     */
    private ApiException missingFactor(PricingRequestItemDto requestItem, String factorName) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
            itemLabel(requestItem) + " ยังไม่มีค่า " + factorLabelTh(factorName)
                + " ในใบเสนอราคาโรงงานที่ฝ่ายนำเข้าบันทึกไว้ (sales.factory_quote_item)"
                + " — กรุณาตรวจสอบกับฝ่ายนำเข้า");
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
     * re-deriving it by dividing the total back down, which would compound a second rounding.
     * {@code originCountryCode} is the catalog-sourced ISO 3166-1 alpha-2 code (V151) — see
     * {@link #resolveOriginCountryCode}. */
    private record ItemPhysicals(ResolvedSource source, BigDecimal sqmPerPiece, BigDecimal qtyPieces,
                                 BigDecimal qtySqm, BigDecimal goodsCostPerPiece, BigDecimal goodsCostThb,
                                 BigDecimal insuranceThb, BigDecimal thicknessMm, String originCountryCode,
                                 String productType, FxSnapshot fx) {}

    /** Freight lookup/allocation grain within a shipment — both fields are per-item, catalog-
     * sourced physical drivers of {@code sales.pricing_freight_rate}'s lookup key (origin country
     * since V151, thickness since V109) — see the class Javadoc. A plain record gets correct
     * {@code equals}/{@code hashCode} for free, which is all a {@code Map} key needs here. */
    private record FreightGroupKey(String originCountryCode, BigDecimal thicknessMm) {}
}
