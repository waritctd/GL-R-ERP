package th.co.glr.hr.factoryquote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class FactoryQuoteDtos {
    private FactoryQuoteDtos() {}

    public record FactoryQuoteDto(
        long id,
        String quoteCode,
        long pricingRequestId,
        Long factoryId,
        String factoryName,
        String status,
        String emailTo,
        String emailSubject,
        String emailBody,
        Instant emailSentAt,
        Long sentBy,
        String supplierQuoteRef,
        String defaultCurrency,
        String paymentTerms,
        String leadTimeText,
        String note,
        String negotiationNote,
        Instant requestedAt,
        Instant receivedAt,
        Long rootFactoryQuoteId,
        Long parentFactoryQuoteId,
        int revisionNo,
        String revisionReason,
        boolean current,
        Instant createdAt,
        Instant updatedAt,
        List<FactoryQuoteItemDto> items,
        List<FactoryQuoteAttachmentDto> attachments
    ) {}

    public record FactoryQuoteItemDto(
        long id,
        long factoryQuoteId,
        long pricingRequestItemId,
        Long catalogProductIdSnapshot,
        String supplierProductCode,
        String supplierProductDescription,
        BigDecimal quotedQuantity,
        String quotedUnit,
        String unitBasis,
        BigDecimal rawUnitPrice,
        String currency,
        BigDecimal minimumOrderQuantity,
        BigDecimal sqmPerUnit,
        BigDecimal piecesPerBox,
        String leadTimeText,
        String availabilityNote,
        String lineNote,
        int sortOrder
    ) {}

    public record FactoryQuoteAttachmentDto(
        long id,
        long factoryQuoteId,
        String fileName,
        String mimeType,
        Long fileSize,
        long uploadedBy,
        Instant uploadedAt
    ) {}
}
