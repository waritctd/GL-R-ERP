package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A sales rep's live, server-computed monthly commission estimate — CommissionService#monthlySummary's
 * response shape, served by GET /api/commissions/monthly-summary. STOCK_BONUS and the manager TEAM OVERRIDE
 * (V199) are included; the former "KNOWN GAP" for STOCK_BONUS is closed. */
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
