import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { api } from '../../api/index.js';
import {
  emptyQuotationItem, QuotationItemRow, resolveTileSqmPerPiece, sizeTextFromCatalog, emptyPlainItem, itemInputFromRow,
} from './QuotationItemRow.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: { catalog: { prices: vi.fn().mockResolvedValue({ items: [] }) } },
}));

function renderRow(itemOverrides = {}, onChange = vi.fn()) {
  const item = { ...emptyQuotationItem(), ...itemOverrides };
  render(<QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()} />);
  return { item, onChange };
}

describe('QuotationItemRow — English per-sqm (owner decision 2026-09-13)', () => {
  function renderPerSqm(itemOverrides = {}, { priceMode = 'SPECIAL_SQM', documentLanguage = 'EN' } = {}) {
    const onChange = vi.fn();
    const item = { ...emptyQuotationItem(), ...itemOverrides };
    render(
      <QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()}
        priceMode={priceMode} documentLanguage={documentLanguage} currency="USD" />,
    );
    return { onChange };
  }

  it('asks for USD/ตร.ม. and ตร.ม./กล่อง instead of a list price per piece', () => {
    renderPerSqm({ sqmPerBox: 0.6 });
    expect(screen.getByLabelText(/^ราคา \(USD\/ตร\.ม\.\)/)).not.toBeNull();
    expect(screen.getByLabelText(/^ตร\.ม\.\/กล่อง/).value).toBe('0.6');
    expect(screen.queryByLabelText(/^ราคาตั้ง\/แผ่น/)).toBeNull();
    expect(screen.queryByLabelText(/^ราคาพิเศษ \(บาท/)).toBeNull();
  });

  // The null-on-clear convention: the payload builders coerce with `??`, and `'' ?? x` stays ''.
  it('clearing ตร.ม./กล่อง stores null, never an empty string', () => {
    const { onChange } = renderPerSqm({ sqmPerBox: 0.6 });
    fireEvent.change(screen.getByLabelText(/^ตร\.ม\.\/กล่อง/), { target: { value: '' } });
    expect(onChange).toHaveBeenLastCalledWith({ sqmPerBox: null });
    fireEvent.change(screen.getByLabelText(/^ตร\.ม\.\/กล่อง/), { target: { value: '0.495' } });
    expect(onChange).toHaveBeenLastCalledWith({ sqmPerBox: 0.495 });
  });

  it('a cleared (null or blank) ตร.ม./กล่อง reaches the wire as null', () => {
    expect(itemInputFromRow({ ...emptyQuotationItem(), sqmPerBox: null }, 'SPECIAL_SQM', 'EN').sqmPerBox).toBeNull();
    expect(itemInputFromRow({ ...emptyQuotationItem(), sqmPerBox: '' }, 'SPECIAL_SQM', 'EN').sqmPerBox).toBeNull();
    expect(itemInputFromRow({ ...emptyQuotationItem(), sqmPerBox: 0.495 }, 'SPECIAL_SQM', 'EN').sqmPerBox).toBe(0.495);
  });

  it('a THAI ราคาพิเศษ row is unchanged: list price + ราคาพิเศษ, no ตร.ม./กล่อง field', () => {
    renderPerSqm({}, { documentLanguage: 'TH' });
    expect(screen.getByLabelText(/^ราคาตั้ง \(บาท\/แผ่น\)/)).not.toBeNull();
    expect(screen.getByLabelText(/^ราคาพิเศษ \(บาท\/ตร\.ม\. รวม VAT\)/)).not.toBeNull();
    expect(screen.queryByLabelText(/^ตร\.ม\.\/กล่อง/)).toBeNull();
  });

  it('English in another mode shows no ตร.ม./กล่อง field', () => {
    renderPerSqm({}, { priceMode: 'NET' });
    expect(screen.queryByLabelText(/^ตร\.ม\.\/กล่อง/)).toBeNull();
  });

  async function pick(cat, rowOverrides = {}) {
    api.catalog.prices.mockImplementation(async (q) => ((q ?? '').includes('Menorca') ? { items: [cat] } : { items: [] }));
    const { onChange } = renderPerSqm({ sqmPerBox: 9.99, ...rowOverrides });
    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Menorca' } });
    fireEvent.mouseDown(await waitFor(() => screen.getByRole('option', { name: /Menorca/ }), { timeout: 1000 }));
    return onChange.mock.calls.at(-1)[0];
  }

  it('a catalogue pick carries ProductPriceDto.sqmPerBox onto the row', async () => {
    const patch = await pick({ priceId: 7, collection: 'Menorca', sizeRaw: '6.5x32.8', priceUnit: 'per_sqm', pcsPerBox: 28, sqmPerBox: 0.6 });
    expect(patch).toMatchObject({ piecesPerBox: 28, sqmPerBox: 0.6 });
  });

  // Owner feedback 2026-09-14 (QT-2026-0017 saved 66 แผ่น/กล่อง; its catalogue row says 38): a pick
  // takes the NEW product's box count, and a product with none clears the field rather than
  // keeping the previous product's number.
  it('a pick whose product has NO box count clears แผ่น/กล่อง instead of keeping the previous product\'s', async () => {
    const patch = await pick(
      { priceId: 9, collection: 'Menorca', sizeRaw: '60x120', priceUnit: 'per_sqm', pcsPerBox: null, sqmPerBox: null },
      { piecesPerBox: 66 },
    );
    expect(patch.piecesPerBox).toBeNull();
  });

  it('a pick replaces a previous product\'s แผ่น/กล่อง with the new product\'s own count', async () => {
    const patch = await pick(
      { priceId: 10, collection: 'Menorca', sizeRaw: '60x120', priceUnit: 'per_sqm', pcsPerBox: 38, sqmPerBox: null },
      { piecesPerBox: 66 },
    );
    expect(patch.piecesPerBox).toBe(38);
  });

  it('a per_linear_m pick NEVER carries its sqm_per_box (linear metres), nor keeps the old value', async () => {
    const patch = await pick({ priceId: 8, collection: 'Menorca trim', sizeRaw: '7x60', priceUnit: 'per_linear_m', pcsPerBox: 10, sqmPerBox: 6.0 });
    expect(patch.sqmPerBox).toBeNull();
  });
});

// Owner feedback 2026-09-14: nothing on the two Thai SPECIAL_SQM price boxes said which one was
// per piece and which was per ตร.ม., and a rep typed a per-ตร.ม. figure into the per-piece box.
describe('QuotationItemRow — Thai SPECIAL_SQM ราคาตั้ง suffix + per-ตร.ม. helper (owner feedback 2026-09-14)', () => {
  function renderThaiSpecial(itemOverrides = {}) {
    const onChange = vi.fn();
    const item = { ...emptyQuotationItem(), ...itemOverrides };
    render(
      <QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()}
        priceMode="SPECIAL_SQM" documentLanguage="TH" currency="THB" />,
    );
    return { item, onChange };
  }

  it('labels ราคาตั้ง (บาท/แผ่น), placed BEFORE ราคาพิเศษ (บาท/ตร.ม. รวม VAT) in DOM order', () => {
    renderThaiSpecial();
    const priceInput = screen.getByLabelText(/^ราคาตั้ง \(บาท\/แผ่น\)/);
    const specialInput = screen.getByLabelText(/^ราคาพิเศษ \(บาท\/ตร\.ม\. รวม VAT\)/);
    expect(priceInput).not.toBeNull();
    expect(specialInput).not.toBeNull();
    expect(priceInput.compareDocumentPosition(specialInput) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('both price inputs render their trailing unit label', () => {
    renderThaiSpecial();
    expect(screen.getByText('บาท/แผ่น')).not.toBeNull();
    expect(screen.getByText('บาท/ตร.ม.')).not.toBeNull();
  });

  it('shows a guidance hint under ราคาตั้ง until both unitPrice and sqmPerPiece are present', () => {
    renderThaiSpecial();
    expect(screen.getByTestId('list-per-sqm-0').textContent).toBe('กรอกราคาตั้งต่อแผ่น ระบบแปลงเป็นต่อ ตร.ม. ให้');
  });

  it('1299 ราคาตั้ง at 0.72 ตร.ม./แผ่น shows ≈ 1,932.00 บาท/ตร.ม. รวม VAT underneath', () => {
    renderThaiSpecial({ unitPrice: 1299, sqmPerPiece: 0.72 });
    expect(screen.getByTestId('list-per-sqm-0').textContent).toBe('≈ 1,932.00 บาท/ตร.ม. รวม VAT');
  });

  // Wrong-way-round: the helper line and the new parenthesised label must be ABSENT everywhere
  // else -- NET, DIRECT_NET, and English per-sqm all keep their unchanged today's rendering.
  it('NET mode never renders the suffix, the helper line, or the new label', () => {
    renderRow({ unitPrice: 850 });
    expect(screen.queryByLabelText(/^ราคาตั้ง \(บาท\/แผ่น\)/)).toBeNull();
    expect(screen.queryByTestId('list-per-sqm-0')).toBeNull();
    expect(screen.queryByText('บาท/แผ่น')).toBeNull();
  });

  it('DIRECT_NET mode never renders the suffix, the helper line, or the new label', () => {
    const onChange = vi.fn();
    render(
      <QuotationItemRow item={{ ...emptyQuotationItem(), unitPrice: 850 }} index={0} onChange={onChange} onRemove={vi.fn()}
        priceMode="DIRECT_NET" documentLanguage="TH" currency="THB" />,
    );
    expect(screen.queryByLabelText(/^ราคาตั้ง \(บาท\/แผ่น\)/)).toBeNull();
    expect(screen.queryByTestId('list-per-sqm-0')).toBeNull();
    expect(screen.queryByText('บาท/แผ่น')).toBeNull();
    // DIRECT_NET's own list-price label stays exactly as it was.
    expect(screen.getByLabelText(/^ราคาตั้ง\/แผ่น/)).not.toBeNull();
  });

  it('English per-sqm mode never renders the suffix, the helper line, or the new label', () => {
    const onChange = vi.fn();
    render(
      <QuotationItemRow item={{ ...emptyQuotationItem(), specialPriceSqm: 64, sqmPerBox: 0.6 }} index={0}
        onChange={onChange} onRemove={vi.fn()} priceMode="SPECIAL_SQM" documentLanguage="EN" currency="USD" />,
    );
    expect(screen.queryByLabelText(/^ราคาตั้ง \(บาท\/แผ่น\)/)).toBeNull();
    expect(screen.queryByTestId('list-per-sqm-0')).toBeNull();
    expect(screen.queryByText('บาท/แผ่น')).toBeNull();
    expect(screen.queryByText('บาท/ตร.ม.')).toBeNull();
  });

  // DISPLAY-ONLY: the payload the server receives is byte-for-byte what it was before this
  // change -- no new field, same two typed prices.
  it('the saved payload for a Thai SPECIAL_SQM row carries no new field', () => {
    const item = {
      ...emptyQuotationItem(),
      model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10,
      piecesPerBox: 3, sqmPerPiece: 0.72, unitPrice: 1299, specialPriceSqm: 1932,
      quantityMode: 'AREA', areaSqm: 20,
    };
    const payload = itemInputFromRow(item, 'SPECIAL_SQM', 'TH');
    expect(payload).toEqual({
      id: null, locationLabel: null, catalogPriceId: null, productCode: null, brand: null,
      model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10,
      sqmPerPiece: 0.72, quantityMode: 'AREA', areaSqm: 20, piecesInput: null,
      wastageMode: 'PERCENT', wastageValue: 0, piecesPerBox: 3, sqmPerBox: null, unitPrice: 1299,
      discountPct: null, originCountry: null, leadTimeMinDays: null, leadTimeMaxDays: null,
      itemNotes: null, lineType: 'TILE', specialPriceSqm: 1932, directNetPrice: null,
    });
  });
});

describe('QuotationItemRow', () => {
  // #M9: typing in รุ่น must clear only catalogPriceId (the row is no longer "picked from the
  // catalog"), never productCode -- that is its own free-text field the user may have typed or
  // edited independently. catalogSqmPerPiece/catalogPriceUnit clear alongside catalogPriceId --
  // same #M9 reasoning, extended to the แผ่น/ตร.ม. provenance badge (owner feedback 2026-09-12).
  it('typing in รุ่น clears catalogPriceId (and its แผ่น/ตร.ม. provenance) but leaves productCode untouched', () => {
    const { onChange } = renderRow({ model: 'Trilogy', catalogPriceId: 99, productCode: 'PN-001' });

    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy X' } });

    expect(onChange).toHaveBeenCalledWith({
      model: 'Trilogy X', catalogPriceId: null, catalogSqmPerPiece: null, catalogPriceUnit: null,
      catalogSizeText: null,
    });
    expect(onChange).not.toHaveBeenCalledWith(expect.objectContaining({ productCode: expect.anything() }));
  });

  // #L4: "กำหนดเอง" must open the custom input, not silently write an arbitrary 12%.
  it('"กำหนดเอง" opens the custom wastage input without changing the item value', () => {
    const { onChange } = renderRow({ wastageMode: 'PERCENT', wastageValue: 0 });

    fireEvent.click(screen.getByRole('button', { name: 'กำหนดเอง' }));

    expect(onChange).not.toHaveBeenCalled();
    // The custom input is now showing (there was none before, since 0 is itself a preset).
    expect(screen.getByLabelText('เผื่อ (wastage)')).not.toBeNull();
  });

  // #L1: the max lead-time input needs its OWN accessible name, not just the shared FormField
  // label the min input already claims via htmlFor.
  it('the lead-time max input has its own accessible label', () => {
    renderRow({ leadTimeMinDays: 30, leadTimeMaxDays: 45 });

    expect(screen.getByLabelText('ถึง (วัน)')).not.toBeNull();
  });

  // Owner ruling 2026-09-10 ("autofill as much as possible") + review fix: pickCatalog fills
  // every field ProductPriceDto carries EXCEPT ราคา/หน่วย -- `cat.price` is the factory purchase
  // price, not a selling price, so it must never land in `unitPrice` (the bug this test guards:
  // the old code did `unitPrice: item.unitPrice || cat.price || ''`, which silently substituted
  // the purchase price into the selling-price field for any row that started blank).
  it('picking a catalog result autofills brand/รุ่น/สี/ผิว/ขนาด/ความหนา/ตร.ม./แผ่น-กล่อง/รหัสสินค้า and leaves ราคา/หน่วย untouched', async () => {
    // mockImplementation (persistent, keyed on the query), not mockResolvedValueOnce -- an
    // EARLIER test's own debounced typeahead call (280ms real setTimeout, never awaited by that
    // test) can still be in flight when this test's render starts, and would silently consume a
    // queued "once" value meant for THIS test's own search instead. Keying on the query text
    // makes this immune to that ordering, rather than merely working around it.
    api.catalog.prices.mockImplementation(async (q) => (
      (q ?? '').includes('Trilogy')
        ? {
          items: [{
            priceId: 42, factoryName: 'Panaria SpA', productCode: 'PAN-T600-IVO',
            collection: 'Trilogy', productName: 'Ivory Lappato', color: 'Ivory', surface: 'Lappato',
            sizeRaw: '60x120', price: 43.0, thicknessMm: 10, sqmPerPiece: 0.72, pcsPerBox: 3,
            // Owner feedback F1 (2026-09-10): ProductPriceDto's own originCountryCode
            // (price_catalog.factories.country) drives the ประเทศต้นทาง autofill below.
            originCountryCode: 'IT',
          }],
        }
        : { items: [] }
    ));
    const { onChange } = renderRow({ unitPrice: '' });

    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy' } });
    // role="option": the catalog typeahead is a listbox since V4 (2026-09-10).
    const result = await waitFor(() => screen.getByRole('option', { name: /Trilogy/ }), { timeout: 1000 });
    fireEvent.mouseDown(result);

    expect(onChange).toHaveBeenCalledWith({
      catalogPriceId: 42,
      productCode: 'PAN-T600-IVO',
      brand: 'Panaria SpA',
      model: 'Trilogy',
      color: 'Ivory',
      texture: 'Lappato',
      sizeText: '60x120',
      thicknessMm: 10,
      sqmPerPiece: 0.72,
      // Owner feedback 2026-09-12: resolved STRAIGHT from the catalogue (resolveTileSqmPerPiece
      // rule (a)) -- catalogSqmPerPiece is the provenance snapshot, piecesPerSqmDisplay is its
      // แผ่น/ตร.ม. reciprocal (round2(1/0.72) = 1.39), and the source is 'catalog', not 'manual'.
      catalogSqmPerPiece: 0.72,
      catalogPriceUnit: null,
      // The picked row's own face size (cm) -- see pickCatalog's own comment for why this is
      // recorded and never sent to the server.
      catalogSizeText: '60x120',
      piecesPerSqmDisplay: 1.39,
      sqmPerPieceSource: 'catalog',
      piecesPerBox: 3,
      // V176: this catalogue row states no ตร.ม./กล่อง, so the field is set blank (null), never
      // inherited from whatever the row carried before the pick.
      sqmPerBox: null,
      // F1: IT -> อิตาลี, plus that country's default lead-time range, both still editable.
      originCountry: 'อิตาลี',
      leadTimeMinDays: 75,
      leadTimeMaxDays: 90,
    });
    // The pick's own patch (the last call) must never carry a `unitPrice` key at all -- not even
    // `undefined` -- since the parent's `{ ...it, ...patch }` merge would otherwise need it absent
    // to leave the row's existing price alone.
    const lastPatch = onChange.mock.calls[onChange.mock.calls.length - 1][0];
    expect('unitPrice' in lastPatch).toBe(false);
  });

  // F1: an originCountryCode with no ประเทศต้นทาง option of its own (a factory outside
  // IT/ES/CN/TH) must leave the field BLANK rather than guessing "อื่นๆ", which carries no lead
  // time and would look filled in while telling the customer nothing.
  it('an unmapped originCountryCode leaves ประเทศต้นทาง alone rather than guessing', async () => {
    api.catalog.prices.mockImplementation(async (q) => (
      (q ?? '').includes('Lusso')
        ? { items: [{ priceId: 7, collection: 'Lusso', factoryName: 'Somewhere', originCountryCode: 'PT' }] }
        : { items: [] }
    ));
    const { onChange } = renderRow({ originCountry: '' });

    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Lusso' } });
    fireEvent.mouseDown(await waitFor(() => screen.getByRole('option', { name: /Lusso/ }), { timeout: 1000 }));

    const lastPatch = onChange.mock.calls[onChange.mock.calls.length - 1][0];
    expect(lastPatch.originCountry).toBe('');
    // No lead-time range either -- there is no country to derive one from.
    expect('leadTimeMinDays' in lastPatch).toBe(false);
  });

  // F1: ตำแหน่งติดตั้ง moved OUT of the row and into its group's header. A per-row copy of that
  // field would put the label back in two places and let them disagree.
  it('no longer renders a per-item ตำแหน่งติดตั้ง field', () => {
    renderRow();
    expect(screen.queryByLabelText(/ตำแหน่งติดตั้ง/)).toBeNull();
  });

  // F1: with only one location there is nowhere to move to, so the row must not carry a dead
  // "ย้ายไปตำแหน่ง…" dropdown -- and ทำซ้ำรายการ degrades to a plain button.
  it('offers ทำซ้ำรายการ as a button and no ย้าย control when there is a single location group', () => {
    const onDuplicate = vi.fn();
    render(
      <QuotationItemRow
        item={emptyQuotationItem('g1')}
        index={0}
        groupId="g1"
        locationGroups={[{ groupId: 'g1', label: 'ชั้น 1' }]}
        onChange={vi.fn()}
        onRemove={vi.fn()}
        onDuplicate={onDuplicate}
      />,
    );

    expect(screen.queryByLabelText('ย้ายไปตำแหน่ง')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'ทำซ้ำรายการ' }));
    expect(onDuplicate).toHaveBeenCalledWith('g1');
  });

  it('offers ย้ายไปตำแหน่ง and ทำซ้ำ-into-another-group once a second location group exists', () => {
    const onMove = vi.fn();
    const onDuplicate = vi.fn();
    render(
      <QuotationItemRow
        item={emptyQuotationItem('g1')}
        index={0}
        groupId="g1"
        locationGroups={[{ groupId: 'g1', label: 'ชั้น 1' }, { groupId: 'g2', label: '' }]}
        onChange={vi.fn()}
        onRemove={vi.fn()}
        onMove={onMove}
        onDuplicate={onDuplicate}
      />,
    );

    // A blank group label reads as ไม่ระบุตำแหน่ง in the picker, never as an empty option.
    fireEvent.change(screen.getByLabelText('ย้ายไปตำแหน่ง'), { target: { value: 'g2' } });
    expect(onMove).toHaveBeenCalledWith('g2');
    expect(screen.getByRole('option', { name: 'ไม่ระบุตำแหน่ง' })).not.toBeNull();

    fireEvent.change(screen.getByLabelText('ทำซ้ำรายการ'), { target: { value: 'g2' } });
    expect(onDuplicate).toHaveBeenCalledWith('g2');
  });

  // Sales convenience (owner ask 2026-09-10): one click on a remembered product instead of
  // retyping its name. Only on an untouched row -- see the component's own comment.
  it('offers ใช้ล่าสุด chips on a blank row and fills the same fields the typeahead would', () => {
    const onChange = vi.fn();
    const onCatalogPicked = vi.fn();
    const recent = [{ priceId: 42, collection: 'Trilogy', factoryName: 'Panaria SpA', color: 'Ivory', surface: 'Lappato', sizeRaw: '60x120', thicknessMm: 10, sqmPerPiece: 0.72, pcsPerBox: 3, originCountryCode: 'IT' }];
    render(
      <QuotationItemRow
        item={emptyQuotationItem('g1')}
        index={0}
        groupId="g1"
        recentPicks={recent}
        onChange={onChange}
        onRemove={vi.fn()}
        onCatalogPicked={onCatalogPicked}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Trilogy' }));

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      catalogPriceId: 42, model: 'Trilogy', color: 'Ivory', originCountry: 'อิตาลี',
    }));
    expect(onCatalogPicked).toHaveBeenCalledWith(recent[0]);
  });

  it('hides the ใช้ล่าสุด chips once the row already names a product', () => {
    render(
      <QuotationItemRow
        item={{ ...emptyQuotationItem('g1'), model: 'Already chosen' }}
        index={0}
        groupId="g1"
        recentPicks={[{ priceId: 42, collection: 'Trilogy' }]}
        onChange={vi.fn()}
        onRemove={vi.fn()}
      />,
    );
    expect(screen.queryByText('ใช้ล่าสุด')).toBeNull();
  });

  // Owner ruling 2026-09-10: reuses PricingRequestCreateModal's own provenance-badge pattern so a
  // row that came from the catalog says so, without locking any field.
  it('shows the "จาก catalog" provenance badge only once catalogPriceId is set', () => {
    renderRow({ catalogPriceId: null });
    expect(screen.queryByText(/จาก catalog/)).toBeNull();
  });

  it('the provenance badge includes the product code when one is set', () => {
    renderRow({ catalogPriceId: 42, productCode: 'PAN-T600-IVO' });
    expect(screen.getByText('จาก catalog · PAN-T600-IVO')).not.toBeNull();
  });
});

// Owner feedback 2026-09-12: "ขอแค่เป็น จำนวนแผ่นต่อตารางเมตรแทน" (แผ่น/ตร.ม. instead of ตร.ม./
// แผ่น), resolved "คำนวณจากขนาดแผ่นให้อัตโนมัติ... คำนวณตรงๆ แต่แก้ทับได้" -- see the CRITICAL
// CONTEXT this was built against: production's price_catalog.product_prices.sqm_per_piece
// disagrees with width x height geometry on 8.6% of rows (up to 14x on per_linear_m trims), so the
// catalogue's own figure must win outright rather than ever being recomputed.
describe('QuotationItemRow — แผ่น/ตร.ม. resolution (resolveTileSqmPerPiece)', () => {
  // The real disagreement this whole feature exists to avoid: a 300x600 mesh/mosaic sheet whose
  // covered area genuinely is not width x height (geometry says 0.18; the catalogue's own figure,
  // from actual coverage, is 0.135 -- CLAUDE.md's own worked example for this task).
  it('resolves STRAIGHT FROM THE CATALOGUE, not from width x height geometry', () => {
    const cat = { priceUnit: 'per_sqm', sizeRaw: '300x600', sqmPerPiece: 0.135 };
    expect(resolveTileSqmPerPiece(cat)).toBe(0.135);
  });

  // Mutation check (CLAUDE.md's own instruction): prove the test above actually exercises the
  // catalogue-vs-geometry choice, not just "some number came back". Swap the implementation for a
  // geometric one, confirm the SAME test goes red, then put it back -- do not leave the mutation
  // in place.
  it('MUTATION CHECK — a geometry-preferring resolver fails the catalogue-wins test above', () => {
    function geometryPreferringResolver(cat) {
      const match = /^(\d+(?:\.\d+)?)\s*[xX×]\s*(\d+(?:\.\d+)?)/.exec(cat?.sizeRaw ?? '');
      if (match) return Number(((Number(match[1]) / 1000) * (Number(match[2]) / 1000)).toFixed(6));
      return cat?.sqmPerPiece ?? null;
    }
    const cat = { priceUnit: 'per_sqm', sizeRaw: '300x600', sqmPerPiece: 0.135 };
    // Geometry reads 300x600 as centimetres and answers 0.18 -- the WRONG figure this task exists
    // to stop the editor from ever showing. If this ever equalled 0.135 the mutation would be
    // worthless as a guard (it would no longer prove the real function's behaviour is load-bearing).
    expect(geometryPreferringResolver(cat)).not.toBe(0.135);
    expect(geometryPreferringResolver(cat)).toBe(0.18);
  });

  // per_linear_m (V153, 561 real rows, ALL of them wrong under geometry per CLAUDE.md): the
  // catalogue's own sqm_per_piece is LINEAR METRES per piece there, not area -- e.g. a 7x60cm
  // trim's 0.600 is 14x a real 0.042 sqm/piece. Never surfaced as a resolved figure.
  it('never resolves a figure for a per_linear_m product, even when the catalogue has one', () => {
    const cat = { priceUnit: 'per_linear_m', sizeRaw: '7x60', sqmPerPiece: 0.6 };
    expect(resolveTileSqmPerPiece(cat)).toBeNull();
  });

  // Branch (b), owner ruling 2026-09-12 "2) ไม่มีค่อยคำนวนเอง": when the catalogue row has no
  // sqm_per_piece, compute from ITS OWN width_mm/height_mm -- always millimetres, so no unit is
  // ever inferred. ~250 production rows carry dimensions without a factor. This MUST agree with
  // DealQuotationService#resolveSqmPerPiece step 3, or the UI blocks a row the server accepts.
  it('falls back to the catalogue\'s own width_mm x height_mm when it has no sqm_per_piece', () => {
    const cat = { priceUnit: 'per_sqm', sqmPerPiece: null, widthMm: 600, heightMm: 1200 };
    expect(resolveTileSqmPerPiece(cat)).toBeCloseTo(0.72, 6);
  });

  // Order matters: sqm_per_piece WINS over geometry wherever the two disagree (8.6% of the real
  // catalogue). 300x600 storing 0.135 against a geometric 0.180 is a real production row.
  it('prefers the catalogue sqm_per_piece over its own dimensions when the two disagree', () => {
    const cat = { priceUnit: 'per_piece', sqmPerPiece: 0.135, widthMm: 300, heightMm: 600 };
    expect(resolveTileSqmPerPiece(cat)).toBe(0.135);
  });

  // A per_linear_m row must not reach branch (b) either -- geometry is as wrong there as the
  // stored figure, so dimensions being present changes nothing.
  it('still resolves nothing for per_linear_m even when width/height are present', () => {
    const cat = { priceUnit: 'per_linear_m', sqmPerPiece: null, widthMm: 70, heightMm: 600 };
    expect(resolveTileSqmPerPiece(cat)).toBeNull();
  });

  // No catalogue link and nothing typed: the rule is "ask the rep", never "invent a number".
  // Branch (b) cannot help here either -- it needs the CATALOGUE's width_mm/height_mm, and an
  // unpicked row has no catalogue at all.
  it('a brand-new row with no catalogue pick invents nothing — the field starts empty', () => {
    const item = emptyQuotationItem();
    expect(item.sqmPerPiece).toBeNull();
    expect(item.sqmPerPieceSource).toBeNull();
  });

  it('picking a catalog row with no sqm_per_piece of its own resolves nothing (asks the rep, invents nothing)', async () => {
    api.catalog.prices.mockImplementation(async (q) => (
      (q ?? '').includes('Corner')
        // No `collection` -- the typeahead falls back to productName for both the search match
        // and its own displayed option text, so this must actually be named "Corner" to find it.
        ? { items: [{ priceId: 5, productName: 'Corner', priceUnit: 'per_piece', sqmPerPiece: null }] }
        : { items: [] }
    ));
    const { onChange } = renderRow();

    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Corner' } });
    const result = await waitFor(() => screen.getByRole('option', { name: /Corner/ }), { timeout: 1000 });
    fireEvent.mouseDown(result);

    const lastPatch = onChange.mock.calls[onChange.mock.calls.length - 1][0];
    expect(lastPatch.sqmPerPiece).toBeNull();
    expect(lastPatch.sqmPerPieceSource).toBeNull();
  });

  // The owner's "แก้ทับได้": typing over a catalog-resolved value is always honoured, and the
  // provenance flips from 'catalog' to 'manual' so the badge stops crediting the catalogue.
  it('a manual edit overrides a catalog-resolved value and is tracked as an override', () => {
    const { onChange } = renderRow({
      catalogPriceId: 42, sqmPerPiece: 0.72, sqmPerPieceSource: 'catalog',
      catalogSqmPerPiece: 0.72, piecesPerSqmDisplay: 1.39,
    });

    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '2' } });

    expect(onChange).toHaveBeenCalledWith({
      piecesPerSqmDisplay: '2',
      // 1 / 2 = 0.5 exactly -- no rounding ambiguity to muddy what this test is checking.
      sqmPerPiece: 0.5,
      sqmPerPieceSource: 'manual',
    });
  });

  // Round trip: what the rep typed must be exactly what redisplays, through the stored reciprocal
  // -- CLAUDE.md's own acceptance check (enter 16.39 -> store 1/16.39 -> redisplay 16.39).
  it('an overridden value round-trips through the stored sqmPerPiece reciprocal without drift', () => {
    const onChange = vi.fn();
    const item = { ...emptyQuotationItem(), catalogPriceId: null };
    const { rerender } = render(<QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()} />);

    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '16.39' } });
    const patch = onChange.mock.calls[onChange.mock.calls.length - 1][0];
    // NUMERIC(10,6) precision, matching every column sqm_per_piece is persisted in (V24/V165) --
    // see sqmPerPieceFromPiecesPerSqm's own comment for why 6dp is what makes the round trip exact.
    expect(patch.sqmPerPiece).toBeCloseTo(1 / 16.39, 6);

    // Re-render as the parent would after applying the patch, and confirm the field reads back
    // exactly 16.39 -- not 16.38 or 16.40 -- from the stored reciprocal alone (piecesPerSqmDisplay
    // cleared, so the input falls back to deriving from item.sqmPerPiece, exactly as a freshly
    // reloaded saved row would).
    rerender(<QuotationItemRow
      item={{ ...item, sqmPerPiece: patch.sqmPerPiece, piecesPerSqmDisplay: null }}
      index={0} onChange={onChange} onRemove={vi.fn()}
    />);
    expect(screen.getByLabelText(/^แผ่น\/ตร\.ม\./).value).toBe('16.39');
  });
});

// ขนาด (ซม.) → แผ่น/ตร.ม. fallback (owner decision 2026-09-14). A 2026-09-12 ruling had removed
// size-based inference because the free-text ขนาด column mixed cm and mm with no way to tell which
// a given row meant; the field is now explicitly labelled ขนาด (ซม.), so its unit is declared rather
// than guessed. See sqmPerPieceFromSizeCm in quotationMeta.js and resolveTileSqmPerPiece's own doc
// (rule (d)) above for the full reasoning.
describe('QuotationItemRow — ขนาด (ซม.) → แผ่น/ตร.ม. fallback (owner decision 2026-09-14)', () => {
  function renderBlank(itemOverrides = {}) {
    const onChange = vi.fn();
    const item = { ...emptyQuotationItem(), ...itemOverrides };
    const view = render(<QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()} />);
    return { item, onChange, ...view };
  }

  it('typing ขนาด fills แผ่น/ตร.ม. from it and shows "คำนวณจากขนาด"; a later size recalculates it', () => {
    const { item, onChange, rerender } = renderBlank();

    fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '60x120' } });
    expect(onChange).toHaveBeenLastCalledWith({
      sizeText: '60x120',
      sqmPerPiece: 0.72,
      piecesPerSqmDisplay: 1.39,
      sqmPerPieceSource: 'size',
    });

    // Re-render as the parent would after applying the patch, then confirm the badge and the
    // แผ่น/ตร.ม. field itself reflect it before continuing to the second edit.
    const patched = { ...item, ...onChange.mock.calls.at(-1)[0] };
    rerender(<QuotationItemRow item={patched} index={0} onChange={onChange} onRemove={vi.fn()} />);
    expect(screen.getByTestId('sqm-status-0').textContent).toContain('คำนวณจากขนาด');
    expect(screen.getByLabelText(/^แผ่น\/ตร\.ม\./).value).toBe('1.39');

    fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '60x60' } });
    expect(onChange).toHaveBeenLastCalledWith({
      sizeText: '60x60',
      sqmPerPiece: 0.36,
      piecesPerSqmDisplay: 2.78,
      sqmPerPieceSource: 'size',
    });
  });

  it('does not recalculate once the rep has typed over แผ่น/ตร.ม. directly (source manual)', () => {
    const { onChange } = renderBlank({ sqmPerPiece: 0.5, sqmPerPieceSource: 'manual' });
    fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '60x120' } });
    expect(onChange).toHaveBeenLastCalledWith({ sizeText: '60x120' });
  });

  it('does not recalculate a catalogue-resolved value (source catalog)', () => {
    const { onChange } = renderBlank({
      catalogPriceId: 42, catalogPriceUnit: 'per_piece', sqmPerPiece: 0.135, sqmPerPieceSource: 'catalog',
    });
    fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '60x120' } });
    expect(onChange).toHaveBeenLastCalledWith({ sizeText: '60x120' });
  });

  // Bug fix (prod QT-2026-0034-1, quotation_id=32, 2026-09-15): a catalogue-resolved row whose
  // แผ่น/ตร.ม. survived a size retype was exactly the prod bug -- two lines linked to a 600x600mm
  // catalogue row kept its 0.36 ตร.ม./แผ่น (and its printed 60x60cm size) after being retyped to
  // "30x60"/"3x60". `catalogSizeText` (set at pick time, see pickCatalog) is what lets the row tell
  // "same tile, different unit" (keep the catalogue figure) from "a different tile" (recompute).
  describe('catalogue-resolved value + a genuinely different retyped size (prod QT-2026-0034-1)', () => {
    function renderCatalogPicked(sizeOverrides = {}) {
      return renderBlank({
        catalogPriceId: 99, catalogPriceUnit: 'per_piece',
        sizeText: '60x60', sqmPerPiece: 0.36, sqmPerPieceSource: 'catalog', catalogSizeText: '60x60',
        piecesPerSqmDisplay: 2.78,
        ...sizeOverrides,
      });
    }

    it('recomputes แผ่น/ตร.ม. from the new size and flips source to \'size\'', () => {
      const { onChange } = renderCatalogPicked();
      fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '30x60' } });
      expect(onChange).toHaveBeenLastCalledWith({
        sizeText: '30x60',
        sqmPerPiece: 0.18,
        piecesPerSqmDisplay: 5.56,
        sqmPerPieceSource: 'size',
      });
    });

    it('the second prod line ("3x60") recomputes the same way', () => {
      const { onChange } = renderCatalogPicked();
      fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '3x60' } });
      const patch = onChange.mock.calls.at(-1)[0];
      expect(patch.sizeText).toBe('3x60');
      expect(patch.sqmPerPieceSource).toBe('size');
      expect(patch.sqmPerPiece).toBeCloseTo(0.018, 6);
    });

    it('leaves the catalogue figure UNCHANGED when the retyped size is the SAME tile in millimetres', () => {
      const { onChange } = renderCatalogPicked();
      fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '600x600' } });
      // Only sizeText patches -- sqmPerPiece/source are NOT touched, exactly like the pre-existing
      // "does not recalculate a catalogue-resolved value" case above.
      expect(onChange).toHaveBeenLastCalledWith({ sizeText: '600x600' });
    });

    it('a \'manual\' value is never overwritten, even on a row that still carries catalogSizeText', () => {
      const { onChange } = renderCatalogPicked({ sqmPerPieceSource: 'manual', sqmPerPiece: 0.5 });
      fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '30x60' } });
      expect(onChange).toHaveBeenLastCalledWith({ sizeText: '30x60' });
    });

    // Review fix (2026-09-15): the earlier version of this branch used the NEGATION of "matches",
    // which treats "can't tell" (an unparseable intermediate) the same as "confirmed different" --
    // the opposite of the backend's own FALLBACK rule. These four pin the corrected behaviour.
    it('clearing ขนาด entirely leaves the catalogue figure UNCHANGED (not wiped)', () => {
      const { onChange } = renderCatalogPicked();
      fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '' } });
      // Only sizeText patches -- sqmPerPiece stays 0.36 and source stays 'catalog' because neither
      // key is even in the patch (the parent's `{ ...item, ...patch }` merge leaves them as they were).
      expect(onChange).toHaveBeenLastCalledWith({ sizeText: '' });
    });

    it('an unparseable intermediate while retyping ("3", then "30x") leaves the catalogue figure UNCHANGED at each step', () => {
      const { onChange, item, rerender } = renderCatalogPicked();

      fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '3' } });
      expect(onChange).toHaveBeenLastCalledWith({ sizeText: '3' });

      // Re-render as the parent would after applying that patch (sqmPerPiece/source untouched),
      // then continue typing -- "30x" is still unparseable (no second number yet).
      const patched = { ...item, ...onChange.mock.calls.at(-1)[0] };
      rerender(<QuotationItemRow item={patched} index={0} onChange={onChange} onRemove={vi.fn()} />);
      fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '30x' } });
      expect(onChange).toHaveBeenLastCalledWith({ sizeText: '30x' });
    });

    it('finishing the retype ("30x60") after those unparseable intermediates still recomputes', () => {
      const { onChange } = renderCatalogPicked({ sizeText: '30x' });
      fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '30x60' } });
      expect(onChange).toHaveBeenLastCalledWith({
        sizeText: '30x60',
        sqmPerPiece: 0.18,
        piecesPerSqmDisplay: 5.56,
        sqmPerPieceSource: 'size',
      });
    });

    it('an unparseable catalogSizeText (e.g. a dirty sizeRaw fallback) never triggers the new branch, even for a genuinely different-looking size', () => {
      const { onChange } = renderCatalogPicked({ catalogSizeText: 'JOLLY COCO 60x120' });
      fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '30x60' } });
      // Falls through to the existing 'catalog'-source logic: only sizeText patches, nothing wiped
      // or replaced via the new branch.
      expect(onChange).toHaveBeenLastCalledWith({ sizeText: '30x60' });
    });
  });

  it('never fills แผ่น/ตร.ม. from a size on a per_linear_m catalogue row', () => {
    const { onChange } = renderBlank({ catalogPriceId: 8, catalogPriceUnit: 'per_linear_m' });
    fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '60x120' } });
    expect(onChange).toHaveBeenLastCalledWith({ sizeText: '60x120' });
  });

  it('never overwrites a value loaded with sqmPerPiece already set and no recorded source (a reloaded draft)', () => {
    const { onChange } = renderBlank({ sqmPerPiece: 0.5, sqmPerPieceSource: null });
    fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '60x120' } });
    expect(onChange).toHaveBeenLastCalledWith({ sizeText: '60x120' });
  });

  it('clears a size-derived value once the size becomes unreadable', () => {
    const { onChange } = renderBlank({ sqmPerPiece: 0.72, sqmPerPieceSource: 'size', piecesPerSqmDisplay: 1.39 });
    fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '60x' } });
    expect(onChange).toHaveBeenLastCalledWith({
      sizeText: '60x',
      sqmPerPiece: null,
      piecesPerSqmDisplay: '',
      sqmPerPieceSource: null,
    });
  });

  it('a catalogue pick whose resolver returns null falls back to the catalogue size text', async () => {
    api.catalog.prices.mockImplementation(async (q) => (
      (q ?? '').includes('NoFactor')
        // No sqmPerPiece, no widthMm/heightMm -- resolveTileSqmPerPiece resolves nothing, so the
        // fallback reads sizeTextFromCatalog's own fallback to sizeRaw ("60x120" here, a clean pair).
        ? { items: [{ priceId: 9, productName: 'NoFactor', priceUnit: 'per_piece', sqmPerPiece: null, sizeRaw: '60x120' }] }
        : { items: [] }
    ));
    const { onChange } = renderBlank();

    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'NoFactor' } });
    const option = await waitFor(() => screen.getByRole('option', { name: /NoFactor/ }), { timeout: 1000 });
    fireEvent.mouseDown(option);

    const patch = onChange.mock.calls.at(-1)[0];
    expect(patch.sizeText).toBe('60x120');
    expect(patch.sqmPerPiece).toBe(0.72);
    expect(patch.piecesPerSqmDisplay).toBe(1.39);
    expect(patch.sqmPerPieceSource).toBe('size');
  });

  it('the saved payload never carries sqmPerPieceSource (or piecesPerSqmDisplay) -- both are UI-only', () => {
    const item = { ...emptyQuotationItem(), sqmPerPiece: 0.72, sqmPerPieceSource: 'size', piecesPerSqmDisplay: 1.39 };
    const payload = itemInputFromRow(item, 'NET', 'TH');
    expect(payload).not.toHaveProperty('sqmPerPieceSource');
    expect(payload).not.toHaveProperty('piecesPerSqmDisplay');
  });
});

describe('sizeTextFromCatalog — ขนาด comes from the catalogue mm, never the raw string', () => {
  it("does not leak Equipe's product name into ขนาด (the owner's 2026-09-13 report)", () => {
    // VEN027994 exactly as production holds it: the Equipe profile takes size_from product_name,
    // so size_raw IS the product name. Before this fix the field read the whole string.
    const cat = {
      widthMm: 12, heightMm: 200,
      sizeRaw: '1,2X20 JOLLY COCO WHITE MATT',
    };
    expect(sizeTextFromCatalog(cat)).toBe('1.2x20');
    expect(sizeTextFromCatalog(cat)).not.toContain('JOLLY');
  });

  it('renders whole-centimetre sizes without trailing zeros, matching the field hint', () => {
    expect(sizeTextFromCatalog({ widthMm: 600, heightMm: 1200 })).toBe('60x120');
    expect(sizeTextFromCatalog({ widthMm: 200, heightMm: 200 })).toBe('20x20');
  });

  it('keeps a real fractional centimetre rather than rounding it away', () => {
    // Vives writes 36'4X33'7 = 364 x 337 mm; 36.4x33.7 must survive, not become 36x34.
    expect(sizeTextFromCatalog({ widthMm: 364, heightMm: 337 })).toBe('36.4x33.7');
  });

  it("prefers the database's own size_cm (V174) when the backend serves it", () => {
    expect(sizeTextFromCatalog({ sizeCm: '20x20', widthMm: 200, heightMm: 200, sizeRaw: '200x200' }))
      .toBe('20x20');
  });

  it('computes the same value when sizeCm is absent — the backend image lags the frontend deploy', () => {
    // Not dead code: main deploys the frontend immediately while Render runs a pinned image, so
    // there is always a window where sizeCm is not yet served. Both paths must agree.
    const cat = { widthMm: 200, heightMm: 200, sizeRaw: '200x200' };
    expect(sizeTextFromCatalog(cat)).toBe('20x20');
    expect(sizeTextFromCatalog({ ...cat, sizeCm: '20x20' })).toBe(sizeTextFromCatalog(cat));
  });

  it('falls back to the raw string only when the catalogue has no dimensions', () => {
    expect(sizeTextFromCatalog({ sizeRaw: '60x120' })).toBe('60x120');
    expect(sizeTextFromCatalog({ widthMm: 0, heightMm: 0, sizeRaw: 'ตามภาพ' })).toBe('ตามภาพ');
    expect(sizeTextFromCatalog({})).toBe('');
  });
});

describe('ส่วนลด % — blank by default, and a rep can clear it (owner, 2026-09-13)', () => {
  function renderNet(item, onChange = vi.fn()) {
    render(<QuotationItemRow item={item} index={0} priceMode="NET" onChange={onChange} onRemove={vi.fn()} />);
    return onChange;
  }

  it('a new row starts with an empty discount, not a pre-filled 0', () => {
    renderNet(emptyQuotationItem());
    expect(screen.getByLabelText(/^ส่วนลด %/).value).toBe('');
  });

  it('clearing a typed discount leaves it blank instead of snapping back to 0', () => {
    // The reported bug: onChange turned '' back into 0, so the field could never be emptied.
    const onChange = renderNet({ ...emptyQuotationItem(), discountPct: 5 });
    fireEvent.change(screen.getByLabelText(/^ส่วนลด %/), { target: { value: '' } });
    expect(onChange).toHaveBeenCalledWith({ discountPct: null });
  });

  it('a typed discount is still captured as a number', () => {
    const onChange = renderNet(emptyQuotationItem());
    fireEvent.change(screen.getByLabelText(/^ส่วนลด %/), { target: { value: '7.5' } });
    expect(onChange).toHaveBeenCalledWith({ discountPct: 7.5 });
  });

  it('a blank discount still reaches the server as 0 — pricing is unchanged', () => {
    // The server prices null and 0 identically (WastageCalculator), but the payload must never send ''.
    expect(itemInputFromRow({ ...emptyQuotationItem(), discountPct: null }, 'NET').discountPct).toBe(0);
    expect(itemInputFromRow({ ...emptyPlainItem(), discountPct: null }).discountPct).toBe(0);
  });
});
