package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Issue #405: one row of the auto-computed INCENTIVE ladder (ข้อ 12) — a flat monthly amount
 * paid once the rep's monthly tier base ({@link CommissionCalculator#monthlyTierBase}) reaches
 * {@code thresholdBase}. Config-driven like {@link TierConfig} (see
 * {@code V108__commission_incentive_and_stock_bonus_config.sql}) so the CEO can revise the
 * ladder without a deploy.
 *
 * <p>Deliberately unlike {@link TierConfig#defaults()}: there is NO fallback default ladder
 * here. An empty or mis-seeded {@code sales.commission_incentive_tier} generation must mean
 * ZERO incentive ({@link CommissionCalculator#monthlyIncentive}), never an invented table — a
 * silent hard-coded default would be real money paid on a config bug, not a display glitch.
 */
public record IncentiveTierConfig(
    int tierNumber,
    BigDecimal thresholdBase,
    BigDecimal incentiveAmount,
    LocalDate effectiveFrom
) {}
