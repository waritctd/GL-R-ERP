package th.co.glr.hr.ticket;

import java.math.BigDecimal;
import java.time.Instant;

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
    String docStatus
) {}
