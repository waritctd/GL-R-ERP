import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Branch fix (deposit-notice autofill): drives the mock's own pricing-request chain end to end
// — Step 1 create through Step 5 customer-accept, then confirmOrder — against the REAL mock
// state (not a stubbed api object), exercising mockApi.depositNotices.createDraft's new
// header-autofill + new-chain item-fallback behaviour the same way
// OrderConfirmationIntegrationTest proves it against the real Java service (backend/src/test/
// java/th/co/glr/hr/orderconfirmation/OrderConfirmationIntegrationTest.java).
//
// Every deal created through this chain reaches createDraft with approved_price = NULL on
// every ticket item (the chain never writes that legacy column — see
// DepositNoticeService.createDraft's own comments) and, before this fix, no header autofill
// at all — this is exactly the bug the branch fixes.

let seq = 0;

function uuid() {
  seq += 1;
  return `77777777-7777-4777-8777-${String(seq).padStart(12, '0')}`;
}

// Everything up to and including the CEO-approved pricing decision. `priceMode`/`discountPct` are
// set only by the new-engine drive: dealQuotations.createFromPricingRequest refuses a decision
// without a priceMode, and the legacy drive below never set either.
async function driveTicketToApprovedDecision({ priceMode, discountPct } = {}) {
  const n = seq + 1;

  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท มัดจำทดสอบ ${n} จำกัด`,
    taxId: `010000000${n}`,
    address: `${n} ถนนทดสอบ`,
    branch: 'สาขาทดสอบ',
    phone: '02-000-0000',
  });
  const { project } = await api.customers.createProject(customer.id, { name: `โครงการมัดจำทดสอบ ${n}` });
  const { ticket: created } = await api.tickets.create({
    entryChannel: 'DESIGNER_LED',
    title: `ดีลมัดจำทดสอบ ${n}`,
    priority: 'NORMAL',
    customerName: customer.name,
    customerId: customer.id,
    projectId: project.id,
    items: [{ brand: 'SCG', model: 'Tile Mock', qty: 10, currency: 'THB' }],
  });
  const ticketId = created.summary.id;
  const sourceItemId = created.items[0].id;

  const { pricingRequest: draftPr } = await api.pricingRequests.create(ticketId, {
    recipientType: 'DESIGNER',
    recipientLabel: 'ผู้ออกแบบทดสอบ',
    clientRequestId: uuid(),
    items: [{
      sourceTicketItemId: sourceItemId,
      // productId 1 = mockProductPrices' seeded "Panaria SpA" line (factoryId 1), which has an
      // ACTIVE mockPriceImportVersions row — submit()'s catalog gate (financial-integrity
      // review Finding A) requires every item to resolve against an active catalog entry.
      productId: 1,
      brand: 'SCG',
      model: 'Tile Mock',
      factory: 'Panaria SpA',
      // V185: color/texture/size/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
      // required on every item create() persists. PIECES mode + roundToFullBox:false keeps the
      // derived requestedQty an exact 10, matching what this fixture always meant.
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
      // GLA-125: required on this form too.
      originCountry: 'ไทย-สต็อก',
      leadTimeMinDays: 3,
      leadTimeMaxDays: 7,
      quantityType: 'ESTIMATE',
    }],
  });
  const prId = draftPr.summary.id;
  await api.pricingRequests.submit(prId);

  await api.auth.login({ role: 'import' });
  await api.pricingRequests.pickup(prId);
  const { items: quotes } = await api.pricingRequests.generateFactoryEmailDrafts(prId);
  // CR-1 (B-R2): price entry is locked until the factory is marked ติดต่อโรงงานแล้ว.
  for (const q of quotes) {
    await api.pricingRequests.markFactoryQuoteContacted(q.id, {
      contactedOn: new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Bangkok' }).format(new Date()),
    });
  }
  const quote = quotes[0];
  const prItemId = quote.items[0].pricingRequestItemId;
  await api.pricingRequests.receiveFactoryQuote(quote.id, {
    clientRequestId: uuid(),
    supplierQuoteRef: 'REF-MOCK',
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
    priceMode,
    items: decision.items.map((item) => ({
      pricingDecisionItemId: item.id, minimumSellingPrice: 50, discountPct,
    })),
  });
  await api.pricingRequests.approvePricingDecision(decision.id, {});

  return { ticketId, prId, customer, project };
}

async function driveTicketToAcceptedQuotation() {
  const { ticketId, prId, customer, project } = await driveTicketToApprovedDecision();

  await api.auth.login({ role: 'sales' });
  const { quotation: draftQuotation } = await api.pricingRequests.createCustomerQuotation(prId, {});
  const { quotation: issued } = await api.pricingRequests.issueCustomerQuotation(draftQuotation.id, {});
  await api.pricingRequests.recordCustomerQuotationOutcome(issued.id, { outcome: 'ACCEPTED' });
  await api.pricingRequests.confirmOrder(prId, {});

  return { ticketId, customer, project, quotation: issued };
}

// New-engine (PRICING_REQUEST-origin) quotation, issued by the sales manager — the same drive as
// DealQuotationOutcomeIntegrationTest#issuedNewOriginQuotation. The line carries a 10% discount
// from the CEO decision, so the deposit mapper's baht discount label is exercised. The discount
// sits on the decision, not on a quotation update: a line that differs from the CEO price makes
// the mock's PRICING_REQUEST approve refuse sales_manager (403, CEO approval required).
async function driveTicketToNewEngineIssuedQuotation({ depositPercent, fullPaymentTerm } = {}) {
  const { ticketId, prId } = await driveTicketToApprovedDecision({ priceMode: 'NET', discountPct: 10 });
  await api.auth.login({ role: 'sales' });
  const { quotation: draft } = await api.dealQuotations.createFromPricingRequest(prId);
  // Java's drive (DealQuotationOutcomeIntegrationTest) saves validityDays before submit; the
  // mock's submit/approve/recordOutcome never require it, so that update is left out here.
  // fullPaymentTerm is only meaningful (and, for submit, required) when depositPercent is 0.
  if (depositPercent != null) {
    await api.dealQuotations.update(draft.id, {
      priceMode: draft.priceMode, depositPercent, remainderMode: 'ON_DELIVERY', fullPaymentTerm,
      items: draft.items.map((item) => ({ ...item })),
    });
  }
  await api.dealQuotations.submit(draft.id, {});
  await api.auth.login({ role: 'sales_manager' });
  const { quotation: issued } = await api.dealQuotations.approve(draft.id, {});
  await api.auth.login({ role: 'sales' });
  return { ticketId, prId, issued };
}

async function driveTicketToNewEngineAcceptedQuotation(options) {
  const { ticketId, prId, issued } = await driveTicketToNewEngineIssuedQuotation(options);
  const { quotation: accepted } = await api.dealQuotations.recordOutcome(issued.id, {
    outcome: 'ACCEPTED', customerNote: null, clientRequestId: uuid(),
  });
  await api.pricingRequests.confirmOrder(prId, { clientRequestId: uuid() });
  return { ticketId, prId, accepted };
}

describe('mockApi.depositNotices.createDraft autofill (branch fix: deposit-notice autofill)', () => {
  it('sources items from the accepted quotation and autofills the header when no ticket item has an approved price', async () => {
    const { ticketId, customer, project, quotation } = await driveTicketToAcceptedQuotation();

    // Precondition matching the branch's own diagnosis: this chain never touches
    // ticket.items[].approvedPrice.
    const { ticket: detail } = await api.tickets.get(ticketId);
    expect(detail.items.length).toBeGreaterThan(0);
    expect(detail.items.every((it) => it.approvedPrice == null)).toBe(true);
    expect(detail.summary.status).toBe('quotation_issued');

    const { depositNotice } = await api.depositNotices.createDraft(ticketId, {
      notes: [],
      depositPercent: 0.5,
    });

    // Items: sourced from the ACCEPTED quotation, not the (all-null) ticket_item rows.
    expect(depositNotice.items).toHaveLength(1);
    expect(depositNotice.items[0].qty).toBe(quotation.items[0].requestedQuantity);
    expect(depositNotice.items[0].netUnitPrice).toBe(quotation.items[0].finalUnitPrice);

    // Header: customerTaxId/customerAddress from the customer master via ticket.customerId,
    // projectName from the ticket's own project.
    expect(depositNotice.customerTaxId).toBe(customer.taxId);
    expect(depositNotice.customerAddress).toBe(`${customer.address} ${customer.branch}`);
    expect(depositNotice.projectName).toBe(project.name);
  });

  it('createDepositNoticeFromQuotation also autofills the header the same way', async () => {
    const { customer, project, quotation } = await driveTicketToAcceptedQuotation();

    // driveTicketToAcceptedQuotation already ran confirmOrder, so this call exercises the
    // SAME createDraft header-autofill path OrderConfirmationService.createDepositNoticeFromQuotation
    // delegates to — this endpoint itself never passes a customer/project explicitly.
    const prId = (await api.pricingRequests.listForTicket(quotation.ticketId)).items
      .find((pr) => pr.status === 'QUOTATION_ACCEPTED').id;
    const { depositNotice } = await api.pricingRequests.createDepositNoticeFromQuotation(prId, {
      depositPercent: 0.5,
    });

    expect(depositNotice.customerTaxId).toBe(customer.taxId);
    expect(depositNotice.customerAddress).toBe(`${customer.address} ${customer.branch}`);
    expect(depositNotice.projectName).toBe(project.name);
  });
});

// Java picks the row with DealQuotationRepository.findAcceptedForPricingRequest.
describe('mockApi.pricingRequests.createDepositNoticeFromQuotation — new-engine quotation (mirrors OrderConfirmationService fix)', () => {
  it('creates the draft with the accepted quotation number as reference, and items and percent from that quotation', async () => {
    const { prId, accepted } = await driveTicketToNewEngineAcceptedQuotation({ depositPercent: 50 });
    const { depositNotice } = await api.pricingRequests.createDepositNoticeFromQuotation(prId, {});
    expect(depositNotice.reference).toBe(accepted.number);
    // DepositNoticeService.createDraftFromQuotation: the chosen quotation's own whole-number percent.
    expect(accepted.depositPercent).toBe(50);
    expect(depositNotice.depositPercent).toBe(0.5);
    expect(depositNotice.items.length).toBeGreaterThan(0);
    // qty is deliberately not distinguishable here: a mock TILE row sets quantity = piecesFinal.
    const [line] = accepted.items;
    expect(depositNotice.items[0]).toMatchObject({
      qty: line.quantity, netUnitPrice: line.netUnitPrice, unit: line.unit,
      description: line.descriptionLine, unitPrice: line.unitPrice,
    });
    // DepositNoticeRepository.findDealQuotationItems prints sales_discount (unitPrice − netUnitPrice)
    // in baht, not the percent: ราคาตั้ง 120 at 10% off is net 108, so 12 baht.
    expect([accepted.items[0].unitPrice, accepted.items[0].netUnitPrice]).toEqual([120, 108]);
    expect(depositNotice.items[0].discountLabel).toBe('ส่วนลด 12 ต่อหน่วย');
  });

  // Covers the ticket-level new-engine fallback (DepositNoticeRepository.findDealQuotationItemsForDeposit):
  // with no items and empty legacy sources, the route would return no items without it.
  it('ticket-level createDraft with no items reads the same new-engine lines as the pricing-request route', async () => {
    const { ticketId, prId, accepted } = await driveTicketToNewEngineAcceptedQuotation();
    // The PDF prints a NULL percent as DealQuotationRenderAdapter.DEFAULT_DEPOSIT_PERCENT (30).
    expect(accepted.depositPercent).toBeNull();
    const { depositNotice: fromTicket } = await api.depositNotices.createDraft(ticketId, {});
    const { depositNotice: fromPr } = await api.pricingRequests.createDepositNoticeFromQuotation(prId, {});
    expect(fromPr.depositPercent).toBe(0.3);
    expect(fromTicket.items.length).toBeGreaterThan(0);
    const pick = (items) => items.map(({ qty, netUnitPrice }) => ({ qty, netUnitPrice }));
    expect(pick(fromTicket.items)).toEqual(pick(fromPr.items));
  });

  it('still 409s while the new-engine quotation is only ISSUED (customer has not accepted)', async () => {
    const { ticketId, prId } = await driveTicketToNewEngineIssuedQuotation();
    await expect(api.pricingRequests.createDepositNoticeFromQuotation(prId, {}))
      .rejects.toMatchObject({ status: 409, message: 'ยังไม่มีใบเสนอราคาที่ลูกค้ายอมรับสำหรับคำขอราคานี้' });
    const { depositNotices } = await api.depositNotices.listByTicket(ticketId);
    expect(depositNotices).toHaveLength(0);
  });

  // A zero-deposit ("ไม่รับมัดจำ", full payment) quotation has nothing to bill: Java refuses rather
  // than drafting a 0-baht document that nothing downstream would stop from being issued.
  it('rejects a 0% (full-payment) new-engine quotation with 409 and creates no draft', async () => {
    const { ticketId, prId, accepted } = await driveTicketToNewEngineAcceptedQuotation({
      depositPercent: 0, fullPaymentTerm: 'BEFORE_DELIVERY',
    });
    expect(accepted.depositPercent).toBe(0);
    await expect(api.pricingRequests.createDepositNoticeFromQuotation(prId, {}))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining('ไม่มีมัดจำ') });
    const { depositNotices } = await api.depositNotices.listByTicket(ticketId);
    expect(depositNotices).toHaveLength(0);
  });
});

// Issue #752: the mock must reproduce production's one-render gap. Per
// DepositNoticeService.issue (backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeService.java
// ~L200-225), PR #721 moved the PDF/XLSX render into an afterCommit callback — so the DTO
// issue() itself returns still reports hasPdf/hasXlsx FALSE (built before the render runs),
// and only a SUBSEQUENT read of the same document reports both true. Before this fix the mock
// never touched hasPdf/hasXlsx in issue() at all, so they stayed false forever and a developer
// wiring a download button to these flags could never observe, under VITE_USE_MOCKS=true, the
// one-render gap that exists in production.
describe('mockApi.depositNotices.issue hasPdf/hasXlsx (issue #752: mock reproduces the one-render gap)', () => {
  it('issue() response reports hasPdf/hasXlsx false; a subsequent read of the same doc reports both true', async () => {
    const { ticketId } = await driveTicketToAcceptedQuotation();

    const { depositNotice: draft } = await api.depositNotices.createDraft(ticketId, {
      notes: [],
      depositPercent: 0.5,
    });
    expect(draft.hasPdf).toBe(false);
    expect(draft.hasXlsx).toBe(false);

    // Advances the payment track to CUSTOMER_CONFIRMED — the precondition depositNotices.issue
    // checks (mirrors TicketService.confirmCustomer).
    await api.tickets.confirmCustomer(ticketId);

    const { depositNotice: issued } = await api.depositNotices.issue(draft.id);
    expect(issued.status).toBe('ISSUED');
    expect(issued.docNumber).not.toBeNull();
    // The issue() RESPONSE mirrors the pre-render committed state — false/false — even
    // though the document IS now ISSUED. This assertion already holds on the unfixed mock
    // too (it never sets these true anywhere), so it is not by itself evidence of the fix;
    // it pins the response contract precisely because the next assertion changes it.
    expect(issued.hasPdf).toBe(false);
    expect(issued.hasXlsx).toBe(false);

    // A SUBSEQUENT read of the SAME document (i.e. after the "render" has "happened") must
    // see both true. This is the assertion that fails on the unfixed mock, because issue()
    // never flips the stored doc's flags at all — they stay false forever.
    const { depositNotice: reread } = await api.depositNotices.get(draft.id);
    expect(reread.hasPdf).toBe(true);
    expect(reread.hasXlsx).toBe(true);

    const { depositNotices: relist } = await api.depositNotices.listByTicket(ticketId);
    const relisted = relist.find((d) => d.id === draft.id);
    expect(relisted.hasPdf).toBe(true);
    expect(relisted.hasXlsx).toBe(true);
  });
});

// GLA-32 (2026-09-19): mirrors DepositNoticeService.issue's account-role notification (backend
// backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeService.java) — issuing a deposit
// notice notifies the account role, in-app, so account picks up the deal here rather than
// nowhere.
describe('mockApi.depositNotices.issue account notification (GLA-32)', () => {
  it('notifies the seeded account user with the ticket code, customer name and doc number', async () => {
    const { ticketId, customer } = await driveTicketToAcceptedQuotation();

    const { depositNotice: draft } = await api.depositNotices.createDraft(ticketId, {
      notes: [],
      depositPercent: 0.5,
    });
    await api.tickets.confirmCustomer(ticketId);
    const { depositNotice: issued } = await api.depositNotices.issue(draft.id);

    await api.auth.login({ role: 'account' });
    const { notifications } = await api.notifications.list();
    const own = notifications.find((n) => n.type === 'DEPOSIT_NOTICE_ISSUED' && n.ticketId === ticketId);

    expect(own).toBeTruthy();
    expect(own.message).toContain(issued.docNumber);
    expect(own.message).toContain(customer.name);
    // Opus review (2026-09-19): account matches this against a bank transfer, which is the
    // deposit PLUS VAT (totalPayable) — the message must show both, not depositAmount alone.
    expect(issued.totalPayable).not.toBe(issued.depositAmount);
    expect(own.message).toContain(String(Math.round(issued.totalPayable)));
  });

  it('falls back to "ไม่ระบุลูกค้า" when the deposit notice has no customer name', async () => {
    const { ticketId } = await driveTicketToAcceptedQuotation();

    const { depositNotice: draft } = await api.depositNotices.createDraft(ticketId, {
      notes: [],
      depositPercent: 0.5,
      customerName: '',
    });
    await api.tickets.confirmCustomer(ticketId);
    await api.depositNotices.issue(draft.id);

    await api.auth.login({ role: 'account' });
    const { notifications } = await api.notifications.list();
    const own = notifications.find((n) => n.type === 'DEPOSIT_NOTICE_ISSUED' && n.ticketId === ticketId);

    expect(own).toBeTruthy();
    expect(own.message).toContain('ไม่ระบุลูกค้า');
  });
});
