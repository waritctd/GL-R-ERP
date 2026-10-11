import { describe, it, expect, beforeEach, vi } from 'vitest';

// Fresh mock per test (same pattern as mockApi.dealQuotations.test.js): develop's slice 2 allows one live
// direct quotation per deal, so each test re-imports the mock and retires any live quotation on the deal.
let api;
let retireLiveDirectQuotationsForTests;
let retireLivePricingRequestsForTests;
beforeEach(async () => {
  vi.resetModules();
  ({ api, retireLiveDirectQuotationsForTests, retireLivePricingRequestsForTests } = await import('./mockApi.js'));
});

// Mirrors DealQuotationService.forViewer / DealQuotationDtos#withoutPriceInternals (ruling 3, 2026-09-30):
// `account` sees a quotation's number, totals and each line's NET price, never list price / discount / CEO pricing.
// The Java DealQuotationOutcomeIntegrationTest is the evidence; this pins that the mock is no MORE permissive.

const ITEM_KEYS = ['unitPrice', 'discountPct', 'specialPriceSqm', 'adjustmentPct', 'adjustmentAmount',
  'catalogPriceId', 'specialPriceLine', 'calculationLine', 'priceChangedFromCeo', 'ceoListUnitPrice',
  'ceoDiscountPct', 'ceoSpecialPriceSqm', 'ceoDirectNetPrice', 'ceoNetUnitPrice',
  // V201 — the per-ตร.ม. prices a Thai row is typed in. Null on every row priced per แผ่น, so in
  // the sweeps below these two are only a guard; the dedicated describe at the end of this file
  // is the case where they are actually populated.
  'listPriceSqm', 'directNetPriceSqm'];
const QUOTATION_KEYS = ['priceMode', 'ceoPriceMode', 'priceModeChangedFromCeo', 'itemsRemovedFromCeoCount', 'removedCeoItems'];
const emptied = (v) => v === null || v === false || v === 0 || (Array.isArray(v) && v.length === 0);

// Seed deal 19 sits at DEPOSIT_NOTICE_ISSUED: inside account's list scope.
const IN_SCOPE_TICKET = 19;

async function createQuotationAsSales() {
  await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' });
  retireLiveDirectQuotationsForTests(IN_SCOPE_TICKET);
  retireLivePricingRequestsForTests(IN_SCOPE_TICKET);
  // Slice 2 (develop): a DEAL_DIRECT create requires a recipient (DESIGNER/OWNER/BUYER).
  const { quotation } = await api.dealQuotations.create(IN_SCOPE_TICKET, { recipientType: 'OWNER', recipientLabel: 'ผู้รับทดสอบ', items: [{
    model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
    quantityMode: 'AREA', areaSqm: 20, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 3, unitPrice: 850,
    discountPct: 10, leadTimeMinDays: 30, leadTimeMaxDays: 45,
  }] });
  return quotation;
}

// Account sees a deal quotation only once it went to the customer (APPROVED for DEAL_DIRECT) -- see the
// visibility describe below -- so the redaction cases need an approved one.
async function createApprovedQuotation() {
  const quotation = await createQuotationAsSales();
  await api.dealQuotations.submit(quotation.id);
  await api.auth.login({ email: 'sales.manager@glr.co.th', password: 'demo1234' });
  await api.dealQuotations.approve(quotation.id, {});
  return quotation;
}

function expectRedacted(q) {
  for (const k of QUOTATION_KEYS) expect(emptied(q[k]), `quotation.${k}=${JSON.stringify(q[k])}`).toBe(true);
  for (const item of q.items) {
    for (const k of ITEM_KEYS) expect(emptied(item[k]), `item.${k}=${JSON.stringify(item[k])}`).toBe(true);
    expect(item.netUnitPrice).not.toBeNull();
    expect(item.lineAmount).not.toBeNull();
  }
  for (const k of ['subtotalAmount', 'vatAmount', 'grandTotal']) expect(q[k], k).not.toBeUndefined();
  expect(q.number).toBeTruthy();
}

describe('mockApi.dealQuotations -- account redaction (mirrors forViewer)', () => {
  it('get and listForTicket give account net prices and totals only', async () => {
    const created = await createApprovedQuotation();
    await api.auth.login({ role: 'account' });

    expectRedacted((await api.dealQuotations.get(created.id)).quotation);
    const listed = (await api.dealQuotations.listForTicket(IN_SCOPE_TICKET)).items.find((q) => q.id === created.id);
    expect(listed).toBeTruthy();
    expectRedacted(listed);
  });

  it('ceo and the sales owner still receive the list price and discount', async () => {
    const created = await createApprovedQuotation();
    for (const email of ['ceo@glr.co.th', 'sales@glr.co.th']) {
      await api.auth.login({ email, password: 'demo1234' });
      const item = (await api.dealQuotations.get(created.id)).quotation.items[0];
      expect(item.unitPrice, `${email} unitPrice`).toBe(850);
      expect(item.discountPct, `${email} discountPct`).toBe(10);
    }
  });

  // list() mirrors DealQuotationService.search: every row goes through forViewer. Account only ever sees quotations
  // on deals it created, and the mock cannot make account own a deal, so this is a guard against a future
  // un-redacted path rather than a proof of the populated case (the Java IT proves that one).
  it('list() rows are redacted for account', async () => {
    await createApprovedQuotation();
    await api.auth.login({ role: 'account' });
    const { items } = await api.dealQuotations.list({});
    for (const q of items) expectRedacted(q);
  });
});

// V201 (sales feedback #1, 2026-10-06) — the two per-ตร.ม. prices are price internals too: ราคาตั้ง
// in another unit, and the VAT-inclusive figure the net is derived from. The Java
// DealQuotationPerSqmPriceIntegrationTest#anAccountCaller_isServedNeitherPerSqmPrice_onAnyReadItCanReach
// is the evidence (real service, real Postgres); this pins only that the mock is no MORE permissive.
describe('mockApi.dealQuotations -- account redaction of prices typed per ตร.ม. (V201)', () => {
  async function createApprovedPerSqmQuotation() {
    await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' });
    retireLiveDirectQuotationsForTests(IN_SCOPE_TICKET);
    retireLivePricingRequestsForTests(IN_SCOPE_TICKET);
    const { quotation } = await api.dealQuotations.create(IN_SCOPE_TICKET, {
      recipientType: 'OWNER', recipientLabel: 'ผู้รับทดสอบ', priceMode: 'DIRECT_NET',
      items: [{
        model: 'Trilogy', color: 'Ivory', texture: 'Lappato', sizeText: '60x120', thicknessMm: 10, sqmPerPiece: 0.72,
        quantityMode: 'PIECES', piecesInput: 10, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 2,
        listPriceSqm: 1800, directNetPriceSqm: 790, leadTimeMinDays: 30, leadTimeMaxDays: 45,
      }],
    });
    await api.dealQuotations.submit(quotation.id);
    await api.auth.login({ email: 'sales.manager@glr.co.th', password: 'demo1234' });
    await api.dealQuotations.approve(quotation.id, {});
    return quotation;
  }

  it('account is served neither figure, on get or listForTicket — the owning rep still is', async () => {
    const created = await createApprovedPerSqmQuotation();
    expect(created.items[0]).toMatchObject({ listPriceSqm: 1800, directNetPriceSqm: 790 });

    await api.auth.login({ role: 'account' });
    const viaGet = (await api.dealQuotations.get(created.id)).quotation;
    const viaList = (await api.dealQuotations.listForTicket(IN_SCOPE_TICKET)).items.find((q) => q.id === created.id);
    for (const q of [viaGet, viaList]) {
      expectRedacted(q);
      expect(q.items[0].listPriceSqm).toBeNull();
      expect(q.items[0].directNetPriceSqm).toBeNull();
    }

    await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' });
    expect((await api.dealQuotations.get(created.id)).quotation.items[0])
      .toMatchObject({ listPriceSqm: 1800, directNetPriceSqm: 790 });
  });
});

// V204 (mixed price modes; sales feedback #14, owner 2026-10-06) — on a MIXED document each tile
// row carries the mode it was priced in. That is the document's priceMode, row by row — which the
// account view already withholds — so it is withheld too; otherwise this view would say, line by
// line, which products went out at a ราคาพิเศษ. The Java
// DealQuotationMixedPriceModeIntegrationTest#anAccountCaller_isNotToldTheDocumentIsMixed_norWhichModeAnyRowIsIn
// is the evidence (real service, real Postgres); this pins only that the mock is no MORE permissive.
describe('mockApi.dealQuotations -- account redaction of a row\'s own price mode (V204)', () => {
  const TILE = {
    model: 'Trilogy', color: 'Ivory', texture: 'Lappato', sizeText: '60x120', thicknessMm: 10, sqmPerPiece: 0.72,
    quantityMode: 'PIECES', piecesInput: 10, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 2,
    leadTimeMinDays: 30, leadTimeMaxDays: 45,
  };

  it('account is told neither that the document is mixed nor any row\'s mode — the owning rep still is', async () => {
    await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' });
    retireLiveDirectQuotationsForTests(IN_SCOPE_TICKET);
    retireLivePricingRequestsForTests(IN_SCOPE_TICKET);
    const { quotation: created } = await api.dealQuotations.create(IN_SCOPE_TICKET, {
      recipientType: 'OWNER', recipientLabel: 'ผู้รับทดสอบ', priceMode: 'MIXED',
      items: [
        { ...TILE, priceMode: 'NET', listPriceSqm: 1800, discountPct: 20 },
        { ...TILE, priceMode: 'DIRECT_NET', listPriceSqm: 1800, directNetPriceSqm: 790 },
      ],
    });
    expect(created.items.map((it) => it.priceMode)).toEqual(['NET', 'DIRECT_NET']);
    await api.dealQuotations.submit(created.id);
    await api.auth.login({ email: 'sales.manager@glr.co.th', password: 'demo1234' });
    await api.dealQuotations.approve(created.id, {});

    await api.auth.login({ role: 'account' });
    const viaGet = (await api.dealQuotations.get(created.id)).quotation;
    const viaList = (await api.dealQuotations.listForTicket(IN_SCOPE_TICKET)).items.find((q) => q.id === created.id);
    for (const q of [viaGet, viaList]) {
      expectRedacted(q);
      expect(q.priceMode).toBeNull();
      expect(q.items.map((it) => it.priceMode)).toEqual([null, null]);
    }

    await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' });
    const forOwner = (await api.dealQuotations.get(created.id)).quotation;
    expect(forOwner.priceMode).toBe('MIXED');
    expect(forOwner.items.map((it) => it.priceMode)).toEqual(['NET', 'DIRECT_NET']);
  });
});

describe('mockApi.dealQuotations -- account sees only quotations that went to the customer', () => {
  it('a DRAFT is 403 on get and absent from listForTicket; ceo and the owner still see it', async () => {
    const draft = await createQuotationAsSales();
    await api.auth.login({ role: 'account' });
    await expect(api.dealQuotations.get(draft.id)).rejects.toMatchObject({ status: 403 });
    expect((await api.dealQuotations.listForTicket(IN_SCOPE_TICKET)).items.map((q) => q.id)).not.toContain(draft.id);
    for (const email of ['ceo@glr.co.th', 'sales@glr.co.th']) {
      await api.auth.login({ email, password: 'demo1234' });
      expect((await api.dealQuotations.get(draft.id)).quotation.id).toBe(draft.id);
    }
  });

  it('an APPROVED quotation is visible to account', async () => {
    const approved = await createApprovedQuotation();
    await api.auth.login({ role: 'account' });
    expect((await api.dealQuotations.get(approved.id)).quotation.id).toBe(approved.id);
    expect((await api.dealQuotations.listForTicket(IN_SCOPE_TICKET)).items.map((q) => q.id)).toContain(approved.id);
  });
});
