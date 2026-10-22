package th.co.glr.hr.factoryquote;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
        // CR-1 (GLA-167): the "ติดต่อโรงงานแล้ว" step. All null until the factory is marked contacted.
        LocalDate contactedOn,
        String contactedNote,
        Long contactedBy,
        Instant contactedAt
    ) {
        /** The pre-CR-1 shape (28 components). Defaults the four contacted fields to null. */
        public FactoryQuoteDto(
            long id, String quoteCode, long pricingRequestId, Long factoryId, String factoryName,
            String status, String emailTo, String emailSubject, String emailBody, Instant emailSentAt,
            Long sentBy, String supplierQuoteRef, String defaultCurrency, String paymentTerms,
            String leadTimeText, String note, String negotiationNote, Instant requestedAt,
            Instant receivedAt, Long rootFactoryQuoteId, Long parentFactoryQuoteId, int revisionNo,
            String revisionReason, boolean current, Instant createdAt, Instant updatedAt,
            List<FactoryQuoteItemDto> items, List<FactoryQuoteAttachmentDto> attachments
        ) {
            this(id, quoteCode, pricingRequestId, factoryId, factoryName, status, emailTo,
                emailSubject, emailBody, emailSentAt, sentBy, supplierQuoteRef, defaultCurrency,
                paymentTerms, leadTimeText, note, negotiationNote, requestedAt, receivedAt,
                rootFactoryQuoteId, parentFactoryQuoteId, revisionNo, revisionReason, current,
                createdAt, updatedAt, items, attachments, null, null, null, null);
        }
    }

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
