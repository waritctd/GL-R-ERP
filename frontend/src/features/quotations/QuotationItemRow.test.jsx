import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { api } from '../../api/index.js';
import { emptyQuotationItem, QuotationItemRow } from './QuotationItemRow.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: { catalog: { prices: vi.fn().mockResolvedValue({ items: [] }) } },
}));

function renderRow(itemOverrides = {}, onChange = vi.fn()) {
  const item = { ...emptyQuotationItem(), ...itemOverrides };
  render(<QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()} />);
  return { item, onChange };
}

describe('QuotationItemRow', () => {
  // #M9: typing in รุ่น must clear only catalogPriceId (the row is no longer "picked from the
  // catalog"), never productCode -- that is its own free-text field the user may have typed or
  // edited independently.
  it('typing in รุ่น clears catalogPriceId but leaves productCode untouched', () => {
    const { onChange } = renderRow({ model: 'Trilogy', catalogPriceId: 99, productCode: 'PN-001' });

    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy X' } });

    expect(onChange).toHaveBeenCalledWith({ model: 'Trilogy X', catalogPriceId: null });
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
      piecesPerBox: 3,
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
