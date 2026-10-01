import { describe, expect, it } from 'vitest';
import { quotedPriceInThb } from './quotedPriceThb.js';

const FX = [
  { currency: 'EUR', rateToThb: 40 },
  { currency: 'THB', rateToThb: 1 },
];

// 5 แผ่น of a 1.44 ตร.ม. slab = 7.2 ตร.ม.
const request = (over = {}) => ({
  sqmPerPiece: 1.44,
  requestedQtySqm: 7.2,
  piecesAfterWastage: 5,
  ...over,
});
const quote = (over = {}) => ({ rawUnitPrice: 115, currency: 'EUR', unitBasis: 'PER_SQM', sqmPerUnit: null, ...over });

describe('quotedPriceInThb', () => {
  it('converts a per-ตร.ม. price to baht per ตร.ม., per แผ่น, and in total for the requested quantity', () => {
    const r = quotedPriceInThb(quote(), request(), FX);
    expect(r.perSqm).toBeCloseTo(4600); // 115 EUR x 40
    expect(r.perPiece).toBeCloseTo(6624); // x 1.44
    expect(r.total).toBeCloseTo(33120); // x 7.2 ตร.ม. = 5 แผ่น x 6,624
    expect(r.pieces).toBe(5);
    expect(r.sqm).toBeCloseTo(7.2);
  });

  it('converts a per-แผ่น price the other way round', () => {
    const r = quotedPriceInThb(quote({ rawUnitPrice: 100, unitBasis: 'PER_PIECE' }), request(), FX);
    expect(r.perPiece).toBeCloseTo(4000);
    expect(r.perSqm).toBeCloseTo(4000 / 1.44);
    expect(r.total).toBeCloseTo(20000); // 5 แผ่น
  });

  it("prefers the quote's own ตร.ม./หน่วย over the request item's", () => {
    expect(quotedPriceInThb(quote({ sqmPerUnit: 0.5 }), request(), FX).perPiece).toBeCloseTo(4600 * 0.5);
  });

  it('treats THB as rate 1', () => {
    expect(quotedPriceInThb(quote({ currency: 'THB', rawUnitPrice: 1000 }), request(), FX).perSqm).toBeCloseTo(1000);
  });

  it.each([
    ['per_box', quote({ unitBasis: 'PER_BOX' }), request()],
    ['per_linear_m', quote({ unitBasis: 'PER_LINEAR_M' }), request()],
    ['no price entered yet', quote({ rawUnitPrice: null }), request()],
    ['no quote item', null, request()],
    ['currency with no FX rate', quote({ currency: 'JPY' }), request()],
    ['no ตร.ม./แผ่น to convert between แผ่น and ตร.ม.', quote(), request({ sqmPerPiece: null })],
    ['no requested quantity', quote(), request({ requestedQtySqm: null, piecesAfterWastage: null })],
  ])('shows nothing for %s — never a guessed baht figure', (_label, q, r) => {
    expect(quotedPriceInThb(q, r, FX)).toBeNull();
  });

  it('shows nothing when the FX list has not loaded (not an import/ceo viewer)', () => {
    expect(quotedPriceInThb(quote(), request(), undefined)).toBeNull();
    expect(quotedPriceInThb(quote(), request(), [])).toBeNull();
  });
});
