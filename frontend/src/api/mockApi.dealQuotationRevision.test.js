import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// PR B — mock coverage for DealQuotationService#createRevision on a PRICING_REQUEST-origin
// quotation (revise an ISSUED / REVISION_REQUESTED quotation, repeatedly). NOT authz evidence —
// CLAUDE.md "Mock API contract": the mock's role gates approximate the Java service and are NOT
// authoritative. The real-DB matrix (every role, wrong-way-round, number family, supersede,
// single-open-child, recipient + stage hook) lives in
// backend/.../DealQuotationPricingRequestRevisionIntegrationTest; this file only keeps the mock's
// own plumbing honest for the same paths a coding agent drives under VITE_USE_MOCKS=true.

const uuid = () => crypto.randomUUID();

async function freshTicket() {
  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท PRB Revision Mock ${uuid()}`, taxId: '0100000000398', address: '999 ถนนทดสอบ', phone: '02-111-1398',
  });
  const { project } = await api.customers.createProject(customer.id, { name: 'โครงการ PRB Revision Mock' });
  const { contact } = await api.customers.createContact(customer.id, {
    firstName: 'สมศรี', lastName: 'ทดสอบ', phone: '081-000-0004', email: 'contact-prb@example.com',
  });
  const { ticket: created } = await api.tickets.create({
    entryChannel: 'DESIGNER_LED', title: 'ดีล PRB Revision Mock', priority: 'NORMAL',
    customerName: customer.name, customerId: customer.id, projectId: project.id, contactId: contact.id,
    items: [{ brand: 'SCG', model: 'Tile PRB Revision Mock', qty: 10, currency: 'THB' }],
  });
  return { ticketId: created.summary.id, sourceItemId: created.items[0].id };
}

// Same pricing-request -> costing -> approved NET decision walk as
// mockApi.dealQuotationOutcome.test.js's approvedDecisionOn (duplicated, as that file duplicates its own).
async function approvedDecisionOn(ticketId, sourceItemId) {
  await api.auth.login({ role: 'sales' });
  const { pricingRequest: draftPr } = await api.pricingRequests.create(ticketId, {
    recipientType: 'DESIGNER', recipientLabel: 'ผู้รับทดสอบ PRB', clientRequestId: uuid(),
    items: [{
      sourceTicketItemId: sourceItemId, productId: 1, brand: 'SCG', model: 'Tile PRB Revision Mock',
      factory: 'Panaria SpA', color: 'ขาว', texture: 'ด้าน', size: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
      quantityMode: 'PIECES', piecesInput: 10, wastageMode: 'NONE', piecesPerBox: 4, roundToFullBox: false,
      originCountry: 'ไทย-สต็อก', leadTimeMinDays: 3, leadTimeMaxDays: 7, quantityType: 'ESTIMATE',
    }],
  });
  const prId = draftPr.summary.id;
  await api.pricingRequests.submit(prId);
  await api.auth.login({ role: 'import' });
  await api.pricingRequests.pickup(prId);
  const { items: quotes } = await api.pricingRequests.generateFactoryEmailDrafts(prId);
  for (const q of quotes) {
    await api.pricingRequests.markFactoryQuoteContacted(q.id, {
      contactedOn: new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Bangkok' }).format(new Date()),
    });
  }
  const quote = quotes[0];
  await api.pricingRequests.receiveFactoryQuote(quote.id, {
    clientRequestId: uuid(), supplierQuoteRef: 'REF-PRB-REVISION-MOCK', defaultCurrency: 'THB',
    paymentTerms: '30 days', leadTimeText: '45 days',
    items: [{
      pricingRequestItemId: quote.items[0].pricingRequestItemId, quotedQuantity: 10, quotedUnit: 'PER_PIECE',
      unitBasis: 'PER_PIECE', rawUnitPrice: 100, currency: 'THB',
    }],
  });
  await api.pricingRequests.markFactoryQuoteReady(quote.id);
  const { costing } = await api.pricingRequests.createCosting(prId, {});
  await api.pricingRequests.recalculateCosting(costing.id, {});
  await api.pricingRequests.submitCosting(costing.id, {});
  await api.auth.login({ role: 'ceo' });
  const { decision: started } = await api.pricingRequests.startPricingDecision(prId, { defaultMarginPct: 0.2 });
  const { decision: afterMode } = await api.pricingRequests.updatePricingDecision(started.id, {
    priceMode: 'NET', items: [{ pricingDecisionItemId: started.items[0].id, discountPct: 10 }],
  });
  await api.pricingRequests.approvePricingDecision(afterMode.id, {});
  return prId;
}

async function issuedQuotation() {
  const { ticketId, sourceItemId } = await freshTicket();
  const prId = await approvedDecisionOn(ticketId, sourceItemId);
  await api.auth.login({ role: 'sales' });
  const { quotation: draft } = await api.dealQuotations.createFromPricingRequest(prId);
  const { quotation: submitted } = await api.dealQuotations.submit(draft.id);
  await api.auth.login({ role: 'sales_manager' });
  const { quotation: issued } = await api.dealQuotations.approve(submitted.id, {});
  return { prId, ticketId, quotation: issued };
}

async function expectStatus(promise, status) {
  await expect(promise).rejects.toMatchObject({ status });
}

async function pendingRevision() {
  const { quotation: source } = await issuedQuotation();
  await api.auth.login({ role: 'sales' });
  const { quotation: child } = await api.dealQuotations.createRevision(source.id);
  const { quotation: submitted } = await api.dealQuotations.submit(child.id);
  return { source, pending: submitted };
}

// Approval of a revision: ONE approval by EITHER the sales_manager OR the CEO issues it; it never
// auto-issues on submit; either may reject it back to DRAFT. (Mock authz is not authoritative.)
describe('mockApi dealQuotations.approve -- a PRICING_REQUEST revision (PR B)', { timeout: 30000 }, () => {
  it('submit leaves the revision PENDING_APPROVAL (never auto-issues)', async () => {
    const a = await pendingRevision();
    expect(a.pending.docStatus).toBe('PENDING_APPROVAL');
    expect((await api.dealQuotations.get(a.source.id)).quotation.docStatus).toBe('ISSUED');
  });

  it('a sales_manager approval issues it and supersedes the parent', async () => {
    const a = await pendingRevision();
    await api.auth.login({ role: 'sales_manager' });
    expect((await api.dealQuotations.approve(a.pending.id, {})).quotation.docStatus).toBe('ISSUED');
    expect((await api.dealQuotations.get(a.source.id)).quotation.docStatus).toBe('SUPERSEDED');
  });

  it('a CEO approval issues it and supersedes the parent', async () => {
    const a = await pendingRevision();
    await api.auth.login({ role: 'ceo' });
    expect((await api.dealQuotations.approve(a.pending.id, {})).quotation.docStatus).toBe('ISSUED');
    expect((await api.dealQuotations.get(a.source.id)).quotation.docStatus).toBe('SUPERSEDED');
  });

  it('the owning sales rep cannot approve it (403)', async () => {
    const a = await pendingRevision();
    await api.auth.login({ role: 'sales' });
    await expectStatus(api.dealQuotations.approve(a.pending.id, {}), 403);
    expect((await api.dealQuotations.get(a.pending.id)).quotation.docStatus).toBe('PENDING_APPROVAL');
  });

  it('reject by either approver returns it to DRAFT', async () => {
    const a = await pendingRevision();
    await api.auth.login({ role: 'ceo' });
    expect((await api.dealQuotations.reject(a.pending.id, { reason: 'แก้ราคา' })).quotation.docStatus).toBe('DRAFT');
    const b = await pendingRevision();
    await api.auth.login({ role: 'sales_manager' });
    expect((await api.dealQuotations.reject(b.pending.id, { reason: 'ทบทวน' })).quotation.docStatus).toBe('DRAFT');
  });
});

describe('mockApi dealQuotations.createRevision -- PRICING_REQUEST origin (PR B)', { timeout: 30000 }, () => {
  it('the owning rep revises an ISSUED quotation into a DRAFT child that keeps origin and the PR link', async () => {
    const { quotation: source } = await issuedQuotation();
    await api.auth.login({ role: 'sales' });
    const { quotation: child } = await api.dealQuotations.createRevision(source.id);
    expect(child.docStatus).toBe('DRAFT');
    expect(child.origin).toBe('PRICING_REQUEST');
    expect(child.pricingRequestId).toBe(source.pricingRequestId);
    expect(child.parentQuotationId).toBe(source.id);
    expect(child.revisionNo).toBe(source.revisionNo + 1);
    expect(child.number).not.toBe(source.number);
    expect(child.items.length).toBe(source.items.length);
    expect((await api.dealQuotations.get(source.id)).quotation.docStatus).toBe('ISSUED');
  });

  it('a REVISION_REQUESTED quotation is revisable too', async () => {
    const { quotation: source } = await issuedQuotation();
    await api.auth.login({ role: 'sales' });
    await api.dealQuotations.recordOutcome(source.id, { outcome: 'REVISION_REQUESTED', clientRequestId: uuid() });
    const { quotation: child } = await api.dealQuotations.createRevision(source.id);
    expect(child.docStatus).toBe('DRAFT');
  });

  it('wrong-way-round: a sales_manager and a ceo cannot revise (Java: owning rep only; mock not authoritative)', async () => {
    const { quotation: source } = await issuedQuotation();
    await api.auth.login({ role: 'sales_manager' });
    await expectStatus(api.dealQuotations.createRevision(source.id), 403);
    await api.auth.login({ role: 'ceo' });
    await expectStatus(api.dealQuotations.createRevision(source.id), 403);
  });

  it('refuses DRAFT and ACCEPTED sources (409)', async () => {
    const { quotation: accepted } = await issuedQuotation();
    await api.auth.login({ role: 'sales' });
    await api.dealQuotations.recordOutcome(accepted.id, { outcome: 'ACCEPTED', clientRequestId: uuid() });
    await expectStatus(api.dealQuotations.createRevision(accepted.id), 409);

    const { quotation: source } = await issuedQuotation();
    await api.auth.login({ role: 'sales' });
    const { quotation: child } = await api.dealQuotations.createRevision(source.id);
    await expectStatus(api.dealQuotations.createRevision(child.id), 409); // DRAFT child
  });

  it('only one open child at a time (409)', async () => {
    const { quotation: source } = await issuedQuotation();
    await api.auth.login({ role: 'sales' });
    await api.dealQuotations.createRevision(source.id);
    await expectStatus(api.dealQuotations.createRevision(source.id), 409);
  });

  it('approving the revision supersedes the parent; the revision can then be revised again', async () => {
    const { quotation: source } = await issuedQuotation();
    await api.auth.login({ role: 'sales' });
    const { quotation: child } = await api.dealQuotations.createRevision(source.id);
    const { quotation: submitted } = await api.dealQuotations.submit(child.id);
    await api.auth.login({ role: 'sales_manager' });
    const { quotation: issuedChild } = await api.dealQuotations.approve(submitted.id, {});
    expect(issuedChild.docStatus).toBe('ISSUED');
    expect((await api.dealQuotations.get(source.id)).quotation.docStatus).toBe('SUPERSEDED');

    await api.auth.login({ role: 'sales' });
    const { quotation: again } = await api.dealQuotations.createRevision(issuedChild.id);
    expect(again.parentQuotationId).toBe(issuedChild.id);
    expect(again.revisionNo).toBe(source.revisionNo + 2);
  });

  it('a revision DRAFT may change the recipient (to OWNER); the parent keeps its own', async () => {
    const { quotation: source } = await issuedQuotation();
    await api.auth.login({ role: 'sales' });
    const { quotation: child } = await api.dealQuotations.createRevision(source.id);
    const { quotation: updated } = await api.dealQuotations.update(child.id, {
      ...child, items: child.items, recipientType: 'OWNER',
    });
    expect(updated.recipientType).toBe('OWNER');
    expect((await api.dealQuotations.get(source.id)).quotation.recipientType).toBe('DESIGNER');
  });
});
