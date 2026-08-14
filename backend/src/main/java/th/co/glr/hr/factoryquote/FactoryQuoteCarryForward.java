package th.co.glr.hr.factoryquote;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;

/**
 * Decides whether a customer-change revision may reuse its parent's factory quotes, and performs
 * the copy when it may.
 *
 * <p><strong>Why this exists</strong> (reissue-through-CEO-chain, owner ruling 2026-08-13): every
 * change to an issued quotation's prices or quantities now goes through the full chain, ending in
 * a fresh CEO decision. Most of those changes are the customer haggling over price with the
 * products and quantities untouched — and making Import re-email the factory for prices nobody
 * disputed is pure delay. When the child's item list is EQUIVALENT to the parent's, the parent's
 * factory quotes are copied across and the child skips straight to
 * {@code READY_FOR_CEO_REVIEW}.
 *
 * <p><strong>Why a quantity change is disqualifying, and not merely "less accurate"</strong>: the
 * landed cost per unit is genuinely quantity-dependent. Freight and clearance rates are
 * quantity-banded ({@code qtyMinSqm}/{@code qtyMaxSqm} on the pricing config), and the factory
 * quote itself carries a {@code minimumOrderQuantity} that a reduced order may fall below — at
 * which point the quoted price is not merely stale, it is void. So a quantity change must go back
 * to Import even though every other field is identical. This class NEVER carries a quote across a
 * quantity change.
 *
 * <p><strong>Fail-closed, twice.</strong> The parent must already be fully costable
 * ({@link LandedCostCalculator#isFullyResolvable}) before anything is copied, and the CHILD is
 * re-checked with the same predicate afterwards. If the second check disagrees the copy is
 * explicitly deleted and the request takes the normal Import path — a pricing request parked at
 * {@code READY_FOR_CEO_REVIEW} that the CEO cannot actually cost is worse than one extra Import
 * step. Sharing {@code isFullyResolvable} with {@code FactoryQuoteService.markReadyForCosting}
 * is deliberate: "we said ready" and "the calculator can run" must not be able to drift apart,
 * and that is as true of this shortcut as of the normal path.
 */
@Component
public class FactoryQuoteCarryForward {

    private final FactoryQuoteRepository quotes;
    private final PricingRequestRepository pricingRequests;
    private final LandedCostCalculator landedCosts;

    public FactoryQuoteCarryForward(FactoryQuoteRepository quotes, PricingRequestRepository pricingRequests,
                                    LandedCostCalculator landedCosts) {
        this.quotes = quotes;
        this.pricingRequests = pricingRequests;
        this.landedCosts = landedCosts;
    }

    /**
     * Attempts the carry-forward for a request that has just been submitted.
     *
     * @return {@code true} only when quotes were copied AND the child is fully costable, i.e. only
     *     when the caller should advance it straight to {@code READY_FOR_CEO_REVIEW}. Every other
     *     outcome returns {@code false} and leaves the request on the normal Import path. This
     *     method never throws for a "not eligible" case — ineligibility is the common case, not an
     *     error.
     */
    public boolean carryForwardOnSubmit(PricingRequestSummaryDto child, long actorId) {
        Long parentId = child.parentPricingRequestId();
        if (parentId == null) {
            return false;
        }
        PricingRequestSummaryDto parent = pricingRequests.findSummary(parentId).orElse(null);
        if (parent == null) {
            return false;
        }
        // Idempotency / safety: never copy onto a request that already has factory quotes of its
        // own. submit() runs once per request (DRAFT -> SUBMITTED is compare-and-set), so this
        // should be unreachable; if it ever is reachable, doing nothing is the safe answer,
        // because a second copy would trip uq_factory_quote_current_factory.
        if (!quotes.findByPricingRequest(child.id()).isEmpty()) {
            return false;
        }
        Map<Long, Long> itemIdMapping = equivalentItemMapping(
            pricingRequests.findItems(parentId), pricingRequests.findItems(child.id()));
        if (itemIdMapping == null) {
            return false;
        }
        // The parent must be costable BEFORE anything is copied. This is what makes the
        // post-copy re-check below a belt-and-braces assertion rather than the only gate: if the
        // parent could not be costed, its quotes are not a shortcut worth taking.
        if (!landedCosts.isFullyResolvable(parent)) {
            return false;
        }
        List<Long> copied = quotes.copyReadyQuotesToRevision(parentId, child.id(), itemIdMapping, actorId);
        if (copied.isEmpty()) {
            return false;
        }
        if (!landedCosts.isFullyResolvable(child)) {
            quotes.deleteQuotes(copied);
            return false;
        }
        return true;
    }

    /**
     * Returns a parent-item-id -&gt; child-item-id mapping when the two item lists describe exactly
     * the same order, or {@code null} when anything differs.
     *
     * <p>The comparison is positional over {@code findItems}' own ordering ({@code sort_order},
     * then id), and requires equal size, so an item added, removed, reordered, or re-specified all
     * fall through to the Import path. That is stricter than "the same set of products" on
     * purpose: this method's output is used to repoint real factory prices at real request lines,
     * and a mapping derived from a fuzzy match is how a price silently lands on the wrong line.
     * Falling through costs one Import step; getting the mapping wrong costs money.
     *
     * <p>Every field that can change what the factory quoted or what the landed-cost calculation
     * does with it is compared — product identity, the physical description, the factory, and BOTH
     * the requested quantity and its unit basis. Fields that cannot (delivery location, special
     * requirement, target delivery date, the free-text note) are deliberately not compared: they
     * are exactly the "commercial terms only" changes this shortcut exists to serve.
     */
    static Map<Long, Long> equivalentItemMapping(List<PricingRequestItemDto> parentItems,
                                                 List<PricingRequestItemDto> childItems) {
        if (parentItems.isEmpty() || parentItems.size() != childItems.size()) {
            return null;
        }
        Map<Long, Long> mapping = new LinkedHashMap<>();
        for (int i = 0; i < parentItems.size(); i++) {
            PricingRequestItemDto parentItem = parentItems.get(i);
            PricingRequestItemDto childItem = childItems.get(i);
            if (!sameProductAndQuantity(parentItem, childItem)) {
                return null;
            }
            mapping.put(parentItem.id(), childItem.id());
        }
        return mapping;
    }

    private static boolean sameProductAndQuantity(PricingRequestItemDto a, PricingRequestItemDto b) {
        return Objects.equals(a.sourceTicketItemId(), b.sourceTicketItemId())
            && Objects.equals(a.productId(), b.productId())
            && Objects.equals(a.variantId(), b.variantId())
            && Objects.equals(a.brand(), b.brand())
            && Objects.equals(a.model(), b.model())
            && Objects.equals(a.productDescription(), b.productDescription())
            && Objects.equals(a.color(), b.color())
            && Objects.equals(a.texture(), b.texture())
            && Objects.equals(a.size(), b.size())
            && Objects.equals(a.factory(), b.factory())
            && Objects.equals(a.requestedUnit(), b.requestedUnit())
            && Objects.equals(a.requestedUnitBasis(), b.requestedUnitBasis())
            && Objects.equals(a.quantityType(), b.quantityType())
            // compareTo, not equals: BigDecimal.equals("10").equals("10.00") is FALSE (scale is
            // part of equality), and a quantity re-entered with a different scale is the same
            // quantity. Using equals here would silently disable the shortcut for half the real
            // inputs — a quiet performance bug rather than a correctness one, which is exactly the
            // kind that survives review.
            && sameAmount(a.requestedQty(), b.requestedQty())
            && sameAmount(a.requestedQtySqm(), b.requestedQtySqm());
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.compareTo(b) == 0;
    }
}
