package th.co.glr.hr.pricingrequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.catalog.CatalogRepository.CatalogFactoryCollection;
import th.co.glr.hr.catalog.CatalogRepository.CatalogThicknessEstimationInputs;
import th.co.glr.hr.catalog.CatalogRepository.FactoryCollectionKey;
import th.co.glr.hr.catalog.CatalogRepository.SiblingThicknessRow;
import th.co.glr.hr.catalog.ThicknessEstimator;
import th.co.glr.hr.catalog.ThicknessEstimator.Suggestion;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.ThicknessSuggestionDto;

/**
 * SPEC-PREFILL.md ladder A orchestration: attaches a {@link ThicknessSuggestionDto} to every item
 * in a {@link PricingRequestDetailDto} that has no {@link PricingRequestItemDto#resolvedThicknessMm()}
 * — i.e. exactly the items {@code PricingRequestDetailPage.jsx} renders the editable ความหนา input
 * for. Never touches an item that already resolves one: {@link ThicknessEstimator}'s rungs exist
 * ONLY for the gap the catalog chain (rungs 1-2, see {@code LandedCostCalculator#resolveThicknessMm})
 * leaves — an item that already has a KNOWN thickness needs no ESTIMATE (SPEC-PREFILL.md's own
 * governing distinction between the two).
 *
 * <p><b>A separate class, composing {@link PricingRequestService}, for the SAME reason {@link
 * PricingRequestItemThicknessService} is one</b> — see that class's own Javadoc. {@code
 * PricingRequestService} is constructed directly by ~30 integration test files, so widening ITS
 * constructor for two more collaborators ({@link CatalogRepository}, {@link ThicknessEstimator})
 * neither needs would force a mechanical edit across every one of them. This class instead POST-
 * PROCESSES the {@link PricingRequestDetailDto} {@code PricingRequestService#get} already returns —
 * called only from {@link PricingRequestController#get}, the one read endpoint the frontend's
 * detail-page query actually fetches from (every mutation on that page invalidates-and-refetches
 * through that SAME endpoint rather than reading a mutation's own response body, so wrapping just
 * this one call site is sufficient — see that page's {@code invalidate()}/{@code
 * useActionMutation}). {@code LandedCostCalculator}, {@code FactoryQuoteService}
 * and the other direct callers of {@code PricingRequestRepository#findItems} never go through this
 * class, so costing/quote logic pays no extra catalog round trip for a UI-only suggestion it never
 * needed.
 *
 * <p><b>Rung order — stop at the first that yields a value</b> (SPEC-PREFILL.md ladder A, rungs 3
 * then 4; rungs 1-2 are the pre-existing catalog chain this class never re-checks, since it only
 * runs for an item where that chain has already failed): sibling agreement first ({@link
 * ThicknessEstimator#fromSiblings}), box weight second ({@link ThicknessEstimator#fromBoxWeight}).
 * Both need this line's resolved catalog identity ({@link CatalogRepository#findFactoryAndCollection(long)}
 * — the SAME {@code catalog_price_id ?? product_id} precedence {@code LandedCostCalculator} and
 * {@link PricingRequestItemThicknessService} both use); an item with no catalog link at all (a
 * free-text line, owner ruling 2026-08-11) has no identity to look siblings up from and no box-
 * weight row to read, so it is left with no suggestion — ladder A rung 5, "leave the input empty."
 */
@Service
public class PricingRequestThicknessSuggestionService {
    private final CatalogRepository catalog;
    private final ThicknessEstimator estimator;

    public PricingRequestThicknessSuggestionService(CatalogRepository catalog, ThicknessEstimator estimator) {
        this.catalog = catalog;
        this.estimator = estimator;
    }

    /**
     * Returns a NEW {@link PricingRequestDetailDto} with every eligible item's {@code
     * thicknessSuggestion} filled in — {@code detail} itself, and every item that already resolves
     * a thickness or has no catalog link, comes back unchanged (same object references; only items
     * that gain a suggestion are reconstructed, via {@link PricingRequestItemDto#withThicknessSuggestion}).
     *
     * <p><b>SPEC-N1 (2026-09 review): prefetches, rather than looping and querying per item.</b>
     * This used to call {@link CatalogRepository#findFactoryAndCollection(long)}, {@link
     * CatalogRepository#findSiblingThicknessesMm(long, String, long)} and {@link
     * CatalogRepository#findThicknessEstimationInputs(long)} up to three times EACH per item — up
     * to 3N queries for N items — the same shape of N+1 {@code LandedCostCalculator} already fixed
     * via {@link CatalogRepository#findPricingKeys}. This method now follows that same pattern,
     * but in TWO batched phases rather than one, to preserve the ORIGINAL per-item laziness the
     * existing tests pin: {@link #fromBoxWeight} was only ever reached once {@link #fromSiblings}
     * had already failed for THAT item (see {@code
     * PricingRequestThicknessSuggestionServiceTest#siblingSuggestionWinsWithoutConsultingBoxWeight}),
     * so box weight cannot be prefetched for every linked id up front — only for the ones siblings
     * did NOT already resolve. Concretely: collect every eligible item's price id up front (step
     * 1); batch-fetch links, then siblings for the distinct pairs those links yield, and decide
     * the SIBLING rung for every linked id in memory (steps 2-3); batch-fetch box weight ONLY for
     * the ids still needing it, and decide that rung too (step 4); replay both decisions per item
     * (step 5). Still a CONSTANT number of batched, chunked round trips (at most three), never one
     * per item — an item failing the eligibility gate contributes to none of them, exactly as it
     * triggered none of the three per-item queries before.
     */
    public PricingRequestDetailDto attachSuggestions(PricingRequestDetailDto detail) {
        List<PricingRequestItemDto> sourceItems = detail.items();

        // Step 1: the eligible price id per item, in the SAME order as sourceItems — null at a
        // position means that item needs no lookup at all (it already resolves a thickness, or has
        // no catalog link id — COALESCE(catalogPriceId, productId) is null), and it must
        // contribute NO query, exactly as the old per-item early returns guaranteed.
        List<Long> priceIdPerItem = new ArrayList<>(sourceItems.size());
        for (PricingRequestItemDto item : sourceItems) {
            if (item.resolvedThicknessMm() != null) {
                priceIdPerItem.add(null);
                continue;
            }
            Long priceId = item.catalogPriceId() != null ? item.catalogPriceId() : item.productId();
            priceIdPerItem.add(priceId);
        }
        Set<Long> eligibleIds = new LinkedHashSet<>();
        for (Long priceId : priceIdPerItem) {
            if (priceId != null) {
                eligibleIds.add(priceId);
            }
        }

        // Step 2: batch-fetch the factory/collection identity for every eligible id in ONE
        // (chunked) round trip. The empty-set guard avoids ever calling through with nothing to
        // fetch — a request where every item is already resolved (or unlinked) makes ZERO catalog
        // calls, not one call with an empty argument.
        Map<Long, CatalogFactoryCollection> linksByPriceId =
            eligibleIds.isEmpty() ? Map.of() : catalog.findFactoryAndCollection(eligibleIds);

        // Step 3: batch-fetch sibling thicknesses for the distinct (factory, collection) pairs
        // those links yield — an eligible id whose link never resolved contributes no pair — then
        // decide the sibling rung for every linked id right away, purely in memory. This decides
        // WHICH ids still need the box-weight rung before that batch is even issued.
        Set<FactoryCollectionKey> siblingKeys = new LinkedHashSet<>();
        for (CatalogFactoryCollection link : linksByPriceId.values()) {
            siblingKeys.add(new FactoryCollectionKey(link.factoryId(), link.collection()));
        }
        Map<FactoryCollectionKey, List<SiblingThicknessRow>> siblingsByKey =
            siblingKeys.isEmpty() ? Map.of() : catalog.findSiblingThicknessesMm(siblingKeys);

        Map<Long, Optional<Suggestion>> siblingSuggestionByPriceId = new HashMap<>();
        Set<Long> needsBoxWeight = new LinkedHashSet<>();
        for (Map.Entry<Long, CatalogFactoryCollection> entry : linksByPriceId.entrySet()) {
            long priceId = entry.getKey();
            Optional<Suggestion> suggestion = fromSiblings(entry.getValue(), priceId, siblingsByKey);
            siblingSuggestionByPriceId.put(priceId, suggestion);
            if (suggestion.isEmpty()) {
                needsBoxWeight.add(priceId);
            }
        }

        // Step 4: batch-fetch box-weight inputs ONLY for ids whose sibling rung came up empty —
        // matching the old per-item code's own laziness exactly (see this method's own Javadoc).
        Map<Long, CatalogThicknessEstimationInputs> boxWeightByPriceId =
            needsBoxWeight.isEmpty() ? Map.of() : catalog.findThicknessEstimationInputs(needsBoxWeight);

        // Step 5: replay the existing per-item decision logic — siblings first, then box weight —
        // purely against the maps above; no further catalog calls from here.
        List<PricingRequestItemDto> items = new ArrayList<>(sourceItems.size());
        for (int i = 0; i < sourceItems.size(); i++) {
            PricingRequestItemDto item = sourceItems.get(i);
            Long priceId = priceIdPerItem.get(i);
            items.add(priceId == null || !linksByPriceId.containsKey(priceId)
                ? item
                : withSuggestion(item, priceId, siblingSuggestionByPriceId, boxWeightByPriceId));
        }
        return new PricingRequestDetailDto(detail.summary(), items, detail.events());
    }

    private PricingRequestItemDto withSuggestion(PricingRequestItemDto item, long priceId,
                                                  Map<Long, Optional<Suggestion>> siblingSuggestionByPriceId,
                                                  Map<Long, CatalogThicknessEstimationInputs> boxWeightByPriceId) {
        Optional<Suggestion> suggestion = siblingSuggestionByPriceId.get(priceId);
        if (suggestion.isEmpty()) {
            suggestion = fromBoxWeight(priceId, boxWeightByPriceId);
        }
        return suggestion
            .map(s -> item.withThicknessSuggestion(new ThicknessSuggestionDto(s.thicknessMm(), s.basis(), s.confidence())))
            .orElse(item);
    }

    private Optional<Suggestion> fromSiblings(CatalogFactoryCollection link, long priceId,
                                               Map<FactoryCollectionKey, List<SiblingThicknessRow>> siblingsByKey) {
        FactoryCollectionKey key = new FactoryCollectionKey(link.factoryId(), link.collection());
        List<SiblingThicknessRow> rows = siblingsByKey.getOrDefault(key, List.of());
        // The row-group is shared across every item resolving to this SAME key — each item must
        // exclude only ITS OWN price id, never another sharer's, so exclusion happens here rather
        // than in the batched query (see CatalogRepository#findSiblingThicknessesMm(Collection)).
        List<BigDecimal> siblingThicknesses = new ArrayList<>(rows.size());
        for (SiblingThicknessRow row : rows) {
            if (row.priceId() != priceId) {
                siblingThicknesses.add(row.thicknessMm());
            }
        }
        return estimator.fromSiblings(siblingThicknesses);
    }

    private Optional<Suggestion> fromBoxWeight(long priceId, Map<Long, CatalogThicknessEstimationInputs> boxWeightByPriceId) {
        CatalogThicknessEstimationInputs in = boxWeightByPriceId.get(priceId);
        if (in == null) {
            return Optional.empty();
        }
        return estimator.fromBoxWeight(in.factoryName(), in.kgPerBox(), in.sqmPerBox());
    }
}
