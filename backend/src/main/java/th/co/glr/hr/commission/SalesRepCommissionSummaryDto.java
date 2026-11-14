package th.co.glr.hr.commission;

import java.math.BigDecimal;

public record SalesRepCommissionSummaryDto(
    long salesRepId,
    String salesRepName,
    BigDecimal commissionableBase,
    // commissionAmount is the FINAL total: the tier-calc commission on commissionableBase PLUS
    // manualAdjustmentAmount, incentiveAmount, and stockBonusAmount below. Manual entries never feed
    // commissionableBase itself -- see CommissionService#payrollReadySummary.
    BigDecimal commissionAmount,
    // Sum of this rep's APPROVED manual (ADJUSTMENT + MANAGER + any un-suppressed manual INCENTIVE/STOCK_BONUS)
    // commission_record.manual_amount for the month, already folded into commissionAmount above. Zero when the rep
    // has no manual entries this month.
    BigDecimal manualAdjustmentAmount,
    // the auto-computed INCENTIVE ladder (ข้อ 12) amount for this rep/month, already folded into commissionAmount
    // above.
    BigDecimal incentiveAmount,
    // the auto-computed STOCK_BONUS amount for this rep/month, already folded into commissionAmount above.
    BigDecimal stockBonusAmount,
    // Manager team override (V199), already folded into commissionAmount above. ZERO unless this rep is a
    // configured recipient.
    BigDecimal teamOverrideAmount
) {}
