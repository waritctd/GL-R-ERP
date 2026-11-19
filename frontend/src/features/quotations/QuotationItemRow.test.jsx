import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { api } from '../../api/index.js';
import {
  emptyQuotationItem,
  QuotationItemRow,
  resolveTileSqmPerPiece,
  emptyPlainItem,
  itemInputFromRow,
} from './QuotationItemRow.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: { catalog: { prices: vi.fn().mockResolvedValue({ items: [] }) } },
}));

function renderRow(itemOverrides = {}, onChange = vi.fn()) {
  const item = { ...emptyQuotationItem(), ...itemOverrides };
  const { container } = render(<QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()} />);
  return { item, onChange, container };
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
    expect(screen.queryByLabelText(/^ราคาพิเศษ\/ตร\.ม\./)).toBeNull();
  });

  // The null-on-clear convention: the payload builders coerce with `??`, and `'' ?? x` stays ''.
  it('clearing ตร.ม./กล่อง stores null, never an empty string', () => {
    const { onChange } = renderPerSqm({ sqmPerBox: 0.6 });
    fireEvent.change(screen.getByLabelText(/^ตร\.ม\.\/กล่อง/), { target: { value: '' } });
    expect(onChange).toHaveBeenLastCalledWith({ sqmPerBox: null });
    fireEvent.change(screen.getByLabelText(/^ตร\.ม\.\/กล่อง/), { target: { value: '0.495' } });
    expect(onChange).toHaveBeenLastCalledWith({ sqmPerBox: 0.495 });
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
});

// Every Thai price box is per ตร.ม. in every price mode, so there is no per-piece box to confuse it
// with. Each box says its unit and the other unit's figure is on screen. quotationPerSqmPrice.test.jsx
// has the rest of the per-ตร.ม. behaviour.
describe('QuotationItemRow — Thai price boxes carry their unit (2026-09-14, per ตร.ม. everywhere since 2026-10-06)', () => {
  function renderThaiSpecial(itemOverrides = {}) {
    const onChange = vi.fn();
    const item = { ...emptyQuotationItem(), ...itemOverrides };
    render(
      <QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()}
        priceMode="SPECIAL_SQM" documentLanguage="TH" currency="THB" />,
    );
    return { item, onChange };
  }

  // The figure the old helper line showed UNDER the box (1,299 × 1.39 × 1.07): a row still holding a
  // price per แผ่น shows it IN the box and names the per-แผ่น figure beneath.
  it('1299 a piece at 0.72 ตร.ม./แผ่น shows as 1932 per ตร.ม. in the box, with the per-แผ่น figure underneath', () => {
    renderThaiSpecial({ unitPrice: 1299, sqmPerPiece: 0.72 });
    expect(screen.getByLabelText(/^ราคาตั้ง\/ตร\.ม\. \(รวม VAT\)/).value).toBe('1932');
    expect(screen.getByTestId('list-per-piece-0').textContent).toBe('คิดจากราคา ฿1,299.00/แผ่น (ก่อน VAT)');
  });

  // The whole wire shape of a row still holding a per-แผ่น list price: its usual fields plus the two
  // per-ตร.ม. fields as null. toEqual on purpose: an extra field or a moved price fails here.
  it('the saved payload for a Thai SPECIAL_SQM row still priced per แผ่น is unchanged apart from two null fields', () => {
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
      wastageMode: 'PERCENT', wastageValue: 0, piecesPerBox: 3, sqmPerBox: null,
      // A deliberate field (emptyQuotationItem defaults it true), not the accidental drift this
      // test otherwise guards against.
      roundToFullBox: true, unitPrice: 1299,
      discountPct: null, originCountry: null, leadTimeMinDays: null, leadTimeMaxDays: null,
      itemNotes: null, lineType: 'TILE', specialPriceSqm: 1932, directNetPrice: null,
      // Present on every tile payload, null until the box is retyped.
      listPriceSqm: null, directNetPriceSqm: null,
      // Stock declaration: null unless the rep ticks จากสต็อก.
      stockSource: null,
    });
  });
});

describe('QuotationItemRow — จากสต็อก (direct quotation ships from Thai stock)', () => {
  function renderStockRow(itemOverrides = {}, props = {}) {
    const onChange = vi.fn();
    const item = { ...emptyQuotationItem(), ...itemOverrides };
    render(<QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()} showStockOption {...props} />);
    return { onChange };
  }

  it('a TILE row offers the checkbox; ticking sets IN_THAILAND and unticking sets null', () => {
    const { onChange } = renderStockRow();
    const box = screen.getByLabelText('จากสต็อก');
    expect(box.checked).toBe(false);
    fireEvent.click(box);
    expect(onChange).toHaveBeenLastCalledWith({ stockSource: 'IN_THAILAND', leadTimeMinDays: null, leadTimeMaxDays: null });
  });

  it('only an IN_THAILAND tick reaches the wire; IN_TRANSIT (a pricing-request value the server refuses) and PLAIN rows send none', () => {
    expect(itemInputFromRow({ ...emptyQuotationItem(), stockSource: 'IN_TRANSIT' }, 'NET', 'TH').stockSource).toBeNull();
    expect(itemInputFromRow({ ...emptyPlainItem(), stockSource: 'IN_THAILAND' }, 'NET', 'TH')).not.toHaveProperty('stockSource');
  });
});

describe('QuotationItemRow', () => {
  // Typing in รุ่น must clear only catalogPriceId (the row is no longer "picked from the catalog"),
  // never productCode, its own free-text field. catalogSqmPerPiece/catalogPriceUnit clear with it
  // so the แผ่น/ตร.ม. provenance badge doesn't outlive the link.
  it('typing in รุ่น clears catalogPriceId (and its แผ่น/ตร.ม. provenance) but leaves productCode untouched', () => {
    const { onChange } = renderRow({ model: 'Trilogy', catalogPriceId: 99, productCode: 'PN-001' });

    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy X' } });

    expect(onChange).toHaveBeenCalledWith({
      model: 'Trilogy X', catalogPriceId: null, catalogSqmPerPiece: null, catalogPriceUnit: null,
      catalogSizeText: null,
    });
    expect(onChange).not.toHaveBeenCalledWith(expect.objectContaining({ productCode: expect.anything() }));
  });

  // pickCatalog fills every field ProductPriceDto carries EXCEPT ราคา/หน่วย: `cat.price` is the
  // factory purchase price, not a selling price, so it must never land in `unitPrice` (the old code
  // substituted it into the selling-price field of any blank row).
  it('picking a catalog result autofills brand/รุ่น/สี/ผิว/ขนาด/ความหนา/ตร.ม./แผ่น-กล่อง/รหัสสินค้า and leaves ราคา/หน่วย untouched', async () => {
    // mockImplementation keyed on the query, not mockResolvedValueOnce: an EARLIER test's debounced
    // typeahead call (280ms real setTimeout, never awaited) can still be in flight and would
    // consume a queued "once" value meant for THIS test.
    api.catalog.prices.mockImplementation(async (q) => (
      (q ?? '').includes('Trilogy')
        ? {
          items: [{
            priceId: 42, factoryName: 'Panaria SpA', productCode: 'PAN-T600-IVO',
            collection: 'Trilogy', productName: 'Ivory Lappato', color: 'Ivory', surface: 'Lappato',
            sizeRaw: '60x120', price: 43.0, thicknessMm: 10, sqmPerPiece: 0.72, pcsPerBox: 3,
            // ProductPriceDto's originCountryCode (price_catalog.factories.country) drives the
            // ประเทศต้นทาง autofill below.
            originCountryCode: 'IT',
          }],
        }
        : { items: [] }
    ));
    const { onChange } = renderRow({ unitPrice: '' });

    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy' } });
    // role="option": the catalog typeahead is a listbox.
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
      // Resolved STRAIGHT from the catalogue (resolveTileSqmPerPiece rule (a)): catalogSqmPerPiece is
      // the provenance snapshot, piecesPerSqmDisplay its แผ่น/ตร.ม. reciprocal (round2(1/0.72) = 1.39),
      // and the source is 'catalog', not 'manual'.
      catalogSqmPerPiece: 0.72,
      catalogPriceUnit: null,
      // The picked row's own face size (cm); see pickCatalog's comment. Never sent to the server.
      catalogSizeText: '60x120',
      piecesPerSqmDisplay: 1.39,
      sqmPerPieceSource: 'catalog',
      piecesPerBox: 3,
      // This catalogue row states no ตร.ม./กล่อง, so the field is blank (null), never inherited
      // from what the row carried before the pick.
      sqmPerBox: null,
      // IT -> อิตาลี, plus that country's default lead-time range, both still editable.
      originCountry: 'อิตาลี',
      leadTimeMinDays: 75,
      leadTimeMaxDays: 90,
    });
    // The pick's own patch (the last call) must not carry a `unitPrice` key at all, not even
    // `undefined`, or the parent's `{ ...it, ...patch }` merge would overwrite the row's price.
    const lastPatch = onChange.mock.calls[onChange.mock.calls.length - 1][0];
    expect('unitPrice' in lastPatch).toBe(false);
  });
});

// The catalogue's own แผ่น/ตร.ม. figure must win outright over geometry: production's
// price_catalog.product_prices.sqm_per_piece disagrees with width x height on 8.6% of rows (up to
// 14x on per_linear_m trims).
describe('QuotationItemRow — แผ่น/ตร.ม. resolution (resolveTileSqmPerPiece)', () => {
  // The real disagreement: a 300x600 mesh/mosaic sheet whose covered area is not width x height
  // (geometry says 0.18; the catalogue's figure, from actual coverage, is 0.135).
  it('resolves STRAIGHT FROM THE CATALOGUE, not from width x height geometry', () => {
    const cat = { priceUnit: 'per_sqm', sizeRaw: '300x600', sqmPerPiece: 0.135 };
    expect(resolveTileSqmPerPiece(cat)).toBe(0.135);
  });

  // Typing over a catalog-resolved value is always honoured, and the provenance flips from
  // 'catalog' to 'manual' so the badge stops crediting the catalogue.
  it('a manual edit overrides a catalog-resolved value and is tracked as an override', () => {
    const { onChange } = renderRow({
      catalogPriceId: 42, sqmPerPiece: 0.72, sqmPerPieceSource: 'catalog',
      catalogSqmPerPiece: 0.72, piecesPerSqmDisplay: 1.39,
    });

    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '2' } });

    expect(onChange).toHaveBeenCalledWith({
      piecesPerSqmDisplay: '2',
      // 1 / 2 = 0.5 exactly: no rounding ambiguity.
      sqmPerPiece: 0.5,
      sqmPerPieceSource: 'manual',
    });
  });
});

// ขนาด (ซม.) -> แผ่น/ตร.ม. fallback. The field is labelled ขนาด (ซม.), so its unit is declared
// rather than guessed (unlike the catalogue's free-text column, which mixes cm and mm). See
// sqmPerPieceFromSizeCm in quotationMeta.js and resolveTileSqmPerPiece's doc (rule (d)).
describe('QuotationItemRow — ขนาด (ซม.) → แผ่น/ตร.ม. fallback (owner decision 2026-09-14)', () => {
  function renderBlank(itemOverrides = {}) {
    const onChange = vi.fn();
    const item = { ...emptyQuotationItem(), ...itemOverrides };
    const view = render(<QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()} />);
    return { item, onChange, ...view };
  }

  // A catalogue-resolved row whose แผ่น/ตร.ม. survived a size retype was a production bug: two lines
  // linked to a 600x600mm catalogue row kept 0.36 ตร.ม./แผ่น (and the printed 60x60cm size) after
  // being retyped to "30x60"/"3x60". `catalogSizeText` (set at pick time) lets the row tell "same
  // tile, different unit" (keep the catalogue figure) from "a different tile" (recompute).
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

    it('leaves the catalogue figure UNCHANGED when the retyped size is the SAME tile in millimetres', () => {
      const { onChange } = renderCatalogPicked();
      fireEvent.change(screen.getByLabelText(/^ขนาด \(ซม\.\)/), { target: { value: '600x600' } });
      // Only sizeText patches; sqmPerPiece/source are NOT touched.
      expect(onChange).toHaveBeenLastCalledWith({ sizeText: '600x600' });
    });
  });

  it('the saved payload never carries sqmPerPieceSource (or piecesPerSqmDisplay) -- both are UI-only', () => {
    const item = { ...emptyQuotationItem(), sqmPerPiece: 0.72, sqmPerPieceSource: 'size', piecesPerSqmDisplay: 1.39 };
    const payload = itemInputFromRow(item, 'NET', 'TH');
    expect(payload).not.toHaveProperty('sqmPerPieceSource');
    expect(payload).not.toHaveProperty('piecesPerSqmDisplay');
  });
});

describe('ส่วนลด % — blank by default, and a rep can clear it (owner, 2026-09-13)', () => {
  it('a blank discount still reaches the server as 0 — pricing is unchanged', () => {
    // The server prices null and 0 identically (WastageCalculator), but the payload must never send ''.
    expect(itemInputFromRow({ ...emptyQuotationItem(), discountPct: null }, 'NET').discountPct).toBe(0);
    expect(itemInputFromRow({ ...emptyPlainItem(), discountPct: null }).discountPct).toBe(0);
  });
});

describe('ขายแผ่นไม่เต็มกล่อง — owner-approved "sell loose pieces" (V182, 2026-09-16)', () => {
  function renderRoundLoose(itemOverrides = {}, extraProps = {}) {
    const onChange = vi.fn();
    const item = { ...emptyQuotationItem(), piecesPerBox: 10, piecesFinal: 32, boxes: 3, ...itemOverrides };
    render(<QuotationItemRow item={item} index={0} onChange={onChange} onRemove={vi.fn()} {...extraProps} />);
    return { onChange };
  }

  it('checking it sends roundToFullBox: false', () => {
    const { onChange } = renderRoundLoose();
    fireEvent.click(screen.getByLabelText(/^ขายแผ่นไม่เต็มกล่อง/));
    expect(onChange).toHaveBeenLastCalledWith({ roundToFullBox: false });
  });

  it('typing a ตร.ม./กล่อง value while ticked resets roundToFullBox to true, so the blocked state is unreachable', () => {
    const { onChange } = renderRoundLoose(
      { sqmPerBox: null, roundToFullBox: false, specialPriceSqm: 64 },
      { priceMode: 'SPECIAL_SQM', documentLanguage: 'EN' },
    );
    fireEvent.change(screen.getByLabelText(/^ตร\.ม\.\/กล่อง/), { target: { value: '0.6' } });
    expect(onChange).toHaveBeenLastCalledWith({ sqmPerBox: 0.6, roundToFullBox: true });
  });
});

// The PCR item form reuses this row with hidePricing.
describe('QuotationItemRow — hidePricing (V185)', () => {
  it('renders a price input by default (hidePricing not set) — NET mode', () => {
    renderRow({}, vi.fn());
    expect(screen.getByLabelText(/^ราคา\/ตร\.ม\./)).not.toBeNull();
  });

  // Queries the label the box has now (ราคา/ตร.ม.); asking for the old "ราคา/หน่วย" would pass
  // whether or not hidePricing hid anything.
  it('hides ราคา/ตร.ม. และส่วนลด under hidePricing (NET mode)', () => {
    const item = { ...emptyQuotationItem() };
    render(<QuotationItemRow item={item} index={0} onChange={vi.fn()} onRemove={vi.fn()} hidePricing />);
    expect(screen.queryByLabelText(/^ราคา\/ตร\.ม\./)).toBeNull();
    expect(screen.queryByTestId('list-per-piece-0')).toBeNull();
    expect(screen.queryByLabelText(/^ส่วนลด/)).toBeNull();
  });
});
