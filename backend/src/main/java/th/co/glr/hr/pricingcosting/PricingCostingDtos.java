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
