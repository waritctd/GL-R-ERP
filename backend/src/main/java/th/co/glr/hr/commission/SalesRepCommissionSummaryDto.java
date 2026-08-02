package th.co.glr.hr.commission;

import java.math.BigDecimal;

public record SalesRepCommissionSummaryDto(
    long salesRepId,
    String salesRepName,
    BigDecimal commissionableBase,
    // commissionAmount is the FINAL total: the tier-calc commission on commissionableBase PLUS
    // manualAdjustmentAmount, incentiveAmount, and stockBonusAmount below. Manual entries never
    // feed commissionableBase itself -- see CommissionService#payrollReadySummary.
    BigDecimal commissionAmount,
    // Sum of this rep's APPROVED manual (ADJUSTMENT + MANAGER + any un-suppressed manual
    // INCENTIVE/STOCK_BONUS) commission_record.manual_amount for the month, already folded into
    // commissionAmount above. Zero when the rep has no manual entries this month. Surfaced
    // separately so payroll/reviewers can see the breakdown.
    BigDecimal manualAdjustmentAmount,
    // Issue #405: the auto-computed INCENTIVE ladder (ข้อ 12) amount for this rep/month, already
    // folded into commissionAmount above. ZERO for every payroll month before 2026-08-01 (no
    // matching sales.commission_incentive_tier generation) and ZERO when the rep already carries
    // an approved MANUAL INCENTIVE entry for the month -- manual wins, see
    // CommissionService#computeRepPayrollCommissions's double-count guard.
    BigDecimal incentiveAmount,
    // Issue #405: the auto-computed STOCK_BONUS amount for this rep/month, already folded into
    // commissionAmount above. ZERO for every rep until the CEO enables sales.stock_bonus_config
    // (ships config-gated OFF) and ZERO when the rep already carries an approved MANUAL
    // STOCK_BONUS entry for the month (same double-count guard as incentiveAmount above).
    BigDecimal stockBonusAmount
) {}
