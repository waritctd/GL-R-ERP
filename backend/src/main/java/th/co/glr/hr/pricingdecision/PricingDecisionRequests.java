package th.co.glr.hr.pricingdecision;

import java.math.BigDecimal;
import java.util.List;

public final class PricingDecisionRequests {
    private PricingDecisionRequests() {}

    /** Starts CEO review: creates a DRAFT pricing_decision against the request's current
     * SUBMITTED costing and moves the pricing request READY_FOR_CEO_REVIEW -> CEO_REVIEWING. */
    public record StartPricingDecisionRequest(
        BigDecimal defaultMarginPct, String currency, String ceoNote, String clientRequestId) {}

    /**
     * {@code priceMode} (Phase 2, CEO pricing method — owner rulings 2026-09-18/19) is chosen
     * ONCE per decision, header-level, mirroring {@code sales.quotation.price_mode} (V168) and
     * reusing its exact three codes ({@code NET}/{@code SPECIAL_SQM}/{@code DIRECT_NET} —
     * {@link th.co.glr.hr.dealquotation.WastageCalculator}'s own constants). Omitted/null leaves
     * the decision's current mode unchanged (COALESCE semantics, same convention as every other
     * field here) — it is never required on every call, only on the first one that sets it.
     * {@code ceo}-only, enforced the same way every other CEO-editing action on this aggregate is
     * ({@code PricingDecisionService.CEO_ROLES}); rejected outright when the decision's pricing
     * request is not the Phase 1 (V185) PER_PIECE + {@code sqm_per_piece} shape — see
     * {@link PricingDecisionService#requireNewFormEligible}. */
    public record UpdatePricingDecisionRequest(
        String ceoNote, String priceMode, List<UpdatePricingDecisionItemRequest> items) {
        /** The pre-Phase-2 shape (no {@code priceMode}) — every call site before this feature,
         * including ~40 integration tests across other packages that construct this positionally.
         * Defaults {@code priceMode} to null (COALESCE "unchanged"/"still legacy"), so every
         * existing caller compiles and behaves byte-for-byte as before. Mirrors the same
         * legacy-constructor device {@code DealQuotationRequests} and
         * {@code WastageCalculator.Input} already use on this codebase for an added field. */
        public UpdatePricingDecisionRequest(String ceoNote, List<UpdatePricingDecisionItemRequest> items) {
            this(ceoNote, null, items);
        }
    }

    /** Every field except {@code pricingDecisionItemId} is optional — omit a field to leave that
     * item column unchanged (COALESCE semantics in {@link PricingDecisionRepository#updateItems}).
     * {@code marginPct} still only ever influences the FORMULA's own computed price
     * ({@code proposedSellingPricePerRequestedUnit}) — the server (re)computes that column from
     * the frozen cost and this margin, never trusting a client-supplied price for it. This is the
     * LEGACY (margin-formula) path — a new-form decision (non-null {@code priceMode}) drives price
     * from {@code discountPct}/{@code specialPriceSqm}/{@code directNetPrice} below instead (paired
     * with the server-maintained {@code list_unit_price} under {@code NET} — never a field on this
     * request, see this record's own Javadoc below), and {@code marginPct} on such a decision is
     * meaningless (never read).
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
     * {@link PricingDecisionService#applyItemUpdates}. Deliberately NOT reused by the new-form
     * price-mode path (Phase 2): there the CEO's typed price IS the primary input for every item,
     * not an override of a baseline, so no reason is required for it — see
     * {@code PricingDecisionService}'s own Phase 2 header comment for this judgment call.
     *
     * <p>Phase 2 (owner rulings 2026-09-18/19) — {@code discountPct} (mode {@code NET}),
     * {@code specialPriceSqm} (mode {@code SPECIAL_SQM}, VAT-inclusive บาท/ตร.ม.),
     * {@code directNetPrice} (mode {@code DIRECT_NET}) mirror the direct-deal quotation editor's
     * own per-item price inputs exactly and are validated/derived the same way
     * ({@link th.co.glr.hr.dealquotation.WastageCalculator}, never reimplemented). All three are
     * {@code ceo}-only; which of them is actually consulted depends on the decision's
     * {@code priceMode} (set on the SAME request or already stored) — see
     * {@link PricingDecisionService#computeNetUnitPrice}.
     *
     * <p><b>Owner correction (2026-09-19), superseding the ORIGINAL Phase 2 shape:</b> NET mode's
     * list price ({@code list_unit_price}) is NEVER a client input. Owner ruling A already made
     * clear the CEO never types it — it is always the auto-calculated formula price
     * ({@code proposedSellingPricePerRequestedUnit}, money2-rounded); the CEO's only price lever
     * is {@code sellingPriceOverride} ("ปรับราคาเอง", ruling B), a SEPARATE field. This record used
     * to also carry {@code listUnitPrice}/{@code clearListUnitPrice} as a fifth client-settable
     * price-mode field, mirrored on a "protect the CEO's typed value from being clobbered by a
     * cost recompute" premise — that premise was wrong (there was never a CEO-typed value to
     * protect) and reintroduced review finding #1's uncosted-item deadlock through a second door:
     * an item that starts uncostable (list_unit_price null) and later becomes costable via
     * {@code overrideItemCost}/{@code recalculateCost}/{@code overrideItemProductType} would have
     * its formula reference become computable while list_unit_price stayed null forever, since
     * nothing refreshed it and the client-set path only ever wrote what the CEO happened to send
     * (nothing, for an item they never touched). The fix: list_unit_price is now maintained
     * EXCLUSIVELY server-side — refreshed to {@code money2(proposedSellingPricePerRequestedUnit)}
     * every time that formula reference is recomputed (see
     * {@link PricingDecisionRepository.FrozenCostUpdate}, wired into
     * {@link PricingDecisionService#overrideItemCost} and
     * {@link PricingDecisionService#recomputeCostingInPlace}, the latter shared by
     * {@code recalculateCost}/{@code overrideItemProductType}) — removed from this request
     * entirely so there is exactly one source of truth for it, matching the owner's own words:
     * "the list price is auto-calculated from the formula; the CEO never types it".
     *
     * <p>Review finding #6 (2026-09-19): each of the remaining three now has its own
     * {@code clearXxx} sibling ({@code clearDiscountPct}, {@code clearSpecialPriceSqm},
     * {@code clearDirectNetPrice}), mirroring {@code sellingPriceOverride}/
     * {@code clearSellingPriceOverride}'s own tri-state exactly — a bare {@code null} on one of
     * the three value fields means "omitted, leave unchanged" (plain COALESCE), which made it
     * IMPOSSIBLE to actually clear a value the CEO had typed and then blanked back out (a blanked
     * NET discount, sent as {@code discountPct: null}, silently kept the OLD discount instead of
     * resetting toward 0). Set and clear are mutually exclusive per field, validated in
     * {@link PricingDecisionService#applyItemUpdates}. */
    public record UpdatePricingDecisionItemRequest(
        long pricingDecisionItemId, BigDecimal marginPct,
        BigDecimal minimumSellingPrice, String decisionNote,
        BigDecimal sellingPriceOverride, boolean clearSellingPriceOverride,
        BigDecimal discountPct, boolean clearDiscountPct,
        BigDecimal specialPriceSqm, boolean clearSpecialPriceSqm,
        BigDecimal directNetPrice, boolean clearDirectNetPrice) {
        /** The pre-Phase-2 shape (no price-mode fields at all) — every call site before this
         * feature, including ~40 integration tests across other packages that construct this
         * positionally. Defaults every new field to null/false (COALESCE "unchanged"/"do not
         * clear"), so every existing caller compiles and behaves byte-for-byte as before. See
         * {@link UpdatePricingDecisionRequest}'s own matching legacy constructor. */
        public UpdatePricingDecisionItemRequest(
            long pricingDecisionItemId, BigDecimal marginPct,
            BigDecimal minimumSellingPrice, String decisionNote,
            BigDecimal sellingPriceOverride, boolean clearSellingPriceOverride) {
            this(pricingDecisionItemId, marginPct, minimumSellingPrice, decisionNote,
                sellingPriceOverride, clearSellingPriceOverride,
                null, false, null, false, null, false);
        }
    }

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

    /** V152 (V109 engine wiring), owner ruling 2026-08-16: the CEO's per-item duty product_type
     * override — {@code productType == null} (or blank) CLEARS the override, reverting to
     * {@code LandedCostCalculator}'s TILE default. Unlike {@link CostOverrideRequest}, no reason
     * field: this does not replace a computed figure the way a cost/price override does, it
     * changes which duty rate the FORMULA itself looks up — the resulting number is still fully
     * formula-derived and auditable from the product_type alone, recorded on the row itself
     * ({@code pricing_costing_item.product_type}), not just in the event trail. */
    public record ProductTypeOverrideRequest(String productType) {}
}
