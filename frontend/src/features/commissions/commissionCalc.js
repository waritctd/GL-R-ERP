// Shared commission math — mirrors backend/src/main/java/th/co/glr/hr/commission/
// CommissionCalculator.java + TierConfig.java exactly, so mockApi's calculations and
// CommissionPage's own display-only figures (the sales calculation-detail waterfall, the
// monthly tier breakdown) can never drift from each other or from the real backend. Per
// CLAUDE.md's mock API contract: this is the display/mock mirror, not the source of truth —
// the real payroll numbers always come from the Java service.
//
// Formula (Commission redesign Slice A1 + calc-refine, 2026-07-22):
//   actualReceived = gross - bankFees - suspenseVat - transportFee - cutFee - shortfall
//                     - withholdingTax + overpayment
//   commissionableBase = actualReceived / 1.07          (per-invoice, 2dp, display only)
//   monthly tier base   = SUM(actualReceived * weightMultiplier) / 1.07   (full precision,
//                          divided once — see monthlyTierBase below)
//   progressive commission: 250k-wide tiers, 0.25% -> 3.25% (tier 13, >3,000,000, V81 fix);
//                            <50,000 monthly base pays 0; only the FINAL total rounds to 2dp.

export const VAT_DIVISOR = 1.07;
export const MONTHLY_FLOOR = 50000;

function tier(tierNumber, lowerBound, upperBound, ratePercent, highRoller = false) {
  return { tierNumber, lowerBound, upperBound, ratePercent, highRoller };
}

// Mirrors TierConfig.defaults() — tier 13 corrected 7.5000% -> 3.2500% by V81.
export const COMMISSION_TIERS = [
  tier(1, 0, 250000, 0.25),
  tier(2, 250000, 500000, 0.50),
  tier(3, 500000, 750000, 0.75),
  tier(4, 750000, 1000000, 1.00),
  tier(5, 1000000, 1250000, 1.25),
  tier(6, 1250000, 1500000, 1.50),
  tier(7, 1500000, 1750000, 1.75),
  tier(8, 1750000, 2000000, 2.00),
  tier(9, 2000000, 2250000, 2.25),
  tier(10, 2250000, 2500000, 2.50),
  tier(11, 2500000, 2750000, 2.75),
  tier(12, 2750000, 3000000, 3.00),
  tier(13, 3000000, null, 3.25, true),
];

export function round2(value) {
  return Math.round((Number(value || 0) + Number.EPSILON) * 100) / 100;
}

// Mirrors CommissionCalculator.calculateInvoice.
export function invoiceCalculation(payload) {
  const actualReceived = round2(
    Number(payload.grossAmount || 0)
    - Number(payload.bankFees || 0)
    - Number(payload.suspenseVat || 0)
    - Number(payload.transportFee || 0)
    - Number(payload.cutFee || 0)
    - Number(payload.shortfall || 0)
    - Number(payload.withholdingTax || 0)
    + Number(payload.overpayment || 0)
  );
  return {
    actualReceived,
    commissionableBase: round2(actualReceived / VAT_DIVISOR),
  };
}

// Mirrors CommissionCalculator.monthlyTierBase — full precision, no rounding here. Only
// progressiveCommission's final total rounds. Round to 2dp only when displaying this value.
export function monthlyTierBase(weightedActualReceived) {
  return Number(weightedActualReceived || 0) / VAT_DIVISOR;
}

function taxableAmountForTier(base, tierConfig) {
  if (tierConfig.highRoller) {
    return Math.max(0, base - tierConfig.lowerBound);
  }
  if (tierConfig.upperBound == null || base <= tierConfig.lowerBound) return 0;
  return Math.max(0, Math.min(base, tierConfig.upperBound) - tierConfig.lowerBound);
}

/**
 * Tier-by-tier breakdown of a monthly (full-precision) commissionable base — mirrors
 * CommissionCalculator.progressiveCommission bracket-by-bracket, for display purposes (the
 * sales "calculation detail" waterfall). Row-level `commission` is individually rounded for
 * display; `total` sums the full-precision per-tier amounts and rounds exactly once, matching
 * the backend's own "round only at final" rule — so `total` is the authoritative-mirror
 * figure, and the sum of row `commission` values may differ from it by a cent or two.
 */
export function tierBreakdown(monthlyCommissionableBase, tiers = COMMISSION_TIERS) {
  const base = Number(monthlyCommissionableBase || 0);
  const belowFloor = base > 0 && base < MONTHLY_FLOOR;
  if (base <= 0 || belowFloor) {
    return {
      rows: tiers.map((tierConfig) => ({ ...tierConfig, taxableAmount: 0, commission: 0 })),
      total: 0,
      belowFloor,
    };
  }
  let total = 0;
  const rows = tiers.map((tierConfig) => {
    const taxableAmount = taxableAmountForTier(base, tierConfig);
    const rawCommission = taxableAmount > 0 ? taxableAmount * (tierConfig.ratePercent / 100) : 0;
    total += rawCommission;
    return { ...tierConfig, taxableAmount, commission: round2(rawCommission) };
  });
  return { rows, total: round2(total), belowFloor: false };
}

// Mirrors CommissionCalculator.progressiveCommission(base, tiers) — final total only.
export function progressiveCommission(monthlyCommissionableBase, tiers = COMMISSION_TIERS) {
  return tierBreakdown(monthlyCommissionableBase, tiers).total;
}

// ── Issue #405: auto-computed INCENTIVE ladder (ข้อ 12) + STOCK_BONUS ──
// Mirrors CommissionCalculator#monthlyIncentive / #stockSaleBonus + IncentiveTierConfig /
// StockBonusConfig exactly, effective 2026-08-01 (see
// V108__commission_incentive_and_stock_bonus_config.sql). The backend config is CEO-revisable
// without a deploy (sales.commission_incentive_tier / sales.stock_bonus_config); these constants
// are the mock/display mirror of the SEEDED generation, not a live config read — per CLAUDE.md,
// this is business logic being mirrored, not authz.

// Flat monthly incentive, highest threshold reached wins — NOT cumulative, NOT pro-rated. No
// 80,000 row -- the workbook's second row is superseded and must not exist.
export const INCENTIVE_LADDER = [
  { tierNumber: 1, thresholdBase: 3000000, incentiveAmount: 15000 },
  { tierNumber: 2, thresholdBase: 4000000, incentiveAmount: 25000 },
  { tierNumber: 3, thresholdBase: 6000000, incentiveAmount: 50000 },
  { tierNumber: 4, thresholdBase: 8000000, incentiveAmount: 65000 },
];

// Mirrors the V108 seed row — ships config-gated OFF (enabled: false); the CEO flips one DB row
// to enable, no deploy needed.
export const STOCK_BONUS_DEFAULTS = { enabled: false, blockAmount: 100000, bonusPerBlock: 1000 };

// The generation's effective date — a payroll month before this must compute byte-identically to
// before this feature shipped (zero incentive, zero stock bonus). "YYYY-MM" lexicographic
// comparison against commissionMonth()'s own "YYYY-MM" slice works because both are zero-padded.
export const INCENTIVE_STOCK_BONUS_EFFECTIVE_MONTH = '2026-08';

// Mirrors CommissionCalculator#monthlyIncentive: ZERO when the ladder is empty, the base is
// null/<=0, or the base is below every threshold. Highest threshold reached wins. Comparison uses
// the full-precision base (no pre-rounding), same discipline as progressiveCommission's own base.
export function monthlyIncentive(monthlyTierBaseValue, ladder = INCENTIVE_LADDER) {
  const base = Number(monthlyTierBaseValue || 0);
  if (base <= 0 || !ladder || ladder.length === 0) return 0;
  let winner = null;
  for (const tierConfig of ladder) {
    if (base < tierConfig.thresholdBase) continue;
    if (!winner || tierConfig.thresholdBase > winner.thresholdBase) winner = tierConfig;
  }
  return winner ? round2(winner.incentiveAmount) : 0;
}

// Mirrors CommissionCalculator#stockSaleBonus: STEPPED (floor division into whole blocks), not a
// percentage — a partial block earns nothing (e.g. ฿250,000 at a ฿100,000 block pays ฿2,000, not
// ฿2,500). ZERO when config is null/disabled/receipts <= 0.
export function stockSaleBonus(stockReceipts, config = STOCK_BONUS_DEFAULTS) {
  const receipts = Number(stockReceipts || 0);
  if (!config || !config.enabled || receipts <= 0) return 0;
  const blocks = Math.floor(receipts / config.blockAmount);
  return round2(blocks * config.bonusPerBlock);
}
