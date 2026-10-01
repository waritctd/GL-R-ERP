import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// CR-1 (GLA-167) mock coverage: the factory-contact step ("ติดต่อโรงงานแล้ว", replaces send), the
// locked currency/price unit, and the lead-time change flow. Mirrors FactoryQuoteService#markContacted,
// #receive and LeadTimeChangeService. This proves the mock's PLUMBING and DTO shapes only — authz in
// mockApi is not authoritative (see CLAUDE.md "Mock API contract"); the real checks are the Java
// integration tests for those two services.

const todayBangkok = () => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Bangkok' }).format(new Date());
const tomorrowBangkok = () => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Bangkok' })
  .format(new Date(Date.now() + 24 * 3600 * 1000));

function nextClientRequestId() {
  return crypto.randomUUID();
}

function lineInput(sourceItemId, extra = {}) {
  return {
    sourceTicketItemId: sourceItemId,
    productId: 1,
    brand: 'SCG',
    model: 'Tile CR1 Mock',
    factory: 'Panaria SpA',
    color: 'ขาว',
    texture: 'ด้าน',
    size: '60x60',
    thicknessMm: 10,
    sqmPerPiece: 0.36,
    quantityMode: 'PIECES',
    piecesInput: 10,
    wastageMode: 'NONE',
    piecesPerBox: 4,
    roundToFullBox: false,
    originCountry: 'อิตาลี',
    leadTimeMinDays: 75,
    leadTimeMaxDays: 90,
    quantityType: 'ESTIMATE',
    requestedCurrency: 'EUR',
    requestedPriceUnitBasis: 'PER_SQM',
    ...extra,
  };
}

/** A submitted + picked-up PCR with one EUR / PER_SQM line, drafts generated, session = import. */
async function pcrWithDraftQuote({ lineExtra = {} } = {}) {
  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท CR1 Mock ${nextClientRequestId()}`,
    taxId: '0100000000198',
    address: '999 ถนนทดสอบ',
    phone: '02-111-1298',
  });
  const { project } = await api.customers.createProject(customer.id, { name: 'โครงการ CR1 Mock' });
  const { contact } = await api.customers.createContact(customer.id, {
    firstName: 'สมชาย', lastName: 'ทดสอบ', phone: '081-000-0000', email: 'cr1-mock@example.com',
  });
  const { ticket: created } = await api.tickets.create({
    entryChannel: 'DESIGNER_LED',
    title: 'ดีล CR1 Mock',
    priority: 'NORMAL',
    customerName: customer.name,
    customerId: customer.id,
    projectId: project.id,
    contactId: contact.id,
    items: [{ brand: 'SCG', model: 'Tile CR1 Mock', qty: 10, currency: 'THB' }],
  });
  const ticketId = created.summary.id;
  const { pricingRequest: draftPr } = await api.pricingRequests.create(ticketId, {
    recipientType: 'DESIGNER',
    recipientLabel: 'ผู้ออกแบบ CR1',
    clientRequestId: nextClientRequestId(),
    items: [lineInput(created.items[0].id, lineExtra)],
  });
  const prId = draftPr.summary.id;
  await api.pricingRequests.submit(prId);
  await api.auth.login({ role: 'import' });
  await api.pricingRequests.pickup(prId);
  const { items: quotes } = await api.pricingRequests.generateFactoryEmailDrafts(prId);
  return { ticketId, prId, quote: quotes[0] };
}

function receivePayload(quote, overrides = {}) {
  return {
    clientRequestId: nextClientRequestId(),
    defaultCurrency: 'EUR',
    items: [{
      pricingRequestItemId: quote.items[0].pricingRequestItemId,
      quotedQuantity: 10,
      quotedUnit: 'PER_SQM',
      unitBasis: 'PER_SQM',
      rawUnitPrice: 12.5,
      currency: 'EUR',
      sqmPerUnit: 0.36,
      ...overrides,
    }],
  };
}

describe('mockApi pricing request items carry the sales-fixed currency and price unit (F4)', () => {
  it('echoes requestedCurrency / requestedPriceUnitBasis on the item DTO', async () => {
    const { prId } = await pcrWithDraftQuote();
    const { pricingRequest } = await api.pricingRequests.get(prId);
    expect(pricingRequest.items[0].requestedCurrency).toBe('EUR');
    expect(pricingRequest.items[0].requestedPriceUnitBasis).toBe('PER_SQM');
  });

  it('seeds the factory draft from those terms (currency + unit basis), not THB / the quantity unit', async () => {
    const { quote } = await pcrWithDraftQuote();
    expect(quote.defaultCurrency).toBe('EUR');
    expect(quote.items[0].currency).toBe('EUR');
    expect(quote.items[0].unitBasis).toBe('PER_SQM');
  });
});

describe('mockApi.pricingRequests.markFactoryQuoteContacted (replaces sendFactoryQuote)', () => {
  it('no longer exposes sendFactoryQuote', () => {
    expect(api.pricingRequests.sendFactoryQuote).toBeUndefined();
    expect(typeof api.pricingRequests.markFactoryQuoteContacted).toBe('function');
  });

  it('moves DRAFT to REQUESTED, records date/note/actor, and promotes the request', async () => {
    const { prId, quote } = await pcrWithDraftQuote();
    const { factoryQuote } = await api.pricingRequests.markFactoryQuoteContacted(quote.id, {
      contactedOn: todayBangkok(), note: 'โทรคุณ Marco',
    });
    expect(factoryQuote.status).toBe('REQUESTED');
    expect(factoryQuote.contactedOn).toBe(todayBangkok());
    expect(factoryQuote.contactedNote).toBe('โทรคุณ Marco');
    expect(factoryQuote.contactedBy).toBeTruthy();
    expect(factoryQuote.contactedAt).toBeTruthy();
    const { pricingRequest } = await api.pricingRequests.get(prId);
    expect(pricingRequest.summary.status).toBe('AWAITING_FACTORY_RESPONSE');
  });

  it('is final: a second call is a 409 and never rewrites the recorded date or note (R7, no undo)', async () => {
    const { quote } = await pcrWithDraftQuote();
    await api.pricingRequests.markFactoryQuoteContacted(quote.id, { contactedOn: todayBangkok(), note: 'ครั้งแรก' });
    await expect(api.pricingRequests.markFactoryQuoteContacted(quote.id, { contactedOn: todayBangkok(), note: 'ครั้งสอง' }))
      .rejects.toMatchObject({ status: 409 });
    const { factoryQuote } = await api.pricingRequests.getFactoryQuote(quote.id);
    expect(factoryQuote.contactedNote).toBe('ครั้งแรก');
  });

  it('rejects a future date and a missing date with 400', async () => {
    const { quote } = await pcrWithDraftQuote();
    await expect(api.pricingRequests.markFactoryQuoteContacted(quote.id, { contactedOn: tomorrowBangkok() }))
      .rejects.toMatchObject({ status: 400 });
    await expect(api.pricingRequests.markFactoryQuoteContacted(quote.id, {}))
      .rejects.toMatchObject({ status: 400 });
  });

  it('is import + CEO only (B-R1): sales is refused, the CEO may mark contacted and edit the email draft', async () => {
    const { quote } = await pcrWithDraftQuote();
    await api.auth.login({ role: 'sales' });
    await expect(api.pricingRequests.markFactoryQuoteContacted(quote.id, { contactedOn: todayBangkok() }))
      .rejects.toMatchObject({ status: 403 });
    await api.auth.login({ role: 'ceo' });
    await expect(api.pricingRequests.updateFactoryQuote(quote.id, { emailSubject: 'หัวข้อใหม่' })).resolves.toBeTruthy();
    const { factoryQuote } = await api.pricingRequests.markFactoryQuoteContacted(quote.id, { contactedOn: todayBangkok() });
    expect(factoryQuote.status).toBe('REQUESTED');
  });

  it('lets the CEO generate the email drafts too (B-R1)', async () => {
    const { prId } = await pcrWithDraftQuote();
    await api.auth.login({ role: 'ceo' });
    await expect(api.pricingRequests.generateFactoryEmailDrafts(prId)).resolves.toBeTruthy();
  });
});

describe('mockApi.pricingRequests.receiveFactoryQuote — price entry locked until contacted, terms locked (B-R2, R1)', () => {
  it('refuses a price on a DRAFT (not yet contacted) quote with 409', async () => {
    const { quote } = await pcrWithDraftQuote();
    await expect(api.pricingRequests.receiveFactoryQuote(quote.id, receivePayload(quote)))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining('ติดต่อโรงงานแล้ว') });
  });

  it('accepts the price once contacted, when currency and unit match the sales terms', async () => {
    const { quote } = await pcrWithDraftQuote();
    await api.pricingRequests.markFactoryQuoteContacted(quote.id, { contactedOn: todayBangkok() });
    const { factoryQuote } = await api.pricingRequests.receiveFactoryQuote(quote.id, receivePayload(quote));
    expect(factoryQuote.status).toBe('RESPONSE_RECEIVED');
  });

  it('409s a different currency than the line requested', async () => {
    const { quote } = await pcrWithDraftQuote();
    await api.pricingRequests.markFactoryQuoteContacted(quote.id, { contactedOn: todayBangkok() });
    await expect(api.pricingRequests.receiveFactoryQuote(quote.id, receivePayload(quote, { currency: 'USD' })))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining('สกุลเงิน') });
  });

  it('409s a different price unit than the line requested', async () => {
    const { quote } = await pcrWithDraftQuote();
    await api.pricingRequests.markFactoryQuoteContacted(quote.id, { contactedOn: todayBangkok() });
    await expect(api.pricingRequests.receiveFactoryQuote(quote.id, receivePayload(quote, { unitBasis: 'PER_PIECE', quotedUnit: 'PER_PIECE' })))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining('หน่วยราคา') });
  });

  it('accepts any currency/unit for a legacy line that carries no requested terms', async () => {
    const { quote } = await pcrWithDraftQuote({ lineExtra: { requestedCurrency: null, requestedPriceUnitBasis: null } });
    await api.pricingRequests.markFactoryQuoteContacted(quote.id, { contactedOn: todayBangkok() });
    await expect(api.pricingRequests.receiveFactoryQuote(quote.id, receivePayload(quote, { currency: 'USD', unitBasis: 'PER_PIECE', quotedUnit: 'PER_PIECE' })))
      .resolves.toBeTruthy();
  });
});

describe('mockApi.leadTimeChanges (R2 / R10, option C)', () => {
  async function contactedQuote() {
    const ctx = await pcrWithDraftQuote();
    await api.pricingRequests.markFactoryQuoteContacted(ctx.quote.id, { contactedOn: todayBangkok() });
    return ctx;
  }
  const body = (quote, over = {}) => ({
    reason: 'โรงงานแจ้งเลื่อนกำหนดผลิต',
    lines: [{ pricingRequestItemId: quote.items[0].pricingRequestItemId, newMinDays: 120, newMaxDays: 150 }],
    ...over,
  });

  it('create (import) snapshots the old range and is PENDING at version 1', async () => {
    const { quote } = await contactedQuote();
    const { leadTimeChange } = await api.leadTimeChanges.create(quote.id, body(quote));
    expect(leadTimeChange.status).toBe('PENDING');
    expect(leadTimeChange.version).toBe(1);
    expect(leadTimeChange.factoryQuoteId).toBe(quote.id);
    expect(leadTimeChange.lines[0]).toMatchObject({ oldMinDays: 75, oldMaxDays: 90, newMinDays: 120, newMaxDays: 150 });
  });

  it('does not touch the pricing-request line until approved (non-blocking, old value stands)', async () => {
    const { prId, quote } = await contactedQuote();
    await api.leadTimeChanges.create(quote.id, body(quote));
    const { pricingRequest } = await api.pricingRequests.get(prId);
    expect(pricingRequest.items[0].leadTimeMinDays).toBe(75);
  });

  it('allows only one PENDING change per factory quote (409)', async () => {
    const { quote } = await contactedQuote();
    await api.leadTimeChanges.create(quote.id, body(quote));
    await expect(api.leadTimeChanges.create(quote.id, body(quote))).rejects.toMatchObject({ status: 409 });
  });

  it('validates reason, min <= max and at least one line (400)', async () => {
    const { quote } = await contactedQuote();
    await expect(api.leadTimeChanges.create(quote.id, body(quote, { reason: '  ' }))).rejects.toMatchObject({ status: 400 });
    await expect(api.leadTimeChanges.create(quote.id, body(quote, { lines: [] }))).rejects.toMatchObject({ status: 400 });
    await expect(api.leadTimeChanges.create(quote.id, body(quote, {
      lines: [{ pricingRequestItemId: quote.items[0].pricingRequestItemId, newMinDays: 150, newMaxDays: 120 }],
    }))).rejects.toMatchObject({ status: 400 });
  });

  it('update (import, PENDING) bumps the version; withdraw frees the factory for a new request', async () => {
    const { quote } = await contactedQuote();
    const { leadTimeChange } = await api.leadTimeChanges.create(quote.id, body(quote));
    const { leadTimeChange: edited } = await api.leadTimeChanges.update(leadTimeChange.id, body(quote, { reason: 'แก้ไขเหตุผล' }));
    expect(edited.version).toBe(2);
    expect(edited.reason).toBe('แก้ไขเหตุผล');
    const { leadTimeChange: withdrawn } = await api.leadTimeChanges.withdraw(leadTimeChange.id);
    expect(withdrawn.status).toBe('WITHDRAWN');
    await expect(api.leadTimeChanges.create(quote.id, body(quote))).resolves.toBeTruthy();
  });

  it('approve (owning rep) writes the new range onto the pricing-request line and records the decision', async () => {
    const { prId, quote } = await contactedQuote();
    const { leadTimeChange } = await api.leadTimeChanges.create(quote.id, body(quote));
    await api.auth.login({ role: 'sales' });
    const { leadTimeChange: approved } = await api.leadTimeChanges.approve(leadTimeChange.id, { expectedVersion: 1 });
    expect(approved.status).toBe('APPROVED');
    expect(approved.decidedBy).toBeTruthy();
    const { pricingRequest } = await api.pricingRequests.get(prId);
    expect(pricingRequest.items[0].leadTimeMinDays).toBe(120);
    expect(pricingRequest.items[0].leadTimeMaxDays).toBe(150);
  });

  it('approve with a stale expectedVersion is a 409 and changes nothing (import edited in between)', async () => {
    const { prId, quote } = await contactedQuote();
    const { leadTimeChange } = await api.leadTimeChanges.create(quote.id, body(quote));
    await api.leadTimeChanges.update(leadTimeChange.id, body(quote, { reason: 'แก้ไขเหตุผล' })); // -> version 2
    await api.auth.login({ role: 'sales' });
    await expect(api.leadTimeChanges.approve(leadTimeChange.id, { expectedVersion: 1 }))
      .rejects.toMatchObject({ status: 409 });
    const { pricingRequest } = await api.pricingRequests.get(prId);
    expect(pricingRequest.items[0].leadTimeMinDays).toBe(75);
  });

  it('reject needs a reason (400), then leaves the old value and records the reason', async () => {
    const { prId, quote } = await contactedQuote();
    const { leadTimeChange } = await api.leadTimeChanges.create(quote.id, body(quote));
    await api.auth.login({ role: 'sales_manager' });
    await expect(api.leadTimeChanges.reject(leadTimeChange.id, { reason: ' ', expectedVersion: 1 }))
      .rejects.toMatchObject({ status: 400 });
    const { leadTimeChange: rejected } = await api.leadTimeChanges.reject(leadTimeChange.id, { reason: 'ลูกค้ารอไม่ได้', expectedVersion: 1 });
    expect(rejected.status).toBe('REJECTED');
    expect(rejected.decisionReason).toBe('ลูกค้ารอไม่ได้');
    const { pricingRequest } = await api.pricingRequests.get(prId);
    expect(pricingRequest.items[0].leadTimeMinDays).toBe(75);
  });

  it('the CEO and import may NOT approve or reject (B-R3)', async () => {
    const { quote } = await contactedQuote();
    const { leadTimeChange } = await api.leadTimeChanges.create(quote.id, body(quote));
    await expect(api.leadTimeChanges.approve(leadTimeChange.id, { expectedVersion: 1 })).rejects.toMatchObject({ status: 403 });
    await api.auth.login({ role: 'ceo' });
    await expect(api.leadTimeChanges.approve(leadTimeChange.id, { expectedVersion: 1 })).rejects.toMatchObject({ status: 403 });
    await expect(api.leadTimeChanges.reject(leadTimeChange.id, { reason: 'x', expectedVersion: 1 })).rejects.toMatchObject({ status: 403 });
  });

  it('only import may create / update / withdraw', async () => {
    const { quote } = await contactedQuote();
    const { leadTimeChange } = await api.leadTimeChanges.create(quote.id, body(quote));
    await api.auth.login({ role: 'sales' });
    await expect(api.leadTimeChanges.create(quote.id, body(quote))).rejects.toMatchObject({ status: 403 });
    await expect(api.leadTimeChanges.update(leadTimeChange.id, body(quote))).rejects.toMatchObject({ status: 403 });
    await expect(api.leadTimeChanges.withdraw(leadTimeChange.id)).rejects.toMatchObject({ status: 403 });
  });

  it('listForPricingRequest is readable by import / CEO / owning rep / sales_manager and carries the history', async () => {
    const { prId, quote } = await contactedQuote();
    const { leadTimeChange } = await api.leadTimeChanges.create(quote.id, body(quote));
    await api.leadTimeChanges.withdraw(leadTimeChange.id);
    await api.leadTimeChanges.create(quote.id, body(quote));
    for (const role of ['import', 'ceo', 'sales', 'sales_manager']) {
      await api.auth.login({ role });
      const { items } = await api.leadTimeChanges.listForPricingRequest(prId);
      expect(items.map((c) => c.status).sort()).toEqual(['PENDING', 'WITHDRAWN']);
    }
    await api.auth.login({ role: 'account' });
    await expect(api.leadTimeChanges.listForPricingRequest(prId)).rejects.toMatchObject({ status: 403 });
  });
});
