package th.co.glr.hr.commission;

import java.math.BigDecimal;

/** One row of CommissionMonthlySummaryDto#tiers() — a tier's own share of the total tierCommission, attributed
 * by CommissionService#monthlySummary composing the unmodified, audited
 * CommissionCalculator#progressiveCommission rather than duplicating its bracket arithmetic. */
public record CommissionTierRowDto(
    int tierNumber,
    BigDecimal lowerBound,
    BigDecimal upperBound,
    BigDecimal ratePercent,
    boolean highRoller,
    BigDecimal commission
) {}
