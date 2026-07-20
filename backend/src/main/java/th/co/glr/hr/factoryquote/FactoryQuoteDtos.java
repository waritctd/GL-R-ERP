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
        List<FactoryQuoteAttachmentDto> attachments,
        // Most recent sales.factory_quote_email_dispatch row for this quote (the outbox worker's
        // state), so the frontend can show pending/sending/sent/failed without a second endpoint.
        // Null when send() has never been called for this quote.
        String dispatchStatus,
        int dispatchAttemptCount,
        String dispatchFailureMessage,
        Instant dispatchNextAttemptAt
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
        BigDecimal linearMPerUnit,
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
        Instant uploadedAt,
        // Audited-tombstone fields (V69, review remediation COMMIT 4): a permitted deletion now
        // sets these three instead of physically removing the row/file — see
        // FactoryQuoteService.deleteAttachment. Null means "not deleted." The row and its
        // deletedAt/deletedBy/deleteReason remain visible in the same list Import/CEO already
        // see, so the audit trail is never hidden, only marked.
        Instant deletedAt,
        Long deletedBy,
        String deleteReason
    ) {}
}
