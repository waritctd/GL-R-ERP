import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  emptyQuotationItem, itemInputFromRow, QuotationItemRow, rowFromServerItem,
} from './QuotationItemRow.jsx';
import {
  availablePriceModes, isTilePriceChangedFromCeoLocally, PRICE_MODE_OPTIONS, quotationItemMissingSummary,
  rowHasPriceForPreview, rowsWithPricesCleared, validateQuotationItem,
} from './quotationMeta.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: { catalog: { prices: vi.fn().mockResolvedValue({ items: [] }) } },
}));

// Sales feedback #1 (แจ้งปัญหาการใช้งาน เสนอราคา, 2026-10-06) — "แก้เป็น ตร.ม. รวม VAT ทุกแบบ".
// Owner ruling of the same day: on a THAI quotation every tile price box is typed per ตร.ม.
// INCLUDING VAT — ราคาตั้ง in all three price modes, and ราคาสุทธิ too — and the system works out
// the per-แผ่น figure the document prints. The English form keeps its per-piece USD inputs.
//
// What these tests are and are not evidence of: they pin the EDITOR — which box asks for what,
// what a row sends, what a saved row reads back as. The conversion itself is the server's
// (WastageCalculator#perPieceExVatFromSqmIncVat, pinned by WastageCalculatorPerSqmPriceTest against
// the owner's own documents); nothing here computes it, and nothing here could prove it right.

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

describe('a new tile row', () => {
  it('starts with both per-ตร.ม. prices blank', () => {
    expect(emptyQuotationItem()).toMatchObject({ listPriceSqm: '', directNetPriceSqm: '', unitPrice: '' });
  });
});

describe('itemInputFromRow — a Thai tile row states its prices per ตร.ม.', () => {
  it('NET sends the typed ราคา/ตร.ม. and NO per-แผ่น price: the server derives that', () => {
    expect(itemInputFromRow({ ...typed, discountPct: 20 }, 'NET')).toMatchObject({
      listPriceSqm: 1800, unitPrice: null, discountPct: 20,
      specialPriceSqm: null, directNetPrice: null, directNetPriceSqm: null,
    });
  });

  // The row keeps the server's last per-แผ่น answer (to show it under the box). It is a derived
  // figure, never a second price to send beside the one it was derived from.
  it('never sends back the per-แผ่น figure the server derived', () => {
    expect(itemInputFromRow({ ...typed, unitPrice: 1210.25 }, 'NET')).toMatchObject({ listPriceSqm: 1800, unitPrice: null });
  });

  // The no-drift guarantee for everything saved before this change: 1,748.13 a piece is NOT
  // re-sent as "2,599.99 per ตร.ม.", which would come back as some other per-piece figure.
  it('a row saved before this change still sends its per-แผ่น price, untouched', () => {
    expect(itemInputFromRow(legacy, 'NET')).toMatchObject({ unitPrice: 1748.13, listPriceSqm: null, directNetPriceSqm: null });
  });

  it('SPECIAL_SQM sends ราคาตั้ง per ตร.ม. beside the ราคาพิเศษ', () => {
    expect(itemInputFromRow({ ...typed, specialPriceSqm: 790 }, 'SPECIAL_SQM')).toMatchObject({
      listPriceSqm: 1800, specialPriceSqm: 790, unitPrice: null, discountPct: null, directNetPriceSqm: null,
    });
  });

  it('DIRECT_NET sends both the net and the list per ตร.ม.', () => {
    expect(itemInputFromRow({ ...typed, directNetPriceSqm: 790 }, 'DIRECT_NET')).toMatchObject({
      listPriceSqm: 1800, directNetPriceSqm: 790, unitPrice: null, directNetPrice: null, specialPriceSqm: null,
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

  // A draft from before this change in which the rep has since retyped only ONE of the two boxes.
  it('DIRECT_NET lets each box be in its own unit — only the retyped one changes', () => {
    expect(itemInputFromRow({ ...complete, unitPrice: 850, directNetPriceSqm: 790 }, 'DIRECT_NET')).toMatchObject({
      unitPrice: 850, listPriceSqm: null, directNetPriceSqm: 790, directNetPrice: null,
    });
    expect(itemInputFromRow({ ...typed, directNetPrice: 500 }, 'DIRECT_NET')).toMatchObject({
      listPriceSqm: 1800, unitPrice: null, directNetPrice: 500, directNetPriceSqm: null,
    });
  });

  // calculate-line infers the price mode FROM THE ROW (it has no quotation): a net per ตร.ม. left
  // over from DIRECT_NET would make a NET row preview as ราคาสุทธิ.
  it('a net per ตร.ม. left on the row travels in DIRECT_NET only', () => {
    const row = { ...typed, directNetPriceSqm: 790, specialPriceSqm: 790 };
    expect(itemInputFromRow(row, 'NET').directNetPriceSqm).toBeNull();
    expect(itemInputFromRow(row, 'SPECIAL_SQM').directNetPriceSqm).toBeNull();
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

  it('a row saved before this change reads back with no per-ตร.ม. price, and an untouched save moves nothing', () => {
    const row = rowFromServerItem({
      id: 2, lineType: 'TILE', sqmPerPiece: 0.72, unitPrice: 1748.13, discountPct: 15, netUnitPrice: 1485.91,
      listPriceSqm: null, directNetPriceSqm: null,
    }, 'NET');
    expect(row).toMatchObject({ listPriceSqm: '', directNetPriceSqm: '' });
    expect(itemInputFromRow(row, 'NET')).toMatchObject({ unitPrice: 1748.13, listPriceSqm: null });
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

  it('DIRECT_NET: a net saved before this change is still recovered from netUnitPrice, per แผ่น', () => {
    const row = rowFromServerItem({ id: 4, lineType: 'TILE', sqmPerPiece: 0.72, unitPrice: 850, netUnitPrice: 500 }, 'DIRECT_NET');
    expect(row).toMatchObject({ directNetPrice: 500, directNetPriceSqm: '', listPriceSqm: '' });
    expect(itemInputFromRow(row, 'DIRECT_NET')).toMatchObject({ unitPrice: 850, directNetPrice: 500, directNetPriceSqm: null });
  });
});

describe('validateQuotationItem — the price a Thai tile row needs is a per-ตร.ม. one', () => {
  it('a row priced per ตร.ม. is complete in every mode', () => {
    expect(validateQuotationItem(typed, 'NET')).toEqual({});
    expect(validateQuotationItem({ ...typed, specialPriceSqm: 790 }, 'SPECIAL_SQM')).toEqual({});
    expect(validateQuotationItem({ ...typed, directNetPriceSqm: 790 }, 'DIRECT_NET')).toEqual({});
    expect(validateQuotationItem({ ...complete, directNetPriceSqm: 790 }, 'DIRECT_NET')).toEqual({});
  });

  // The per-แผ่น figure is the server's answer and arrives a moment after typing — or not at all
  // when a preview is refused. A row must not read "ขาด ราคา" for a price the rep has typed.
  it('does not wait for the per-แผ่น figure the server derives', () => {
    expect(validateQuotationItem({ ...typed, unitPrice: null }, 'NET')).toEqual({});
    expect(validateQuotationItem({ ...typed, unitPrice: null, directNetPriceSqm: 790, netUnitPrice: null }, 'DIRECT_NET')).toEqual({});
  });

  it('a row saved before this change (a per-แผ่น price) is still complete', () => {
    expect(validateQuotationItem(legacy, 'NET')).toEqual({});
    expect(validateQuotationItem({ ...complete, unitPrice: 850, directNetPrice: 500 }, 'DIRECT_NET')).toEqual({});
  });

  it('names the per-ตร.ม. box when no price is typed at all', () => {
    expect(validateQuotationItem(complete, 'NET')).toEqual({ unitPrice: 'กรุณาระบุราคา/ตร.ม.' });
    expect(validateQuotationItem({ ...complete, specialPriceSqm: 790 }, 'SPECIAL_SQM'))
      .toEqual({ unitPrice: 'กรุณาระบุราคาตั้ง (บาท/ตร.ม.)' });
    expect(validateQuotationItem(complete, 'DIRECT_NET')).toEqual({ directNetPrice: 'กรุณาระบุราคาสุทธิ (บาท/ตร.ม.)' });
  });

  it('refuses a zero or negative per-ตร.ม. price — the server would', () => {
    for (const bad of [0, -1800]) {
      expect(validateQuotationItem({ ...complete, listPriceSqm: bad }, 'NET')).toEqual({ unitPrice: 'ราคาต้องมากกว่าศูนย์' });
      expect(validateQuotationItem({ ...complete, listPriceSqm: bad, directNetPriceSqm: 790 }, 'DIRECT_NET'))
        .toEqual({ unitPrice: 'ราคาตั้งต้องมากกว่าศูนย์' });
      expect(validateQuotationItem({ ...typed, directNetPriceSqm: bad }, 'DIRECT_NET'))
        .toEqual({ directNetPrice: 'ราคาสุทธิต้องมากกว่าศูนย์' });
    }
  });

  // ItemInput.listPriceSqm / directNetPriceSqm are @Digits(fraction = 2): a third decimal is a 400
  // from bean validation, so the editor says so on the field instead of letting the save fail.
  it('refuses more than two decimals on either per-ตร.ม. price', () => {
    expect(validateQuotationItem({ ...complete, listPriceSqm: 1800.125 }, 'NET')).toEqual({ unitPrice: 'ทศนิยมได้ไม่เกิน 2 ตำแหน่ง' });
    expect(validateQuotationItem({ ...typed, directNetPriceSqm: 790.125 }, 'DIRECT_NET'))
      .toEqual({ directNetPrice: 'ทศนิยมได้ไม่เกิน 2 ตำแหน่ง' });
  });

  it('an English document still asks for its per-piece prices, and a per-ตร.ม. figure left on the row is not one', () => {
    expect(validateQuotationItem(complete, 'NET', 'EN')).toEqual({ unitPrice: 'กรุณาระบุราคา/หน่วย' });
    expect(validateQuotationItem(typed, 'NET', 'EN')).toEqual({ unitPrice: 'กรุณาระบุราคา/หน่วย' });
    expect(validateQuotationItem({ ...complete, directNetPriceSqm: 790 }, 'DIRECT_NET', 'EN'))
      .toEqual({ directNetPrice: 'กรุณาระบุราคาสุทธิ/แผ่น' });
    expect(validateQuotationItem({ ...complete, unitPrice: 15 }, 'NET', 'EN')).toEqual({});
  });

  it('the checklist summary names the box without claiming a unit', () => {
    expect(quotationItemMissingSummary({ unitPrice: 'x' }, 0)).toBe('รายการที่ 1: ขาด ราคา');
    expect(quotationItemMissingSummary({ directNetPrice: 'x' }, 1)).toBe('รายการที่ 2: ขาด ราคาสุทธิ');
  });
});

describe('rowHasPriceForPreview — a per-ตร.ม. price is a price', () => {
  it('previews a row priced per ตร.ม., in each mode', () => {
    expect(rowHasPriceForPreview(typed, 'NET')).toBe(true);
    expect(rowHasPriceForPreview({ ...typed, specialPriceSqm: 790 }, 'SPECIAL_SQM')).toBe(true);
    expect(rowHasPriceForPreview({ ...complete, directNetPriceSqm: 790 }, 'DIRECT_NET')).toBe(true);
    expect(rowHasPriceForPreview(legacy, 'NET')).toBe(true);
  });

  it('SPECIAL_SQM still needs a list price beside the ราคาพิเศษ', () => {
    expect(rowHasPriceForPreview({ ...complete, specialPriceSqm: 790 }, 'SPECIAL_SQM')).toBe(false);
  });

  // The server refuses to turn a per-ตร.ม. price into a per-แผ่น one with no area per piece —
  // there is nothing honest to preview, so nothing is asked.
  it('does not preview a per-ตร.ม. price on a row with no แผ่น/ตร.ม.', () => {
    expect(rowHasPriceForPreview({ ...typed, sqmPerPiece: null }, 'NET')).toBe(false);
    expect(rowHasPriceForPreview({ ...complete, sqmPerPiece: null, directNetPriceSqm: 790 }, 'DIRECT_NET')).toBe(false);
    // A per-แผ่น price needs no area to be a price.
    expect(rowHasPriceForPreview({ ...legacy, sqmPerPiece: null }, 'NET')).toBe(true);
  });

  it('on an English document a per-ตร.ม. figure left on the row is not a price', () => {
    expect(rowHasPriceForPreview(typed, 'NET', 'EN')).toBe(false);
    expect(rowHasPriceForPreview({ ...complete, directNetPriceSqm: 790 }, 'DIRECT_NET', 'EN')).toBe(false);
  });
});

describe('rowsWithPricesCleared — a language switch clears the per-ตร.ม. prices too', () => {
  it('clears both, with every other typed amount', () => {
    const [row] = rowsWithPricesCleared([{ ...typed, unitPrice: 1210.25, directNetPriceSqm: 790, discountPct: 5 }]);
    expect(row).toMatchObject({ listPriceSqm: null, directNetPriceSqm: null, unitPrice: null, discountPct: 5 });
  });
});

describe('isTilePriceChangedFromCeoLocally — a net typed per ตร.ม. is compared by what it comes to per แผ่น', () => {
  const ceoLinked = { ceoListUnitPrice: 1210.25, ceoDiscountPct: 0, ceoDirectNetPrice: 531.16 };

  it('reads unchanged when the derived net is the CEO\'s own', () => {
    expect(isTilePriceChangedFromCeoLocally(
      { ...ceoLinked, directNetPriceSqm: 790, directNetPrice: '', netUnitPrice: 531.16 }, 'DIRECT_NET')).toBe(false);
  });

  it('reads changed when it is not — and while the server has not answered yet', () => {
    expect(isTilePriceChangedFromCeoLocally(
      { ...ceoLinked, directNetPriceSqm: 800, directNetPrice: '', netUnitPrice: 537.88 }, 'DIRECT_NET')).toBe(true);
    expect(isTilePriceChangedFromCeoLocally(
      { ...ceoLinked, directNetPriceSqm: 800, directNetPrice: '', netUnitPrice: null }, 'DIRECT_NET')).toBe(true);
  });

  it('a net still stated per แผ่น is compared exactly as before', () => {
    expect(isTilePriceChangedFromCeoLocally({ ...ceoLinked, directNetPrice: 531.16 }, 'DIRECT_NET')).toBe(false);
    expect(isTilePriceChangedFromCeoLocally({ ...ceoLinked, directNetPrice: 500 }, 'DIRECT_NET')).toBe(true);
  });
});

describe('availablePriceModes — the Thai editor says per ตร.ม.; the CEO\'s own screen does not change', () => {
  const byCode = (list) => Object.fromEntries(list.map((opt) => [opt.code, opt]));

  it('a Thai document\'s three modes all describe a per-ตร.ม. price', () => {
    const th = byCode(availablePriceModes('TH'));
    expect(th.NET).toMatchObject({ label: 'ราคาตั้ง − ส่วนลด %', hint: 'กรอกราคาตั้งต่อ ตร.ม. (รวม VAT) และส่วนลด % ระบบคำนวณราคาต่อแผ่นให้' });
    expect(th.SPECIAL_SQM).toMatchObject({ label: 'ราคาพิเศษ บาท/ตร.ม.', hint: 'กรอกราคาตั้งและราคาพิเศษต่อ ตร.ม. (รวม VAT) ระบบคำนวณราคาสุทธิต่อแผ่นให้' });
    expect(th.DIRECT_NET).toMatchObject({ label: 'ราคาสุทธิ บาท/ตร.ม.', hint: 'กรอกราคาสุทธิต่อ ตร.ม. (รวม VAT) ตรง ๆ ระบบคำนวณราคาสุทธิต่อแผ่นให้' });
  });

  it('an English document keeps its per-piece wording', () => {
    const en = byCode(availablePriceModes('EN'));
    expect(en.NET.hint).toBe('กรอกราคาตั้งต่อแผ่นและส่วนลด % (แบบเดิม)');
    expect(en.DIRECT_NET).toMatchObject({ label: 'ราคาสุทธิต่อแผ่น', hint: 'กรอกราคาสุทธิต่อแผ่นตรง ๆ' });
    expect(en.SPECIAL_SQM.label).toBe('ราคา USD/ตร.ม.');
  });

  // PRICE_MODE_OPTIONS is also the CEO's pricing screen's list (PricingRequestDetailPage), where
  // prices ARE typed per แผ่น. It must not start saying ตร.ม. as a side effect of this change.
  it('PRICE_MODE_OPTIONS itself is untouched', () => {
    const base = byCode(PRICE_MODE_OPTIONS);
    expect(base.NET.hint).toBe('กรอกราคาตั้งต่อแผ่นและส่วนลด % (แบบเดิม)');
    expect(base.DIRECT_NET).toMatchObject({ label: 'ราคาสุทธิต่อแผ่น', hint: 'กรอกราคาสุทธิต่อแผ่นตรง ๆ' });
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
  const follows = (a, b) => Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);

  describe('ราคาตั้ง − ส่วนลด %', () => {
    it('asks for ราคา/ตร.ม. (รวม VAT) — the per-หน่วย box is gone from a tile row', () => {
      renderRow(complete);
      expect(screen.getByLabelText(/^ราคา\/ตร\.ม\. \(รวม VAT\)/)).not.toBeNull();
      expect(screen.queryByLabelText(/^ราคา\/หน่วย/)).toBeNull();
      expect(screen.getByLabelText(/^ส่วนลด %/)).not.toBeNull();
    });

    it('typing writes the per-ตร.ม. price and nothing else; the per-แผ่น figure is the server\'s to fill in', () => {
      const { onChange } = renderRow(complete);
      fireEvent.change(screen.getByLabelText(/^ราคา\/ตร\.ม\./), { target: { value: '1800' } });
      expect(onChange).toHaveBeenLastCalledWith({ listPriceSqm: 1800 });
    });

    // Emptying the box must leave it EMPTY. Were the old per-แผ่น figure kept, the box would refill
    // itself with that figure's per-ตร.ม. equivalent the moment the last digit was deleted.
    it('clearing the box clears the price altogether', () => {
      const { onChange } = renderRow({ ...typed, unitPrice: 1210.25 });
      fireEvent.change(screen.getByLabelText(/^ราคา\/ตร\.ม\./), { target: { value: '' } });
      expect(onChange).toHaveBeenLastCalledWith({ listPriceSqm: '', unitPrice: null });
    });

    it('shows what it comes to per แผ่น — the SERVER\'s figure, never one worked out here', () => {
      renderRow({ ...typed, unitPrice: 1210.25 });
      expect(screen.getByLabelText(/^ราคา\/ตร\.ม\./).value).toBe('1800');
      expect(screen.getByTestId('list-per-piece-0').textContent).toBe('= ฿1,210.25/แผ่น (ก่อน VAT)');
    });

    it('says so while the server is still working it out, and before any price is typed', () => {
      renderRow({ ...typed, unitPrice: 1210.25, calcPending: true });
      expect(screen.getByTestId('list-per-piece-0').textContent).toBe('กำลังคำนวณราคาต่อแผ่น...');
    });

    it('with nothing typed yet, says the per-แผ่น price will be worked out', () => {
      renderRow(complete);
      expect(screen.getByTestId('list-per-piece-0').textContent).toBe('ระบบคำนวณราคาต่อแผ่นให้');
    });

    it('with no แผ่น/ตร.ม. on the row, says that is what is missing — no figure is invented', () => {
      renderRow({ ...typed, sqmPerPiece: null, unitPrice: null });
      expect(screen.getByTestId('list-per-piece-0').textContent).toBe('กรอกแผ่น/ตร.ม. ก่อน ระบบจึงคำนวณราคาต่อแผ่นได้');
    });

    // 1,748.13 × 1.39 แผ่น/ตร.ม. × 1.07 = 2,599.99 — the same display rule the ราคาพิเศษ mode has
    // used since 2026-09-14 (listPricePerSqmIncVat). Shown, not saved: see itemInputFromRow above.
    it('a row saved before this change shows its price per ตร.ม., and says which figure is the stored one', () => {
      renderRow(legacy);
      expect(screen.getByLabelText(/^ราคา\/ตร\.ม\./).value).toBe('2599.99');
      expect(screen.getByTestId('list-per-piece-0').textContent).toBe('คิดจากราคา ฿1,748.13/แผ่น (ก่อน VAT)');
    });

    // A row saved before this change whose แผ่น/ตร.ม. has since been cleared: the per-แผ่น price
    // is still there (and still what a save sends), but it cannot be shown per ตร.ม. — so the box
    // is empty, and an empty box with no word of explanation would read as "no price".
    it('a per-แผ่น price that cannot be shown per ตร.ม. is still named, with what is needed to show it', () => {
      renderRow({ ...legacy, sqmPerPiece: null });
      expect(screen.getByLabelText(/^ราคา\/ตร\.ม\./).value).toBe('');
      expect(screen.getByTestId('list-per-piece-0').textContent)
        .toBe('ราคา ฿1,748.13/แผ่น (ก่อน VAT) · กรอกแผ่น/ตร.ม. เพื่อแสดงเป็นราคาต่อ ตร.ม.');
    });

    // The per-แผ่น figure is the figure the document prints; a screen-reader user tabbing into the
    // box should hear it, not only see it.
    it('the line under the box describes the box', () => {
      renderRow({ ...typed, unitPrice: 1210.25 });
      const note = screen.getByTestId('list-per-piece-0');
      expect(note.id).toBe('price-0-per-piece');
      expect(screen.getByLabelText(/^ราคา\/ตร\.ม\./).getAttribute('aria-describedby')).toContain('price-0-per-piece');
    });
  });

  describe('ราคาพิเศษ บาท/ตร.ม.', () => {
    it('ราคาตั้ง is per ตร.ม. too, and still comes before ราคาพิเศษ', () => {
      const { onChange } = renderRow(complete, { priceMode: 'SPECIAL_SQM' });
      const list = screen.getByLabelText(/^ราคาตั้ง\/ตร\.ม\. \(รวม VAT\)/);
      const special = screen.getByLabelText(/^ราคาพิเศษ\/ตร\.ม\. \(รวม VAT\)/);
      expect(follows(list, special)).toBe(true);
      expect(screen.queryByLabelText(/^ราคาตั้ง \(บาท\/แผ่น\)/)).toBeNull();
      fireEvent.change(list, { target: { value: '2000' } });
      expect(onChange).toHaveBeenLastCalledWith({ listPriceSqm: 2000 });
    });
  });

  describe('ราคาสุทธิ', () => {
    it('asks for the net AND the list per ตร.ม. (รวม VAT); the list stays optional', () => {
      renderRow(complete, { priceMode: 'DIRECT_NET' });
      expect(screen.getByLabelText(/^ราคาสุทธิ\/ตร\.ม\. \(รวม VAT\)/)).not.toBeNull();
      expect(screen.getByLabelText(/^ราคาตั้ง\/ตร\.ม\. \(รวม VAT\)/)).not.toBeNull();
      expect(screen.queryByLabelText(/^ราคาสุทธิ\/แผ่น/)).toBeNull();
      expect(screen.getByText('เว้นว่าง = ใช้ราคาสุทธิ (พิมพ์ส่วนลดเป็น Net)')).not.toBeNull();
    });

    // One line under each box, not three under one: once ราคาตั้ง is filled in, "leave it blank"
    // has nothing left to say and gives way to what the price comes to per แผ่น.
    it('the line under ราคาสุทธิ describes that box, and a per-แผ่น net with no แผ่น/ตร.ม. is still named', () => {
      renderRow({ ...complete, sqmPerPiece: null, directNetPrice: 500, netUnitPrice: 500 }, { priceMode: 'DIRECT_NET' });
      const box = screen.getByLabelText(/^ราคาสุทธิ\/ตร\.ม\./);
      const note = screen.getByTestId('direct-net-per-piece-0');
      expect(box.value).toBe('');
      expect(note.textContent).toBe('ราคา ฿500.00/แผ่น (ก่อน VAT) · กรอกแผ่น/ตร.ม. เพื่อแสดงเป็นราคาต่อ ตร.ม.');
      expect(note.id).toBe('direct-net-0-per-piece');
      expect(box.getAttribute('aria-describedby')).toContain('direct-net-0-per-piece');
    });

    it('the "เว้นว่าง = …" hint steps aside once ราคาตั้ง is filled in', () => {
      renderRow({ ...typed, unitPrice: 1210.25, directNetPriceSqm: 790, netUnitPrice: 531.16 }, { priceMode: 'DIRECT_NET' });
      expect(screen.queryByText('เว้นว่าง = ใช้ราคาสุทธิ (พิมพ์ส่วนลดเป็น Net)')).toBeNull();
      expect(screen.getByTestId('list-per-piece-0').textContent).toBe('= ฿1,210.25/แผ่น (ก่อน VAT)');
    });

    it('typing the net writes it per ตร.ม. and drops a per-แผ่น net the row was carrying', () => {
      const { onChange } = renderRow({ ...complete, directNetPrice: 500 }, { priceMode: 'DIRECT_NET' });
      fireEvent.change(screen.getByLabelText(/^ราคาสุทธิ\/ตร\.ม\./), { target: { value: '790' } });
      expect(onChange).toHaveBeenLastCalledWith({ directNetPriceSqm: 790, directNetPrice: '' });
      fireEvent.change(screen.getByLabelText(/^ราคาสุทธิ\/ตร\.ม\./), { target: { value: '' } });
      expect(onChange).toHaveBeenLastCalledWith({ directNetPriceSqm: '', directNetPrice: '' });
    });

    it('shows the server\'s net per แผ่น under the box', () => {
      renderRow({ ...complete, directNetPriceSqm: 790, netUnitPrice: 531.16 }, { priceMode: 'DIRECT_NET' });
      expect(screen.getByTestId('direct-net-per-piece-0').textContent).toBe('= สุทธิ ฿531.16/แผ่น (ก่อน VAT)');
    });

    // 500 × 1.39 × 1.07 = 743.65
    it('a net saved before this change shows per ตร.ม., and says which figure is the stored one', () => {
      renderRow({ ...complete, directNetPrice: 500, netUnitPrice: 500 }, { priceMode: 'DIRECT_NET' });
      expect(screen.getByLabelText(/^ราคาสุทธิ\/ตร\.ม\./).value).toBe('743.65');
      expect(screen.getByTestId('direct-net-per-piece-0').textContent).toBe('คิดจากราคา ฿500.00/แผ่น (ก่อน VAT)');
    });
  });

  describe('an English document is untouched', () => {
    it('NET still asks for a price per piece and writes unitPrice', () => {
      const { onChange } = renderRow(complete, { documentLanguage: 'EN' });
      const box = screen.getByLabelText(/^ราคา\/หน่วย/);
      expect(screen.queryByLabelText(/^ราคา\/ตร\.ม\./)).toBeNull();
      expect(screen.queryByTestId('list-per-piece-0')).toBeNull();
      fireEvent.change(box, { target: { value: '15' } });
      expect(onChange).toHaveBeenLastCalledWith({ unitPrice: 15 });
    });

    it('DIRECT_NET still asks for the net and the list per piece', () => {
      const { onChange } = renderRow(complete, { priceMode: 'DIRECT_NET', documentLanguage: 'EN' });
      expect(screen.getByLabelText(/^ราคาตั้ง\/แผ่น/)).not.toBeNull();
      expect(screen.queryByTestId('direct-net-per-piece-0')).toBeNull();
      fireEvent.change(screen.getByLabelText(/^ราคาสุทธิ\/แผ่น/), { target: { value: '12.5' } });
      expect(onChange).toHaveBeenLastCalledWith({ directNetPrice: 12.5 });
    });
  });

  // jsdom has no layout engine, so this pins the MECHANISM, not the pixels (those were measured
  // in a browser: at an 820px window this grid's columns are 142px, the in-field "บาท/ตร.ม."
  // reserves 72px of that, and a price of 1041.11 was cut to "1041."). When the box itself is too
  // narrow for both, the unit steps aside and the number gets the room — the label still carries
  // the unit. A container query on the box, not a viewport breakpoint: the same squeeze happens
  // in the tablet band and at the narrow end of the desktop band, with the sidebar open.
  describe('a narrow price box shows the price, not the unit', () => {
    const unitOf = (box) => box.parentElement.querySelector('[aria-hidden="true"]');

    it.each([
      ['NET', /^ราคา\/ตร\.ม\./],
      ['SPECIAL_SQM', /^ราคาตั้ง\/ตร\.ม\./],
      ['SPECIAL_SQM', /^ราคาพิเศษ\/ตร\.ม\./],
      ['DIRECT_NET', /^ราคาสุทธิ\/ตร\.ม\./],
      ['DIRECT_NET', /^ราคาตั้ง\/ตร\.ม\./],
    ])('%s: the unit inside %s gives way below 11rem, and the number takes its padding back', (priceMode, label) => {
      renderRow(complete, { priceMode });
      const box = screen.getByLabelText(label);
      expect(box.parentElement.className).toContain('@container');
      expect(unitOf(box).textContent).toBe('บาท/ตร.ม.');
      expect(unitOf(box).className).toContain('@max-[11rem]:hidden');
      expect(box.className).toContain('@max-[11rem]:pr-3');
    });

    // …and the row the price boxes sit in goes from four columns to two when the ROW is narrow.
    // Measured at a 1045px window (desktop band, sidebar open — what an iPad in landscape gets):
    // four columns of 133px, a 133px "ราคา/ตร.ม. (รวม VAT) *" that wrapped beside a "ส่วนลด %" that
    // did not, and a price input sitting one line lower than its neighbours. Four columns need
    // ~170px each for the longest label here (164px), hence 45rem. A container query on the row
    // for the same reason as above; `mobile:` still takes it to one column on a phone.
    it('the price row is two columns until the row itself is 45rem wide, and one on a phone', () => {
      renderRow(complete);
      const box = screen.getByLabelText(/^ราคา\/ตร\.ม\./);
      const priceRow = box.closest('.gap-3.grid-cols-2');
      expect(priceRow).not.toBeNull();
      expect(priceRow.className).toContain('@min-[45rem]:grid-cols-4');
      expect(priceRow.className).toContain('mobile:grid-cols-1');
      // The container is a wrapper around the price row ONLY, never the whole <li>: a query
      // container is also a layout-containment and stacking boundary, and the row holds the
      // catalogue dropdown, which has to stay free to overlap the row beneath it.
      expect(priceRow.parentElement.className).toBe('@container');
      expect(box.closest('li').className).not.toContain('@container');
    });

    // The wrong-way-round case. เผื่อ's "กรอกเอง" box uses the same component, and ITS label does not
    // say % or แผ่น — the unit inside the field is the only place that distinction lives.
    it('the เผื่อ box never drops its unit: nothing else on screen says whether it is % or แผ่น', () => {
      renderRow(complete);
      const waste = document.getElementById('waste-0');
      expect(unitOf(waste).textContent).toBe('%');
      expect(unitOf(waste).className).not.toContain('hidden');
      expect(waste.parentElement.className).not.toContain('@container');
    });
  });

  it('the คำขอราคา form (hidePricing) still shows no price box at all', () => {
    renderRow(typed, { hidePricing: true });
    expect(screen.queryByLabelText(/^ราคา\/ตร\.ม\./)).toBeNull();
    expect(screen.queryByTestId('list-per-piece-0')).toBeNull();
  });
});
