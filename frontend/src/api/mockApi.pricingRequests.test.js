import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';
import { buildDemoSalesSeed } from '../data/demoSales.js';

// Guards mockApi.js's pricingRequests.create/update item validation directly
// against the mock module (not just through the create modal's own client-side
// checks) — see CLAUDE.md "Mock API contract": a mock that is MORE permissive
// than production is the dangerous direction (issue #199). The real backend's
// PricingRequestRequests.PricingRequestItemRequest declares
// @NotNull @DecimalMin("0.0001") requestedQty and @NotBlank requestedUnit as
// Bean Validation, enforced before PricingRequestService ever runs — so even a
// caller that bypasses the UI (a script, a future component) must still be
// rejected by the mock the same way the real backend would 400 it.

async function ownedActiveTicketWithItems() {
  await api.auth.login({ role: 'sales' });
  const { tickets } = await api.tickets.list();
  const candidate = tickets.find((t) => t.itemCount > 0 && t.lifecycle === 'ACTIVE');
  expect(candidate).toBeTruthy();
  const { ticket } = await api.tickets.get(candidate.id);
  return ticket;
}

let clientRequestSeq = 0;

function nextClientRequestId() {
  clientRequestSeq += 1;
  return `66666666-6666-4666-8666-${String(clientRequestSeq).padStart(12, '0')}`;
}

function validPayload(sourceItem) {
  return {
    recipientType: 'DESIGNER',
    recipientLabel: 'ผู้ออกแบบทดสอบ',
    clientRequestId: nextClientRequestId(),
    items: [{
      sourceTicketItemId: sourceItem.id,
      brand: sourceItem.brand,
      model: sourceItem.model,
      requestedQty: 10,
      requestedUnit: 'แผ่น',
      requestedUnitBasis: 'PER_PIECE',
      quantityType: 'ESTIMATE',
    }],
  };
}

describe('mockApi.pricingRequests.create item validation', () => {
  it('rejects a blank requestedUnit, mirroring PricingRequestItemRequest\'s @NotBlank requestedUnit', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    payload.items[0].requestedUnit = '   '; // blank after trim
    await expect(api.pricingRequests.create(ticket.summary.id, payload)).rejects.toThrow();
  });

  it('rejects a zero/negative requestedQty, mirroring @DecimalMin("0.0001") requestedQty', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    payload.items[0].requestedQty = 0;
    await expect(api.pricingRequests.create(ticket.summary.id, payload)).rejects.toThrow();
  });

  it('accepts a valid item (sanity check the validation above is not over-rejecting)', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    const { pricingRequest } = await api.pricingRequests.create(ticket.summary.id, payload);
    expect(pricingRequest.summary.status).toBe('DRAFT');
    expect(pricingRequest.items[0].requestedUnit).toBe('แผ่น');
  });

  it('update() rejects a blank requestedUnit on an existing draft the same way create() does', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const created = await api.pricingRequests.create(ticket.summary.id, validPayload(ticket.items[0]));
    const draftId = created.pricingRequest.summary.id;
    const badPayload = validPayload(ticket.items[0]);
    badPayload.items[0].requestedUnit = '';
    await expect(api.pricingRequests.update(draftId, badPayload)).rejects.toThrow();
  });

  // Mirrors PricingRequestService.validateItems (Part 1 of the review-remediation
  // plan): brand alone does not identify a product. Without this check, the
  // mock would be MORE permissive than the real backend (issue #199's failure
  // mode) — it would happily persist a line that says nothing more than
  // "1 แผ่น" and hand it to Import.
  it('rejects an item with no identity field (brand alone is not enough)', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    payload.items[0].sourceTicketItemId = null;
    payload.items[0].model = null; // brand alone remains — not sufficient
    await expect(api.pricingRequests.create(ticket.summary.id, payload)).rejects.toThrow();
  });

  it('rejects an item identified only by specialRequirement, with no sourceTicketItemId/model/brand', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    payload.items[0].sourceTicketItemId = null;
    payload.items[0].brand = null;
    payload.items[0].model = null;
    payload.items[0].specialRequirement = 'กระเบื้องลายไม้สีเข้ม ผิวด้าน';
    await expect(api.pricingRequests.create(ticket.summary.id, payload)).rejects.toThrow();
  });

  it('accepts an item identified only by productDescription', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    payload.items[0].sourceTicketItemId = null;
    payload.items[0].brand = null;
    payload.items[0].model = null;
    payload.items[0].productDescription = 'กระเบื้องพอร์ซเลน 60x60 สีขาว';
    const { pricingRequest } = await api.pricingRequests.create(ticket.summary.id, payload);
    expect(pricingRequest.summary.status).toBe('DRAFT');
    expect(pricingRequest.items[0].productDescription).toBe('กระเบื้องพอร์ซเลน 60x60 สีขาว');
  });

  // submit()'s own re-check against the persisted items (mirroring
  // PricingRequestService.submit) is exercised on the backend
  // (PricingRequestServiceTest#submit_rejectsPreExistingDraftWithUnidentifiedItem)
  // — there is no way to reach that state through this mock's public API
  // surface, since create()/update() both already reject an unidentified item
  // and delay() structuredClone()s every response, so a returned item can't be
  // mutated back into the mock's own store. That asymmetry is expected: the
  // real-world scenario is a row that predates this rule in a persisted
  // database, which a fresh in-memory mock session has no equivalent of.
});

describe('mockApi.pricingRequests create idempotency', () => {
  it('same user and same clientRequestId returns the existing request without a duplicate CREATED event or item rows', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    const first = await api.pricingRequests.create(ticket.summary.id, payload);
    const second = await api.pricingRequests.create(ticket.summary.id, payload);

    expect(second.pricingRequest.summary.id).toBe(first.pricingRequest.summary.id);
    expect(second.pricingRequest.events.filter((e) => e.eventKind === 'PRICING_REQUEST_CREATED')).toHaveLength(1);
    expect(second.pricingRequest.items).toHaveLength(first.pricingRequest.items.length);
  });

  it('same user and different clientRequestId creates a separate request', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const first = await api.pricingRequests.create(ticket.summary.id, validPayload(ticket.items[0]));
    const second = await api.pricingRequests.create(ticket.summary.id, validPayload(ticket.items[0]));
    expect(second.pricingRequest.summary.id).not.toBe(first.pricingRequest.summary.id);
  });
});

// Review-remediation plan Commit D, part 1: the DB column is
// `revision_no INTEGER NOT NULL DEFAULT 1` with
// `CONSTRAINT chk_pricing_request_revision CHECK (revision_no >= 1)` (V58).
// A mock starting the counter at 0 would produce a row production would
// reject outright, not merely a cosmetic divergence.
describe('mockApi.pricingRequests revisionNo', () => {
  it('create() starts a new pricing request at revisionNo 1, not 0', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const { pricingRequest } = await api.pricingRequests.create(ticket.summary.id, validPayload(ticket.items[0]));
    expect(pricingRequest.summary.revisionNo).toBe(1);
  });
});

// Review-remediation plan Commit D, part 1: PricingRequestRepository.
// normalizeCurrency trims + uppercases on both insert and update, blank ->
// null. Mirrored here so the mock never stores a raw/mixed-case currency the
// real column wouldn't have.
describe('mockApi.pricingRequests targetCurrency normalisation', () => {
  it('create() normalises a lowercase/whitespace-padded currency to trimmed uppercase', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    payload.targetCurrency = ' usd ';
    const { pricingRequest } = await api.pricingRequests.create(ticket.summary.id, payload);
    expect(pricingRequest.summary.targetCurrency).toBe('USD');
  });

  it('create() collapses an empty-string currency to null, mirroring normalizeCurrency', async () => {
    // Note: a whitespace-only string (e.g. '   ') is NOT used here — it trips
    // a separate, pre-existing format-check quirk unrelated to this fix (the
    // mock's 3-letter-code check treats a non-empty whitespace string as
    // "present but wrong length" where the Java validateCurrency's
    // currency.isBlank() short-circuits first); out of scope for this task.
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    payload.targetCurrency = '';
    const { pricingRequest } = await api.pricingRequests.create(ticket.summary.id, payload);
    expect(pricingRequest.summary.targetCurrency).toBeNull();
  });

  it('update() re-normalises targetCurrency the same way create() does', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const created = await api.pricingRequests.create(ticket.summary.id, validPayload(ticket.items[0]));
    const draftId = created.pricingRequest.summary.id;
    const payload = validPayload(ticket.items[0]);
    payload.targetCurrency = 'thb';
    const { pricingRequest } = await api.pricingRequests.update(draftId, payload);
    expect(pricingRequest.summary.targetCurrency).toBe('THB');
  });
});

// Review finding M1 (Opus review on feat/pcr-quotation-revision-suffix, 2026-09-18): the
// pricing-request-chain (customerquotation/) quotation numbering added to mockApi.js —
// nextMockCustomerQuotationNumber/nextMockCustomerQuotationRevisionNo and the two endpoints that
// call them, createCustomerQuotation (~L11794) and createCustomerQuotationRevision (~L11958) —
// shipped with NO test pinning it. Pins the same four cases the backend's own
// CustomerQuotationRepository/CustomerQuotationService tests pin for the real Java service,
// driven end to end through the public `api` surface only (no reach into mockApi.js internals).
//
// Drives a fresh deal all the way to APPROVED_FOR_QUOTATION — copied from
// mockApi.depositNotices.test.js's own driveTicketToAcceptedQuotation, which already proves this
// exact sequence reaches the mock's real state (not a stub), trimmed to stop right after the
// CEO's approvePricingDecision since numbering only needs a pricing request the customer-quotation
// endpoints will accept.
let numberingUuidSeq = 0;
function nextNumberingUuid() {
  numberingUuidSeq += 1;
  return `55555555-5555-4555-8555-${String(numberingUuidSeq).padStart(12, '0')}`;
}

async function driveToApprovedForQuotation() {
  const n = numberingUuidSeq + 1;

  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท เลขที่ใบเสนอราคาทดสอบ ${n} จำกัด`,
    taxId: `020000000${n}`,
    address: `${n} ถนนทดสอบเลขที่`,
    branch: 'สาขาทดสอบ',
    phone: '02-111-1111',
  });
  const { project } = await api.customers.createProject(customer.id, { name: `โครงการเลขที่ใบเสนอราคาทดสอบ ${n}` });
  const { ticket: created } = await api.tickets.create({
    title: `ดีลเลขที่ใบเสนอราคาทดสอบ ${n}`,
    priority: 'NORMAL',
    customerName: customer.name,
    customerId: customer.id,
    projectId: project.id,
    items: [{ brand: 'SCG', model: 'Tile Numbering Mock', qty: 10, currency: 'THB' }],
  });
  const ticketId = created.summary.id;
  const sourceItemId = created.items[0].id;

  const { pricingRequest: draftPr } = await api.pricingRequests.create(ticketId, {
    recipientType: 'DESIGNER',
    recipientLabel: 'ผู้ออกแบบทดสอบ',
    clientRequestId: nextNumberingUuid(),
    items: [{
      sourceTicketItemId: sourceItemId,
      // productId 1 = mockProductPrices' seeded "Panaria SpA" line (factoryId 1), which has an
      // ACTIVE mockPriceImportVersions row — submit()'s catalog gate requires every item to
      // resolve against an active catalog entry (same precondition depositNotices' own driver
      // relies on).
      productId: 1,
      brand: 'SCG',
      model: 'Tile Numbering Mock',
      factory: 'Panaria SpA',
      requestedQty: 10,
      requestedUnit: 'แผ่น',
      requestedUnitBasis: 'PER_PIECE',
      quantityType: 'ESTIMATE',
    }],
  });
  const prId = draftPr.summary.id;
  await api.pricingRequests.submit(prId);

  await api.auth.login({ role: 'import' });
  await api.pricingRequests.pickup(prId);
  const { items: quotes } = await api.pricingRequests.generateFactoryEmailDrafts(prId);
  const quote = quotes[0];
  const prItemId = quote.items[0].pricingRequestItemId;
  await api.pricingRequests.receiveFactoryQuote(quote.id, {
    clientRequestId: nextNumberingUuid(),
    supplierQuoteRef: 'REF-MOCK-NUMBERING',
    defaultCurrency: 'THB',
    paymentTerms: '30 days',
    leadTimeText: '45 days',
    items: [{
      pricingRequestItemId: prItemId,
      quotedQuantity: 10,
      quotedUnit: 'PER_PIECE',
      unitBasis: 'PER_PIECE',
      rawUnitPrice: 100,
      currency: 'THB',
    }],
  });
  await api.pricingRequests.markFactoryQuoteReady(quote.id);
  const { costing } = await api.pricingRequests.createCosting(prId, {});
  await api.pricingRequests.recalculateCosting(costing.id, {});
  await api.pricingRequests.submitCosting(costing.id, {});

  await api.auth.login({ role: 'ceo' });
  const { decision } = await api.pricingRequests.startPricingDecision(prId, { defaultMarginPct: 0.2 });
  await api.pricingRequests.updatePricingDecision(decision.id, {
    items: decision.items.map((item) => ({ pricingDecisionItemId: item.id, minimumSellingPrice: 50 })),
  });
  await api.pricingRequests.approvePricingDecision(decision.id, {});

  await api.auth.login({ role: 'sales' });
  return prId;
}

// Mirrors mockApi.js's own quotationBaseNumber (~L4901): strips a source number's own
// `-{sourceRevisionNo}` suffix to recover the base a revision chain shares. Re-implemented here
// (rather than importing mockApi.js's non-exported helper) so this file keeps asserting only
// through values the public `api` surface actually returns.
function quotationBase(number, revisionNo) {
  const suffix = `-${revisionNo}`;
  return number.endsWith(suffix) ? number.slice(0, -suffix.length) : number;
}

describe('mockApi.pricingRequests customerQuotation numbering (review finding M1)', () => {
  it('mints "-1" on the first document, then "-2"/"-3" on successive revisions, all sharing the source base', async () => {
    const prId = await driveToApprovedForQuotation();

    const { quotation: draft } = await api.pricingRequests.createCustomerQuotation(prId, {});
    expect(draft.number).toMatch(/^QT-\d{4}-\d+-1$/);
    expect(draft.quotationRevisionNo).toBe(1);
    const base = quotationBase(draft.number, draft.quotationRevisionNo);

    const { quotation: issued1 } = await api.pricingRequests.issueCustomerQuotation(draft.id, {});

    const { quotation: rev2 } = await api.pricingRequests.createCustomerQuotationRevision(issued1.id, {});
    expect(rev2.quotationRevisionNo).toBe(2);
    expect(rev2.number).toBe(`${base}-2`);
    // Case 3: the revision keeps the SAME base as its source — not a newly minted code.
    expect(quotationBase(rev2.number, rev2.quotationRevisionNo)).toBe(base);

    const { quotation: issued2 } = await api.pricingRequests.issueCustomerQuotation(rev2.id, {});

    const { quotation: rev3 } = await api.pricingRequests.createCustomerQuotationRevision(issued2.id, {});
    expect(rev3.quotationRevisionNo).toBe(3);
    expect(rev3.number).toBe(`${base}-3`);
    expect(quotationBase(rev3.number, rev3.quotationRevisionNo)).toBe(base);
  });

  // Concrete failure this catches (CLAUDE.md's "Mock API contract": the mock is the default
  // verification surface, so silence here is the dangerous kind): if mockCustomerQuotationNumberSeq's
  // top-level `let` (mockApi.js ~L4878, `= mockCustomerQuotationSeq`) ever executed BEFORE the
  // top-level seed block (~L1161) advances mockCustomerQuotationSeq past every demoSales.js-seeded
  // row's id, the counter would start at 1 and mint a BASE identical to an already-seeded row's
  // bare number. Every seeded row's number is literally `QT-2026-{id}` (demoSales.js#makeQuotation)
  // and every mock-minted number always carries a `-{revisionNo}` suffix, so the two FULL strings
  // never collide char-for-char even under this bug — asserting on the raw `.number` would stay
  // green and prove nothing. Asserting on the stripped-suffix BASE is what actually observes the
  // reuse: under the real (correct) seeding this base is always one past every seeded id, so it can
  // never appear in the seeded set; under the bug, the very first mock-minted base is `QT-2026-0001`,
  // which demoSales.js's own row id 1 already owns.
  it('a freshly minted quotation never reuses a base number demoSales.js already seeded', async () => {
    const seededNumbers = new Set(buildDemoSalesSeed().customerQuotations.map((q) => q.number));
    expect(seededNumbers.size).toBeGreaterThan(0);

    const prId = await driveToApprovedForQuotation();
    const { quotation: draft } = await api.pricingRequests.createCustomerQuotation(prId, {});
    const base = quotationBase(draft.number, draft.quotationRevisionNo);

    expect(seededNumbers.has(base)).toBe(false);
  });
});

// GLA-102: mirrors PricingRequestService's requireUnchangedRecipientType guard, on BOTH routes
// that can write recipient_type onto an existing pricing request. Before this pass the mock
// accepted what production refuses on both — the dangerous "mock more permissive than production"
// direction CLAUDE.md warns about (issue #199's own failure mode): a mock-driven click-through or
// mock-mode test would show success on a request the real Java service 409s.
describe('mockApi.pricingRequests recipient guard (GLA-102)', () => {
  async function submittedParent() {
    const ticket = await ownedActiveTicketWithItems();
    const created = await api.pricingRequests.create(ticket.summary.id, validPayload(ticket.items[0]));
    const parentId = created.pricingRequest.summary.id;
    await api.pricingRequests.submit(parentId); // must be past DRAFT before a revision is allowed
    return { ticket, parentId };
  }

  function revisionPayload(ticket, recipientType) {
    return { ...validPayload(ticket.items[0]), recipientType, revisionReason: 'ลูกค้าเปลี่ยนใจ' };
  }

  it('createCustomerChangeRevision rejects a recipientType different from the parent\'s', async () => {
    const { ticket, parentId } = await submittedParent(); // parent's recipientType is DESIGNER
    // toMatchObject({ status: 409 }), not a bare toThrow(): a bare assertion would stay green if this
    // path later started failing for an unrelated reason (a 400 on payload shape, say), and the test
    // would silently stop pinning the guard it exists for. Same convention as mockApi.customers.test.js.
    await expect(api.pricingRequests.createCustomerChangeRevision(parentId, revisionPayload(ticket, 'OWNER')))
      .rejects.toMatchObject({ status: 409 });
  });

  it('createCustomerChangeRevision accepts the SAME recipientType as the parent (unchanged behaviour)', async () => {
    const { ticket, parentId } = await submittedParent();
    const { pricingRequest } = await api.pricingRequests.createCustomerChangeRevision(
      parentId, revisionPayload(ticket, 'DESIGNER'));
    expect(pricingRequest.summary.recipientType).toBe('DESIGNER');
    expect(pricingRequest.summary.parentPricingRequestId).toBe(parentId);
  });

  it('update() rejects changing recipientType on a revision child — the sideways route the backend guard closed', async () => {
    const { ticket, parentId } = await submittedParent();
    const revised = await api.pricingRequests.createCustomerChangeRevision(
      parentId, revisionPayload(ticket, 'DESIGNER'));
    const childId = revised.pricingRequest.summary.id;

    const badUpdate = { ...validPayload(ticket.items[0]), recipientType: 'OWNER' };
    await expect(api.pricingRequests.update(childId, badUpdate)).rejects.toMatchObject({ status: 409 });
  });

  it('update() still allows changing recipientType on the ROOT pricing request (deliberate scope boundary)', async () => {
    // A root has no parentPricingRequestId and, while still DRAFT (the only status update()
    // reaches), can never yet own a customer-change-revision child — so there is nothing
    // downstream for a recipient change to orphan. Mirrors the same scope decision made in
    // PricingRequestService#updateDraft.
    const ticket = await ownedActiveTicketWithItems();
    const created = await api.pricingRequests.create(ticket.summary.id, validPayload(ticket.items[0]));
    const rootId = created.pricingRequest.summary.id;

    const changedRecipient = { ...validPayload(ticket.items[0]), recipientType: 'OWNER' };
    const { pricingRequest } = await api.pricingRequests.update(rootId, changedRecipient);
    expect(pricingRequest.summary.recipientType).toBe('OWNER');
  });
});
