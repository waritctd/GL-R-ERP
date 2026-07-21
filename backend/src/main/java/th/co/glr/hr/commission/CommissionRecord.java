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
    Long managerApprovedBy,
    String managerApprovedByName,
    Instant managerApprovedAt,
    Long ceoApprovedBy,
    String ceoApprovedByName,
    Instant ceoApprovedAt,
    Long rejectedById,
    String rejectedByName,
    Instant rejectedAt,
    String rejectionReason,
    Long cancellationOfId,
    String cancellationReason,
    Instant createdAt,
    Instant updatedAt,
    // Step 9 (final payment / closeout / commission gate): when sourceTicketId is set, the deal's
    // payableAmount at submission time (a snapshot, not a live join) and whether grossAmount
    // diverged from it by more than the 5% cross-check threshold. Both stay null/false for
    // unlinked (sourceTicketId = null) commissions — unchanged from pre-Step-9 behavior.
    BigDecimal dealPayableAmountSnapshot,
    boolean dealAmountMismatch
) {}
