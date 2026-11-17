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
    // Commission redesign calc-refine (2026-07-22, V82): how many times this receipt's actual cash counts toward
    // the monthly TIER BASE (1, 2, or 3 -- see CommissionCalculator).
    int weightMultiplier,
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
    // Step 9 (final payment / closeout / commission gate): when sourceTicketId is set, the deal's payableAmount at
    // submission time (a snapshot, not a live join) and whether grossAmount diverged from it by more than the 5%
    // cross-check threshold.
    BigDecimal dealPayableAmountSnapshot,
    boolean dealAmountMismatch,
    // Manual commission entries (feat/commission-manual-adjustments, V84): the signed, hand-typed amount for kind
    // ADJUSTMENT/MANAGER (can be negative for a deduction) and its required reason. See
    // CommissionService#createManualCommission.
    BigDecimal manualAmount,
    String manualReason,
    // V148 (per-item stock-commission weighting): the FROZEN, blended per-item weight -- see
    // sales.commission_record.effective_weight_multiplier's migration comment (V148) for the full
    // backward-compatibility rationale.
    BigDecimal effectiveWeightMultiplier
) {
    /** The weight payroll/simulate/monthlySummary actually use for this record: the frozen item-derived blend when
     * one was computed, else the plain manager-set #weightMultiplier. */
    public BigDecimal effectiveWeight() {
        return effectiveWeightMultiplier != null ? effectiveWeightMultiplier : BigDecimal.valueOf(weightMultiplier);
    }
}
