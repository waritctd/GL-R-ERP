package th.co.glr.hr.pricingdecision;

import java.math.BigDecimal;
import java.util.List;

public final class PricingDecisionRequests {
    private PricingDecisionRequests() {}

    /** Starts CEO review: creates a DRAFT pricing_decision against the request's current
     * SUBMITTED costing and moves the pricing request READY_FOR_CEO_REVIEW -> CEO_REVIEWING. */
    public record StartPricingDecisionRequest(
        BigDecimal defaultMarginPct, String currency, String ceoNote, String clientRequestId) {}

    public record UpdatePricingDecisionRequest(
        String ceoNote, List<UpdatePricingDecisionItemRequest> items) {}

    /** Every field except {@code pricingDecisionItemId} is optional — omit a field to leave that
     * item column unchanged. Deliberately has NO selling-price field: the server always
     * (re)computes {@code proposedSellingPricePerRequestedUnit} from the frozen cost and
     * {@code marginPct}; a client can influence the price only by changing the margin. */
    public record UpdatePricingDecisionItemRequest(
        long pricingDecisionItemId, BigDecimal marginPct, BigDecimal discountCeilingPct,
        BigDecimal minimumSellingPrice, String decisionNote) {}

    /** {@code defaultMarginPct}, if present, is written onto the decision and reapplied to
     * EVERY item's proposed margin (overwriting any prior per-item customization) — an explicit
     * CEO bulk-reset action. If absent, every item's proposed selling price is simply
     * recomputed from its current margin and the frozen cost (idempotent refresh). */
    public record RecalculatePricingDecisionRequest(BigDecimal defaultMarginPct) {}

    /** No selling-price or margin field on purpose (design correction 7): approval always
     * freezes whatever proposedMarginPct each item currently holds into approvedMarginPct and
     * recomputes approvedSellingPricePerRequestedUnit server-side — the CEO edits margins via
     * {@link UpdatePricingDecisionItemRequest} beforehand, not at approval time. */
    public record ApprovePricingDecisionRequest(String ceoNote, String clientRequestId) {}

    public record ReturnPricingDecisionRequest(String returnReason) {}
}
