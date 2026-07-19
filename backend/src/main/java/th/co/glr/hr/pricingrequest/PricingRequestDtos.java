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
        Instant updatedAt
    ) {}

    public record PricingRequestItemDto(
        long id,
        long pricingRequestId,
        Long sourceTicketItemId,
        Long productId,
        Long variantId,
        String brand,
        String model,
        String color,
        String texture,
        String size,
        String factory,
        BigDecimal requestedQty,
        BigDecimal requestedQtySqm,
        String requestedUnit,
        String quantityType,
        LocalDate targetDeliveryDate,
        String deliveryLocation,
        String specialRequirement,
        int sortOrder
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
}
