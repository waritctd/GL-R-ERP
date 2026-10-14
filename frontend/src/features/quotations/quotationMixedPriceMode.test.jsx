import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  emptyQuotationItem, itemInputFromRow, QuotationItemRow, rowFromServerItem,
} from './QuotationItemRow.jsx';
import {
  availablePriceModes, documentDiscountLabel, hasSpecialPricing, isMixedPriceMode, isPriceModeAvailable,
  mixedPriceModeAvailable, PRICE_MODE_MIXED, PRICE_MODE_MIXED_OPTION, priceModeForLanguage, rowPriceMode,
  summarizeRowPriceModes,
} from './quotationMeta.js';

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

describe('rowPriceMode — which mode one tile row is in', () => {
  it('on a mixed document it is the row\'s own', () => {
    for (const mode of ['NET', 'SPECIAL_SQM', 'DIRECT_NET']) {
      expect(rowPriceMode(PRICE_MODE_MIXED, { priceMode: mode })).toBe(mode);
    }
  });

  // The half that protects every quotation that is not mixed: nothing left on a row can move it.
  it('on a single-mode document the row\'s own mode is never consulted', () => {
    for (const documentMode of ['NET', 'SPECIAL_SQM', 'DIRECT_NET']) {
      for (const rowMode of [undefined, null, '', 'NET', 'SPECIAL_SQM', 'DIRECT_NET']) {
        expect(rowPriceMode(documentMode, { priceMode: rowMode })).toBe(documentMode);
      }
    }
  });

  it('is never MIXED: a row with no mode of its own on a mixed document reads as NET', () => {
    expect(rowPriceMode(PRICE_MODE_MIXED, {})).toBe('NET');
    expect(rowPriceMode(PRICE_MODE_MIXED, { priceMode: 'MIXED' })).toBe('NET');
    expect(rowPriceMode(PRICE_MODE_MIXED, null)).toBe('NET');
  });
});

describe('where mixing is offered', () => {
  it('is a Thai, direct-deal thing — not on English, not on a quotation from a คำขอราคา', () => {
    expect(mixedPriceModeAvailable('TH', 'DEAL_DIRECT')).toBe(true);
    expect(mixedPriceModeAvailable('TH', undefined)).toBe(true); // a brand-new quotation
    expect(mixedPriceModeAvailable('EN', 'DEAL_DIRECT')).toBe(false);
    expect(mixedPriceModeAvailable('TH', 'PRICING_REQUEST')).toBe(false);
  });

  // The three ROW modes are still exactly what availablePriceModes offers — MIXED is a choice
  // about the document, not a fourth way to price a row, so it is not in that list.
  it('MIXED is not one of the row modes', () => {
    expect(availablePriceModes('TH').map((opt) => opt.code)).toEqual(['NET', 'SPECIAL_SQM', 'DIRECT_NET']);
    expect(isMixedPriceMode(PRICE_MODE_MIXED)).toBe(true);
    expect(isMixedPriceMode('NET')).toBe(false);
  });

  it('a mixed document is a valid Thai document, and not a valid English one', () => {
    expect(isPriceModeAvailable(PRICE_MODE_MIXED, 'TH')).toBe(true);
    expect(isPriceModeAvailable(PRICE_MODE_MIXED, 'EN')).toBe(false);
    for (const mode of ['NET', 'SPECIAL_SQM', 'DIRECT_NET']) {
      expect(isPriceModeAvailable(mode, 'TH')).toBe(true);
      expect(isPriceModeAvailable(mode, 'EN')).toBe(true);
    }
  });

  it('switching a mixed document to English moves it to ราคาตั้ง − ส่วนลด % for the whole document, and says so', () => {
    expect(priceModeForLanguage(PRICE_MODE_MIXED, 'EN')).toEqual({ priceMode: 'NET', moved: true });
    expect(priceModeForLanguage(PRICE_MODE_MIXED, 'TH')).toEqual({ priceMode: PRICE_MODE_MIXED, moved: false });
    // …and nothing about the single modes changed.
    expect(priceModeForLanguage('SPECIAL_SQM', 'EN')).toEqual({ priceMode: 'SPECIAL_SQM', moved: false });
  });

  // The owner's "ต้องอธิบายด้วยว่ากรอกวิธีหลายแบบในใบเสนอราคาเดียวกัน": the explanation is part of the
  // option itself, so it cannot be dropped by a caller that only wanted the label.
  it('the option explains itself: several methods in one quotation, chosen per row', () => {
    expect(PRICE_MODE_MIXED_OPTION.code).toBe(PRICE_MODE_MIXED);
    expect(PRICE_MODE_MIXED_OPTION.label).toBe('ผสมหลายวิธี');
    expect(PRICE_MODE_MIXED_OPTION.hint).toContain('หลายวิธีในใบเสนอราคาเดียวกัน');
    expect(PRICE_MODE_MIXED_OPTION.hint).toContain('แต่ละรายการ');
  });
});

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

  it('on a single-mode document the same rows are all read in the document\'s mode', () => {
    expect([net20, special, directBelowList].map((row) => documentDiscountLabel(row, 'SPECIAL_SQM', 'TH')))
      .toEqual(['พิเศษ', 'พิเศษ', 'พิเศษ']);
    expect(documentDiscountLabel(special, 'NET', 'TH')).toBe('Net'); // its own "SPECIAL_SQM" is ignored
  });

  // ยืนราคาแบบ "ระบุวันที่" is offered only with special pricing — decided row by row when mixed.
  it('a mixed document has special pricing as soon as ONE row does', () => {
    expect(hasSpecialPricing(PRICE_MODE_MIXED, [net0, special])).toBe(true);
    expect(hasSpecialPricing(PRICE_MODE_MIXED, [net0, directBelowList])).toBe(true);
    expect(hasSpecialPricing(PRICE_MODE_MIXED, [net0, net20])).toBe(true);
  });

  it('…and none when no row has any, each judged by its own mode', () => {
    expect(hasSpecialPricing(PRICE_MODE_MIXED, [net0, directAtList])).toBe(false);
    // Wrong-way-round: read by the DOCUMENT-level rule, "MIXED" is neither ราคาพิเศษ nor ราคาสุทธิ and
    // this ราคาพิเศษ row (which carries no percent) would be missed.
    expect(hasSpecialPricing(PRICE_MODE_MIXED, [special])).toBe(true);
  });
});

describe('summarizeRowPriceModes — the line under the mixed option', () => {
  it('counts tile rows per mode, in the order the modes are offered, skipping modes nobody uses', () => {
    const rows = [
      { lineType: 'TILE', priceMode: 'SPECIAL_SQM' }, { lineType: 'TILE', priceMode: 'NET' },
      { lineType: 'TILE', priceMode: 'SPECIAL_SQM' }, { lineType: 'PLAIN' }, { lineType: 'ADJUSTMENT' },
    ];
    expect(summarizeRowPriceModes(rows, 'TH')).toBe('ราคาตั้ง − ส่วนลด % 1 รายการ · ราคาพิเศษ บาท/ตร.ม. 2 รายการ');
  });

  it('says so when there is no tile row yet', () => {
    expect(summarizeRowPriceModes([{ lineType: 'PLAIN' }], 'TH')).toBe('ยังไม่มีรายการกระเบื้อง');
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

  // Wrong-way-round, and what keeps every single-mode quotation's payload exactly as it was: no
  // priceMode key at all unless the document is mixed — even if the row object carries one.
  it('says nothing about a mode on a single-mode document', () => {
    expect(itemInputFromRow({ ...typed, priceMode: 'SPECIAL_SQM' }, 'NET')).not.toHaveProperty('priceMode');
    expect(itemInputFromRow({ ...typed, priceMode: 'SPECIAL_SQM' }, 'NET', 'TH', { mixed: false }))
      .not.toHaveProperty('priceMode');
  });

  it('a สินค้า/บริการอื่น row has no price mode even on a mixed document', () => {
    const plain = { lineType: 'PLAIN', description: 'ค่าขนส่ง', quantity: 2, unit: 'JOB', unitPrice: 500 };
    expect(itemInputFromRow(plain, 'NET', 'TH', { mixed: true })).not.toHaveProperty('priceMode');
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

describe('<QuotationItemRow> on a mixed document', () => {
  function renderRow(item, props = {}) {
    const onChange = vi.fn();
    const onChangePriceMode = vi.fn();
    render(
      <ul>
        <QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()}
          priceMode="NET" documentLanguage="TH" currency="THB"
          onChangePriceMode={onChangePriceMode} {...props} />
      </ul>,
    );
    return { onChange, onChangePriceMode };
  }
  const picker = () => screen.queryByLabelText('วิธีคิดราคา');

  it('shows a "วิธีคิดราคา" picker offering the three modes, set to the row\'s own', () => {
    renderRow({ ...complete, priceMode: 'SPECIAL_SQM' }, { mixedPriceModes: true, priceMode: 'SPECIAL_SQM' });

    expect(picker()).not.toBeNull();
    expect(picker().value).toBe('SPECIAL_SQM');
    expect(within(picker()).getAllByRole('option').map((opt) => opt.textContent))
      .toEqual(['ราคาตั้ง − ส่วนลด %', 'ราคาพิเศษ บาท/ตร.ม.', 'ราคาสุทธิ บาท/ตร.ม.']);
  });

  it('the row asks for exactly its own mode\'s prices', () => {
    renderRow({ ...complete, priceMode: 'SPECIAL_SQM' }, { mixedPriceModes: true, priceMode: 'SPECIAL_SQM' });

    expect(screen.getByLabelText(/^ราคาตั้ง\/ตร\.ม\. \(รวม VAT\)/)).not.toBeNull();
    expect(screen.getByLabelText(/^ราคาพิเศษ\/ตร\.ม\. \(รวม VAT\)/)).not.toBeNull();
    expect(screen.queryByLabelText(/^ส่วนลด %/)).toBeNull();
    expect(screen.queryByLabelText(/^ราคาสุทธิ\/ตร\.ม\./)).toBeNull();
  });

  it('choosing another mode tells the page, which re-prices this row', () => {
    const { onChangePriceMode, onChange } = renderRow(
      { ...complete, priceMode: 'NET' }, { mixedPriceModes: true, priceMode: 'NET' });

    fireEvent.change(picker(), { target: { value: 'DIRECT_NET' } });

    expect(onChangePriceMode).toHaveBeenCalledTimes(1);
    expect(onChangePriceMode).toHaveBeenCalledWith('DIRECT_NET');
    // It is the PAGE's to do (carry the net over, drop the stale preview, re-preview) — the row
    // does not also patch itself, which would race that.
    expect(onChange).not.toHaveBeenCalled();
  });

  // Wrong-way-round: a single-mode document has no per-row choice to offer — one would contradict
  // the document's own setting.
  it('is NOT shown on a single-mode document', () => {
    renderRow({ ...complete, priceMode: 'SPECIAL_SQM' });
    expect(picker()).toBeNull();
  });

  it('is NOT shown where the row has no prices at all (the คำขอราคา form)', () => {
    renderRow({ ...complete, priceMode: 'NET' }, { mixedPriceModes: true, hidePricing: true });
    expect(picker()).toBeNull();
  });

  it('is read-only with the rest of the row', () => {
    renderRow({ ...complete, priceMode: 'NET' }, { mixedPriceModes: true, readOnly: true });
    expect(picker().disabled).toBe(true);
  });
});
