import { describe, expect, it } from 'vitest';
import { ceoRowFigures, netPerPieceFromSpecialSqm, specialPriceSqmFromFormula } from './ceoPricingRow.js';

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

describe('SPECIAL_SQM — กำไร % drives ราคาพิเศษ (owner rule: ทุน/ตร.ม. × (1 + กำไร) × 1.07)', () => {
  const special = { priceMode: 'SPECIAL_SQM', currency: 'THB', fxRateUsed: 1 };
  // The owner's worked example: ทุน 953.72 บาท/ตร.ม., 0.36 ตร.ม./แผ่น -> cost 343.3416 / แผ่น (unrounded ทุน 953.7266).
  const ownerItem = item({ sqmPerPiece: 0.36, netUnitPrice: null, proposedMarginPct: 0.3, proposedSellingPricePerRequestedUnit: null });
  // The selling buffer is 1.00 since 2026-11-06; VAT 1.07 is applied on its own.
  const ownerFigures = (o) => figures({ decision: special, item: ownerItem, cost: 343.3416, sellingBuffer: 1, ...o });

  it('prefills ราคาพิเศษ = 953.72 × 1.3 × 1.07 = 1,326.63 (VAT applied once, not twice)', () => {
    expect(ownerFigures().draft.specialPriceSqm).toBe(1326.63);
  });

  it('a typed กำไร % re-derives ราคาพิเศษ and previews net + profit before save', () => {
    const f = ownerFigures({ item: { ...ownerItem, specialPriceSqm: 1000, netUnitPrice: 300 }, rawDraft: { marginPct: '40' } });
    expect(f.draft.specialPriceSqm).toBe(round2Js((343.3416 / 0.36) * 1.4 * 1.07));
    expect(f.netPending).toBe(false);
    expect(f.net).toBeGreaterThan(0);
    expect(f.profit.pct).toBeCloseTo(40, 0);
    expect(f.dirty).toBe(true);
  });

  it('a typed ราคาพิเศษ wins over the formula', () => {
    expect(ownerFigures({ rawDraft: { specialPriceSqm: '1500' } }).draft.specialPriceSqm).toBe('1500');
  });
});

describe('SPECIAL_SQM — a typed ราคาพิเศษ implies กำไร % (exact inverse of specialPriceSqmFromFormula)', () => {
  const special = { priceMode: 'SPECIAL_SQM', currency: 'THB', fxRateUsed: 1 };
  const spItem = item({ sqmPerPiece: 0.014706, netUnitPrice: null, proposedMarginPct: 0.3, proposedSellingPricePerRequestedUnit: null });
  const sp = (rawDraft, o = {}) => figures({ decision: special, item: spItem, cost: 14.1544, sellingBuffer: 1, rawDraft, ...o });

  it('cost 14.1544/แผ่น at 0.014706 ตร.ม., special 1400 -> about 35.94 % (a fraction, full precision)', () => {
    const f = sp({ specialPriceSqm: '1400' });
    expect(f.impliedMarginFraction).toBeCloseTo(0.3594, 3);
    expect(f.impliedMarginPct).toBe('35.94');
    expect(f.marginInvalid).toBe(false);
  });

  it('round-trips: feeding the implied fraction back into the formula gives the same ราคาพิเศษ', () => {
    const f = sp({ specialPriceSqm: '1400' });
    expect(specialPriceSqmFromFormula({
      cost: 14.1544, marginFraction: f.impliedMarginFraction, sellingBuffer: 1, sqmPerPiece: 0.014706,
    })).toBe(1400);
  });

  it('the selling buffer is part of the inverse (buffer 1.1 lowers the implied margin)', () => {
    expect(sp({ specialPriceSqm: '1400' }, { sellingBuffer: 1.1 }).impliedMarginFraction)
      .toBeCloseTo(1400 / ((14.1544 / 0.014706) * 1.1 * 1.07) - 1, 6);
  });

  it('a special below cost is a loss: implied margin < 0 yet still valid (saving allowed)', () => {
    const f = sp({ specialPriceSqm: '900' });
    expect(f.impliedMarginFraction).toBeLessThan(0);
    expect(f.marginInvalid).toBe(false);
    expect(f.invalid).toBe(false);
  });

  it('an implied margin past the server bound marks the row invalid', () => {
    const f = sp({ specialPriceSqm: '5000000' });
    expect(f.marginInvalid).toBe(true);
    expect(f.invalid).toBe(true);
  });

  it('a typed กำไร % takes the lead: nothing is implied from a special that is not typed', () => {
    expect(sp({ marginPct: '30' }).impliedMarginFraction ?? null).toBeNull();
    expect(sp({}).impliedMarginFraction ?? null).toBeNull();
  });

  it('NET mode, non-THB, stock lines and lines with no cost imply nothing', () => {
    const raw = { specialPriceSqm: '1400' };
    expect(figures({ item: spItem, cost: 14.1544, sellingBuffer: 1, rawDraft: raw }).impliedMarginFraction ?? null).toBeNull();
    expect(sp(raw, { decision: { ...special, currency: 'USD', fxRateUsed: 35 } }).impliedMarginFraction ?? null).toBeNull();
    expect(sp(raw, { item: { ...spItem, stockSource: 'IN_THAILAND' } }).impliedMarginFraction ?? null).toBeNull();
    expect(sp(raw, { cost: null }).impliedMarginFraction ?? null).toBeNull();
  });
});

describe('netPerPieceFromSpecialSqm mirrors WastageCalculator to the satang', () => {
  it.each([[1350, 0.36, 453.84], [1400, 0.36, 470.65], [1800, 0.72, 1210.25], [790, 0.72, 531.16]])(
    '%s/ตร.ม. at %s ตร.ม./แผ่น -> %s', (sp, sqm, want) => expect(netPerPieceFromSpecialSqm(sp, sqm)).toBe(want),
  );
});

function round2Js(n) { return Math.round((n + Number.EPSILON) * 100) / 100; }
