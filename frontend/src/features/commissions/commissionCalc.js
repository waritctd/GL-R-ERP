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
