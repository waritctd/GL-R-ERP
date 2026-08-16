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
    // Commission redesign calc-refine (2026-07-22, V82): how many times this receipt's actual
    // cash counts toward the monthly TIER BASE (1, 2, or 3 -- see CommissionCalculator). Never
    // affects actualReceived/commissionableBase themselves, which stay the literal per-record
    // amounts; only the aggregation across a rep's month is weighted by this.
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
    // Step 9 (final payment / closeout / commission gate): when sourceTicketId is set, the deal's
    // payableAmount at submission time (a snapshot, not a live join) and whether grossAmount
    // diverged from it by more than the 5% cross-check threshold. Both stay null/false for
    // unlinked (sourceTicketId = null) commissions — unchanged from pre-Step-9 behavior.
    BigDecimal dealPayableAmountSnapshot,
    boolean dealAmountMismatch,
    // Manual commission entries (feat/commission-manual-adjustments, V84): the signed, hand-typed
    // amount for kind ADJUSTMENT/MANAGER (can be negative for a deduction) and its required
    // reason. Both null for SALE/CLAWBACK -- those keep going through actualReceived/
    // commissionableBase/the tier calc exactly as before. See CommissionService#createManualCommission.
    BigDecimal manualAmount,
    String manualReason,
    // V148 (per-item stock-commission weighting): the FROZEN, blended per-item weight -- see
    // sales.commission_record.effective_weight_multiplier's migration comment (V148) for the full
    // backward-compatibility rationale. NULL for every record that predates this feature and for
    // every record this feature does not apply to (unlinked/manual, no items, zero item value);
    // non-null only for a SALE/CLAWBACK whose ticket had priced, stock-covered items at the moment
    // the record was created. Use #effectiveWeight() below to read "the weight payroll actually
    // uses" -- never read weightMultiplier() directly expecting it to be authoritative.
    BigDecimal effectiveWeightMultiplier
) {
    /**
     * The weight payroll/simulate/monthlySummary actually use for this record: the frozen
     * item-derived blend when one was computed, else the plain manager-set {@link
     * #weightMultiplier()} -- the exact {@code COALESCE(effective_weight_multiplier,
     * weight_multiplier)} {@link CommissionRepository#sumActiveWeightedActualReceived} runs in
     * SQL, mirrored here in Java for {@link CommissionService#computeRepPayrollCommissions}'s
     * per-record accumulation, which cannot use a single SQL aggregate (it also layers in manual
     * entries per rep). The two must never diverge -- if this method's logic ever changes, that
     * repository method's SQL must change with it, and vice versa.
     */
    public BigDecimal effectiveWeight() {
        return effectiveWeightMultiplier != null ? effectiveWeightMultiplier : BigDecimal.valueOf(weightMultiplier);
    }
}
