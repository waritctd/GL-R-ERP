package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record CommissionRecord(
    long id,
    InvoiceDetails invoiceDetails,
    Long sourceTicketId,
    long salesRepId,
    String salesRepName,
    long submittedById,
    String kind,
    String status,
    LocalDate payrollMonth,
    BigDecimal actualReceived,
    BigDecimal commissionableBase,
    Long approvedById,
    Instant approvedAt,
    Long cancellationOfId,
    String cancellationReason,
    Instant createdAt,
    Instant updatedAt
) {}
