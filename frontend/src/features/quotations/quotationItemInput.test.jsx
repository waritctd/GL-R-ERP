import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  adjustmentInputFromRow, emptyQuotationItem, itemInputFromRow, QuotationItemRow, rowFromServerItem,
} from './QuotationItemRow.jsx';

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

  it('SPECIAL_SQM sends the ราคาพิเศษ and NOT the stale direct net — calculate-line infers the mode from the row', () => {
    const input = itemInputFromRow(tile, 'SPECIAL_SQM');
    expect(input).toMatchObject({ specialPriceSqm: 1350, directNetPrice: null, discountPct: null, unitPrice: 850 });
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

  // Owner-approved "sell loose pieces" (2026-09-16, V182).
  it('roundToFullBox defaults true (from emptyQuotationItem) and travels verbatim otherwise', () => {
    expect(itemInputFromRow(tile, 'NET').roundToFullBox).toBe(true);
    expect(itemInputFromRow({ ...tile, roundToFullBox: false }, 'NET').roundToFullBox).toBe(false);
  });

  it('English per-sqm FORCES roundToFullBox true, even when the row itself says false', () => {
    const perSqmRow = { ...tile, specialPriceSqm: 64, sqmPerBox: 0.6, roundToFullBox: false };
    expect(itemInputFromRow(perSqmRow, 'SPECIAL_SQM', 'EN').roundToFullBox).toBe(true);
    // The same row's roundToFullBox=false survives in every OTHER mode/language.
    expect(itemInputFromRow(perSqmRow, 'SPECIAL_SQM', 'TH').roundToFullBox).toBe(false);
    expect(itemInputFromRow(perSqmRow, 'NET', 'EN').roundToFullBox).toBe(false);
  });

  it('a PLAIN row sends only its five fields and a numeric quantity', () => {
    const input = itemInputFromRow({ lineType: 'PLAIN', description: ' ค่าขนส่ง ', quantity: '1', unit: 'งาน', unitPrice: 3500 }, 'SPECIAL_SQM');
    expect(input).toEqual({
      id: null, lineType: 'PLAIN', locationLabel: null, description: 'ค่าขนส่ง', quantity: 1, unit: 'งาน', unitPrice: 3500, discountPct: 0,
      leadTimeMinDays: null, leadTimeMaxDays: null, itemNotes: null,
    });
  });

  // D1 (owner decision, 2026-09-16): a PLAIN row's OPTIONAL import lead time must reach the wire —
  // this is the round-trip the review flagged as missing (QuotationPlainItemRow had no input for
  // it at all). Mutation check: drop leadTimeMinDays/leadTimeMaxDays from itemInputFromRow's PLAIN
  // branch and this assertion goes red.
  it('a PLAIN row\'s optional lead time travels to the payload when the rep fills it in', () => {
    const input = itemInputFromRow({
      lineType: 'PLAIN', description: 'สุขภัณฑ์', quantity: '1', unit: 'ชุด', unitPrice: 5000,
      leadTimeMinDays: 75, leadTimeMaxDays: 90,
    });
    expect(input).toMatchObject({ leadTimeMinDays: 75, leadTimeMaxDays: 90 });
  });

  it('a ส่วนลดพิเศษ sends EXACTLY one of percent / amount, and no unitPrice', () => {
    const pct = adjustmentInputFromRow({ adjustmentKind: 'PERCENT', adjustmentPct: 3, adjustmentAmount: 999, adjustmentDeadline: '2026-07-31' });
    expect(pct).toEqual({
      id: null, lineType: 'ADJUSTMENT', locationLabel: null, adjustmentPct: 3, adjustmentAmount: null, adjustmentDeadline: '2026-07-31', description: null,
    });
    const flat = adjustmentInputFromRow({ adjustmentKind: 'AMOUNT', adjustmentPct: 3, adjustmentAmount: 500, description: 'ท้ายบิล' });
    expect(flat).toMatchObject({ adjustmentPct: null, adjustmentAmount: 500, description: 'ท้ายบิล' });
    expect('unitPrice' in flat).toBe(false);
  });
});

describe('rowFromServerItem — the GET half of the GET→PUT round trip', () => {
  it('recovers a DIRECT_NET row\'s typed net from netUnitPrice (it has no column of its own)', () => {
    const row = rowFromServerItem({ id: 1, lineType: 'TILE', unitPrice: 850, netUnitPrice: 500 }, 'DIRECT_NET');
    expect(row.directNetPrice).toBe(500);
    expect(itemInputFromRow(row, 'DIRECT_NET').directNetPrice).toBe(500);
  });

  it('recovers a PLAIN row\'s description, and a FLAT adjustment by which field is non-null', () => {
    expect(rowFromServerItem({ id: 2, lineType: 'PLAIN', descriptionLine: 'Mapei Adhesive', quantity: 85, unit: 'Bags' }))
      .toMatchObject({ description: 'Mapei Adhesive', quantity: 85, unit: 'Bags' });
    expect(rowFromServerItem({ id: 3, lineType: 'ADJUSTMENT', adjustmentPct: null, adjustmentAmount: 500, descriptionLine: 'ท้ายบิล' }))
      .toMatchObject({ adjustmentKind: 'AMOUNT', adjustmentAmount: 500, description: 'ท้ายบิล' });
    expect(rowFromServerItem({ id: 4, lineType: 'ADJUSTMENT', adjustmentPct: 3, adjustmentAmount: null }))
      .toMatchObject({ adjustmentKind: 'PERCENT', adjustmentPct: 3, description: '' });
  });

  it('treats a pre-V168 row with no lineType as a TILE', () => {
    expect(rowFromServerItem({ id: 5, unitPrice: 1 }).lineType).toBe('TILE');
  });

  // D1 (owner decision, 2026-09-16): the GET→row→PUT round trip for a PLAIN row's optional lead
  // time — `base`'s own `...item` spread already carries the DTO fields through untouched, so this
  // pins that end to end rather than merely trusting the spread (same reasoning as the
  // roundToFullBox test below).
  it('round-trips a PLAIN row\'s optional lead time — GET → row → PUT', () => {
    const row = rowFromServerItem({
      id: 8, lineType: 'PLAIN', descriptionLine: 'สุขภัณฑ์', quantity: 2, unit: 'ชุด', unitPrice: 5000,
      leadTimeMinDays: 75, leadTimeMaxDays: 90,
    });
    expect(row).toMatchObject({ leadTimeMinDays: 75, leadTimeMaxDays: 90 });
    expect(itemInputFromRow(row, 'NET')).toMatchObject({ leadTimeMinDays: 75, leadTimeMaxDays: 90 });

    // A PLAIN row with none set still round-trips to null, not undefined -- a blank row must save
    // exactly as it always did.
    const blankRow = rowFromServerItem({ id: 9, lineType: 'PLAIN', descriptionLine: 'ค่าขนส่ง', quantity: 1, unit: 'งาน', unitPrice: 500 });
    expect(itemInputFromRow(blankRow, 'NET')).toMatchObject({ leadTimeMinDays: null, leadTimeMaxDays: null });
  });

  // Owner-approved "sell loose pieces" (V182): roundToFullBox is a plain DTO field — `...item`
  // in the base spread already carries it through, so this pins that the round trip actually
  // works end to end (GET → row → PUT) rather than merely trusting the spread.
  it('round-trips roundToFullBox — false stays false, true stays true', () => {
    const looseRow = rowFromServerItem({ id: 6, lineType: 'TILE', unitPrice: 100, roundToFullBox: false });
    expect(looseRow.roundToFullBox).toBe(false);
    expect(itemInputFromRow(looseRow, 'NET').roundToFullBox).toBe(false);

    const roundedRow = rowFromServerItem({ id: 7, lineType: 'TILE', unitPrice: 100, roundToFullBox: true });
    expect(roundedRow.roundToFullBox).toBe(true);
    expect(itemInputFromRow(roundedRow, 'NET').roundToFullBox).toBe(true);
  });
});

describe('QuotationItemRow price fields follow the quotation\'s mode', () => {
  function renderRow(priceMode, item = tile) {
    render(<ul><QuotationItemRow item={item} index={0} priceMode={priceMode} onChange={vi.fn()} onRemove={vi.fn()} /></ul>);
  }

  it('NET: ราคา/หน่วย and ส่วนลด %, no ราคาพิเศษ', () => {
    renderRow('NET');
    expect(screen.getByLabelText(/^ราคา\/หน่วย/)).not.toBeNull();
    expect(screen.getByLabelText(/^ส่วนลด %/)).not.toBeNull();
    expect(screen.queryByLabelText(/ราคาพิเศษ/)).toBeNull();
  });

  it('SPECIAL_SQM: shows the SERVER\'s derived net per piece, never a client computation', () => {
    renderRow('SPECIAL_SQM', { ...tile, netUnitPrice: 453.84 });
    expect(screen.getByLabelText(/^ราคาพิเศษ \(บาท\/ตร\.ม\. รวม VAT\)/)).not.toBeNull();
    expect(screen.queryByLabelText(/^ส่วนลด %/)).toBeNull();
    expect(screen.getByTestId('special-net-0').textContent).toBe('= สุทธิ ฿453.84/แผ่น (ก่อน VAT)');
  });

  it('SPECIAL_SQM with no server figure yet says so instead of guessing one', () => {
    renderRow('SPECIAL_SQM', { ...tile, netUnitPrice: null });
    expect(screen.getByTestId('special-net-0').textContent).toBe('ระบบคำนวณราคาสุทธิต่อแผ่นให้');
  });

  it('DIRECT_NET: ราคาสุทธิ/แผ่น is required and the list price is optional', () => {
    renderRow('DIRECT_NET');
    expect(screen.getByLabelText(/^ราคาสุทธิ\/แผ่น/)).not.toBeNull();
    expect(screen.getByText('เว้นว่าง = ใช้ราคาสุทธิ (พิมพ์ส่วนลดเป็น Net)')).not.toBeNull();
  });

  it('renders the picture extension point only when a caller supplies one', () => {
    render(<ul><QuotationItemRow item={tile} index={0} onChange={vi.fn()} onRemove={vi.fn()} renderMedia={() => <span>รูปสินค้า</span>} /></ul>);
    expect(screen.getByText('รูปสินค้า')).not.toBeNull();
  });
});
