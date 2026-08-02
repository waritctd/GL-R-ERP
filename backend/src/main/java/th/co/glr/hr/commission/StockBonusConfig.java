package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Issue #405: the STOCK_BONUS (พิเศษขายของในสต๊อค) generation active for a payroll month —
 * config-driven like {@link TierConfig} / {@link IncentiveTierConfig} (see
 * {@code V108__commission_incentive_and_stock_bonus_config.sql}). Ships seeded
 * {@code enabled = false}; the CEO flips a single DB row (or seeds a later, enabled generation)
 * to turn it on — no deploy required either way.
 */
public record StockBonusConfig(
    boolean enabled,
    LocalDate effectiveFrom,
    BigDecimal blockAmount,
    BigDecimal bonusPerBlock
) {
    /**
     * No generation found for the payroll month being computed (e.g. a month before
     * 2026-08-01, or before anything is ever seeded) — {@link CommissionCalculator#stockSaleBonus}
     * treats this identically to a real, but disabled, config row: pays zero.
     */
    public static StockBonusConfig disabled() {
        return new StockBonusConfig(false, null, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
