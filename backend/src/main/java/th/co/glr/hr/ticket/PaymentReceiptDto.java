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
    Instant createdAt
) {}
