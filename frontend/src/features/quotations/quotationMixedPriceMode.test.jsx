import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { emptyQuotationItem, itemInputFromRow, rowFromServerItem } from './QuotationItemRow.jsx';
import { documentDiscountLabel, PRICE_MODE_MIXED } from './quotationMeta.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: { catalog: { prices: vi.fn().mockResolvedValue({ items: [] }) } },
}));

// Mixed price modes (V192.5). Sales feedback #14 (แจ้งปัญหาการใช้งาน เสนอราคา): "อยากให้เลือกการคิดราคา
// แต่ละข้อไม่เหมือนกันได้ เช่น บางข้อราคาพิเศษต่อตรม บางข้อลด% ตามสเต็ป". Owner, 2026-10-06: do it, keep
// the flow clear, and offer the choice between one mode for the whole document and a mix — "ต้อง
// อธิบายด้วยว่ากรอกวิธีหลายแบบในใบเสนอราคาเดียวกัน".
//
// The model is the server's (WastageCalculator#rowPriceMode): the DOCUMENT's mode may be MIXED, and
// then each tile row carries its own. A row is always priced in exactly one of the three; on a
// single-mode document a row's own mode is never consulted.
//
// What these tests are and are not evidence of: they pin the EDITOR's pieces — the rule's mirror,
// what a row shows and sends. The pricing itself is the server's and is proved there
// (DealQuotationMixedPriceModeIntegrationTest); nothing here computes a price.

// The owner's 60×120: 0.72 ตร.ม./แผ่น → 1.39 แผ่น/ตร.ม.
const complete = {
  ...emptyQuotationItem('g1'),
  model: 'Trilogy', color: 'Ivory', texture: 'Lappato', sizeText: '60x120', thicknessMm: 10,
  sqmPerPiece: 0.72, piecesPerBox: 2, quantityMode: 'PIECES', piecesInput: 10,
};

describe('what the document prints for a row follows the row\'s mode', () => {
  const net20 = { lineType: 'TILE', priceMode: 'NET', unitPrice: 1210.25, discountPct: 20, netUnitPrice: 968.2 };
  const special = { lineType: 'TILE', priceMode: 'SPECIAL_SQM', unitPrice: 1210.25, netUnitPrice: 531.16, specialPriceSqm: 790 };
  const directBelowList = { lineType: 'TILE', priceMode: 'DIRECT_NET', unitPrice: 1210.25, netUnitPrice: 531.16 };
  const directAtList = { lineType: 'TILE', priceMode: 'DIRECT_NET', unitPrice: 1210.25, netUnitPrice: 1210.25 };
  const net0 = { lineType: 'TILE', priceMode: 'NET', unitPrice: 1210.25, discountPct: 0, netUnitPrice: 1210.25 };

  it('the ส่วนลด cell, row by row on one mixed document', () => {
    expect([net20, special, directBelowList, directAtList, net0]
      .map((row) => documentDiscountLabel(row, PRICE_MODE_MIXED, 'TH')))
      .toEqual(['20%', 'พิเศษ', 'พิเศษ', 'Net', 'Net']);
  });
});

describe('itemInputFromRow — a row of a mixed document says which mode it is in', () => {
  const typed = { ...complete, listPriceSqm: 1800, discountPct: 20, specialPriceSqm: 790, directNetPriceSqm: 790 };

  it('carries priceMode, and only that mode\'s own price fields', () => {
    expect(itemInputFromRow(typed, 'NET', 'TH', { mixed: true })).toMatchObject({
      priceMode: 'NET', listPriceSqm: 1800, discountPct: 20, specialPriceSqm: null, directNetPriceSqm: null,
    });
    expect(itemInputFromRow(typed, 'SPECIAL_SQM', 'TH', { mixed: true })).toMatchObject({
      priceMode: 'SPECIAL_SQM', listPriceSqm: 1800, specialPriceSqm: 790, discountPct: null, directNetPriceSqm: null,
    });
    expect(itemInputFromRow(typed, 'DIRECT_NET', 'TH', { mixed: true })).toMatchObject({
      priceMode: 'DIRECT_NET', listPriceSqm: 1800, directNetPriceSqm: 790, discountPct: null, specialPriceSqm: null,
    });
  });
});

describe('rowFromServerItem — a saved row of a mixed document keeps its mode', () => {
  it('reads the row in ITS mode: a ราคาสุทธิ row gets its typed net back', () => {
    const saved = {
      id: 7, lineType: 'TILE', priceMode: 'DIRECT_NET', unitPrice: 1210.25, netUnitPrice: 531.16,
      listPriceSqm: 1800, directNetPriceSqm: 790,
    };
    const row = rowFromServerItem(saved, 'DIRECT_NET');
    expect(row.priceMode).toBe('DIRECT_NET');
    expect(row.directNetPriceSqm).toBe(790);
  });
});

