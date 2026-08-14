package th.co.glr.hr.commission;

import java.math.BigDecimal;

/**
 * One row of {@link CommissionMonthlySummaryDto#tiers()} — a tier's own share of the total
 * {@code tierCommission}, attributed by {@link CommissionService#monthlySummary} composing the
 * unmodified, audited {@link CommissionCalculator#progressiveCommission} rather than duplicating
 * its bracket arithmetic. See that method's Javadoc for the attribution technique and its one
 * documented caveat.
 */
public record CommissionTierRowDto(
    int tierNumber,
    BigDecimal lowerBound,
    BigDecimal upperBound,
    BigDecimal ratePercent,
    boolean highRoller,
    BigDecimal commission
) {}
