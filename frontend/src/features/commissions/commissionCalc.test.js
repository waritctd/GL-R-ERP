import { describe, expect, it } from 'vitest';
import {
  INCENTIVE_LADDER,
  INCENTIVE_STOCK_BONUS_EFFECTIVE_MONTH,
  STOCK_BONUS_DEFAULTS,
  monthlyIncentive,
  stockSaleBonus,
} from './commissionCalc.js';

// Issue #405: mirrors backend/.../commission/CommissionCalculatorTest.java's own boundary and
// reconciliation cases exactly, so the mock/display math can never quietly drift from the real
// CommissionCalculator#monthlyIncentive / #stockSaleBonus.

describe('commissionCalc — monthlyIncentive (issue #405, ข้อ 12)', () => {
  it('exposes the V108 seed as INCENTIVE_LADDER, with no 80,000 row', () => {
    expect(INCENTIVE_LADDER).toEqual([
      { tierNumber: 1, thresholdBase: 3000000, incentiveAmount: 15000 },
      { tierNumber: 2, thresholdBase: 4000000, incentiveAmount: 25000 },
      { tierNumber: 3, thresholdBase: 6000000, incentiveAmount: 50000 },
      { tierNumber: 4, thresholdBase: 8000000, incentiveAmount: 65000 },
    ]);
  });

  it('pays zero just below the first threshold', () => {
    expect(monthlyIncentive(2999999.99)).toBe(0);
  });

  it('pays 15,000 at exactly the first threshold', () => {
    expect(monthlyIncentive(3000000)).toBe(15000);
  });

  it('stays at 15,000 just below the second threshold', () => {
    expect(monthlyIncentive(3999999.99)).toBe(15000);
  });

  it('pays 25,000 at exactly the second threshold', () => {
    expect(monthlyIncentive(4000000)).toBe(25000);
  });

  it('pays 50,000 at exactly the third threshold', () => {
    expect(monthlyIncentive(6000000)).toBe(50000);
  });

  it('pays 65,000 at exactly the fourth threshold', () => {
    expect(monthlyIncentive(8000000)).toBe(65000);
  });

  it('stays at 65,000 well above the fourth threshold — no 80,000 row', () => {
    expect(monthlyIncentive(12000000)).toBe(65000);
  });

  it('pays zero for an empty ladder', () => {
    expect(monthlyIncentive(9000000, [])).toBe(0);
  });

  it('pays zero for a null/negative/zero base', () => {
    expect(monthlyIncentive(null)).toBe(0);
    expect(monthlyIncentive(-100)).toBe(0);
    expect(monthlyIncentive(0)).toBe(0);
  });

  // Four-rep workbook reconciliation (the issue's own table), to the satang.
  it.each([
    ['ชนิดา', 1373688.69, 0],
    ['สุวรรณี', 559711.64, 0],
    ['เจนเนตร', 3246381.33, 15000],
    ['ประภัสสร', 5051807.61, 25000],
  ])('%s: tier base %d -> incentive %d', (_name, tierBase, expected) => {
    expect(monthlyIncentive(tierBase)).toBe(expected);
  });
});

describe('commissionCalc — stockSaleBonus (issue #405, พิเศษขายของในสต๊อค)', () => {
  const enabled = { enabled: true, blockAmount: 100000, bonusPerBlock: 1000 };

  it('ships config-gated OFF by default (STOCK_BONUS_DEFAULTS.enabled === false)', () => {
    expect(STOCK_BONUS_DEFAULTS.enabled).toBe(false);
    expect(stockSaleBonus(1000000)).toBe(0);
  });

  it('pays zero just below one block', () => {
    expect(stockSaleBonus(99999.99, enabled)).toBe(0);
  });

  it('pays 1,000 at exactly one block', () => {
    expect(stockSaleBonus(100000, enabled)).toBe(1000);
  });

  it('is stepped: 250,000 pays two whole blocks (2,000), not 2,500', () => {
    expect(stockSaleBonus(250000, enabled)).toBe(2000);
    expect(stockSaleBonus(250000, enabled)).not.toBe(2500);
  });

  it('pays zero when the config is disabled', () => {
    expect(stockSaleBonus(250000, { ...enabled, enabled: false })).toBe(0);
  });

  it('pays zero when the config is null', () => {
    expect(stockSaleBonus(250000, null)).toBe(0);
  });

  it('pays zero for negative receipts (clamped before the floor division)', () => {
    expect(stockSaleBonus(-50000, enabled)).toBe(0);
  });
});

describe('commissionCalc — INCENTIVE_STOCK_BONUS_EFFECTIVE_MONTH (fix-forward gate)', () => {
  it('is the 2026-08-01 effective month', () => {
    expect(INCENTIVE_STOCK_BONUS_EFFECTIVE_MONTH).toBe('2026-08');
  });

  it('sorts lexicographically the same as a real month comparison would', () => {
    expect('2026-07' < INCENTIVE_STOCK_BONUS_EFFECTIVE_MONTH).toBe(true);
    expect('2026-08' >= INCENTIVE_STOCK_BONUS_EFFECTIVE_MONTH).toBe(true);
    expect('2026-09' >= INCENTIVE_STOCK_BONUS_EFFECTIVE_MONTH).toBe(true);
  });
});
