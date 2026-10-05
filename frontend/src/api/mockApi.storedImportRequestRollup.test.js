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
 *
 * Returns `{ ticketId, prId }` — the pricing request id too, which the D1 tests below need to
 * raise the deal's deposit notice (createDepositNoticeFromQuotation keys on it). The session is
 * left logged in as `sales`, the deal's owning rep.
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
    entryChannel: 'DESIGNER_LED',
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
        // V185/GLA-125: the direct-deal item fields are required on a pricing request item.
        color: 'ขาว', texture: 'ด้าน', size: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
        quantityMode: 'PIECES', piecesInput: 10, wastageMode: 'NONE', piecesPerBox: 4, roundToFullBox: false,
        originCountry: 'อิตาลี', leadTimeMinDays: 75, leadTimeMaxDays: 90,
      },
      {
        sourceTicketItemId: itemB.id, productId: 4, brand: 'REFIN', model: 'L-Trim',
        factory: 'REFIN', requestedQty: 20, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE',
        quantityType: 'ESTIMATE',
        // V185/GLA-125: the direct-deal item fields are required on a pricing request item.
        color: 'ขาว', texture: 'ด้าน', size: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
        quantityMode: 'PIECES', piecesInput: 20, wastageMode: 'NONE', piecesPerBox: 4, roundToFullBox: false,
        originCountry: 'อิตาลี', leadTimeMinDays: 75, leadTimeMaxDays: 90,
      },
    ],
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

  return { ticketId, prId };
}

describe('mockApi storedImportRequests — S3: rollup requires EVERY required factory issued+received', () => {
  it(
    'does NOT roll the deal up to GOODS_RECEIVED while a second required factory is still only DRAFT',
    async () => {
      const { ticketId } = await driveTwoFactoryTicketToOrderReceived();

      // Bypass the deposit-ready gate the cheap way: the owning rep sets a bypass policy,
      // matching the owner-approved "no deposit required" path — this test is about the ROLLUP,
      // not the payment gate (that is S4, covered separately below).
      // GLA-118 (owner ruling 2026-09-17): deposit policy is set by the OWNING sales rep only now
      // — driveTwoFactoryTicketToOrderReceived already leaves the session logged in as 'sales'
      // (the ticket's creator, since login({role}) always resolves the same seed user for a
      // given role), so this used to switch to 'ceo' for the call and is no longer allowed to.
      await api.tickets.setDepositPolicy(ticketId, { policy: 'NOT_REQUIRED', reason: 'ทดสอบ S3' });

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
      // Read as import through ITS OWN per-deal view: the whole-deal GET /api/tickets/{id} is
      // refused to import now (feat/import-own-page), and importDeals.get carries the same
      // server-computed fulfillmentStatus rollup.
      const { deal: afterPanaria } = await api.importDeals.get(ticketId);
      expect(afterPanaria.fulfillmentStatus).not.toBe('GOODS_RECEIVED');
      expect(afterPanaria.fulfillmentStatus).toBe('IR_ISSUED');

      // Now issue and receive REFIN too — every required factory has an ISSUED row, all RECEIVED.
      await api.auth.login({ role: 'sales' });
      const { importRequest: refinIssued } = await api.storedImportRequests.issue(refinDraft.id, {});
      await api.auth.login({ role: 'import' });
      for (const target of ['ORDERED', 'PICKED_UP', 'IN_TRANSIT', 'AWAITING_CUSTOMS', 'RECEIVED']) {
        await api.storedImportRequests.advanceStep(refinIssued.id, { targetStep: target });
      }

      const { deal: afterBoth } = await api.importDeals.get(ticketId);
      expect(afterBoth.fulfillmentStatus).toBe('GOODS_RECEIVED');
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
    // GLA-118: deposit policy is set by the OWNING sales rep only now -- see the matching note in
    // the S3 test above for why this no longer switches to 'ceo' first.
    await api.tickets.setDepositPolicy(ticketId, { policy: 'WAIVED', reason: 'ทดสอบ S4' });

    const { importRequests: drafts } = await api.storedImportRequests.createDrafts(ticketId, {});
    const draft = drafts[0];
    const { importRequest: issued } = await api.storedImportRequests.issue(draft.id, {});
    expect(issued.status).toBe('ISSUED');
  });
});

// D1 (owner rules 2026-10-05, C3): on a DEPOSIT deal (the accepted quotation asks more than 0%) the import
// request may be ISSUED only once ฝ่ายบัญชี has confirmed the deposit — issuing a deposit NOTICE is not enough. It holds
// on the per-factory route (storedImportRequests.issue) and the legacy one-click route
// (tickets.issueImportRequest), the advertised ISSUE_IMPORT_REQUEST action follows the same
// predicate, and preparing the draft stays allowed. Mirrors TicketService#requireImportRequestIssuable.
//
// Like everything in this file, it proves the MOCK mirrors that rule — never that production does
// (CLAUDE.md: the mock's behaviour is not authoritative); the real-DB evidence is the backend's
// ImportRequestPaymentGateIntegrationTest / PaymentTrackIntegrationTest. The deal here is built by
// the mock's real chain (order confirmed, deposit notice issued through api.depositNotices.issue),
// so the DEPOSIT_NOTICE_ISSUED it reaches is the state a real notice produces, not a stamped field.
describe('mockApi import request — D1: the deposit must be RECEIVED, a notice is not enough', () => {
  // The deal's summary as `role` sees it. The ticket's owner is the seeded sales user (login({role})
  // always resolves the same user for a role); import is refused the whole-deal GET, so read as sales.
  async function dealAs(role, ticketId) {
    await api.auth.login({ role });
    const { ticket } = await api.tickets.get(ticketId);
    return ticket.summary;
  }

  async function driveToDepositNoticeIssued() {
    const { ticketId, prId } = await driveTwoFactoryTicketToOrderReceived(); // leaves the session as sales
    const { depositNotice: doc } = await api.pricingRequests.createDepositNoticeFromQuotation(prId, {});
    await api.depositNotices.issue(doc.id);
    expect((await dealAs('sales', ticketId)).paymentStatus).toBe('DEPOSIT_NOTICE_ISSUED');
    return ticketId;
  }

  async function accountConfirmsTheDeposit(ticketId) {
    await api.auth.login({ role: 'account' });
    await api.finance.confirmDepositPaid(ticketId);
  }

  it('per-factory route: draft allowed, issue() refused (409, deposit message, nothing written) at notice-only, allowed once account confirms the deposit', async () => {
    const ticketId = await driveToDepositNoticeIssued();
    const before = await dealAs('sales', ticketId);

    // Preparing the form is still allowed — the rule gates ISSUING, not drafting.
    const { importRequests: drafts } = await api.storedImportRequests.createDrafts(ticketId, {});
    expect(drafts.length).toBeGreaterThan(0);
    const draft = drafts[0];
    expect(draft.status).toBe('DRAFT');

    // 409 AND the deposit message: a bare 409 could equally be "already issued" or "no items".
    await expect(api.storedImportRequests.issue(draft.id, {}))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining('มัดจำ') });

    // Nothing was written: still an unnumbered DRAFT, and the deal is exactly as it was.
    const { importRequest: untouched } = await api.storedImportRequests.get(draft.id);
    expect(untouched.status).toBe('DRAFT');
    expect(untouched.docNumber).toBeNull();
    const refused = await dealAs('sales', ticketId);
    expect(refused.fulfillmentStatus).toBeNull();
    expect(refused.salesStage).toBe(before.salesStage);
    expect(refused.paymentStatus).toBe('DEPOSIT_NOTICE_ISSUED');

    // ฝ่ายบัญชี confirms the deposit received — then the same draft issues.
    await accountConfirmsTheDeposit(ticketId);
    await api.auth.login({ role: 'sales' });
    const { importRequest: issued } = await api.storedImportRequests.issue(draft.id, {});
    expect(issued.status).toBe('ISSUED');
  }, 30000);

  it('legacy route: tickets.issueImportRequest is refused (409, deposit message, nothing written) at notice-only, allowed once account confirms the deposit', async () => {
    const ticketId = await driveToDepositNoticeIssued();
    const before = await dealAs('sales', ticketId);

    await api.auth.login({ role: 'import' });
    await expect(api.tickets.issueImportRequest(ticketId))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining('มัดจำ') });
    const refused = await dealAs('sales', ticketId);
    expect(refused.fulfillmentStatus).toBeNull();
    expect(refused.salesStage).toBe(before.salesStage);

    await accountConfirmsTheDeposit(ticketId);
    await api.auth.login({ role: 'import' });
    await api.tickets.issueImportRequest(ticketId);
    expect((await dealAs('sales', ticketId)).fulfillmentStatus).toBe('IR_ISSUED');
  }, 30000);

  it('advertised action: ISSUE_IMPORT_REQUEST is not listed at notice-only, and is listed once account confirms the deposit', async () => {
    const ticketId = await driveToDepositNoticeIssued();

    await api.auth.login({ role: 'ceo' });
    const atNotice = await api.tickets.actions(ticketId);
    expect(atNotice.availableActions.map((a) => a.action)).not.toContain('ISSUE_IMPORT_REQUEST');

    await accountConfirmsTheDeposit(ticketId);
    await api.auth.login({ role: 'ceo' });
    const afterDeposit = await api.tickets.actions(ticketId);
    // Without this the "not listed" half above would pass for an actions() that listed nothing at all.
    expect(afterDeposit.availableActions.map((a) => a.action)).toContain('ISSUE_IMPORT_REQUEST');
  }, 30000);
});

// Helpers shared by the D2 and D3 describes below. The D1 describe above has its own
// accountConfirmsTheDeposit, which leaves the session logged in as account; the one here is named
// differently because it hands the session back to the owning rep.

// The owning rep declares EVERY line from stock (allowed at any payment state — the
// declaration stays open). The session must be the owning rep, which is what the drive leaves it as.
async function declareEveryLineFromStock(ticketId) {
  const { ticket } = await api.tickets.get(ticketId);
  await api.tickets.reserveStock(ticketId, {
    lines: ticket.items.map((item) => ({ itemId: item.id, qtyFromStock: item.qty })),
  });
  return (await api.tickets.get(ticketId)).ticket;
}

async function issueTheDepositNotice(ticketId, prId) {
  await api.auth.login({ role: 'sales' });
  const { depositNotice: doc } = await api.pricingRequests.createDepositNoticeFromQuotation(prId, {});
  await api.depositNotices.issue(doc.id);
  expect((await api.tickets.get(ticketId)).ticket.summary.paymentStatus).toBe('DEPOSIT_NOTICE_ISSUED');
}

async function accountConfirmsTheDepositAndTheRepResumes(ticketId) {
  await api.auth.login({ role: 'account' });
  await api.finance.confirmDepositPaid(ticketId);
  await api.auth.login({ role: 'sales' });
}

// A deal whose ACCEPTED quotation asks 0% — no deposit step at all (owner rules 2026-10-05, C1) — with its
// order confirmed through the mock's real confirm-order call (dealQuotations.promoteToDeal: an APPROVED direct
// quotation -> CUSTOMER_CONFIRMED + ORDER_RECEIVED), built the way mockApi.promoteToDeal.test.js builds its
// deals. Not the deal-level switch (tickets.setDepositPolicy). The mock IGNORES the quotation's percentage
// today, so what a test on this deal pins is the over-holding guard for the day it does not. The session is
// left as the owning rep, who declares and delivers.
const ZERO_PERCENT_TILE = {
  brand: 'SCG', model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10,
  sqmPerPiece: 0.36, quantityMode: 'PIECES', piecesInput: 10, wastageMode: 'NONE', wastageValue: 0,
  piecesPerBox: null, unitPrice: 850, discountPct: 0, leadTimeMinDays: 30, leadTimeMaxDays: 45,
};

async function confirmedZeroPercentDeal() {
  await api.auth.login({ role: 'sales' });
  const { ticket } = await api.tickets.create({
    entryChannel: 'DESIGNER_LED', title: 'ดีลไม่รับมัดจำ', priority: 'NORMAL', customerName: 'ลูกค้า',
    projectId: 1, items: [], nextFollowUpAt: '2026-10-07',
  });
  const ticketId = ticket.summary.id;
  const { quotation: draft } = await api.dealQuotations.create(ticketId, {
    recipientType: 'DESIGNER', depositPercent: 0, fullPaymentTerm: 'ON_DELIVERY', items: [ZERO_PERCENT_TILE],
  });
  expect(draft.depositPercent).toBe(0);
  await api.dealQuotations.submit(draft.id);
  await api.auth.login({ role: 'sales_manager' });
  await api.dealQuotations.approve(draft.id);
  await api.auth.login({ role: 'sales' });
  await api.dealQuotations.promoteToDeal(draft.id); // the real confirmation of the order
  const { ticket: confirmed } = await api.tickets.get(ticketId);
  expect(confirmed.summary.paymentStatus).toBe('CUSTOMER_CONFIRMED');
  expect(confirmed.summary.salesStage).toBe('ORDER_RECEIVED');
  return ticketId;
}

// D2 (owner rules 2026-10-05, C5): on a DEPOSIT deal (the accepted quotation asks more than 0%) NOTHING IS
// DELIVERED until ฝ่ายบัญชี has confirmed the deposit — not on the customer's confirmation, not on a deposit
// NOTICE either. Partial or complete, from stock or from the warehouse. Declaring stock stays allowed, the
// balance is never required, and the advertised RECORD_PARTIAL_DELIVERY / COMPLETE_DELIVERY follow the same
// rule. A 0% quotation has no deposit step at all: delivery is allowed once the order is confirmed. Mirrors
// TicketService#recordPartialDelivery/completeDelivery and canRecordDelivery.
//
// Like everything in this file, it proves the MOCK mirrors that rule — never that production does
// (CLAUDE.md: the mock's behaviour is not authoritative); the real-DB evidence is the backend's
// DeliveryDepositGateIntegrationTest / PaymentTrackIntegrationTest. The deal is built by the mock's
// real chain, and the deposit notice is issued through api.depositNotices.issue, so the states
// reached are the ones a real order and a real notice produce, not stamped fields. Only the STOCK
// route is driven here; the warehouse route needs the whole import journey first and is pinned on
// the backend.
describe('mockApi delivery — D2: nothing is delivered until the deposit is RECEIVED', () => {
  const DELIVERY_ACTIONS = ['RECORD_PARTIAL_DELIVERY', 'COMPLETE_DELIVERY'];

  async function offeredActions(ticketId) {
    return (await api.tickets.actions(ticketId)).availableActions.map((a) => a.action);
  }

  it('stock route, deposit deal: the declaration is allowed, delivery is refused (409, deposit message, nothing delivered) on the confirmation and on the notice, and lands once account confirms the deposit', async () => {
    const { ticketId, prId } = await driveTwoFactoryTicketToOrderReceived(); // session = sales, the owner
    const declared = await declareEveryLineFromStock(ticketId);
    expect(declared.summary.fulfillmentStatus).toBe('FROM_STOCK');
    const first = declared.items[0];

    // 409 AND the deposit message: a bare 409 could equally be "over-delivery" or "receive the goods
    // first". And nothing is delivered: every line still 0, fulfilment and stage unchanged.
    async function expectBothWritersRefusedAndNothingDelivered() {
      await expect(api.tickets.recordDelivery(ticketId, { source: 'STOCK', lines: [{ itemId: first.id, qty: 4 }] }))
        .rejects.toMatchObject({ status: 409, message: expect.stringContaining('มัดจำ') });
      await expect(api.tickets.completeDelivery(ticketId, {}))
        .rejects.toMatchObject({ status: 409, message: expect.stringContaining('มัดจำ') });
      const { ticket } = await api.tickets.get(ticketId);
      expect(ticket.items.map((item) => Number(item.qtyDelivered ?? 0))).toEqual(ticket.items.map(() => 0));
      expect(ticket.summary.fulfillmentStatus).toBe(declared.summary.fulfillmentStatus);
      expect(ticket.summary.salesStage).toBe(declared.summary.salesStage);
    }

    // On the customer's confirmation alone...
    await expectBothWritersRefusedAndNothingDelivered();
    // ...and still not once the deposit NOTICE is issued: a notice is a document, not money.
    await issueTheDepositNotice(ticketId, prId);
    await expectBothWritersRefusedAndNothingDelivered();

    // ฝ่ายบัญชี confirms the deposit received — then the rep's completeDelivery lands, with only the
    // deposit paid (the balance is never required).
    await accountConfirmsTheDepositAndTheRepResumes(ticketId);
    await api.tickets.completeDelivery(ticketId, {});
    const { ticket: delivered } = await api.tickets.get(ticketId);
    expect(delivered.summary.fulfillmentStatus).toBe('FULLY_DELIVERED');
    expect(delivered.summary.paymentStatus).toBe('DEPOSIT_PAID');
  }, 40000);

  it('advertised actions: RECORD_PARTIAL_DELIVERY / COMPLETE_DELIVERY are not listed to the owning rep before the deposit is received, and are once account confirms it', async () => {
    const { ticketId, prId } = await driveTwoFactoryTicketToOrderReceived();
    await declareEveryLineFromStock(ticketId); // a source exists, so delivery is otherwise possible

    const onConfirmation = await offeredActions(ticketId);
    DELIVERY_ACTIONS.forEach((action) => expect(onConfirmation).not.toContain(action));
    await issueTheDepositNotice(ticketId, prId);
    const onNotice = await offeredActions(ticketId);
    DELIVERY_ACTIONS.forEach((action) => expect(onNotice).not.toContain(action));

    await accountConfirmsTheDepositAndTheRepResumes(ticketId);
    // Without this the "not listed" halves above would pass for an actions() that listed nothing at all.
    const afterDeposit = await offeredActions(ticketId);
    DELIVERY_ACTIONS.forEach((action) => expect(afterDeposit).toContain(action));
  }, 40000);

  // GREEN today: guard against over-correcting. A 0% quotation has no deposit step, so the confirmed order
  // alone opens delivery — with nothing paid.
  it('a 0% quotation deal: the rep declares stock, the actions are offered, and completeDelivery succeeds with nothing paid', async () => {
    const ticketId = await confirmedZeroPercentDeal();
    await declareEveryLineFromStock(ticketId);

    const offered = await offeredActions(ticketId);
    DELIVERY_ACTIONS.forEach((action) => expect(offered).toContain(action));
    await api.tickets.completeDelivery(ticketId, {});

    const { ticket } = await api.tickets.get(ticketId);
    expect(ticket.summary.fulfillmentStatus).toBe('FULLY_DELIVERED');
    expect(ticket.summary.paymentStatus).toBe('CUSTOMER_CONFIRMED'); // nothing was paid
  }, 40000);
});

// D3 (owner rules 2026-10-05, C4): declaring FULL stock must not move a DEPOSIT deal past ขั้น 10
// (ORDER_RECEIVED) before the deposit is CONFIRMED. Declaring stays allowed at any payment state, and
// everything a declaration writes today it still writes — each line's qtyFromStock, the STOCK_RESERVED
// event, fulfillmentStatus FROM_STOCK. ONLY the stage move waits: it happens at the moment ฝ่ายบัญชี
// confirms the deposit (DEPOSIT_PAID). A 0% quotation has no deposit step, so it moves at once.
// Mirrors TicketService#reserveStock.
//
// Like everything in this file, it proves the MOCK mirrors that rule — never that production does
// (CLAUDE.md: the mock's behaviour is not authoritative); the real-DB evidence is the backend's
// StockDeclarationDepositHoldIntegrationTest / PaymentTrackIntegrationTest. Every deal here is already at
// ORDER_RECEIVED, built by the mock's real chain; the stage FLOOR on a declaration (refused below
// ORDER_RECEIVED) is a different rule and is not exercised here.
describe('mockApi stock declaration — D3: the stage waits for the deposit', () => {
  it('deposit deal / CUSTOMER_CONFIRMED: the declaration is saved but the stage holds at ORDER_RECEIVED — through the deposit notice — and moves to DELIVERY_SCHEDULING when account confirms the deposit', async () => {
    const { ticketId, prId } = await driveTwoFactoryTicketToOrderReceived(); // session = sales, the owner
    const declared = await declareEveryLineFromStock(ticketId);

    // Everything the declaration writes today, it still writes (all of this holds today)...
    expect(declared.summary.fulfillmentStatus).toBe('FROM_STOCK');
    expect(declared.summary.paymentStatus).toBe('CUSTOMER_CONFIRMED');
    expect(declared.items.length).toBeGreaterThan(0);
    declared.items.forEach((item) => expect(Number(item.qtyFromStock)).toBe(Number(item.qty)));
    // ...except the stage move, which waits for the deposit.
    expect(declared.summary.salesStage).toBe('ORDER_RECEIVED');

    // A deposit NOTICE is a document, not money — the stage is still held.
    await issueTheDepositNotice(ticketId, prId);
    expect((await api.tickets.get(ticketId)).ticket.summary.salesStage).toBe('ORDER_RECEIVED');

    // ฝ่ายบัญชี confirms the deposit received — the held deal goes on to DELIVERY_SCHEDULING.
    await accountConfirmsTheDepositAndTheRepResumes(ticketId);
    const { ticket: released } = await api.tickets.get(ticketId);
    expect(released.summary.salesStage).toBe('DELIVERY_SCHEDULING');
    expect(released.summary.fulfillmentStatus).toBe('FROM_STOCK');
  }, 40000);

  // GREEN today, and the half that ties the hold to its release: asserts ONLY the end state, never the
  // hold in the middle. It passes today because the stage jumped early; after a fix that held the stage
  // but FORGOT the release it goes red.
  it('the release half on its own: declare, issue the notice, account confirms the deposit — the deal ends at DELIVERY_SCHEDULING and FROM_STOCK', async () => {
    const { ticketId, prId } = await driveTwoFactoryTicketToOrderReceived();
    await declareEveryLineFromStock(ticketId);
    await issueTheDepositNotice(ticketId, prId);
    await accountConfirmsTheDepositAndTheRepResumes(ticketId);

    const { ticket } = await api.tickets.get(ticketId);
    expect(ticket.summary.paymentStatus).toBe('DEPOSIT_PAID');
    expect(ticket.summary.fulfillmentStatus).toBe('FROM_STOCK');
    expect(ticket.summary.salesStage).toBe('DELIVERY_SCHEDULING');
  }, 40000);

  // GREEN today: guard against over-holding. A 0% quotation has no deposit step, so the confirmed order
  // alone is enough and the declaration moves the deal on at once — with nothing paid.
  it('a 0% quotation deal: the declaration moves it to DELIVERY_SCHEDULING at once', async () => {
    const ticketId = await confirmedZeroPercentDeal();
    const declared = await declareEveryLineFromStock(ticketId);

    expect(declared.summary.fulfillmentStatus).toBe('FROM_STOCK');
    expect(declared.summary.paymentStatus).toBe('CUSTOMER_CONFIRMED'); // nothing was paid
    expect(declared.summary.salesStage).toBe('DELIVERY_SCHEDULING');
  }, 40000);
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
