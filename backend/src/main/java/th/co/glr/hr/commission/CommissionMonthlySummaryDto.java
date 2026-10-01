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
 * <p>STOCK_BONUS and the manager TEAM OVERRIDE (V199) are included; the former "KNOWN GAP" for
 * STOCK_BONUS is closed. The last seven components are the "how was my commission built" detail.
 *
 * @param commissionableBase the 2dp display value of the full-precision monthly tier base
 *                            ({@link CommissionCalculator#monthlyTierBase})
 * @param tierCommission     {@code CommissionCalculator#progressiveCommission(commissionableBase, tiers)}
 * @param manualTotal         sum of {@code manualAmount} over this rep/month's APPROVED manual-kind
 *                            records (ADJUSTMENT/MANAGER/STOCK_BONUS/INCENTIVE) — never fed into
 *                            {@code commissionableBase}, only added on top of the total, mirroring
 *                            {@link CommissionService#computeRepPayrollCommissions} exactly
 * @param totalCommission    {@code tierCommission + incentiveAmount + manualTotal + stockBonusAmount +
 *                            teamOverrideAmount}
 * @param rawCommissionableBase UNWEIGHTED sum of actual_received / 1.07 for this rep (same preview
 *                            filter as {@code commissionableBase}), 2dp
 * @param weightUpliftBase    {@code commissionableBase - rawCommissionableBase}: the extra the x2/x3
 *                            weighting added to the tier base, 2dp
 * @param stockBonusAmount    auto STOCK_BONUS exactly as payroll computes it (config-gated; a positive
 *                            approved manual STOCK_BONUS replaces it)
 * @param teamOverrideAmount  manager team override; ZERO unless this rep is a recipient
 * @param companyCommissionableBase company-wide unweighted ex-VAT base the override is computed from;
 *                            NULL unless this rep is a recipient (company-wide data -- never shown to
 *                            a non-recipient)
 * @param teamOverrideThresholdBase the generation's threshold; NULL unless recipient
 * @param teamOverrideRatePercent the generation's rate in percent; NULL unless recipient
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
    List<CommissionTierRowDto> tiers,
    // Team override + full-detail additions (V199).
    BigDecimal rawCommissionableBase,
    BigDecimal weightUpliftBase,
    BigDecimal stockBonusAmount,
    BigDecimal teamOverrideAmount,
    BigDecimal companyCommissionableBase,
    BigDecimal teamOverrideThresholdBase,
    BigDecimal teamOverrideRatePercent
) {}
