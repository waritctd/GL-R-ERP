package th.co.glr.hr.ticket;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentReceiptDto(
    long receiptId,
    long ticketId,
    String kind,
    BigDecimal amount,
    String currency,
    Instant receivedAt,
    long recordedById,
    String recordedByName,
    String note,
    Long depositNoticeId,
    String receiptRef,
    Instant createdAt,
    // V213: MANUAL (typed by account) or EXPRESS (uploaded; receiptRef is then the Express document number), and the
    // payer name as the Express file printed it (null for MANUAL).
    String source,
    String customerName
) {}
