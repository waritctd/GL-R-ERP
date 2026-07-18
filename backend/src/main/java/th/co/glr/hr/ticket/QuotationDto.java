package th.co.glr.hr.ticket;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record QuotationDto(
    long id,
    long ticketId,
    String number,
    long issuedById,
    String issuedByName,
    Instant issuedAt,
    String pdfPath,
    BigDecimal totalAmount,
    String currency,
    int quotationVersion,
    String docStatus,
    String recipientType,
    String recipientLabel,
    String paymentTerms,
    String leadTime,
    String deliveryTerms,
    LocalDate validityDate,
    Instant sentAt,
    Instant acceptedAt,
    Instant rejectedAt,
    Long parentQuotationId,
    LocalDate offerDate,
    Integer depositPercent,
    Integer deliveryLeadDays
) {
    public QuotationDto(long id, long ticketId, String number, long issuedById, String issuedByName,
                        Instant issuedAt, String pdfPath, BigDecimal totalAmount, String currency,
                        int quotationVersion, String docStatus) {
        this(id, ticketId, number, issuedById, issuedByName, issuedAt, pdfPath, totalAmount, currency,
            quotationVersion, docStatus, QuotationRecipient.UNSPECIFIED, null, null, null, null,
            null, null, null, null, null, null, null, null);
    }
}
