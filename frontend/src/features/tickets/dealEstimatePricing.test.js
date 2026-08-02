import { describe, expect, it } from 'vitest';
import { computeItemEstimateThb, formatThb, summarizeItemsEstimate } from './dealEstimatePricing.js';

const CONTEXT = {
  fxRatesByCurrency: { THB: 1, EUR: 38.5, USD: 33 },
  markupMultiplier: 2,
};

// unitBasis defaults to whichever field catalogPriceUnit actually needs, so a fixture that
// doesn't care about the unitBasis-mismatch behaviour (D2) doesn't have to think about it —
// override unitBasis explicitly in tests that DO care.
function catalogItem(overrides = {}) {
  const catalogPriceUnit = overrides.catalogPriceUnit ?? 'per_sqm';
  const unitBasis = overrides.unitBasis ?? (catalogPriceUnit === 'per_piece' ? 'PIECE' : 'SQM');
  return {
    source: 'catalog',
    catalogPrice: 43,
    catalogCurrency: 'EUR',
    catalogPriceUnit,
    unitBasis,
    qty: 10,
    qtySqm: 10,
    sqmPerPiece: null,
    ...overrides,
  };
}

describe('computeItemEstimateThb', () => {
  it('computes a per_sqm line against qtySqm', () => {
    const result = computeItemEstimateThb(catalogItem({ catalogPriceUnit: 'per_sqm', qtySqm: 10 }), CONTEXT);
    // 43 * 38.5 * 2 = 3311 THB/sqm; * 10 sqm = 33110
    expect(result.ok).toBe(true);
    expect(result.unitThb).toBeCloseTo(3311, 6);
    expect(result.total).toBeCloseTo(33110, 6);
  });

  it('computes a per_piece line against qty, not qtySqm', () => {
    const result = computeItemEstimateThb(
      catalogItem({ catalogPriceUnit: 'per_piece', catalogPrice: 5.5, catalogCurrency: 'USD', qty: 20, qtySqm: 999 }),
      CONTEXT,
    );
    // 5.5 * 33 * 2 = 363 THB/piece; * 20 pieces = 7260. qtySqm (999) must be ignored.
    expect(result.ok).toBe(true);
    expect(result.unitThb).toBeCloseTo(363, 6);
    expect(result.total).toBeCloseTo(7260, 6);
  });

  it.each(['per_box', 'per_linear_m', 'unknown', null, undefined])(
    'returns not-ok/UNSUPPORTED_UNIT for a non-normalizable priceUnit (%s) rather than guessing',
    (priceUnit) => {
      const result = computeItemEstimateThb(catalogItem({ catalogPriceUnit: priceUnit }), CONTEXT);
      expect(result.ok).toBe(false);
      expect(result.reason).toBe('UNSUPPORTED_UNIT');
    },
  );

  it('returns not-ok/NOT_CATALOG for a custom (non-catalog) item', () => {
    const result = computeItemEstimateThb({ source: 'custom', qty: 5 }, CONTEXT);
    expect(result.ok).toBe(false);
    expect(result.reason).toBe('NOT_CATALOG');
  });

  it('returns not-ok/NO_FX_RATE when the catalog currency has no FX rate', () => {
    const result = computeItemEstimateThb(catalogItem({ catalogCurrency: 'GBP' }), CONTEXT);
    expect(result.ok).toBe(false);
    expect(result.reason).toBe('NO_FX_RATE');
  });

  it('returns not-ok/MISSING_QUANTITY when the needed quantity is absent or zero', () => {
    expect(computeItemEstimateThb(catalogItem({ catalogPriceUnit: 'per_sqm', qtySqm: 0 }), CONTEXT))
      .toMatchObject({ ok: false, reason: 'MISSING_QUANTITY' });
    expect(computeItemEstimateThb(catalogItem({ catalogPriceUnit: 'per_sqm', qtySqm: '' }), CONTEXT))
      .toMatchObject({ ok: false, reason: 'MISSING_QUANTITY' });
    expect(computeItemEstimateThb(catalogItem({ catalogPriceUnit: 'per_piece', qty: 0 }), CONTEXT))
      .toMatchObject({ ok: false, reason: 'MISSING_QUANTITY' });
  });

  it('treats THB as rate 1 without needing an explicit fxRatesByCurrency.THB entry', () => {
    const result = computeItemEstimateThb(
      catalogItem({ catalogCurrency: 'THB', catalogPrice: 100, catalogPriceUnit: 'per_sqm', qtySqm: 1 }),
      { fxRatesByCurrency: {}, markupMultiplier: 2 },
    );
    expect(result.ok).toBe(true);
    expect(result.unitThb).toBeCloseTo(200, 6);
  });

  it('never falls back to markupMultiplier = 1 — a missing/invalid markup is not computable', () => {
    expect(computeItemEstimateThb(catalogItem(), { ...CONTEXT, markupMultiplier: null }))
      .toMatchObject({ ok: false, reason: 'NO_MARKUP' });
    expect(computeItemEstimateThb(catalogItem(), { ...CONTEXT, markupMultiplier: 0 }))
      .toMatchObject({ ok: false, reason: 'NO_MARKUP' });
    expect(computeItemEstimateThb(catalogItem(), { ...CONTEXT, markupMultiplier: -1 }))
      .toMatchObject({ ok: false, reason: 'NO_MARKUP' });
  });

  it('returns not-ok/NO_CATALOG_PRICE when the catalog item has no price at all', () => {
    const result = computeItemEstimateThb(catalogItem({ catalogPrice: null }), CONTEXT);
    expect(result.ok).toBe(false);
    expect(result.reason).toBe('NO_CATALOG_PRICE');
  });

  // D2 regression: a per_piece-priced item whose unitBasis has been switched to SQM (the rep is
  // now maintaining qtySqm, not qty) with no sqmPerPiece conversion factor must NOT fall back to
  // whatever stale qty happens to be sitting in state (e.g. emptyItem()'s qty: 1). Fire path:
  // REFIN "Corner" (per_piece, sqmPerPiece: null) switched to ตร.ม. and given qtySqm: 50 — before
  // the fix this computed 55 * 38.5 * 2 * 1 (the stale qty) instead of refusing.
  describe('D2 — unitBasis vs catalogPriceUnit mismatch with no exact conversion', () => {
    it('refuses a per_piece price when the user is maintaining qtySqm and sqmPerPiece is null', () => {
      const item = catalogItem({
        catalogPrice: 55, catalogCurrency: 'EUR',
        catalogPriceUnit: 'per_piece', unitBasis: 'SQM', sqmPerPiece: null,
        qty: 1, qtySqm: 50,
      });
      const result = computeItemEstimateThb(item, CONTEXT);
      expect(result.ok).toBe(false);
      expect(result.reason).toBe('UNIT_BASIS_MISMATCH');
    });

    it('refuses a per_sqm price when the user is maintaining qty and sqmPerPiece is null (converse case)', () => {
      const item = catalogItem({
        catalogPriceUnit: 'per_sqm', unitBasis: 'PIECE', sqmPerPiece: null,
        qty: 12, qtySqm: '',
      });
      const result = computeItemEstimateThb(item, CONTEXT);
      expect(result.ok).toBe(false);
      expect(result.reason).toBe('UNIT_BASIS_MISMATCH');
    });

    it('still computes when sqmPerPiece IS present, even though unitBasis differs from catalogPriceUnit', () => {
      // sqmPerPiece present means TicketCreateModal's updateItem() cross-fill keeps qty and
      // qtySqm in lockstep, so the "wrong" field is still trustworthy.
      const item = catalogItem({
        catalogPrice: 55, catalogCurrency: 'EUR',
        catalogPriceUnit: 'per_piece', unitBasis: 'SQM', sqmPerPiece: 1.44,
        qtySqm: 72, qty: 50, // 72 / 1.44 = 50 — kept in sync by the cross-fill
      });
      const result = computeItemEstimateThb(item, CONTEXT);
      // 55 * 38.5 * 2 = 4235 THB/piece; * 50 pieces (the field per_piece actually needs) = 211750
      expect(result.ok).toBe(true);
      expect(result.unitThb).toBeCloseTo(4235, 6);
      expect(result.total).toBeCloseTo(211750, 6);
    });
  });
});

describe('summarizeItemsEstimate', () => {
  it('sums only the computable lines and counts the rest as uncomputable', () => {
    const items = [
      catalogItem({ catalogPriceUnit: 'per_sqm', qtySqm: 10 }),                                    // 33110
      catalogItem({ catalogPriceUnit: 'per_piece', catalogPrice: 5.5, catalogCurrency: 'USD', qty: 20 }), // 7260
      catalogItem({ catalogPriceUnit: 'per_box' }),                                                 // not computable
      { source: 'custom', qty: 3 },                                                                 // not computable
    ];

    const summary = summarizeItemsEstimate(items, CONTEXT);

    expect(summary.total).toBeCloseTo(33110 + 7260, 6);
    expect(summary.uncomputableCount).toBe(2);
    expect(summary.perItem).toHaveLength(4);
    expect(summary.perItem[0].ok).toBe(true);
    expect(summary.perItem[1].ok).toBe(true);
    expect(summary.perItem[2].ok).toBe(false);
    expect(summary.perItem[3].ok).toBe(false);
  });

  // D1 regression: an all-uncomputable cart must roll up to `total: null`, never a numeric 0 that
  // a caller could mistake for "computed to zero" and render as a bold money figure. Fire path:
  // adding one custom item (the default "add item" flow) with nothing else in the cart.
  it('D1: returns total: null (not 0) when every line is uncomputable', () => {
    const summary = summarizeItemsEstimate([{ source: 'custom', qty: 1 }, { source: 'custom', qty: 2 }], CONTEXT);
    expect(summary.total).toBeNull();
    expect(summary.uncomputableCount).toBe(2);
  });

  // D1 regression: an empty cart is the same "nothing computed" case and must also roll up to
  // null, not 0.
  it('D1: returns total: null (not 0) for an empty cart', () => {
    const summary = summarizeItemsEstimate([], CONTEXT);
    expect(summary.total).toBeNull();
    expect(summary.uncomputableCount).toBe(0);
    expect(summary.perItem).toEqual([]);
  });
});

describe('formatThb', () => {
  it('formats Thai-style with exactly 2 decimal places', () => {
    expect(formatThb(33110)).toBe('33,110.00');
    expect(formatThb(0)).toBe('0.00');
    expect(formatThb(1234.5)).toBe('1,234.50');
  });
});
