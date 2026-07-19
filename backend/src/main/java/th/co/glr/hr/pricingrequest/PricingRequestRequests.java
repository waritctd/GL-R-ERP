package th.co.glr.hr.pricingrequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
        @NotBlank String quantityType,
        LocalDate targetDeliveryDate,
        String deliveryLocation,
        String specialRequirement
    ) {}

    public record RequestMoreInformationRequest(
        @NotBlank String message,
        LocalDate dueDate
    ) {}

    public record RespondMoreInformationRequest(
        @NotBlank String response
    ) {}

    public record CancelPricingRequestRequest(
        @NotBlank String reason
    ) {}
}
