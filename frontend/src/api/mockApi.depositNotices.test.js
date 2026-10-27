import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { describe, it, expect } from 'vitest';
import {
  api, MOCK_DEFAULT_DEPOSIT_PERCENT, MOCK_LEGACY_DEPOSIT_PERCENT, retireLivePricingRequestsForTests,
} from './mockApi.js';
import { stripComments } from './apiSurface.js';

// Copy of the canonical ONE_ITEM in mockApi.dealQuotations.test.js.
const ONE_ITEM = {
  model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
  quantityMode: 'AREA', areaSqm: 20, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0,
  leadTimeMinDays: 30, leadTimeMaxDays: 45,
};

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

    // A typed percent is ignored: the legacy quotation's PDF prints 50%, so the draft bills 50%.
    const { depositNotice } = await api.depositNotices.createDraft(ticketId, {
      notes: [],
      depositPercent: 0.9,
    });
    expect(depositNotice.depositPercent).toBe(0.5);
    expect(depositNotice.reference).toBe(quotation.number);

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
    // A typed percent is ignored here too: the legacy branch bills its PDF's 50%.
    const { depositNotice } = await api.pricingRequests.createDepositNoticeFromQuotation(prId, {
      depositPercent: 0.9,
    });
    expect(depositNotice.depositPercent).toBe(0.5);

    expect(depositNotice.customerTaxId).toBe(customer.taxId);
    expect(depositNotice.customerAddress).toBe(`${customer.address} ${customer.branch}`);
    expect(depositNotice.projectName).toBe(project.name);
  });
});

// Java picks the row with DealQuotationRepository.findAcceptedForPricingRequest.
describe('mockApi.pricingRequests.createDepositNoticeFromQuotation — new-engine quotation (mirrors OrderConfirmationService fix)', () => {
  it('creates the draft with the accepted quotation number as reference, and items and percent from that quotation', async () => {
    // 40, unlike the 30 default, the legacy 50 and the no-quotation 0.5, so only the quotation's own percent fits.
    const { ticketId, prId, accepted } = await driveTicketToNewEngineAcceptedQuotation({ depositPercent: 40 });
    const { depositNotice } = await api.pricingRequests.createDepositNoticeFromQuotation(prId, {});
    expect(depositNotice.reference).toBe(accepted.number);
    // DepositNoticeService.createDraftFromQuotation: the chosen quotation's own whole-number percent.
    expect(accepted.depositPercent).toBe(40);
    expect(depositNotice.depositPercent).toBe(0.4);
    // The ticket route bills the same quotation (DepositNoticeRepository.findGoverningQuotation).
    const { depositNotice: fromTicket } = await api.depositNotices.createDraft(ticketId, {});
    expect(fromTicket.depositPercent).toBe(0.4);
    expect(fromTicket.reference).toBe(accepted.number);
    // The caller supplies the lines (even this quotation's own), so the reference is null, as in
    // Java; the percent still comes from the quotation (DepositNoticeService, linesFromGoverning).
    const { depositNotice: ownLines } = await api.depositNotices.createDraft(ticketId, { items: fromTicket.items });
    expect(ownLines.reference).toBeNull();
    expect(ownLines.depositPercent).toBe(0.4);
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

  // Covers the ticket-level route's governing quotation (DepositNoticeRepository.findGoverningQuotation):
  // with no items and no legacy quotation, it bills the new-engine quotation's lines, percent and number.
  it('ticket-level createDraft with no items drafts the same lines, percent and reference as the pricing-request route', async () => {
    const { ticketId, prId, accepted } = await driveTicketToNewEngineAcceptedQuotation();
    // The PDF prints a NULL percent as DealQuotationRenderAdapter.DEFAULT_DEPOSIT_PERCENT (30).
    expect(accepted.depositPercent).toBeNull();
    const { depositNotice: fromTicket } = await api.depositNotices.createDraft(ticketId, {});
    const { depositNotice: fromPr } = await api.pricingRequests.createDepositNoticeFromQuotation(prId, {});
    expect(fromPr.depositPercent).toBe(0.3);
    expect(fromTicket.depositPercent).toBe(0.3);
    expect(fromTicket.reference).toBe(fromPr.reference);
    expect(fromTicket.items.length).toBeGreaterThan(0);
    const pick = (items) => items.map(({ qty, netUnitPrice }) => ({ qty, netUnitPrice }));
    expect(pick(fromTicket.items)).toEqual(pick(fromPr.items));
  });

  // Mirrors DepositNoticeService.createDraftFromQuotation's owner guard. sales2@glr.co.th is a
  // second seeded sales rep; the drive above runs as the first one, who owns the deal.
  it('ticket-level createDraft refuses a sales rep who does not own the deal and creates nothing', async () => {
    const { ticketId } = await driveTicketToNewEngineAcceptedQuotation();
    await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
    await expect(api.depositNotices.createDraft(ticketId, {}))
      .rejects.toMatchObject({ status: 403, message: 'ไม่มีสิทธิ์เข้าถึงรายการนี้' });
    // Role gate (SALES_ROLES = sales only) runs before the ticket lookup, as in Java: a non-sales
    // caller gets 403 even for a ticket that does not exist, never the 404.
    await api.auth.login({ role: 'ceo' });
    await expect(api.depositNotices.createDraft(999999, {})).rejects.toMatchObject({ status: 403 });

    await api.auth.login({ role: 'sales' });
    expect((await api.depositNotices.listByTicket(ticketId)).depositNotices).toHaveLength(0);
    const { depositNotice } = await api.depositNotices.createDraft(ticketId, {});
    expect(depositNotice.ticketId).toBe(ticketId);
  });

  it('still 409s while the new-engine quotation is only ISSUED (customer has not accepted)', async () => {
    const { ticketId, prId } = await driveTicketToNewEngineIssuedQuotation();
    await expect(api.pricingRequests.createDepositNoticeFromQuotation(prId, {}))
      .rejects.toMatchObject({ status: 409, message: 'ยังไม่มีใบเสนอราคาที่ลูกค้ายอมรับสำหรับคำขอราคานี้' });
    const { depositNotices } = await api.depositNotices.listByTicket(ticketId);
    expect(depositNotices).toHaveLength(0);
  });

  // A zero-deposit ("ไม่รับมัดจำ", full payment) quotation has nothing to bill: Java refuses on both
  // routes rather than drafting a 0-baht document that nothing downstream would stop from being issued.
  it('rejects a 0% (full-payment) new-engine quotation with 409 on both routes and creates no draft', async () => {
    const { ticketId, prId, accepted } = await driveTicketToNewEngineAcceptedQuotation({
      depositPercent: 0, fullPaymentTerm: 'BEFORE_DELIVERY',
    });
    expect(accepted.depositPercent).toBe(0);
    // Each route's own guard: the pricing-request route refuses on the quotation, the ticket route on the policy.
    await expect(api.pricingRequests.createDepositNoticeFromQuotation(prId, {}))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining(`ใบเสนอราคา ${accepted.number} ระบุไม่มีมัดจำ`) });
    await expect(api.depositNotices.createDraft(ticketId, {}))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining('ดีลนี้ไม่ต้องเก็บมัดจำ') });
    const { depositNotices } = await api.depositNotices.listByTicket(ticketId);
    expect(depositNotices).toHaveLength(0);
  });

  // DepositNoticeRepositoryDepositPercentIntegrationTest#mixedTicket_aDealDirectApprovedQuotationBeatsAnOlderAcceptedOne
  // on the mock: two answers in two mock arrays, so only "newest id" across both arrays can pick.
  // Cancelling the pricing request stages the pre-lock data that lets a direct quotation sit beside it.
  it('ticket-level createDraft bills a DEAL_DIRECT approval made after the accepted legacy quotation', async () => {
    const { ticketId } = await driveTicketToAcceptedQuotation();
    retireLivePricingRequestsForTests(ticketId);
    const { quotation: draft } = await api.dealQuotations.create(ticketId, {
      recipientType: 'DESIGNER', depositPercent: 40, remainderMode: 'ON_DELIVERY',
      items: [ONE_ITEM],
    });
    await api.dealQuotations.submit(draft.id, {});
    await api.auth.login({ role: 'sales_manager' });
    const { quotation: approved } = await api.dealQuotations.approve(draft.id, {});
    await api.auth.login({ role: 'sales' });
    expect(approved.docStatus).toBe('APPROVED');

    const { depositNotice } = await api.depositNotices.createDraft(ticketId, {});
    expect(depositNotice.reference).toBe(approved.number);
    expect(depositNotice.depositPercent).toBe(0.4);
  });

  // Mirrors TicketService.applyCustomerConfirmation's policy sync (owner ruling 2026-10-22).
  it('confirmOrder on a 0% quotation sets depositPolicy NOT_REQUIRED, and both draft routes 409', async () => {
    const { ticketId, prId, accepted } = await driveTicketToNewEngineAcceptedQuotation({
      depositPercent: 0, fullPaymentTerm: 'BEFORE_DELIVERY',
    });
    const { ticket } = await api.tickets.get(ticketId);
    expect(ticket.summary.depositPolicy).toBe('NOT_REQUIRED');
    expect(ticket.summary.depositPolicyReason).toContain(accepted.number);
    expect(ticket.events.filter((e) => e.kind === 'POLICY_CHANGED').map((e) => e.message))
      .toEqual([expect.stringContaining(accepted.number)]);
    await expect(api.depositNotices.createDraft(ticketId, {}))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining('ไม่ต้องเก็บมัดจำ') });
    await expect(api.pricingRequests.createDepositNoticeFromQuotation(prId, {}))
      .rejects.toMatchObject({ status: 409 });
    expect((await api.depositNotices.listByTicket(ticketId)).depositNotices).toHaveLength(0);
  });

  it('a 30% quotation leaves REQUIRED; once waived, both draft routes 409 on the policy', async () => {
    const { ticketId, prId } = await driveTicketToNewEngineAcceptedQuotation({ depositPercent: 30 });
    const { ticket } = await api.tickets.get(ticketId);
    expect(ticket.summary.depositPolicy).toBe('REQUIRED');
    expect(ticket.events.some((e) => e.kind === 'POLICY_CHANGED')).toBe(false);

    await api.tickets.setDepositPolicy(ticketId, { policy: 'WAIVED', reason: 'ลูกค้าโอนเต็มจำนวนล่วงหน้า' });
    const policyRefusal = { status: 409, message: expect.stringContaining('ไม่ต้องเก็บมัดจำ') };
    await expect(api.depositNotices.createDraft(ticketId, {})).rejects.toMatchObject(policyRefusal);
    await expect(api.pricingRequests.createDepositNoticeFromQuotation(prId, {})).rejects.toMatchObject(policyRefusal);
    expect((await api.depositNotices.listByTicket(ticketId)).depositNotices).toHaveLength(0);
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

// Owner ruling 2026-10-23 (mirrors DepositNoticeService.update): sales may change the deposit %
// on a DRAFT and the amount follows; 0 is refused; an ISSUED notice refuses every edit.
describe('mockApi.depositNotices.update deposit percent', () => {
  it('on a DRAFT takes the new percent and recomputes the amount; 0, over 100% and fractional are refused', async () => {
    const { ticketId } = await driveTicketToAcceptedQuotation();
    const { depositNotice: draft } = await api.depositNotices.createDraft(ticketId, { notes: [] });
    expect(draft.subtotal).toBeGreaterThan(0);

    // Items sent the way DepositNoticePage sends them on save.
    const { depositNotice: updated } = await api.depositNotices.update(draft.id, { depositPercent: 0.4, items: draft.items });
    expect(updated.depositPercent).toBe(0.4);
    expect(updated.depositAmount).toBe(Math.round(draft.subtotal * 0.4 * 100) / 100);

    // DepositNoticeDraftRequest's @DecimalMin/@DecimalMax run before the service (field-prefixed).
    await expect(api.depositNotices.update(draft.id, { depositPercent: 0 }))
      .rejects.toMatchObject({ status: 400, message: 'depositPercent เปอร์เซ็นต์มัดจำต้องมากกว่า 0' });
    await expect(api.depositNotices.update(draft.id, { depositPercent: 1.5 }))
      .rejects.toMatchObject({ status: 400, message: 'depositPercent เปอร์เซ็นต์มัดจำต้องไม่เกิน 100' });
    await expect(api.depositNotices.update(draft.id, { depositPercent: 0.405 }))
      .rejects.toMatchObject({ status: 400, message: 'เปอร์เซ็นต์มัดจำต้องเป็นจำนวนเต็ม' });
    const { depositNotice: afterRejects } = await api.depositNotices.get(draft.id);
    expect(afterRejects.depositPercent).toBe(0.4);
    expect(afterRejects.depositAmount).toBe(updated.depositAmount);
    // 0.29 * 100 is 28.999999999999996 in floating point; it is still a whole 29%.
    await api.depositNotices.update(draft.id, { depositPercent: 0.29, items: draft.items });
    const { depositNotice: reread } = await api.depositNotices.get(draft.id);
    expect(reread.depositPercent).toBe(0.29);
  });

  // @Valid runs before DepositNoticeService.update looks anything up, so a bad % is a 400 even
  // for a notice that does not exist or is already issued.
  it('validates the % before the 404 and 409 lookups', async () => {
    await api.auth.login({ role: 'sales' });
    await expect(api.depositNotices.update(999999, { depositPercent: 1.5 }))
      .rejects.toMatchObject({ status: 400 });
    const { ticketId } = await driveTicketToAcceptedQuotation();
    const { depositNotice: draft } = await api.depositNotices.createDraft(ticketId, { notes: [] });
    await api.tickets.confirmCustomer(ticketId);
    await api.depositNotices.issue(draft.id);
    await expect(api.depositNotices.update(draft.id, { depositPercent: 0 }))
      .rejects.toMatchObject({ status: 400 });
  });

  // Mirrors the stale-save check (DepositNoticeRepository#update): a save carrying an older
  // updatedAt than the stored one is refused; one carrying the current stamp, or none, saves.
  it('refuses a save whose expectedUpdatedAt is stale', async () => {
    const { ticketId } = await driveTicketToAcceptedQuotation();
    const { depositNotice: draft } = await api.depositNotices.createDraft(ticketId, { notes: [] });
    const { depositNotice: savedByA } = await api.depositNotices.update(draft.id,
      { depositPercent: 0.4, items: draft.items, expectedUpdatedAt: draft.updatedAt });

    await expect(api.depositNotices.update(draft.id,
      { depositPercent: 0.3, items: draft.items, expectedUpdatedAt: draft.updatedAt }))
      .rejects.toMatchObject({ status: 409, message: 'ใบแจ้งรับมัดจำนี้ถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง' });
    expect((await api.depositNotices.get(draft.id)).depositNotice.depositPercent).toBe(0.4);

    await api.depositNotices.update(draft.id, { depositPercent: 0.3, expectedUpdatedAt: savedByA.updatedAt });
    expect((await api.depositNotices.get(draft.id)).depositNotice.depositPercent).toBe(0.3);
  });

  // Mirrors DepositNoticeService.update/issue: sales only (requireRole), then the deal owner.
  it('refuses update and issue to a sales rep who does not own the deal, and to a non-sales role', async () => {
    const { ticketId } = await driveTicketToAcceptedQuotation();
    const { depositNotice: draft } = await api.depositNotices.createDraft(ticketId, { notes: [] });
    await api.tickets.confirmCustomer(ticketId);

    await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
    await expect(api.depositNotices.update(draft.id, { customerName: 'x' }))
      .rejects.toMatchObject({ status: 403, message: 'ไม่มีสิทธิ์เข้าถึงรายการนี้' });
    await expect(api.depositNotices.issue(draft.id)).rejects.toMatchObject({ status: 403 });
    await api.auth.login({ role: 'sales_manager' });
    await expect(api.depositNotices.update(draft.id, { customerName: 'x' })).rejects.toMatchObject({ status: 403 });
    await expect(api.depositNotices.issue(draft.id)).rejects.toMatchObject({ status: 403 });

    await api.auth.login({ role: 'sales' });
    const { depositNotice: reread } = await api.depositNotices.get(draft.id);
    expect(reread.status).toBe('DRAFT');
    expect(reread.customerName).toBe(draft.customerName);
  });

  it('on an ISSUED notice is refused with 409 and changes nothing; no second draft on either route', async () => {
    const { ticketId } = await driveTicketToAcceptedQuotation();
    const { depositNotice: draft } = await api.depositNotices.createDraft(ticketId, { notes: [] });
    const { depositNotice: secondDraft } = await api.depositNotices.createDraft(ticketId, { notes: [] });
    await api.tickets.confirmCustomer(ticketId);
    const { depositNotice: issued } = await api.depositNotices.issue(draft.id);

    // Owner ruling 2026-10-23: a second draft that predates the issue takes no number either.
    await expect(api.depositNotices.issue(secondDraft.id)).rejects.toMatchObject({
      status: 409,
      message: `ดีลนี้ออกใบแจ้งรับมัดจำเลขที่ ${issued.docNumber} แล้ว จึงออกฉบับร่างนี้ไม่ได้`,
    });
    const { depositNotice: secondReread } = await api.depositNotices.get(secondDraft.id);
    expect(secondReread.status).toBe('DRAFT');
    expect(secondReread.docNumber).toBeNull();
    expect((await api.depositNotices.get(issued.id)).depositNotice.status).toBe('ISSUED');

    await expect(api.depositNotices.update(issued.id, { depositPercent: 0.4 }))
      .rejects.toMatchObject({ status: 409 });
    const { depositNotice: reread } = await api.depositNotices.get(issued.id);
    expect(reread.depositPercent).toBe(issued.depositPercent);
    expect(reread.depositAmount).toBe(issued.depositAmount);

    // Owner ruling 2026-10-23 (DepositNoticeService.createDraftFromQuotation): one notice per deal.
    const message = `ดีลนี้ออกใบแจ้งรับมัดจำเลขที่ ${issued.docNumber} แล้ว จึงสร้างฉบับร่างใหม่ไม่ได้`;
    await expect(api.depositNotices.createDraft(ticketId, {})).rejects.toMatchObject({ status: 409, message });
    const prId = (await api.pricingRequests.listForTicket(ticketId)).items
      .find((pr) => pr.status === 'QUOTATION_ACCEPTED').id;
    await expect(api.pricingRequests.createDepositNoticeFromQuotation(prId, {})).rejects.toMatchObject({ status: 409, message });
    expect((await api.depositNotices.listByTicket(ticketId)).depositNotices).toHaveLength(2); // no third
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

// The mock's blank-percent defaults restate two Java constants; read them out of the source so the
// copies cannot drift (the dealTrackingMeta.test.js approach). Throws rather than skips when the
// files move, so the guard cannot quietly disable itself; comments are stripped first, so a
// commented-out declaration does not count.
function readBackendSource(relativePath) {
  for (let dir = process.cwd(), depth = 0; depth < 8; depth += 1, dir = dirname(dir)) {
    const candidate = resolve(dir, relativePath);
    if (existsSync(candidate)) return readFileSync(candidate, 'utf8');
  }
  throw new Error(`could not find ${relativePath} above ${process.cwd()}`);
}

function javaIntConstant(relativePath, name) {
  const match = stripComments(readBackendSource(relativePath)).match(new RegExp(`static final int ${name} = (\\d+);`));
  if (!match) throw new Error(`could not find int ${name} in ${relativePath}`);
  return Number(match[1]);
}

describe('mock deposit-percent defaults match the Java PDFs', () => {
  it('a blank new-engine percent is DealQuotationRenderAdapter.DEFAULT_DEPOSIT_PERCENT', () => {
    expect(MOCK_DEFAULT_DEPOSIT_PERCENT).toBe(javaIntConstant(
      'backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationRenderAdapter.java', 'DEFAULT_DEPOSIT_PERCENT'));
  });

  it('a legacy quotation is QuotationRenderer.LEGACY_DEPOSIT_PERCENT', () => {
    expect(MOCK_LEGACY_DEPOSIT_PERCENT).toBe(javaIntConstant(
      'backend/src/main/java/th/co/glr/hr/ticket/QuotationRenderer.java', 'LEGACY_DEPOSIT_PERCENT'));
  });
});

// Mirrors DepositNoticeService.issue's amount guard: a 0-baht notice must not take a document number.
describe('mockApi.depositNotices.issue refuses a 0-baht notice', () => {
  it('409s, and the draft stays DRAFT with no number', async () => {
    const { ticketId } = await driveTicketToNewEngineAcceptedQuotation({ depositPercent: 30 });
    // update refuses a 0% now, so a free line (net price 0) stands in for the Java test's jdbc insert.
    const { depositNotice: created } = await api.depositNotices.createDraft(ticketId, {});
    const { depositNotice: draft } = await api.depositNotices.update(created.id, {
      items: [{ seq: 1, description: 'Free item', qty: 2, unit: 'แผ่น', unitPrice: 0, netUnitPrice: 0 }],
    });
    expect(draft.depositAmount).toBe(0);
    await expect(api.depositNotices.issue(draft.id))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining('0 บาท') });
    const { depositNotice } = await api.depositNotices.get(draft.id);
    expect(depositNotice).toMatchObject({ status: 'DRAFT', docNumber: null });
  });
});
