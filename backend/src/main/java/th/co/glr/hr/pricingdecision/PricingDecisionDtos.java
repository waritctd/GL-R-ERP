package th.co.glr.hr.pricingdecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PricingDecisionDtos {
    private PricingDecisionDtos() {}

    /** Full, cost-and-margin-bearing view. Restricted to import/ceo — see PricingDecisionService's
     * RAW_DECISION_ROLES. Never returned to sales. */
    public record PricingDecisionDto(
        long id,
        String decisionCode,
        long pricingRequestId,
        long pricingCostingId,
        int decisionVersionNo,
        String status,
        BigDecimal defaultMarginPct,
        String currency,
        BigDecimal fxRateUsed,
        String fxSource,
        LocalDate fxEffectiveDate,
        String ceoNote,
        String returnReason,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt,
        Long approvedBy,
        Instant approvedAt,
        Instant returnedAt,
        List<PricingDecisionItemDto> items,
        // ── CEO pricing method ──────────────────────────────────────────────────────────────
        // NULL = legacy decision (predates the pricing-method columns) or a new decision the CEO has not
        // picked a mode for yet — either way PricingDecisionService keeps driving price from the
        // margin/"ปรับราคาเอง" formula path. CEO-ONLY: stripped to null for every other role that
        // can reach get()/list() (import) — see PricingDecisionService#stripPriceModeFieldsForNonCeo.
        String priceMode
    ) {}

    /** One pricing-request line's cost + CEO decision. Every price/quantity field's name states which unit basis it
     * is expressed in (design correction 1) — *PerPiece* fields mirror the costing engine's own physical basis;
     * *PerRequestedUnit* fields are the customer-facing basis (#requestedUnitBasis). */
    public record PricingDecisionItemDto(
        long id,
        long pricingDecisionId,
        long pricingRequestItemId,
        long pricingCostingItemId,
        // Descriptive fields joined from pricing_request_item / pricing_costing_item at read time for display — never
        // stored redundantly on this row.
        String brand,
        String model,
        String productDescription,
        String factoryName,
        String requestedUnitBasis,
        BigDecimal requestedQuantity,
        BigDecimal normalizedQuantityPieces,
        BigDecimal frozenLandedCostPerPieceThb,
        BigDecimal frozenLandedCostPerRequestedUnitThb,
        String currency,
        BigDecimal proposedMarginPct,
        BigDecimal approvedMarginPct,
        BigDecimal proposedSellingPricePerRequestedUnit,
        BigDecimal approvedSellingPricePerRequestedUnit,
        BigDecimal minimumSellingPricePerRequestedUnit,
        String decisionNote,
        Instant createdAt,
        Instant updatedAt,
        // ── CEO selling-price override ("ปรับราคาเอง") ───────────────────────────────────────
        // Sits BESIDE proposedSellingPricePerRequestedUnit above, which keeps holding the
        // FORMULA's own computed output forever — an override never overwrites it. NULL means
        // "no override, the formula drives this line"; non-null is the CEO's fixed final price,
        // in the SAME per-requested-unit basis and currency. The mandatory reason for setting OR
        // clearing this lives in decisionNote (see PricingDecisionService#applyItemUpdates) —
        // reused rather than a second dedicated column, mirroring the sibling cost override's
        // overrideReason but without a second column for it.
        BigDecimal manualSellingPricePerRequestedUnit,
        // Derived, never stored (mirrors PricingCostingItemDto#effectiveLandedCostPerUnitThb) — recomputed on every
        // read (PricingDecisionRepository#mapItem) so it can never drift out of sync with the two columns it is
        // derived from.
        BigDecimal effectiveSellingPricePerRequestedUnit,
        // ── CEO pricing method ──────────────────────────────────────────────────────────────
        // ตร.ม./แผ่น from the bound pricing_request_item -- NOT new/CEO-only info (Sales
        // already typed it on the item form), joined in here so the SPECIAL_SQM derivation
        // (WastageCalculator#netPerPieceFromSpecialSqm) and the "is this a new-form item"
        // eligibility check both have it without a second query. Never stripped for non-CEO.
        BigDecimal sqmPerPiece,
        // ราคา/หน่วย (ราคาตั้งต่อแผ่น), mode NET. CEO-only.
        BigDecimal listUnitPrice,
        // ส่วนลด %, mode NET. CEO-only.
        BigDecimal discountPct,
        // ราคาพิเศษ บาท/ตร.ม. รวม VAT, mode SPECIAL_SQM. CEO-only.
        BigDecimal specialPriceSqm,
        // ราคาสุทธิต่อแผ่นตรง ๆ, mode DIRECT_NET. CEO-only.
        BigDecimal directNetPrice,
        // Server-derived net price per requested unit for whichever price_mode is active -- freezes into
        // approvedSellingPricePerRequestedUnit AND minimumSellingPricePerRequestedUnit on approve() of a new-form
        // decision. CEO-only.
        BigDecimal netUnitPrice,
        // ── Stock lines, read from the bound pricing_request_item ───────────────────────────
        // null = สั่งนำเข้า; IN_THAILAND / IN_TRANSIT = a stock line (no costing link, no cost).
        String stockSource,
        // วันที่คาดว่าจะถึง - IN_TRANSIT lines only. Read-only here; import sets it on the request.
        LocalDate expectedArrivalDate
    ) {
        /** The shape without the stock-line fields. Defaults both of them to null. */
        public PricingDecisionItemDto(
            long id, long pricingDecisionId, long pricingRequestItemId, long pricingCostingItemId,
            String brand, String model, String productDescription, String factoryName,
            String requestedUnitBasis, BigDecimal requestedQuantity, BigDecimal normalizedQuantityPieces,
            BigDecimal frozenLandedCostPerPieceThb, BigDecimal frozenLandedCostPerRequestedUnitThb,
            String currency, BigDecimal proposedMarginPct, BigDecimal approvedMarginPct,
            BigDecimal proposedSellingPricePerRequestedUnit, BigDecimal approvedSellingPricePerRequestedUnit,
            BigDecimal minimumSellingPricePerRequestedUnit, String decisionNote, Instant createdAt,
            Instant updatedAt, BigDecimal manualSellingPricePerRequestedUnit,
            BigDecimal effectiveSellingPricePerRequestedUnit, BigDecimal sqmPerPiece,
            BigDecimal listUnitPrice, BigDecimal discountPct, BigDecimal specialPriceSqm,
            BigDecimal directNetPrice, BigDecimal netUnitPrice
        ) {
            this(id, pricingDecisionId, pricingRequestItemId, pricingCostingItemId, brand, model,
                productDescription, factoryName, requestedUnitBasis, requestedQuantity,
                normalizedQuantityPieces, frozenLandedCostPerPieceThb,
                frozenLandedCostPerRequestedUnitThb, currency, proposedMarginPct, approvedMarginPct,
                proposedSellingPricePerRequestedUnit, approvedSellingPricePerRequestedUnit,
                minimumSellingPricePerRequestedUnit, decisionNote, createdAt, updatedAt,
                manualSellingPricePerRequestedUnit, effectiveSellingPricePerRequestedUnit, sqmPerPiece,
                listUnitPrice, discountPct, specialPriceSqm, directNetPrice, netUnitPrice, null, null);
        }
    }

    /** Design correction 2 ("never leak cost to Sales"): the ONLY shape a sales/ sales_manager actor can ever
     * receive for a pricing decision. */
    public record PricingDecisionSalesViewDto(
        long pricingRequestId,
        long pricingDecisionId,
        String currency,
        Instant approvedAt,
        List<PricingDecisionSalesItemDto> items,
        // A plain boolean, not the CEO's pricing METHOD label (NET/SPECIAL_SQM/DIRECT_NET): the
        // label is a price-shaped fact, and this endpoint is legitimately callable by `import`
        // for an UNRELATED reason (PricingDecisionService.SALES_VIEW_ROLES — import needs the
        // approved selling price for its own factory-costing workflow), so it must not learn the
        // CEO's pricing method as a side effect of a field added for a different caller (sales,
        // deciding which create button to show). This boolean leaks NOTHING about which method
        // the CEO chose or any other price-shaped fact — "is this decision new-form" is the ONLY
        // question PricingRequestDetailPage#createDealQuotationFromRequest's button-hiding logic
        // needs answered (server gate: DealQuotationService#createFromPricingRequest refuses a
        // legacy decision with 409 — see legacyDecision_refused409). Safe to return to EVERY
        // SALES_VIEW_ROLES caller uniformly (sales/sales_manager/ceo/import alike): one shape,
        // no role-conditional stripping logic to keep correct on this one field.
        boolean newFormPricing
    ) {}

    public record PricingDecisionSalesItemDto(
        long pricingRequestItemId,
        // Quotation issuance needs this to snapshot the FK onto
        // sales.quotation_item.pricing_decision_item_id — an id, not a cost/margin/FX
        // value, so exposing it here does not leak cost.
        long pricingDecisionItemId,
        String brand,
        String model,
        // color/texture/size exist here for the same reason brand/model do: no cost/margin/FX
        // value, so no cost leak. They exist SOLELY so quotation issuance
        // (CustomerQuotationService#buildItem) can populate sales.quotation_item's legacy
        // brand/model/color/texture/size columns, which stay populated so the existing
        // renderer's buildDesc() has something to read — see
        // QuotationRenderer#buildDesc(TicketItemDto), the method that reads them back out at
        // render time.
        String color,
        String texture,
        String size,
        String productDescription,
        String requestedUnitBasis,
        BigDecimal requestedQuantity,
        BigDecimal approvedSellingPricePerRequestedUnit,
        BigDecimal minimumSellingPricePerRequestedUnit
    ) {}

    /** CEO price-review fix result: the saved fix always stands; {@code costingError} is the recalculation's
     * 422 message when cost could not be recomputed (the CEO fixes the next problem and goes again);
     * {@code decision} is null when no decision exists yet (request still READY_FOR_CEO_REVIEW). */
    public record CeoFixResult(PricingDecisionDto decision, String costingError) {}
}
