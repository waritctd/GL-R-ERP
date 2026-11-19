import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  emptyQuotationItem, itemInputFromRow, QuotationItemRow, rowFromServerItem,
} from './QuotationItemRow.jsx';
import { validateQuotationItem } from './quotationMeta.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: { catalog: { prices: vi.fn().mockResolvedValue({ items: [] }) } },
}));

// Sales feedback #1 (แจ้งปัญหาการใช้งาน เสนอราคา, 2026-10-06) — "แก้เป็น ตร.ม. รวม VAT ทุกแบบ". Owner ruling
// of the same day: on a THAI quotation every tile price box is typed per ตร.ม. The English form keeps its
// per-piece USD inputs.

// The owner's 60×120: 0.72 ตร.ม./แผ่น → 1.39 แผ่น/ตร.ม.
const complete = {
  ...emptyQuotationItem('g1'),
  model: 'Trilogy', color: 'Ivory', texture: 'Lappato', sizeText: '60x120', thicknessMm: 10,
  sqmPerPiece: 0.72, piecesPerBox: 2, quantityMode: 'PIECES', piecesInput: 10,
};
/** Priced the way the editor asks for it since this change. */
const typed = { ...complete, listPriceSqm: 1800 };
/** Saved BEFORE this change: its price is a figure per แผ่น, before VAT, and nothing else. */
const legacy = { ...complete, unitPrice: 1748.13 };

describe('itemInputFromRow — a Thai tile row states its prices per ตร.ม.', () => {
  it('NET sends the typed ราคา/ตร.ม. and NO per-แผ่น price: the server derives that', () => {
    expect(itemInputFromRow({ ...typed, discountPct: 20 }, 'NET')).toMatchObject({
      listPriceSqm: 1800, unitPrice: null, discountPct: 20,
      specialPriceSqm: null, directNetPrice: null, directNetPriceSqm: null,
    });
  });

  // The no-drift guarantee for everything saved before this change: 1,748.13 a piece is NOT re-sent as "2,599.99
  // per ตร.ม.", which would come back as some other per-piece figure.
  it('a row saved before this change still sends its per-แผ่น price, untouched', () => {
    expect(itemInputFromRow(legacy, 'NET')).toMatchObject({ unitPrice: 1748.13, listPriceSqm: null, directNetPriceSqm: null });
  });

  it('SPECIAL_SQM sends ราคาตั้ง per ตร.ม. beside the ราคาพิเศษ', () => {
    expect(itemInputFromRow({ ...typed, specialPriceSqm: 790 }, 'SPECIAL_SQM')).toMatchObject({
      listPriceSqm: 1800, specialPriceSqm: 790, unitPrice: null, discountPct: null, directNetPriceSqm: null,
    });
  });

  it('DIRECT_NET with ราคาตั้ง left blank sends the net as the list too, in the unit it was typed in', () => {
    expect(itemInputFromRow({ ...complete, directNetPriceSqm: 790 }, 'DIRECT_NET')).toMatchObject({
      listPriceSqm: 790, directNetPriceSqm: 790, unitPrice: null, directNetPrice: null,
    });
    // …and a net saved before this change, per แผ่น, exactly as it always was.
    expect(itemInputFromRow({ ...complete, directNetPrice: 500 }, 'DIRECT_NET')).toMatchObject({
      unitPrice: 500, directNetPrice: 500, listPriceSqm: null, directNetPriceSqm: null,
    });
  });

  // "รวม VAT" means nothing on the English form (USD, no VAT) and the server refuses one there.
  it('an English document never sends either per-ตร.ม. price, and keeps its per-piece ones', () => {
    const row = { ...complete, unitPrice: 15, directNetPrice: 12.5, listPriceSqm: 1800, directNetPriceSqm: 790 };
    expect(itemInputFromRow(row, 'DIRECT_NET', 'EN')).toMatchObject({
      unitPrice: 15, directNetPrice: 12.5, listPriceSqm: null, directNetPriceSqm: null,
    });
    expect(itemInputFromRow(row, 'NET', 'EN')).toMatchObject({ unitPrice: 15, listPriceSqm: null, directNetPriceSqm: null });
  });
});

describe('rowFromServerItem — what a saved row reads back as', () => {
  it('a row priced per ตร.ม. reads back the figure that was typed, and an untouched save re-sends exactly that', () => {
    const row = rowFromServerItem({
      id: 1, lineType: 'TILE', sqmPerPiece: 0.72, unitPrice: 1210.25, discountPct: 20, netUnitPrice: 968.2, listPriceSqm: 1800,
      directNetPriceSqm: null,
    }, 'NET');
    expect(row).toMatchObject({ listPriceSqm: 1800, directNetPriceSqm: '' });
    expect(itemInputFromRow(row, 'NET')).toMatchObject({ listPriceSqm: 1800, unitPrice: null, discountPct: 20 });
  });

  it('DIRECT_NET: a net typed per ตร.ม. is what comes back — not a per-แผ่น net recovered beside it', () => {
    const row = rowFromServerItem({
      id: 3, lineType: 'TILE', sqmPerPiece: 0.72, unitPrice: 1210.25, netUnitPrice: 531.16, listPriceSqm: 1800,
      directNetPriceSqm: 790,
    }, 'DIRECT_NET');
    expect(row).toMatchObject({ listPriceSqm: 1800, directNetPriceSqm: 790, directNetPrice: '' });
    expect(itemInputFromRow(row, 'DIRECT_NET')).toMatchObject({
      listPriceSqm: 1800, directNetPriceSqm: 790, unitPrice: null, directNetPrice: null,
    });
  });
});

describe('validateQuotationItem — the price a Thai tile row needs is a per-ตร.ม. one', () => {
  it('refuses a zero or negative per-ตร.ม. price — the server would', () => {
    for (const bad of [0, -1800]) {
      expect(validateQuotationItem({ ...complete, listPriceSqm: bad }, 'NET')).toEqual({ unitPrice: 'ราคาต้องมากกว่าศูนย์' });
      expect(validateQuotationItem({ ...complete, listPriceSqm: bad, directNetPriceSqm: 790 }, 'DIRECT_NET'))
        .toEqual({ unitPrice: 'ราคาตั้งต้องมากกว่าศูนย์' });
      expect(validateQuotationItem({ ...typed, directNetPriceSqm: bad }, 'DIRECT_NET'))
        .toEqual({ directNetPrice: 'ราคาสุทธิต้องมากกว่าศูนย์' });
    }
  });
});

describe('QuotationItemRow — the Thai price boxes', () => {
  function renderRow(item, { priceMode = 'NET', documentLanguage = 'TH', ...props } = {}) {
    const onChange = vi.fn();
    render(
      <ul>
        <QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()}
          priceMode={priceMode} documentLanguage={documentLanguage}
          currency={documentLanguage === 'EN' ? 'USD' : 'THB'} {...props} />
      </ul>,
    );
    return { onChange };
  }

  describe('ราคาตั้ง − ส่วนลด %', () => {
    it('typing writes the per-ตร.ม. price and nothing else; the per-แผ่น figure is the server\'s to fill in', () => {
      const { onChange } = renderRow(complete);
      fireEvent.change(screen.getByLabelText(/^ราคา\/ตร\.ม\./), { target: { value: '1800' } });
      expect(onChange).toHaveBeenLastCalledWith({ listPriceSqm: 1800 });
    });
  });

  describe('ราคาสุทธิ', () => {
    it('typing the net writes it per ตร.ม. and drops a per-แผ่น net the row was carrying', () => {
      const { onChange } = renderRow({ ...complete, directNetPrice: 500 }, { priceMode: 'DIRECT_NET' });
      fireEvent.change(screen.getByLabelText(/^ราคาสุทธิ\/ตร\.ม\./), { target: { value: '790' } });
      expect(onChange).toHaveBeenLastCalledWith({ directNetPriceSqm: 790, directNetPrice: '' });
      fireEvent.change(screen.getByLabelText(/^ราคาสุทธิ\/ตร\.ม\./), { target: { value: '' } });
      expect(onChange).toHaveBeenLastCalledWith({ directNetPriceSqm: '', directNetPrice: '' });
    });
  });
});
