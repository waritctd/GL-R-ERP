import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { emptyQuotationItem, itemInputFromRow, rowFromServerItem } from './QuotationItemRow.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({ api: { catalog: { prices: vi.fn() } } }));

const tile = {
  ...emptyQuotationItem('g1'),
  model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
  piecesPerBox: 3, areaSqm: 20, unitPrice: 850, discountPct: 5, specialPriceSqm: 1350, directNetPrice: 500,
};

describe('itemInputFromRow — only the QUOTATION\'s price mode travels', () => {
  it('NET sends the list price and discount, and neither mode-specific price', () => {
    const input = itemInputFromRow(tile, 'NET');
    expect(input).toMatchObject({ lineType: 'TILE', unitPrice: 850, discountPct: 5, specialPriceSqm: null, directNetPrice: null });
  });

  it('English per-sqm sends the USD/ตร.ม. AS the unit price, the ตร.ม./กล่อง, and no discount (owner decision 2026-09-13)', () => {
    const input = itemInputFromRow({ ...tile, specialPriceSqm: 64, sqmPerBox: 0.6 }, 'SPECIAL_SQM', 'EN');
    expect(input).toMatchObject({ specialPriceSqm: 64, unitPrice: 64, sqmPerBox: 0.6, discountPct: null, directNetPrice: null });
    // The same row in Thai keeps the rep's list price per piece.
    expect(itemInputFromRow({ ...tile, specialPriceSqm: 64, sqmPerBox: 0.6 }, 'SPECIAL_SQM', 'TH').unitPrice).toBe(850);
  });

  it('DIRECT_NET sends the net, and a blank list price becomes the net (prints Net)', () => {
    expect(itemInputFromRow(tile, 'DIRECT_NET')).toMatchObject({ directNetPrice: 500, specialPriceSqm: null, unitPrice: 850 });
    expect(itemInputFromRow({ ...tile, unitPrice: '' }, 'DIRECT_NET').unitPrice).toBe(500);
  });

  it('English per-sqm WITH a box area FORCES roundToFullBox true, even when the row itself says false', () => {
    const perSqmRow = { ...tile, specialPriceSqm: 64, sqmPerBox: 0.6, roundToFullBox: false };
    expect(itemInputFromRow(perSqmRow, 'SPECIAL_SQM', 'EN').roundToFullBox).toBe(true);
    // The same row's roundToFullBox=false survives in every OTHER mode/language.
    expect(itemInputFromRow(perSqmRow, 'SPECIAL_SQM', 'TH').roundToFullBox).toBe(false);
    expect(itemInputFromRow(perSqmRow, 'NET', 'EN').roundToFullBox).toBe(false);
  });
});

describe('rowFromServerItem — the GET half of the GET→PUT round trip', () => {
  it('recovers a DIRECT_NET row\'s typed net from netUnitPrice (it has no column of its own)', () => {
    const row = rowFromServerItem({ id: 1, lineType: 'TILE', unitPrice: 850, netUnitPrice: 500 }, 'DIRECT_NET');
    expect(row.directNetPrice).toBe(500);
    expect(itemInputFromRow(row, 'DIRECT_NET').directNetPrice).toBe(500);
  });
});

