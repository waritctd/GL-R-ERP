package th.co.glr.hr.pricingcosting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PricingCostingDtos {
    private PricingCostingDtos() {}

    public record PricingCostingDto(
        long id,
        String costingCode,
        long pricingRequestId,
        int versionNo,
        String status,
        String note,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant calculatedAt,
        Long submittedBy,
        Instant submittedAt,
        BigDecimal totalLandedCostThb,
        List<PricingCostingItemDto> items
    ) {}

    public record PricingCostingItemDto(
        long id,
        long pricingCostingId,
        long pricingRequestItemId,
        long factoryQuoteId,
        long factoryQuoteItemId,
        int factoryQuoteRevisionNo,
        Long factoryId,
        String factoryName,
        String supplierQuoteRef,
        BigDecimal rawUnitPrice,
        String rawCurrency,
        String rawUnit,
        String unitBasis,
        BigDecimal requestedQuantity,
        String requestedUnit,
        // Audit trail for the unit-normalization fix (financial-integrity review Finding B, commit 3): which basis
        // requestedQuantity was expressed in, how many physical pieces it normalized to, and the linear-metre
        // conversion factor used (if this line's price or requested quantity was PER_LINEAR_M).
        String requestedUnitBasis,
        BigDecimal normalizedQuantityPieces,
        BigDecimal linearMPerUnit,
        BigDecimal sqmPerUnit,
        BigDecimal piecesPerBox,
        BigDecimal fxRate,
        String fxSource,
        LocalDate fxEffectiveDate,
        Instant fxFetchedAt,
        long calculationConfigId,
        int calculationConfigVersion,
        BigDecimal goodsCostThb,
        BigDecimal freightCostThb,
        BigDecimal insuranceCostThb,
        BigDecimal importDutyThb,
        BigDecimal inlandTransportCostThb,
        BigDecimal otherCostThb,
        BigDecimal cifCostThb,
        BigDecimal landedCostPerUnitThb,
        BigDecimal totalLandedCostThb,
        // V109 engine (V152) ──────────────────────────────────────────────────────────────── clearanceFeeThb: the
        // item's own allocated share of its factory shipment's flat customs clearance fee (S). productType: the
        // resolved product_type (override, or TILE default) actually used for THIS row's duty lookup.
        BigDecimal clearanceFeeThb,
        String productType,
        Instant calculatedAt,
        String calculationSnapshot,
        // CEO cost override (V141, "CEO owns costing") ───────────────────────────────────── Sits BESIDE
        // landedCostPerUnitThb/totalLandedCostThb above, which keep holding the COMPUTED figures forever — an override
        // never overwrites them.
        BigDecimal manualLandedCostPerUnitThb,
        String overrideReason,
        Long overriddenBy,
        Instant overriddenAt,
        // Snapshotted from THIS row's fxRate/calculationConfigVersion at the moment the override was entered —
        // compared against the row's live values to derive overrideStale below.
        BigDecimal overrideFxRate,
        Integer overrideCalcConfigVersion,
        // Derived, never stored (so none of the three can drift out of sync with the row they describe —
        // PricingCostingRepository#mapItem recomputes them on every read)
        BigDecimal effectiveLandedCostPerUnitThb,
        BigDecimal effectiveTotalLandedCostThb,
        boolean overrideStale,
        /** V156: non-null when the freight table could not be looked up for this line (no thickness_mm or no origin
         * country on the catalogue row), in which case every shipment-derived cost above is null and the CEO must
         * supply one before approval. */
        String uncostableReason,
        /** Comma-separated codes behind {@code uncostableReason}: MISSING_SQM, MISSING_THICKNESS, MISSING_ORIGIN, NO_FREIGHT_BAND. */
        String uncostableCode
    ) {}
}
