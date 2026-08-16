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
     * item column unchanged (COALESCE semantics in {@link PricingDecisionRepository#updateItems}).
     * {@code marginPct} still only ever influences the FORMULA's own computed price
     * ({@code proposedSellingPricePerRequestedUnit}) — the server (re)computes that column from
     * the frozen cost and this margin, never trusting a client-supplied price for it.
     *
     * <p>{@code sellingPriceOverride}/{@code clearSellingPriceOverride} are the one deliberate
     * exception to "never trust a client-supplied price" (Phase 1 UI simplification,
     * "ปรับราคาเอง"): a real, explicit, reason-logged CEO action that sets the FINAL price
     * directly, bypassing margin for that line entirely. The two fields together form a tri-state
     * the COALESCE-based columns above cannot express on their own — set (
     * {@code sellingPriceOverride} non-null), clear ({@code clearSellingPriceOverride} true,
     * mirroring {@link PricingDecisionRequests.CostOverrideRequest}'s own null-means-clear
     * convention, which does not fit this bulk/COALESCE endpoint's "omit = unchanged" shape), or
     * leave untouched (both absent). Reason is mandatory in BOTH directions and is carried in
     * {@code decisionNote} on the SAME request — see
     * {@link PricingDecisionService#applyItemUpdates}. */
    public record UpdatePricingDecisionItemRequest(
        long pricingDecisionItemId, BigDecimal marginPct,
        BigDecimal minimumSellingPrice, String decisionNote,
        BigDecimal sellingPriceOverride, boolean clearSellingPriceOverride) {}

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

    /** V141 ("CEO owns costing"): {@code manualLandedCostPerUnitThb} null CLEARS the override
     * (back to "use the computed figure"). {@code reason} is mandatory in BOTH directions —
     * clearing is money-affecting too, exactly like setting one (mirrors {@link
     * ReturnPricingDecisionRequest}'s own mandatory-reason precedent). */
    public record CostOverrideRequest(BigDecimal manualLandedCostPerUnitThb, String reason) {}
}
