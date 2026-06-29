package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record InvoiceDetails(
    long id,
    String invoiceNumber,
    LocalDate invoiceDate,
    BigDecimal grossAmount,
    BigDecimal bankFees,
    BigDecimal suspenseVat,
    BigDecimal transportFee,
    BigDecimal cutFee,
    BigDecimal shortfall,
    Instant createdAt,
    Instant updatedAt
) {}
