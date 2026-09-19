import { describe, it, expect } from 'vitest';
import { api, applyImportRequestRollupMock } from './mockApi.js';

// PR-B REVIEW ROUND 1, S3 + S4: mockApi.storedImportRequests's own fidelity to
// ImportRequestService — see mockRequiredFactoryIds/the issue() payment-gate comments in
// mockApi.js for the code-level explanation of each fix. Drives a REAL ticket through the mock's
// own pricing-request chain end to end (adapted from mockApi.depositNotices.test.js's
// driveTicketToAcceptedQuotation, itself modeled on OrderConfirmationIntegrationTest), rather than
// stubbing api.storedImportRequests directly the way DealFulfilmentPanel.test.jsx/
// ImportFulfilmentPage.test.jsx do — those files pin the UI's OWN behaviour against a fake
// api object; this file is the one that actually exercises mockApi.js's internal logic, which is
// the thing S3/S4 changed.
//
// NOT authz evidence about production — CLAUDE.md is explicit that mock authz/behaviour is never
// authoritative for the real backend. These only prove the MOCK matches its own intended mirror
// (ImportRequestService#allFactoriesReceived / #requiredFactoryIds and
// TicketService#requireImportRequestIssuable), which is what actually broke.

let seq = 0;
// NOT the '88888888-8888-4888-8888-…' prefix demoSales.js's own seed generator uses (collides
// with real seeded pricing requests' clientRequestId, tripping the idempotency 409 on the very
// first call) — a distinct prefix avoids that.
function uuid() {
  seq += 1;
  return `deadbeef-0000-4000-8000-${String(seq).padStart(12, '0')}`;
}

/**
 * Drives a ticket with TWO items on TWO DIFFERENT factories (Panaria SpA / productId 1,
 * REFIN / productId 4 — see mockProductPrices's own seed) all the way to an accepted,
 * order-confirmed quotation: status quotation_issued, paymentStatus CUSTOMER_CONFIRMED,
 * salesStage ORDER_RECEIVED. This is the floor createDrafts requires (dealStageIndex >=
 * ORDER_RECEIVED) — there is no shortcut around the real pricing/quotation chain, so this
 * mirrors driveTicketToAcceptedQuotation but generalises the single-item body to N items/quotes.
 */
async function driveTwoFactoryTicketToOrderReceived() {
  const n = seq + 1;

  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท รายงานนำเข้าทดสอบ ${n} จำกัด`,
    taxId: `020000000${n}`,
    address: `${n} ถนนทดสอบ`,
    branch: 'สาขาทดสอบ',
    phone: '02-111-1111',
  });
  const { project } = await api.customers.createProject(customer.id, { name: `โครงการนำเข้าทดสอบ ${n}` });
  const { ticket: created } = await api.tickets.create({
    title: `ดีลนำเข้าทดสอบ ${n}`,
    priority: 'NORMAL',
    customerName: customer.name,
    customerId: customer.id,
    projectId: project.id,
    // `factory` set directly on the TICKET ITEM (not just the pricing-request item) —
    // reconcileTicketItemsFromPricingRequest only reconciles qty, never factory/catalogPriceId
    // back onto an existing ticket_item, so createDrafts's own resolution cascade
    // (mockResolveFactoryGroups) needs it here to have anything to resolve against.
    items: [
      { brand: 'Panaria', model: 'Ivory Lappato', qty: 10, currency: 'THB', factory: 'Panaria SpA' },
      { brand: 'REFIN', model: 'L-Trim', qty: 20, currency: 'THB', factory: 'REFIN' },
    ],
  });
  const ticketId = created.summary.id;
  const [itemA, itemB] = created.items;

  const { pricingRequest: draftPr } = await api.pricingRequests.create(ticketId, {
    recipientType: 'DESIGNER',
    recipientLabel: 'ผู้ออกแบบทดสอบ',
    clientRequestId: uuid(),
    items: [
      {
        sourceTicketItemId: itemA.id, productId: 1, brand: 'Panaria', model: 'Ivory Lappato',
        factory: 'Panaria SpA', requestedQty: 10, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE',
        quantityType: 'ESTIMATE',
      },
      {
        sourceTicketItemId: itemB.id, productId: 4, brand: 'REFIN', model: 'L-Trim',
        factory: 'REFIN', requestedQty: 20, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE',
        quantityType: 'ESTIMATE',
      },
    ],
  });
  const prId = draftPr.summary.id;
  await api.pricingRequests.submit(prId);

  await api.auth.login({ role: 'import' });
  await api.pricingRequests.pickup(prId);
  const { items: quotes } = await api.pricingRequests.generateFactoryEmailDrafts(prId);
  expect(quotes).toHaveLength(2); // one per factory — the whole point of this fixture.
  for (const quote of quotes) {
    await api.pricingRequests.receiveFactoryQuote(quote.id, {
      clientRequestId: uuid(),
      supplierQuoteRef: `REF-MOCK-${quote.id}`,
      defaultCurrency: 'THB',
      paymentTerms: '30 days',
      leadTimeText: '45 days',
      items: quote.items.map((it) => ({
        pricingRequestItemId: it.pricingRequestItemId,
        quotedQuantity: it.requestedQuantity ?? 10,
        quotedUnit: 'PER_PIECE',
        unitBasis: 'PER_PIECE',
        rawUnitPrice: 100,
        currency: 'THB',
      })),
    });
    await api.pricingRequests.markFactoryQuoteReady(quote.id);
  }
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

  const { ticket: detail } = await api.tickets.get(ticketId);
  expect(detail.summary.status).toBe('quotation_issued');
  expect(detail.summary.paymentStatus).toBe('CUSTOMER_CONFIRMED');

  return { ticketId };
}

describe('mockApi storedImportRequests — S3: rollup requires EVERY required factory issued+received', () => {
  it(
    'does NOT roll the deal up to GOODS_RECEIVED while a second required factory is still only DRAFT',
    async () => {
      const { ticketId } = await driveTwoFactoryTicketToOrderReceived();

      // Bypass the deposit-ready gate the cheap way: CEO sets a bypass policy, matching the
      // owner-approved "no deposit required" path — this test is about the ROLLUP, not the
      // payment gate (that is S4, covered separately below).
      await api.auth.login({ role: 'ceo' });
      await api.tickets.setDepositPolicy(ticketId, { policy: 'NOT_REQUIRED', reason: 'ทดสอบ S3' });

      await api.auth.login({ role: 'sales' });
      const { importRequests: drafts } = await api.storedImportRequests.createDrafts(ticketId, {});
      expect(drafts).toHaveLength(2); // Panaria SpA + REFIN.
      const panariaDraft = drafts.find((r) => r.factoryName === 'Panaria SpA');
      const refinDraft = drafts.find((r) => r.factoryName === 'REFIN');
      expect(panariaDraft).toBeTruthy();
      expect(refinDraft).toBeTruthy();

      // Issue and fully advance ONLY Panaria SpA — REFIN stays a DRAFT, never issued.
      const { importRequest: panariaIssued } = await api.storedImportRequests.issue(panariaDraft.id, {});
      await api.auth.login({ role: 'import' });
      for (const target of ['ORDERED', 'PICKED_UP', 'IN_TRANSIT', 'AWAITING_CUSTOMS']) {
        await api.storedImportRequests.advanceStep(panariaIssued.id, { targetStep: target });
      }
      await api.storedImportRequests.advanceStep(panariaIssued.id, { targetStep: 'RECEIVED' });

      // THE BUG THIS PINS: before S3, the rollup only checked "every ISSUED row is RECEIVED" —
      // with REFIN never issued, `liveRows` was just [Panaria], all RECEIVED, so the old mock
      // rolled the deal up here. The fixed mock must NOT, because REFIN is still a required
      // factory with no ISSUED row at all.
      const { ticket: afterPanaria } = await api.tickets.get(ticketId);
      expect(afterPanaria.summary.fulfillmentStatus).not.toBe('GOODS_RECEIVED');
      expect(afterPanaria.summary.fulfillmentStatus).toBe('IR_ISSUED');

      // Now issue and receive REFIN too — every required factory has an ISSUED row, all RECEIVED.
      await api.auth.login({ role: 'sales' });
      const { importRequest: refinIssued } = await api.storedImportRequests.issue(refinDraft.id, {});
      await api.auth.login({ role: 'import' });
      for (const target of ['ORDERED', 'PICKED_UP', 'IN_TRANSIT', 'AWAITING_CUSTOMS', 'RECEIVED']) {
        await api.storedImportRequests.advanceStep(refinIssued.id, { targetStep: target });
      }

      const { ticket: afterBoth } = await api.tickets.get(ticketId);
      expect(afterBoth.summary.fulfillmentStatus).toBe('GOODS_RECEIVED');
    },
    20000,
  );
});

describe('mockApi storedImportRequests — S4: bypass issue gate requires EXACTLY CUSTOMER_CONFIRMED', () => {
  it('issue() succeeds once a bypass-policy deal is genuinely CUSTOMER_CONFIRMED', async () => {
    const { ticketId } = await driveTwoFactoryTicketToOrderReceived();
    // driveTwoFactoryTicketToOrderReceived's own confirmOrder already leaves paymentStatus at
    // CUSTOMER_CONFIRMED — setting a bypass policy on TOP of that is exactly the "bypass-policy
    // deal that has actually reached CUSTOMER_CONFIRMED" case TicketService
    // #requireImportRequestIssuable's own Javadoc describes as the only one that may issue.
    await api.auth.login({ role: 'ceo' });
    await api.tickets.setDepositPolicy(ticketId, { policy: 'WAIVED', reason: 'ทดสอบ S4' });

    await api.auth.login({ role: 'sales' });
    const { importRequests: drafts } = await api.storedImportRequests.createDrafts(ticketId, {});
    const draft = drafts[0];
    const { importRequest: issued } = await api.storedImportRequests.issue(draft.id, {});
    expect(issued.status).toBe('ISSUED');
  });
});

// PR-B REVIEW ROUND 2, X5: applyImportRequestRollupMock's own firewall guards, mirroring
// TicketService#applyImportRequestRollup (backend/src/main/java/th/co/glr/hr/ticket/
// TicketService.java ~939-960) directly against a plain ticket-like object — see that exported
// function's own header comment in mockApi.js for why FULLY_DELIVERED and "already
// GOODS_RECEIVED" are not practical to reach end to end through storedImportRequests.advanceStep
// (unlike S3/S4 above, which do drive a real fixture through the full chain).
function fakeRollupTicket(over = {}) {
  return {
    id: 999001,
    code: 'TEST-ROLLUP',
    status: 'quotation_issued',
    fulfillmentStatus: 'IR_ISSUED',
    paymentStatus: 'CUSTOMER_CONFIRMED',
    salesStage: 'PROCUREMENT',
    lifecycle: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
    events: [],
    ...over,
  };
}
const rollupActor = { id: 900001, name: 'ทดสอบ Rollup' };

describe('applyImportRequestRollupMock — X5: firewall guards mirror TicketService#applyImportRequestRollup', () => {
  it('rolls a genuine import-axis deal up to GOODS_RECEIVED, advances DEPOSIT_PAID to AWAITING_FINAL_PAYMENT, and advances the stage', () => {
    const ticket = fakeRollupTicket({ fulfillmentStatus: 'SHIPPING', paymentStatus: 'DEPOSIT_PAID' });
    applyImportRequestRollupMock(ticket, rollupActor);
    expect(ticket.fulfillmentStatus).toBe('GOODS_RECEIVED');
    expect(ticket.paymentStatus).toBe('AWAITING_FINAL_PAYMENT');
    expect(ticket.salesStage).toBe('DELIVERY_SCHEDULING');
    expect(ticket.events.map((e) => e.kind)).toEqual(
      expect.arrayContaining(['AWAITING_FINAL_PAYMENT', 'GOODS_RECEIVED', 'STAGE_CHANGED']),
    );
  });

  it('writes nothing at all when the deal is already FULLY_DELIVERED — no status regression, no event', () => {
    const ticket = fakeRollupTicket({ fulfillmentStatus: 'FULLY_DELIVERED', paymentStatus: 'FULLY_PAID', salesStage: 'DELIVERED' });
    const eventsBefore = ticket.events.length;
    applyImportRequestRollupMock(ticket, rollupActor);
    expect(ticket.fulfillmentStatus).toBe('FULLY_DELIVERED');
    expect(ticket.paymentStatus).toBe('FULLY_PAID');
    expect(ticket.salesStage).toBe('DELIVERED');
    expect(ticket.events.length).toBe(eventsBefore);
  });

  it('writes nothing when the deal has already rolled up to GOODS_RECEIVED — no duplicate event, no re-run of autoAdvanceStage', () => {
    const ticket = fakeRollupTicket({ fulfillmentStatus: 'GOODS_RECEIVED', salesStage: 'DELIVERY_SCHEDULING' });
    const eventsBefore = ticket.events.length;
    applyImportRequestRollupMock(ticket, rollupActor);
    expect(ticket.fulfillmentStatus).toBe('GOODS_RECEIVED');
    expect(ticket.salesStage).toBe('DELIVERY_SCHEDULING');
    expect(ticket.events.length).toBe(eventsBefore);
  });

  it('writes ONLY the GOODS_RECEIVED event for a PARTIALLY_DELIVERED deal — no status/stage/payment write (Owner decision 3)', () => {
    const ticket = fakeRollupTicket({ fulfillmentStatus: 'PARTIALLY_DELIVERED', paymentStatus: 'DEPOSIT_PAID' });
    applyImportRequestRollupMock(ticket, rollupActor);
    expect(ticket.fulfillmentStatus).toBe('PARTIALLY_DELIVERED');
    expect(ticket.paymentStatus).toBe('DEPOSIT_PAID');
    expect(ticket.salesStage).toBe('PROCUREMENT');
    expect(ticket.events).toHaveLength(1);
    expect(ticket.events[0].kind).toBe('GOODS_RECEIVED');
  });

  it('writes nothing when the deal is not on the import axis at all (e.g. FROM_STOCK)', () => {
    const ticket = fakeRollupTicket({ fulfillmentStatus: 'FROM_STOCK' });
    const eventsBefore = ticket.events.length;
    applyImportRequestRollupMock(ticket, rollupActor);
    expect(ticket.fulfillmentStatus).toBe('FROM_STOCK');
    expect(ticket.events.length).toBe(eventsBefore);
  });
});
