package th.co.glr.hr.procurement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Import/CEO-only shapes — see {@link ProcurementService}'s own {@code RAW_PO_ROLES} (reusing the
 * {@code FactoryQuoteService.RAW_QUOTE_ROLES}/{@code PricingDecisionService.RAW_DECISION_ROLES}
 * confidentiality pattern: raw supplier price/PO detail never reaches {@code sales}/{@code
 * sales_manager}).
 */
public final class ProcurementDtos {
    private ProcurementDtos() {}

    public record FactoryPurchaseOrderDto(
        long id,
        String poNumber,
        long pricingRequestId,
        String pricingRequestCode,
        long ticketId,
        String ticketCode,
        Long factoryId,
        String factoryName,
        String status,
        String supplierProformaRef,
        String supplierPaymentScheduleNote,
        String currency,
        BigDecimal totalAmount,
        LocalDate etd,
        LocalDate eta,
        String containerRef,
        String customsStatus,
        BigDecimal actualLandedCostThb,
        String cancelReason,
        Long createdBy,
        String createdByName,
        Instant createdAt,
        Instant updatedAt,
        Instant receivedAt,
        Instant cancelledAt,
        List<FactoryPurchaseOrderItemDto> items
    ) {}

    public record FactoryPurchaseOrderItemDto(
        long id,
        long factoryPurchaseOrderId,
        long pricingCostingItemId,
        long pricingRequestItemId,
        String brand,
        String model,
        String productDescription,
        BigDecimal quantity,
        BigDecimal unitPrice,
        String currency,
        BigDecimal lineTotal,
        // The Step 2 ESTIMATE, joined read-only for comparison against the parent PO's own
        // actualLandedCostThb once goods are received — never conflated (task's own explicit
        // instruction). Deliberately named "estimated..." so no reader can mistake it for the
        // real number.
        BigDecimal estimatedLandedCostPerUnitThb,
        BigDecimal estimatedTotalLandedCostThb,
        // Step 8 (V78): the ACTUAL quantity Import counted on receipt, and any QC/damage note —
        // both null until ProcurementService#recordGoodsReceived is called for this line.
        BigDecimal qtyReceived,
        String qcNote,
        // Computed, never stored (same "estimate vs actual, never conflated" discipline as
        // above): qtyReceived - quantity. Null until qtyReceived is recorded; positive = over-
        // shipment, negative = shortage, zero = exactly as ordered.
        BigDecimal discrepancyQty
    ) {}
}
