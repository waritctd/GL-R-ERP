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
  'ceoDiscountPct', 'ceoSpecialPriceSqm', 'ceoDirectNetPrice', 'ceoNetUnitPrice'];
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
