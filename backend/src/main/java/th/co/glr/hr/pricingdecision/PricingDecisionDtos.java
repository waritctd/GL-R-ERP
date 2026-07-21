package th.co.glr.hr.pricingdecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PricingDecisionDtos {
    private PricingDecisionDtos() {}

    /** Full, cost-and-margin-bearing view. Restricted to {@code import}/{@code ceo} — see
     * {@link PricingDecisionService}'s {@code RAW_DECISION_ROLES}. Never returned to sales. */
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
        List<PricingDecisionItemDto> items
    ) {}

    /**
     * One pricing-request line's cost + CEO decision. Every price/quantity field's name states
     * which unit basis it is expressed in (design correction 1) — {@code *PerPiece*} fields
     * mirror the costing engine's own physical basis; {@code *PerRequestedUnit*} fields are the
     * customer-facing basis ({@link #requestedUnitBasis}).
     */
    public record PricingDecisionItemDto(
        long id,
        long pricingDecisionId,
        long pricingRequestItemId,
        long pricingCostingItemId,
        // Descriptive fields joined from pricing_request_item / pricing_costing_item at read
        // time for display — never stored redundantly on this row.
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
        BigDecimal discountCeilingPct,
        BigDecimal minimumSellingPricePerRequestedUnit,
        String decisionNote,
        Instant createdAt,
        Instant updatedAt
    ) {}

    /**
     * Design correction 2 ("never leak cost to Sales"): the ONLY shape a {@code sales}/
     * {@code sales_manager} actor can ever receive for a pricing decision. Deliberately has no
     * cost, margin, or raw-factory field of any kind — this is a distinct query path
     * ({@link PricingDecisionRepository#findApprovedSalesView}), not a filter applied to
     * {@link PricingDecisionDto} after the fact, so there is no cost-bearing object in memory on
     * this path for a filter to forget to strip.
     */
    public record PricingDecisionSalesViewDto(
        long pricingRequestId,
        long pricingDecisionId,
        String currency,
        Instant approvedAt,
        List<PricingDecisionSalesItemDto> items
    ) {}

    public record PricingDecisionSalesItemDto(
        long pricingRequestItemId,
        // Step 4 (Customer Quotation Generation and Issuance) needs this to snapshot the FK
        // onto sales.quotation_item.pricing_decision_item_id — an id, not a cost/margin/FX
        // value, so exposing it here does not weaken design correction 2's "no cost leak".
        long pricingDecisionItemId,
        String brand,
        String model,
        String productDescription,
        String requestedUnitBasis,
        BigDecimal requestedQuantity,
        BigDecimal approvedSellingPricePerRequestedUnit,
        BigDecimal discountCeilingPct,
        BigDecimal minimumSellingPricePerRequestedUnit
    ) {}
}
