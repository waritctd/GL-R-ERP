package th.co.glr.hr.pricingrequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class PricingRequestRequests {
    private PricingRequestRequests() {}

    public record CreatePricingRequestRequest(
        @NotBlank String recipientType,
        Long recipientContactId,
        String recipientLabel,
        LocalDate requiredDate,
        @DecimalMin("0.00") BigDecimal customerTargetPrice,
        String targetCurrency,
        String note,
        String clientRequestId,
        @NotEmpty List<@Valid PricingRequestItemRequest> items
    ) {}

    /** Same fields as {@link CreatePricingRequestRequest}, but all optional. */
    public record UpdatePricingRequestRequest(
        String recipientType,
        Long recipientContactId,
        String recipientLabel,
        LocalDate requiredDate,
        @DecimalMin("0.00") BigDecimal customerTargetPrice,
        String targetCurrency,
        String note,
        List<@Valid PricingRequestItemRequest> items
    ) {}

    public record PricingRequestItemRequest(
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
        @NotNull @DecimalMin("0.0001") BigDecimal requestedQty,
        @DecimalMin("0.0000") BigDecimal requestedQtySqm,
        @NotBlank String requestedUnit,
        // See PricingRequestItemDto.requestedUnitBasis's javadoc — required so submit()/
        // PricingCostingService can normalize this line's requested quantity onto the same
        // basis as the factory-quoted price (financial-integrity review Finding B).
        @NotBlank String requestedUnitBasis,
        @NotBlank String quantityType,
        LocalDate targetDeliveryDate,
        String deliveryLocation,
        String specialRequirement
    ) {}

    public record CancelPricingRequestRequest(
        @NotBlank String reason
    ) {}

    /**
     * Import fills in the factory on ONE line Sales left blank, so the factory-email step can
     * route it. Import-only, and only while the request sits in Import's hands — see
     * {@code PricingRequestService#setItemFactory} for the full set of guards and why this is a
     * gap-FILL rather than a re-route.
     *
     * <p>The 255 cap matches {@code sales.pricing_request_item.factory VARCHAR(255)} (V59): the
     * column would otherwise reject the write as a raw constraint violation (500) instead of the
     * 400 a too-long name deserves.
     */
    public record SetItemFactoryRequest(
        @NotBlank @Size(max = 255) String factory
    ) {}

    /** Import-only toggle on a Pricing Request attachment (V69, review remediation COMMIT 4). */
    public record UpdatePricingRequestAttachmentRequest(
        @NotNull Boolean includeInFactoryEmail
    ) {}

    public record CustomerChangeRevisionRequest(
        @NotBlank String revisionReason,
        @NotBlank String clientRequestId,
        @NotBlank String recipientType,
        Long recipientContactId,
        String recipientLabel,
        LocalDate requiredDate,
        @DecimalMin("0.00") BigDecimal customerTargetPrice,
        String targetCurrency,
        String note,
        @NotEmpty List<@Valid PricingRequestItemRequest> items
    ) {}
}
