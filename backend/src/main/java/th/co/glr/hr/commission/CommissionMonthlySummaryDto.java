package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A sales rep's live, server-computed monthly commission estimate — {@link
 * CommissionService#monthlySummary}'s response shape, served by {@code GET
 * /api/commissions/monthly-summary}. Replaces the frontend's own JS re-implementation of the tier
 * math (the former {@code commissionCalc.js}), which could desynchronise from a DB tier-config
 * change (see the V81 tier-13 rate correction, the case on record). Every figure here is derived
 * from the same {@link CommissionCalculator}/{@link CommissionRepository} the real payroll run
 * uses — never re-derived client-side.
 *
 * <p>KNOWN GAP, deliberate: STOCK_BONUS is not included. The page this replaces never showed it,
 * it ships config-gated OFF ({@link StockBonusConfig#disabled()}), and adding a new rendered
 * figure is out of scope for a "who computes the number" change.
 *
 * @param commissionableBase the 2dp display value of the full-precision monthly tier base
 *                            ({@link CommissionCalculator#monthlyTierBase})
 * @param tierCommission     {@code CommissionCalculator#progressiveCommission(commissionableBase, tiers)}
 * @param manualTotal         sum of {@code manualAmount} over this rep/month's APPROVED manual-kind
 *                            records (ADJUSTMENT/MANAGER/STOCK_BONUS/INCENTIVE) — never fed into
 *                            {@code commissionableBase}, only added on top of the total, mirroring
 *                            {@link CommissionService#computeRepPayrollCommissions} exactly
 * @param totalCommission    {@code tierCommission + incentiveAmount + manualTotal}
 * @param belowFloor         true when a positive base still produced zero tier commission — the
 *                            only way that happens is {@link CommissionCalculator}'s private
 *                            monthly floor, so this is derived rather than re-declared
 */
public record CommissionMonthlySummaryDto(
    LocalDate payrollMonth,
    long salesRepId,
    BigDecimal commissionableBase,
    BigDecimal tierCommission,
    BigDecimal incentiveAmount,
    BigDecimal manualTotal,
    BigDecimal totalCommission,
    boolean belowFloor,
    List<CommissionTierRowDto> tiers
) {}
