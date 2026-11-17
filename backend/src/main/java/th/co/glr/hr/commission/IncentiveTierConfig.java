package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.time.LocalDate;

/** one row of the auto-computed INCENTIVE ladder (ข้อ 12) — a flat monthly amount paid once the rep's monthly
 * tier base (CommissionCalculator#monthlyTierBase) reaches thresholdBase. Deliberately unlike
 * TierConfig#defaults(): there is NO fallback default ladder here. */
public record IncentiveTierConfig(
    int tierNumber,
    BigDecimal thresholdBase,
    BigDecimal incentiveAmount,
    LocalDate effectiveFrom
) {}
