import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// GLA-123 slice S2, REWORKED (owner reversed the dual-approval design, 2026-09-20): mock coverage
// for DealQuotationService#approveAndIssuePricingRequestOrigin/#requireCeoApprovalIfChanged — ONE
// approval (sales_manager OR ceo) issues the document, and a sales_manager is refused once the
// quotation no longer matches the CEO's own decision. NOT authz/permission evidence — see
// CLAUDE.md's "Mock API contract": the mock's role gates approximate the Java service and are NOT
// authoritative. The real-DB test matrix (unchanged/changed x each role, mutation-checked) lives in
// backend/.../DealQuotationPricingRequestApprovalIntegrationTest — this file only verifies the mock's OWN
// plumbing stays green for the same happy/unhappy paths a coding agent would drive under
// VITE_USE_MOCKS=true.

function nextClientRequestId() {
  return crypto.randomUUID();
}

/** Drives a fresh PCR to an APPROVED NET decision, then createFromPricingRequest — the shared
 * starting point (a DRAFT quotation, unchanged from the CEO's decision) every test below builds
 * on. Mirrors mockApi.pricingRequestQuotation.test.js's own `approvedNetPricingRequest` helper,
 * extended one step further (createFromPricingRequest) since this file always needs the row. */
async function draftPricingRequestQuotation() {
  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท S2 Approval Mock ${nextClientRequestId()}`,
    taxId: '0100000000396',
    address: '999 ถนนทดสอบ',
    phone: '02-111-1396',
  });
  const { project } = await api.customers.createProject(customer.id, { name: 'โครงการ S2 Approval Mock' });
  const { contact } = await api.customers.createContact(customer.id, {
    firstName: 'สมศักดิ์', lastName: 'ทดสอบ', phone: '081-000-0002', email: 'contact-s2approval@example.com',
  });
  const { ticket: created } = await api.tickets.create({
    title: 'ดีล S2 Approval Mock',
    priority: 'NORMAL',
    customerName: customer.name,
    customerId: customer.id,
    projectId: project.id,
    contactId: contact.id,
    items: [{ brand: 'SCG', model: 'Tile S2 Approval Mock', qty: 10, currency: 'THB' }],
  });
  const ticketId = created.summary.id;
  const sourceItemId = created.items[0].id;

  const { pricingRequest: draftPr } = await api.pricingRequests.create(ticketId, {
    recipientType: 'DESIGNER',
    recipientLabel: 'ผู้ออกแบบทดสอบ S2',
    clientRequestId: nextClientRequestId(),
    items: [{
      sourceTicketItemId: sourceItemId,
      productId: 1,
      brand: 'SCG',
      model: 'Tile S2 Approval Mock',
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
  const quote = quotes[0];
  const prItemId = quote.items[0].pricingRequestItemId;
  await api.pricingRequests.receiveFactoryQuote(quote.id, {
    clientRequestId: nextClientRequestId(),
    supplierQuoteRef: 'REF-S2-APPROVAL-MOCK',
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
  const { decision: started } = await api.pricingRequests.startPricingDecision(prId, { defaultMarginPct: 0.2 });
  const { decision: afterMode } = await api.pricingRequests.updatePricingDecision(started.id, {
    priceMode: 'NET', items: [{ pricingDecisionItemId: started.items[0].id, discountPct: 10 }],
  });
  await api.pricingRequests.approvePricingDecision(afterMode.id, {});

  await api.auth.login({ role: 'sales' });
  const { quotation: draftQuotation } = await api.dealQuotations.createFromPricingRequest(prId);
  return { ticketId, prId, quotationId: draftQuotation.id };
}

/** The shared starting point for the "unchanged" test cases — a submitted, unmodified quotation
 * at PENDING_APPROVAL, whose current price still matches the CEO's decision exactly. */
async function pendingApprovalPricingRequestQuotation() {
  const { ticketId, prId, quotationId } = await draftPricingRequestQuotation();
  await api.auth.login({ role: 'sales' });
  const { quotation: submitted } = await api.dealQuotations.submit(quotationId);
  return { ticketId, prId, quotationId: submitted.id };
}

/** The starting point for the "changed" test cases — a submitted quotation whose first line's
 * discount no longer matches the CEO's own decision (10%), so `priceChangedFromCeo` is true. */
async function changedPendingApprovalPricingRequestQuotation() {
  const { ticketId, prId, quotationId } = await draftPricingRequestQuotation();
  await api.auth.login({ role: 'sales' });
  const { quotation: draftQuotation } = await api.dealQuotations.get(quotationId);
  const item = draftQuotation.items[0];
  const { quotation: changed } = await api.dealQuotations.update(quotationId, {
    priceMode: draftQuotation.priceMode,
    items: [{ ...item, id: item.id, discountPct: 25 }],
  });
  expect(changed.items[0].priceChangedFromCeo).toBe(true);
  const { quotation: submitted } = await api.dealQuotations.submit(quotationId);
  return { ticketId, prId, quotationId: submitted.id };
}

describe('mockApi dealQuotations approval (GLA-123 slice S2, reworked)', () => {
  it('submit reaches PENDING_APPROVAL for a PRICING_REQUEST-origin quotation', async () => {
    const { quotationId } = await pendingApprovalPricingRequestQuotation();
    const { quotation } = await api.dealQuotations.get(quotationId);
    expect(quotation.docStatus).toBe('PENDING_APPROVAL');
  });

  it('sales_manager alone issues an UNCHANGED quotation, and is the printed approver', async () => {
    const { quotationId } = await pendingApprovalPricingRequestQuotation();
    const { user: smUser } = await api.auth.login({ role: 'sales_manager' });
    const { quotation } = await api.dealQuotations.approve(quotationId, {});
    expect(quotation.docStatus).toBe('ISSUED');
    expect(quotation.approvedById).toBe(smUser.employeeId);
  });

  it('ceo alone issues an UNCHANGED quotation', async () => {
    const { quotationId } = await pendingApprovalPricingRequestQuotation();
    const { user: ceoUser } = await api.auth.login({ role: 'ceo' });
    const { quotation } = await api.dealQuotations.approve(quotationId, {});
    expect(quotation.docStatus).toBe('ISSUED');
    expect(quotation.approvedById).toBe(ceoUser.employeeId);
  });

  it('a second approve on an already-ISSUED row conflicts', async () => {
    const { quotationId } = await pendingApprovalPricingRequestQuotation();
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(quotationId, {});
    await api.auth.login({ role: 'ceo' });
    await expect(api.dealQuotations.approve(quotationId, {})).rejects.toMatchObject({ status: 409 });
  });

  it('sales_manager is refused on a CHANGED quotation, and nothing is written', async () => {
    const { quotationId } = await changedPendingApprovalPricingRequestQuotation();
    await api.auth.login({ role: 'sales_manager' });
    await expect(api.dealQuotations.approve(quotationId, {})).rejects.toMatchObject({ status: 403 });
    const { quotation } = await api.dealQuotations.get(quotationId);
    expect(quotation.docStatus).toBe('PENDING_APPROVAL');
    expect(quotation.approvedById).toBeNull();
  });

  it('ceo still issues a CHANGED quotation', async () => {
    const { quotationId } = await changedPendingApprovalPricingRequestQuotation();
    const { user: ceoUser } = await api.auth.login({ role: 'ceo' });
    const { quotation } = await api.dealQuotations.approve(quotationId, {});
    expect(quotation.docStatus).toBe('ISSUED');
    expect(quotation.approvedById).toBe(ceoUser.employeeId);
  });

  it('reject by either role returns to DRAFT with the reason', async () => {
    const { quotationId } = await pendingApprovalPricingRequestQuotation();
    await api.auth.login({ role: 'ceo' });
    const { quotation } = await api.dealQuotations.reject(quotationId, { reason: 'ราคาไม่เหมาะสม' });
    expect(quotation.docStatus).toBe('DRAFT');
    expect(quotation.approvalNote).toBe('ราคาไม่เหมาะสม');
  });

  // 'qc' has no dedicated login in this mock's demo user set (src/data/demoData.js seeds it under
  // role: 'employee' — see that file's own comment); the real qc-role refusal is covered by the
  // real-DB DealQuotationPricingRequestApprovalIntegrationTest#approve_byQc_forbidden_nothingWritten
  // instead, per CLAUDE.md's "verify authz against the Java service, never the mock".
  it('a wrong-way-round role (sales, import, account) cannot approve at all', async () => {
    const { quotationId } = await pendingApprovalPricingRequestQuotation();
    for (const role of ['sales', 'import', 'account']) {
      await api.auth.login({ role });
      await expect(api.dealQuotations.approve(quotationId, {})).rejects.toMatchObject({ status: 403 });
    }
  });
});
