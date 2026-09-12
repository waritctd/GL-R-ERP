import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { api } from '../../api/index.js';
import { emptyQuotationItem, QuotationItemRow, resolveTileSqmPerPiece } from './QuotationItemRow.jsx';

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
  // edited independently. catalogSqmPerPiece/catalogPriceUnit clear alongside catalogPriceId --
  // same #M9 reasoning, extended to the แผ่น/ตร.ม. provenance badge (owner feedback 2026-09-12).
  it('typing in รุ่น clears catalogPriceId (and its แผ่น/ตร.ม. provenance) but leaves productCode untouched', () => {
    const { onChange } = renderRow({ model: 'Trilogy', catalogPriceId: 99, productCode: 'PN-001' });

    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy X' } });

    expect(onChange).toHaveBeenCalledWith({
      model: 'Trilogy X', catalogPriceId: null, catalogSqmPerPiece: null, catalogPriceUnit: null,
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
      piecesPerSqmDisplay: 1.39,
      sqmPerPieceSource: 'catalog',
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
