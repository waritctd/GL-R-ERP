import { describe, expect, it } from 'vitest';
import { catalogPriceInThb } from './catalogPriceThb.js';

const FX = [
  { currency: 'EUR', rateToThb: 40 },
  { currency: 'THB', rateToThb: 1 },
];

// 5 แผ่น of a 1.44 ตร.ม. slab = 7.2 ตร.ม.
const item = (over = {}) => ({
  catalogBasePrice: 115,
  catalogCurrency: 'EUR',
  catalogPriceUnit: 'per_sqm',
  sqmPerPiece: 1.44,
  requestedQtySqm: 7.2,
  piecesAfterWastage: 5,
  ...over,
});

describe('catalogPriceInThb', () => {
  it('converts a per-ตร.ม. price to baht per ตร.ม., per แผ่น, and in total for the requested quantity', () => {
    const r = catalogPriceInThb(item(), FX);
    expect(r.perSqm).toBeCloseTo(4600); // 115 EUR x 40
    expect(r.perPiece).toBeCloseTo(6624); // x 1.44
    expect(r.total).toBeCloseTo(33120); // x 7.2 ตร.ม. = 5 แผ่น x 6,624
    expect(r.pieces).toBe(5);
    expect(r.sqm).toBeCloseTo(7.2);
  });

  it('converts a per-แผ่น price the other way round', () => {
    const r = catalogPriceInThb(item({ catalogBasePrice: 100, catalogPriceUnit: 'per_piece' }), FX);
    expect(r.perPiece).toBeCloseTo(4000);
    expect(r.perSqm).toBeCloseTo(4000 / 1.44);
    expect(r.total).toBeCloseTo(20000); // 5 แผ่น
  });

  it('treats THB as rate 1', () => {
    expect(catalogPriceInThb(item({ catalogCurrency: 'THB', catalogBasePrice: 1000 }), FX).perSqm).toBeCloseTo(1000);
  });

  it.each([
    ['per_box', { catalogPriceUnit: 'per_box' }],
    ['per_linear_m', { catalogPriceUnit: 'per_linear_m' }],
    ['unknown unit', { catalogPriceUnit: 'unknown' }],
    ['no unit (legacy / free-text line)', { catalogPriceUnit: null }],
    ['no price', { catalogBasePrice: null }],
    ['currency with no FX rate', { catalogCurrency: 'JPY' }],
    ['no sqmPerPiece to convert between แผ่น and ตร.ม.', { sqmPerPiece: null }],
    ['no requested quantity', { requestedQtySqm: null, piecesAfterWastage: null }],
  ])('shows nothing for %s — never a guessed baht figure', (_label, over) => {
    expect(catalogPriceInThb(item(over), FX)).toBeNull();
  });

  it('shows nothing when the FX list has not loaded (not an import/ceo viewer)', () => {
    expect(catalogPriceInThb(item(), undefined)).toBeNull();
    expect(catalogPriceInThb(item(), [])).toBeNull();
  });
});
