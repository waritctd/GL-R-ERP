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
        boolean stale,
        String staleReason,
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
        // Audit trail for the unit-normalization fix (financial-integrity review Finding B,
        // commit 3): which basis requestedQuantity was expressed in, how many physical pieces
        // it normalized to, and the linear-metre conversion factor used (if this line's price
        // or requested quantity was PER_LINEAR_M). sqmPerUnit/piecesPerBox above already
        // played this role for PER_SQM/PER_BOX; these three columns close the same gap for
        // PER_LINEAR_M and make the normalization itself (not just the raw inputs) inspectable.
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
        Instant calculatedAt,
        String calculationSnapshot
    ) {}
}
