import { describe, expect, it } from 'vitest';
import { ceoRowFigures } from './ceoPricingRow.js';

const item = (o = {}) => ({
  stockSource: null, manualSellingPricePerRequestedUnit: null, listUnitPrice: 100, discountPct: null,
  specialPriceSqm: null, directNetPrice: null, netUnitPrice: 100, proposedMarginPct: 0.2,
  proposedSellingPricePerRequestedUnit: 72, requestedQuantity: 20, sqmPerPiece: 0.36, ...o,
});
const thb = { priceMode: 'NET', currency: 'THB', fxRateUsed: 1 };
const figures = (o) => ceoRowFigures({ decision: thb, item: item(), rawDraft: {}, cost: 60, editable: true, sellingBuffer: 1.1, ...o });

describe('ceoRowFigures', () => {
  it('margin 35 -> list = cost x 1.35 x buffer (60 x 1.35 x 1.1 = 89.10), net follows, row is dirty', () => {
    const f = figures({ rawDraft: { marginPct: '35' } });
    expect(f.formulaFromMargin).toBe(89.1);
    expect(f.net).toBe(89.1);
    expect(f.total).toBe(1782);
    expect(f.dirty).toBe(true);
  });

  it('a non-THB decision divides the THB list by the fx rate (89.10 / 35 = 2.55)', () => {
    const f = figures({ decision: { ...thb, currency: 'USD', fxRateUsed: 35 }, rawDraft: { marginPct: '35' } });
    expect(f.formulaFromMargin).toBe(2.55);
  });

  it('an invalid margin (-150 %) gives no preview and marks the row invalid', () => {
    const f = figures({ rawDraft: { marginPct: '-150' } });
    expect(f.formulaFromMargin).toBeNull();
    expect(f.marginInvalid).toBe(true);
    expect(f.invalid).toBe(true);
  });

  it('no preview before the formula buffer has loaded', () => {
    expect(figures({ rawDraft: { marginPct: '35' }, sellingBuffer: NaN }).formulaFromMargin).toBeNull();
  });

  it.each([
    ['margin', { marginPct: '20' }],
    ['discount', { discountPct: '5' }],
    ['special', { specialPriceSqm: '1350' }],
    ['direct net', { directNetPrice: '100' }],
  ])('a %s draft equal to the saved value is not dirty', (_n, rawDraft) => {
    const saved = item({ discountPct: 5, specialPriceSqm: 1350, directNetPrice: 100 });
    expect(figures({ item: saved, rawDraft }).dirty).toBe(false);
  });

  it('a draft that differs from the saved value is dirty', () => {
    expect(figures({ rawDraft: { discountPct: '10' } }).dirty).toBe(true);
    expect(figures({ rawDraft: { marginPct: '25' } }).dirty).toBe(true);
  });

  it('the SPECIAL_SQM formula prefill counts as pending and is tagged as such; a typed value is not', () => {
    const decision = { ...thb, priceMode: 'SPECIAL_SQM' };
    const pre = figures({ decision, item: item({ netUnitPrice: null }) });
    expect(pre.dirty).toBe(true);
    expect(pre.autoSpecialPending).toBe(true);
    const typed = figures({ decision, item: item({ netUnitPrice: null }), rawDraft: { specialPriceSqm: '1350' } });
    expect(typed.dirty).toBe(true);
    expect(typed.autoSpecialPending).toBe(false);
  });

  it('profit on a non-THB decision converts the net with the fx rate (cost 60, net 2 USD at 35 -> 10 per unit, 16.7 %)', () => {
    const f = figures({ decision: { ...thb, currency: 'USD', fxRateUsed: 35 }, item: item({ netUnitPrice: 2 }) });
    expect(f.profit.profitPerUnit).toBeCloseTo(10);
    expect(f.profit.pct).toBeCloseTo(16.67, 1);
  });
});
