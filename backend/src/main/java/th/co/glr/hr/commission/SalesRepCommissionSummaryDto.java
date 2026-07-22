package th.co.glr.hr.commission;

import java.math.BigDecimal;

public record SalesRepCommissionSummaryDto(
    long salesRepId,
    String salesRepName,
    BigDecimal commissionableBase,
    // commissionAmount is the FINAL total: the tier-calc commission on commissionableBase PLUS
    // manualAdjustmentAmount below. Manual entries never feed commissionableBase itself -- see
    // CommissionService#payrollReadySummary.
    BigDecimal commissionAmount,
    // Sum of this rep's APPROVED manual (ADJUSTMENT + MANAGER) commission_record.manual_amount
    // for the month, already folded into commissionAmount above. Zero when the rep has no manual
    // entries this month. Surfaced separately so payroll/reviewers can see the breakdown.
    BigDecimal manualAdjustmentAmount
) {}
