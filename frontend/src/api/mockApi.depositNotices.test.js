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

async function driveTicketToAcceptedQuotation() {
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
  // receiveFactoryQuote accepts a still-DRAFT quote (see its own status check) — the
  // send/dispatch step is a UX-polling simulation, not a gate this drive needs.
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
    items: decision.items.map((item) => ({ pricingDecisionItemId: item.id, minimumSellingPrice: 50 })),
  });
  await api.pricingRequests.approvePricingDecision(decision.id, {});

  await api.auth.login({ role: 'sales' });
  const { quotation: draftQuotation } = await api.pricingRequests.createCustomerQuotation(prId, {});
  const { quotation: issued } = await api.pricingRequests.issueCustomerQuotation(draftQuotation.id, {});
  await api.pricingRequests.recordCustomerQuotationOutcome(issued.id, { outcome: 'ACCEPTED' });
  await api.pricingRequests.confirmOrder(prId, {});

  return { ticketId, customer, project, quotation: issued };
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
