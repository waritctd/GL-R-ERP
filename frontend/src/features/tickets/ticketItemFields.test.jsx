import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../api/index.js';
import {
  applyCatalogPick,
  applyDescriptiveFieldEdit,
  isRequiredItemField,
  isStockSalePriceMissing,
  ITEM_FIELD_META,
  missingQtyMessage,
  missingStockSalePriceMessage,
  REQUIRED_ITEM_FIELD_LABELS,
  requiredItemFieldErrors,
  requiredQtyField,
  searchCatalog,
  stockSalePriceError,
} from './ticketItemFields.jsx';

// fix/ticket-edit-items-required-markers: TicketCreateModal.jsx and TicketDetailPage.jsx both used
// to carry byte-identical (create) or hand-rolled/drifted (edit) copies of this logic. This file
// pins the pure pieces that were lifted out — required fields, the qty/qtySqm basis rule, and the
// catalog-pick/hand-edit row mappings — plus a source-text guard (bottom of the file) that neither
// surface re-inlines its own copy.

describe('REQUIRED_ITEM_FIELD_LABELS', () => {
  it('is exactly brand/model/size — สี and เนื้อผิว are deliberately excluded', () => {
    expect(Object.keys(REQUIRED_ITEM_FIELD_LABELS).sort()).toEqual(['brand', 'model', 'size']);
  });

  it('isRequiredItemField matches the same set', () => {
    expect(isRequiredItemField('brand')).toBe(true);
    expect(isRequiredItemField('model')).toBe(true);
    expect(isRequiredItemField('size')).toBe(true);
    expect(isRequiredItemField('color')).toBe(false);
    expect(isRequiredItemField('texture')).toBe(false);
    expect(isRequiredItemField('qty')).toBe(false);
  });
});

describe('requiredItemFieldErrors', () => {
  it('reports a กรุณากรอก<label> message for every blank required field', () => {
    const errors = requiredItemFieldErrors({ brand: '', model: '', color: 'ขาว', texture: '', size: '' });
    expect(errors).toEqual({
      brand: 'กรุณากรอกยี่ห้อ / โรงงาน',
      model: 'กรุณากรอกชื่อรุ่น / Collection',
      size: 'กรุณากรอกขนาด',
    });
  });

  it('treats whitespace-only as blank (trims before checking)', () => {
    const errors = requiredItemFieldErrors({ brand: '   ', model: 'A1', size: '60x60' });
    expect(errors).toEqual({ brand: 'กรุณากรอกยี่ห้อ / โรงงาน' });
  });

  it('reports nothing when every required field is filled', () => {
    expect(requiredItemFieldErrors({ brand: 'SCG', model: 'A1', size: '60x60' })).toEqual({});
  });

  it('never reports สี/เนื้อผิว even when blank', () => {
    const errors = requiredItemFieldErrors({ brand: 'SCG', model: 'A1', size: '60x60', color: '', texture: '' });
    expect(errors).toEqual({});
  });

  it('tolerates missing keys (undefined) the same as blank', () => {
    expect(requiredItemFieldErrors({})).toEqual({
      brand: 'กรุณากรอกยี่ห้อ / โรงงาน',
      model: 'กรุณากรอกชื่อรุ่น / Collection',
      size: 'กรุณากรอกขนาด',
    });
  });
});

describe('requiredQtyField / missingQtyMessage — PIECE vs SQM basis', () => {
  it('PIECE (and no basis at all) requires qty', () => {
    expect(requiredQtyField('PIECE')).toBe('qty');
    expect(requiredQtyField(undefined)).toBe('qty');
    expect(requiredQtyField(null)).toBe('qty');
  });

  it('SQM requires qtySqm', () => {
    expect(requiredQtyField('SQM')).toBe('qtySqm');
  });

  it('missingQtyMessage names the right field and row number', () => {
    expect(missingQtyMessage('PIECE', 2)).toBe('กรุณากรอกจำนวน (แผ่น) ในรายการที่ 2');
    expect(missingQtyMessage('SQM', 2)).toBe('กรุณากรอกพื้นที่ (ตร.ม.) ในรายการที่ 2');
  });
});

// V183 (stock-sourced deal-line pricing, owner ruling): sales may flag a deal line "จากสต็อก"
// and type in its own selling price. CAPTURE-ONLY today — nothing downstream reads this pair
// yet, so a flagged line can still be attached to an ordinary PricingRequest exactly as before.
// Shared by TicketCreateModal.jsx (create-time) and TicketDetailPage.jsx (edit-items mode).
describe('isStockSalePriceMissing / stockSalePriceError / missingStockSalePriceMessage', () => {
  it('not missing when the item is not flagged, regardless of price', () => {
    expect(isStockSalePriceMissing({ sourcedFromStock: false, stockSalePrice: null })).toBe(false);
    expect(isStockSalePriceMissing({ sourcedFromStock: false, stockSalePrice: '' })).toBe(false);
    expect(isStockSalePriceMissing({})).toBe(false);
  });

  it('missing when flagged and the price is empty/null/zero/negative', () => {
    expect(isStockSalePriceMissing({ sourcedFromStock: true, stockSalePrice: '' })).toBe(true);
    expect(isStockSalePriceMissing({ sourcedFromStock: true, stockSalePrice: null })).toBe(true);
    expect(isStockSalePriceMissing({ sourcedFromStock: true, stockSalePrice: 0 })).toBe(true);
    expect(isStockSalePriceMissing({ sourcedFromStock: true, stockSalePrice: '-5' })).toBe(true);
  });

  it('not missing when flagged and a positive price is set (string or number)', () => {
    expect(isStockSalePriceMissing({ sourcedFromStock: true, stockSalePrice: '420.50' })).toBe(false);
    expect(isStockSalePriceMissing({ sourcedFromStock: true, stockSalePrice: 420.5 })).toBe(false);
  });

  // Matches TicketService.isStorablePositivePrice exactly: stock_sale_price is numeric(14,2), and
  // Postgres rounds HALF_UP to that scale BEFORE chk_ticket_item_stock_sale_price sees the value —
  // 0.004 stores as 0.00 (rejected by the CHECK), 0.005 stores as 0.01 (accepted). A plain
  // `Number(price) > 0` would accept 0.004 here and then have the server 400/500 it.
  it('sub-cent boundary matches Postgres numeric(14,2) HALF_UP rounding (0.004 missing, 0.005 not)', () => {
    expect(isStockSalePriceMissing({ sourcedFromStock: true, stockSalePrice: '0.004' })).toBe(true);
    expect(isStockSalePriceMissing({ sourcedFromStock: true, stockSalePrice: 0.004 })).toBe(true);
    expect(isStockSalePriceMissing({ sourcedFromStock: true, stockSalePrice: '0.005' })).toBe(false);
    expect(isStockSalePriceMissing({ sourcedFromStock: true, stockSalePrice: 0.005 })).toBe(false);
  });

  it('missingStockSalePriceMessage names the row (create modal convention)', () => {
    expect(missingStockSalePriceMessage(3)).toBe('กรุณากรอกราคาขายเมื่อเลือกสินค้าจากสต็อก ในรายการที่ 3');
  });

  it('stockSalePriceError has no row-number suffix (edit-page inline convention, matches requiredItemFieldErrors)', () => {
    expect(stockSalePriceError({ sourcedFromStock: true, stockSalePrice: '' }))
      .toBe('กรุณากรอกราคาขายเมื่อเลือกสินค้าจากสต็อก');
    expect(stockSalePriceError({ sourcedFromStock: true, stockSalePrice: 100 })).toBeNull();
    expect(stockSalePriceError({ sourcedFromStock: false, stockSalePrice: null })).toBeNull();
  });
});

describe('ITEM_FIELD_META', () => {
  it('carries the canonical labels both surfaces render', () => {
    expect(ITEM_FIELD_META.brand.label).toBe('ยี่ห้อ / โรงงาน');
    expect(ITEM_FIELD_META.model.label).toBe('ชื่อรุ่น / Collection');
    expect(ITEM_FIELD_META.size.label).toBe('ขนาด');
    expect(ITEM_FIELD_META.color.label).toBe('สี');
    expect(ITEM_FIELD_META.texture.label).toBe('เนื้อผิว');
  });
});

describe('applyCatalogPick', () => {
  function mockCatalogProduct(overrides = {}) {
    return {
      priceId: 501,
      productCode: 'BNFJ30126CA',
      factoryName: 'Bode',
      grade: null,
      collection: 'Stone gallary',
      productName: null,
      color: null,
      surface: 'MATT',
      sizeRaw: '600x1200',
      price: 8.8,
      currency: 'USD',
      priceUnit: 'per_sqm',
      sqmPerPiece: null,
      ...overrides,
    };
  }

  it('maps factory name into BOTH brand and factory', () => {
    const result = applyCatalogPick({ brand: '', factory: '', qty: '' }, mockCatalogProduct());
    expect(result.brand).toBe('Bode');
    expect(result.factory).toBe('Bode');
  });

  it('never substitutes grade or product code for a blank colour — blank stays blank', () => {
    const result = applyCatalogPick(
      { brand: '', qty: '' },
      mockCatalogProduct({ grade: 'A01', color: null }),
    );
    expect(result.color).toBe('');
    expect(result.brand).not.toBe('A01');
    expect(result.catalogGrade).toBe('A01');
  });

  it('carries the catalog link fields for persistence (V110)', () => {
    const result = applyCatalogPick({ brand: '', qty: '' }, mockCatalogProduct());
    expect(result.source).toBe('catalog');
    expect(result.catalogPriceId).toBe(501);
    expect(result.catalogProductCode).toBe('BNFJ30126CA');
    expect(result.catalogPrice).toBe(8.8);
    expect(result.catalogCurrency).toBe('USD');
    expect(result.catalogPriceUnit).toBe('per_sqm');
  });

  it('resolves sqmPerPiece from the catalog row and cross-fills qtySqm from an existing qty', () => {
    const result = applyCatalogPick({ brand: '', qty: 10 }, mockCatalogProduct({ sqmPerPiece: 0.72 }));
    expect(result.sqmPerPiece).toBe(0.72);
    expect(result.qtySqm).toBe('7.200');
  });

  it('resolves to null (never guessed from free-text size) when the catalog row has none', () => {
    const result = applyCatalogPick({ brand: '', qty: 10 }, mockCatalogProduct({ sqmPerPiece: null }));
    expect(result.sqmPerPiece).toBeNull();
    expect(result.qtySqm).toBe('');
  });
});

describe('applyDescriptiveFieldEdit', () => {
  const catalogLinkedItem = {
    brand: 'Bode', factory: 'Bode', model: 'Stone gallary', color: '', texture: 'MATT', size: '600x1200',
    source: 'catalog', catalogPriceId: 501, catalogProductCode: 'BNFJ30126CA',
    catalogPrice: 8.8, catalogCurrency: 'USD', catalogPriceUnit: 'per_sqm', catalogGrade: null,
  };

  it('a brand hand-edit sets factory to the same value (ยี่ห้อ/โรงงาน lockstep)', () => {
    const result = applyDescriptiveFieldEdit(catalogLinkedItem, 'brand', 'Panaria');
    expect(result.brand).toBe('Panaria');
    expect(result.factory).toBe('Panaria');
  });

  it('a descriptive hand-edit clears every catalog-link field', () => {
    const result = applyDescriptiveFieldEdit(catalogLinkedItem, 'color', 'เทาเข้ม');
    expect(result.color).toBe('เทาเข้ม');
    expect(result.source).toBe('custom');
    expect(result.catalogPriceId).toBeNull();
    expect(result.catalogProductCode).toBe('');
    expect(result.catalogPrice).toBeNull();
    expect(result.catalogCurrency).toBeNull();
    expect(result.catalogPriceUnit).toBeNull();
    expect(result.catalogGrade).toBeNull();
  });

  it('every descriptive field triggers the same clear — model/texture/size too', () => {
    for (const field of ['model', 'texture', 'size']) {
      const result = applyDescriptiveFieldEdit(catalogLinkedItem, field, 'x');
      expect(result.source, `field ${field}`).toBe('custom');
      expect(result.catalogPriceId, `field ${field}`).toBeNull();
    }
  });

  it('a non-descriptive field (qty) is a plain assign — no catalog-link clearing', () => {
    const result = applyDescriptiveFieldEdit(catalogLinkedItem, 'qty', 5);
    expect(result.qty).toBe(5);
    expect(result.source).toBe('catalog');
    expect(result.catalogPriceId).toBe(501);
  });
});

// Review round 2 (NIT, cheap-only): a stale-response guard on searchCatalog's fetch — the catalog
// endpoint has no ordering guarantee, so two searches issued close together can each fire their own
// fetch, and a slower EARLIER one resolving after a faster LATER one must not overwrite the fresher
// dropdown results with stale ones.
describe('searchCatalog — stale-response guard', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('drops an earlier request\'s results when a later request has already fired, even though the earlier one resolves last', async () => {
    vi.useFakeTimers();
    let resolveFirst;
    let resolveSecond;
    const firstResponse = new Promise((resolve) => { resolveFirst = resolve; });
    const secondResponse = new Promise((resolve) => { resolveSecond = resolve; });
    const pricesSpy = vi.spyOn(api.catalog, 'prices')
      .mockImplementationOnce(() => firstResponse)
      .mockImplementationOnce(() => secondResponse);

    const onResults = vi.fn();
    // Two searches spaced further apart than the 280ms debounce, so BOTH actually fire their own
    // fetch instead of the second's debounce simply cancelling the first's timer.
    searchCatalog('first query', onResults);
    await vi.advanceTimersByTimeAsync(280);
    searchCatalog('second query', onResults);
    await vi.advanceTimersByTimeAsync(280);
    expect(pricesSpy).toHaveBeenCalledTimes(2);

    // Timers are done — the rest is plain promise resolution, deliberately in NETWORK-REORDERED
    // order: the second (later-issued) request's response arrives first, then the first
    // (earlier-issued) one arrives last, simulating exactly the reordering that motivated the guard.
    vi.useRealTimers();
    resolveSecond({ items: [{ priceId: 2, factoryName: 'Second (fresh)' }] });
    await Promise.resolve();
    await Promise.resolve();
    resolveFirst({ items: [{ priceId: 1, factoryName: 'First (stale)' }] });
    await Promise.resolve();
    await Promise.resolve();

    // Only the second (latest-issued) request's results may ever reach the callback — the first's
    // late-arriving response must be dropped, not appended or allowed to overwrite it.
    expect(onResults).toHaveBeenCalledTimes(1);
    expect(onResults).toHaveBeenCalledWith([{ priceId: 2, factoryName: 'Second (fresh)' }]);
  });
});

// ── shared-module usage pin ─────────────────────────────────────────────────
// The bug this branch fixes was exactly this: TicketCreateModal.jsx had ItemFieldLabel/ItemField/
// CatalogOption/CatalogAutocompleteField/REQUIRED_ITEM_FIELD_LABELS, and TicketDetailPage.jsx had
// none of it — its own hand-rolled fields, no asterisks. A source-text guard is what stops the two
// surfaces re-diverging the next time someone "quickly" adds a field inline instead of touching the
// shared module — a behavioural test could not catch that regression (the page would still render;
// it just would have silently forked the labels/rules again).
describe('shared usage pin — TicketCreateModal.jsx and TicketDetailPage.jsx both import from here, neither re-inlines', () => {
  function readSource(relativePath) {
    return readFileSync(resolve(process.cwd(), relativePath), 'utf8');
  }

  const files = {
    'TicketCreateModal.jsx': readSource('src/features/tickets/TicketCreateModal.jsx'),
    'TicketDetailPage.jsx': readSource('src/features/tickets/TicketDetailPage.jsx'),
  };

  for (const [name, source] of Object.entries(files)) {
    it(`${name} imports CatalogAutocompleteField, ITEM_FIELD_META and REQUIRED_ITEM_FIELD_LABELS from ticketItemFields.jsx`, () => {
      // A single import statement is not required — just that these names are pulled in from the
      // shared module somewhere in the file's import section. ITEM_FIELD_META is required
      // alongside the components: a reviewer found that importing CatalogAutocompleteField alone
      // does NOT stop someone hand-typing a wrong literal into its `label` prop (the component
      // itself accepts any string) — see the rendered-label assertions in
      // TicketDetailPage.test.jsx's "edit-items required fields" describe block, which is the
      // guard that actually catches that class of regression; this import check is necessary but
      // not sufficient on its own.
      const importBlockMatch = source.match(/import\s*\{([\s\S]*?)\}\s*from\s*'\.\/ticketItemFields\.jsx';/);
      expect(importBlockMatch, `${name} has no import from ./ticketItemFields.jsx`).toBeTruthy();
      const importedNames = importBlockMatch[1];
      expect(importedNames).toMatch(/\bCatalogAutocompleteField\b/);
      expect(importedNames).toMatch(/\bREQUIRED_ITEM_FIELD_LABELS\b/);
      expect(importedNames).toMatch(/\bItemField\b/);
      expect(importedNames).toMatch(/\bITEM_FIELD_META\b/);
    });

    it(`${name} does not locally define/re-declare ItemFieldLabel/ItemField/CatalogOption/CatalogAutocompleteField/REQUIRED_ITEM_FIELD_LABELS`, () => {
      // function/const/let/class — not just `function`. A local `const ItemFieldLabel = (...) =>`
      // or `const REQUIRED_ITEM_FIELD_LABELS = {...}` re-inlines exactly as effectively as a
      // `function` declaration would, and the earlier version of this guard only caught the latter.
      const localRedeclaration = /(?:function|const|let|class)\s+(ItemFieldLabel|ItemField|CatalogOption|CatalogAutocompleteField|REQUIRED_ITEM_FIELD_LABELS)\b/;
      const match = source.match(localRedeclaration);
      expect(match, `${name} re-declares ${match?.[1]} locally`).toBeNull();
    });
  }
});
