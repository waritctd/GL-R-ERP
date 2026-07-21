package th.co.glr.hr.pricingrequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PricingRequestDtos {
    private PricingRequestDtos() {}

    public record PricingRequestSummaryDto(
        long id,
        String requestCode,
        long ticketId,
        String ticketCode,
        String projectName,
        String customerName,
        // Required for read-scoping: "sales" role may only see requests on
        // tickets they created (commit 3).
        long ticketCreatedById,
        String recipientType,
        Long recipientContactId,
        String recipientLabel,
        String status,
        long requestedById,
        String requestedByName,
        Long assignedImportId,
        String assignedImportName,
        LocalDate requiredDate,
        BigDecimal customerTargetPrice,
        String targetCurrency,
        String note,
        int itemCount,
        int revisionNo,
        Long parentPricingRequestId,
        Instant submittedAt,
        Instant pickedUpAt,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt,
        // Step 6 (V76): non-null once OrderConfirmationService.confirmOrder has bridged this
        // (terminal, QUOTATION_ACCEPTED) request into the legacy ticket payment/deposit
        // pipeline. QUOTATION_ACCEPTED itself never changes again, so this is the only signal
        // the frontend (or a replay) has that the bridge already ran.
        Instant orderConfirmedAt
    ) {}

    public record PricingRequestItemDto(
        long id,
        long pricingRequestId,
        Long sourceTicketItemId,
        Long productId,
        Long variantId,
        String brand,
        String model,
        String productDescription,
        String color,
        String texture,
        String size,
        String factory,
        BigDecimal requestedQty,
        BigDecimal requestedQtySqm,
        String requestedUnit,
        // Machine-readable basis for requestedQty/requestedUnit (V68, financial-integrity
        // review Finding B) — one of UnitBasis's four canonical codes. requestedUnit stays
        // free text for display/the factory email body; this is what PricingCostingService
        // now uses to normalize the requested quantity onto the same basis as the quoted
        // price before multiplying.
        String requestedUnitBasis,
        String quantityType,
        LocalDate targetDeliveryDate,
        String deliveryLocation,
        String specialRequirement,
        int sortOrder,
        Long priceListVersionId,
        Long catalogPriceId,
        BigDecimal catalogBasePrice,
        String catalogCurrency,
        LocalDate catalogEffectiveDate,
        Long resolvedFactoryId,
        String resolvedFactoryName,
        String catalogProductCode,
        String catalogBrand,
        String catalogCollection,
        String catalogModel
    ) {}

    public record PricingRequestEventDto(
        long id,
        long pricingRequestId,
        long ticketId,
        Long actorId,
        String actorName,
        String eventKind,
        String fromStatus,
        String toStatus,
        String message,
        // Raw JSON string — the service owns (de)serialisation, not this DTO.
        String metadata,
        Instant createdAt
    ) {}

    public record PricingRequestDetailDto(
        PricingRequestSummaryDto summary,
        List<PricingRequestItemDto> items,
        List<PricingRequestEventDto> events
    ) {}

    /**
     * Sales-level supporting attachment on the Pricing Request itself (V69, review remediation
     * COMMIT 4) — distinct from a factory quote's raw supplier evidence
     * ({@code FactoryQuoteDtos.FactoryQuoteAttachmentDto}). Uploaded by Sales while the request is
     * still {@code DRAFT}/{@code MORE_INFO_REQUIRED}; Import may mark {@code includeInFactoryEmail}
     * so a later factory email carries it. Deliberately has no local file path field — that stays
     * server-internal (see {@code PricingRequestRepository.PricingRequestEmailAttachmentFile}, used
     * only by {@code FactoryQuoteService.attemptSend}).
     */
    public record PricingRequestAttachmentDto(
        long id,
        long pricingRequestId,
        String fileName,
        String mimeType,
        Long fileSize,
        boolean includeInFactoryEmail,
        long uploadedBy,
        Instant uploadedAt
    ) {}
}
