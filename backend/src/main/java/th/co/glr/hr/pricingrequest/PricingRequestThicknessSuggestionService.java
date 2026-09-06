package th.co.glr.hr.pricingrequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.catalog.CatalogRepository.CatalogFactoryCollection;
import th.co.glr.hr.catalog.CatalogRepository.CatalogThicknessEstimationInputs;
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
 * Both need this line's resolved catalog identity ({@link CatalogRepository#findFactoryAndCollection}
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
     */
    public PricingRequestDetailDto attachSuggestions(PricingRequestDetailDto detail) {
        List<PricingRequestItemDto> items = new ArrayList<>(detail.items().size());
        for (PricingRequestItemDto item : detail.items()) {
            items.add(withSuggestion(item));
        }
        return new PricingRequestDetailDto(detail.summary(), items, detail.events());
    }

    private PricingRequestItemDto withSuggestion(PricingRequestItemDto item) {
        if (item.resolvedThicknessMm() != null) {
            return item;
        }
        Long priceId = item.catalogPriceId() != null ? item.catalogPriceId() : item.productId();
        if (priceId == null) {
            return item;
        }
        Optional<CatalogFactoryCollection> link = catalog.findFactoryAndCollection(priceId);
        if (link.isEmpty()) {
            return item;
        }
        Optional<Suggestion> suggestion = fromSiblings(link.get(), priceId);
        if (suggestion.isEmpty()) {
            suggestion = fromBoxWeight(priceId);
        }
        return suggestion
            .map(s -> item.withThicknessSuggestion(new ThicknessSuggestionDto(s.thicknessMm(), s.basis(), s.confidence())))
            .orElse(item);
    }

    private Optional<Suggestion> fromSiblings(CatalogFactoryCollection link, long priceId) {
        List<BigDecimal> siblingThicknesses =
            catalog.findSiblingThicknessesMm(link.factoryId(), link.collection(), priceId);
        return estimator.fromSiblings(siblingThicknesses);
    }

    private Optional<Suggestion> fromBoxWeight(long priceId) {
        Optional<CatalogThicknessEstimationInputs> inputs = catalog.findThicknessEstimationInputs(priceId);
        if (inputs.isEmpty()) {
            return Optional.empty();
        }
        CatalogThicknessEstimationInputs in = inputs.get();
        return estimator.fromBoxWeight(in.factoryName(), in.kgPerBox(), in.sqmPerBox());
    }
}
