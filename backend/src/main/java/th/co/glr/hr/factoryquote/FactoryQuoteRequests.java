package th.co.glr.hr.factoryquote;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public final class FactoryQuoteRequests {
    private FactoryQuoteRequests() {}

    public record UpdateFactoryQuoteDraftRequest(
        String emailTo,
        String emailSubject,
        String emailBody,
        String note
    ) {}

    public record SendFactoryQuoteRequest(
        String emailTo,
        String emailSubject,
        String emailBody,
        @NotBlank String clientRequestId
    ) {}

    public record ReceiveFactoryQuoteRequest(
        String supplierQuoteRef,
        String defaultCurrency,
        String paymentTerms,
        String leadTimeText,
        String revisionReason,
        String negotiationNote,
        @NotEmpty List<@Valid ReceiveFactoryQuoteItemRequest> items,
        @NotBlank String clientRequestId
    ) {}

    public record ReceiveFactoryQuoteItemRequest(
        @NotNull Long pricingRequestItemId,
        String supplierProductCode,
        String supplierProductDescription,
        @NotNull @DecimalMin("0.0001") BigDecimal quotedQuantity,
        @NotBlank String quotedUnit,
        @NotBlank String unitBasis,
        @NotNull @DecimalMin("0.0000") BigDecimal rawUnitPrice,
        @NotBlank String currency,
        @DecimalMin("0.0000") BigDecimal minimumOrderQuantity,
        @DecimalMin("0.000001") BigDecimal sqmPerUnit,
        @DecimalMin("0.0000") BigDecimal piecesPerBox,
        @DecimalMin("0.000001") BigDecimal linearMPerUnit,
        String leadTimeText,
        String availabilityNote,
        String lineNote
    ) {}

    public record StartNegotiationRequest(
        @NotBlank String note
    ) {}

    public record MarkNotAvailableRequest(
        @NotBlank String reason
    ) {}
}
