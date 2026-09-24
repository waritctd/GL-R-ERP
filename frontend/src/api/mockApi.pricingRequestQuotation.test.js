import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// GLA-123 slice S1 (Phase 3, coordinator follow-up 2026-09-20) — mock coverage for
// DealQuotationService#createFromPricingRequest and #update's origin=PRICING_REQUEST behaviour:
// the extra-row refusal, and the pricing_request_item/pricing_decision_item link surviving a
// save. NOT authz/permission coverage (nothing here substitutes for the real-DB Java tests in
// PricingRequestQuotationIntegrationTest) — this only verifies the mock's OWN plumbing, per
// CLAUDE.md's "Mock API contract".

function nextClientRequestId() {
  return crypto.randomUUID();
}

/** Drives a fresh PCR (Phase 1 new-form shape) all the way to an APPROVED NET decision — mirrors
 * mockApi.pricingDecisionCeoPriceMode.test.js's own `startNewFormDecision` helper, extended to
 * also set NET mode (zero discount) and approve, since this file needs
 * pricing_request.status === APPROVED_FOR_QUOTATION to call createFromPricingRequest at all.
 * Returns { ticketId, prId, decision } with the session left logged in as the OWNING sales user. */
async function approvedNetPricingRequest() {
  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท PCR Quotation Mock ${nextClientRequestId()}`,
    taxId: '0100000000198',
    address: '999 ถนนทดสอบ',
    phone: '02-111-1298',
  });
  const { project } = await api.customers.createProject(customer.id, { name: 'โครงการ PCR Quotation Mock' });
  const { contact } = await api.customers.createContact(customer.id, {
    firstName: 'สมชาย', lastName: 'ทดสอบ', phone: '081-000-0000', email: 'contact-pcrq@example.com',
  });
  const { ticket: created } = await api.tickets.create({
    title: 'ดีล PCR Quotation Mock',
    priority: 'NORMAL',
    customerName: customer.name,
    customerId: customer.id,
    projectId: project.id,
    contactId: contact.id,
    items: [{ brand: 'SCG', model: 'Tile PCRQ Mock', qty: 10, currency: 'THB' }],
  });
  const ticketId = created.summary.id;
  const sourceItemId = created.items[0].id;

  const { pricingRequest: draftPr } = await api.pricingRequests.create(ticketId, {
    recipientType: 'DESIGNER',
    recipientLabel: 'ผู้ออกแบบทดสอบ PCRQ',
    clientRequestId: nextClientRequestId(),
    items: [{
      sourceTicketItemId: sourceItemId,
      productId: 1,
      brand: 'SCG',
      model: 'Tile PCRQ Mock',
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
    supplierQuoteRef: 'REF-PCRQ-MOCK',
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
  const { decision: approved } = await api.pricingRequests.approvePricingDecision(afterMode.id, {});

  await api.auth.login({ role: 'sales' });
  return { ticketId, prId, decision: approved };
}

/** M4(c)/(d)'s own fixture — the SAME chain as {@link approvedNetPricingRequest}, but with TWO
 * pricing-request items instead of one, so a drop test can remove ONE linked line while leaving
 * the quotation with at least one (both the real service and this mock refuse a fully-empty
 * item list on save — see buildItems'/buildDealQuotationItems' own "ต้องมีอย่างน้อยหนึ่งรายการ"). */
async function twoItemApprovedNetPricingRequest() {
  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท PCR Quotation Mock 2 ${nextClientRequestId()}`,
    taxId: '0100000000297',
    address: '999 ถนนทดสอบ',
    phone: '02-111-1297',
  });
  const { project } = await api.customers.createProject(customer.id, { name: 'โครงการ PCR Quotation Mock 2' });
  const { contact } = await api.customers.createContact(customer.id, {
    firstName: 'สมหญิง', lastName: 'ทดสอบ', phone: '081-000-0001', email: 'contact-pcrq2@example.com',
  });
  const { ticket: created } = await api.tickets.create({
    title: 'ดีล PCR Quotation Mock 2',
    priority: 'NORMAL',
    customerName: customer.name,
    customerId: customer.id,
    projectId: project.id,
    contactId: contact.id,
    items: [
      { brand: 'SCG', model: 'Tile PCRQ Mock A', qty: 10, currency: 'THB' },
      { brand: 'Cotto', model: 'Tile PCRQ Mock B', qty: 5, currency: 'THB' },
    ],
  });
  const ticketId = created.summary.id;

  function prItemInput(sourceTicketItemId, brand, model) {
    return {
      sourceTicketItemId, productId: 1, brand, model, factory: 'Panaria SpA', color: 'ขาว', texture: 'ด้าน',
      size: '60x60', thicknessMm: 10, sqmPerPiece: 0.36, quantityMode: 'PIECES', piecesInput: 10,
      wastageMode: 'NONE', piecesPerBox: 4, roundToFullBox: false, originCountry: 'ไทย-สต็อก',
      leadTimeMinDays: 3, leadTimeMaxDays: 7, quantityType: 'ESTIMATE',
    };
  }
  const { pricingRequest: draftPr } = await api.pricingRequests.create(ticketId, {
    recipientType: 'DESIGNER',
    recipientLabel: 'ผู้ออกแบบทดสอบ PCRQ2',
    clientRequestId: nextClientRequestId(),
    items: [
      prItemInput(created.items[0].id, 'SCG', 'Tile PCRQ Mock A'),
      prItemInput(created.items[1].id, 'Cotto', 'Tile PCRQ Mock B'),
    ],
  });
  const prId = draftPr.summary.id;
  await api.pricingRequests.submit(prId);

  await api.auth.login({ role: 'import' });
  await api.pricingRequests.pickup(prId);
  const { items: quotes } = await api.pricingRequests.generateFactoryEmailDrafts(prId);
  for (const quote of quotes) {
    await api.pricingRequests.receiveFactoryQuote(quote.id, {
      clientRequestId: nextClientRequestId(),
      supplierQuoteRef: `REF-PCRQ-MOCK2-${quote.id}`,
      defaultCurrency: 'THB',
      paymentTerms: '30 days',
      leadTimeText: '45 days',
      items: quote.items.map((it) => ({
        pricingRequestItemId: it.pricingRequestItemId,
        quotedQuantity: 10, quotedUnit: 'PER_PIECE', unitBasis: 'PER_PIECE', rawUnitPrice: 100, currency: 'THB',
      })),
    });
    await api.pricingRequests.markFactoryQuoteReady(quote.id);
  }
  const { costing } = await api.pricingRequests.createCosting(prId, {});
  await api.pricingRequests.recalculateCosting(costing.id, {});
  await api.pricingRequests.submitCosting(costing.id, {});

  await api.auth.login({ role: 'ceo' });
  const { decision: started } = await api.pricingRequests.startPricingDecision(prId, { defaultMarginPct: 0.2 });
  const { decision: afterMode } = await api.pricingRequests.updatePricingDecision(started.id, {
    priceMode: 'NET',
    items: started.items.map((it) => ({ pricingDecisionItemId: it.id, discountPct: 10 })),
  });
  const { decision: approved } = await api.pricingRequests.approvePricingDecision(afterMode.id, {});

  await api.auth.login({ role: 'sales' });
  return { ticketId, prId, decision: approved };
}

describe('mockApi.dealQuotations.createFromPricingRequest', () => {
  it('prefills a PRICING_REQUEST-origin quotation whose linked line net matches the decision net', async () => {
    const { prId, decision } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);
    expect(quotation.origin).toBe('PRICING_REQUEST');
    expect(quotation.pricingRequestId).toBe(prId);
    expect(quotation.priceMode).toBe('NET');
    expect(quotation.items).toHaveLength(1);
    const item = quotation.items[0];
    expect(item.ceoNetUnitPrice).toBeCloseTo(decision.items[0].netUnitPrice, 2);
    expect(item.netUnitPrice).toBeCloseTo(decision.items[0].netUnitPrice, 2);
    expect(item.priceChangedFromCeo).toBe(false);
  });

  // MINOR (Opus review, 2026-09-20) — mock parity: a second call for the same PR (a double-click;
  // this endpoint takes no clientRequestId to dedupe on) must not mint a duplicate DRAFT. Mirrors
  // DealQuotationService#createFromPricingRequest's own idempotent-replay guard
  // (findOpenDraftForPricingRequest) — see duplicateCreate_returnsTheSameOpenDraft_doesNotInsertASecondRow
  // on the backend IT.
  it('a second call for the same PR returns the SAME quotation, not a duplicate', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation: first } = await api.dealQuotations.createFromPricingRequest(prId);
    const { quotation: second } = await api.dealQuotations.createFromPricingRequest(prId);
    // A duplicate would have minted a DIFFERENT id — this is the assertion that matters.
    expect(second.id).toBe(first.id);
    expect(second.items).toHaveLength(first.items.length);
  });
});

describe('mockApi.dealQuotations.update — origin=PRICING_REQUEST extra-row refusal', () => {
  it('refuses a payload that adds a new row, and writes nothing', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);
    const existingItem = quotation.items[0];
    const extraRow = {
      lineType: 'PLAIN', description: 'ค่าติดตั้ง', quantity: 1, unit: 'JOB', unitPrice: 500,
    };
    await expect(api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      items: [
        { ...existingItem, id: existingItem.id },
        extraRow,
      ],
    })).rejects.toMatchObject({ status: 400 });

    const { quotation: reread } = await api.dealQuotations.get(quotation.id);
    expect(reread.items).toHaveLength(1);
  });
});

describe('mockApi.dealQuotations.update — origin=PRICING_REQUEST link carry-forward', () => {
  it('an edited linked line keeps its CEO-original fields (and the flag) across a second save', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);
    const item = quotation.items[0];
    const ceoDiscountPct = item.ceoDiscountPct;

    const { quotation: afterFirstEdit } = await api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      items: [{ ...item, id: item.id, discountPct: 25 }],
    });
    const changedItem = afterFirstEdit.items[0];
    expect(changedItem.priceChangedFromCeo).toBe(true);
    expect(changedItem.ceoDiscountPct).toBe(ceoDiscountPct);
    expect(changedItem.ceoNetUnitPrice).not.toBeNull();

    // Second save — the link must still be there (it was never sent on the wire; ItemInput has
    // no field for it), so reverting to the CEO's own discount clears the flag again.
    const { quotation: afterRevert } = await api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      items: [{ ...changedItem, id: changedItem.id, discountPct: ceoDiscountPct }],
    });
    const revertedItem = afterRevert.items[0];
    expect(revertedItem.priceChangedFromCeo).toBe(false);
    expect(revertedItem.ceoDiscountPct).toBe(ceoDiscountPct);
  });
});

// M4 (Opus review, 2026-09-20) — mock coverage for the identity guard and the drop/restore
// flag, mirroring DealQuotationService's requireLinkedLineIdentityUnchanged/#update/
// #restoreRemovedItem's own three behaviours.
describe('mockApi.dealQuotations.update — origin=PRICING_REQUEST M4(a)/(b) linked-line identity guard', () => {
  it('refuses changing a linked line away from TILE', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);
    const item = quotation.items[0];

    await expect(api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      items: [{
        id: item.id, lineType: 'PLAIN', description: 'เปลี่ยนชนิดรายการ', quantity: 1, unit: 'JOB', unitPrice: 500,
      }],
    })).rejects.toMatchObject({ status: 400 });

    const { quotation: reread } = await api.dealQuotations.get(quotation.id);
    expect(reread.items[0].lineType).toBe('TILE');
  });

  it('refuses changing a linked line\'s brand', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);
    const item = quotation.items[0];

    await expect(api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      items: [{ ...item, id: item.id, brand: 'ยี่ห้ออื่น' }],
    })).rejects.toMatchObject({ status: 400 });

    const { quotation: reread } = await api.dealQuotations.get(quotation.id);
    expect(reread.items[0].brand).toBe(item.brand);
  });

  it('allows changing a linked line\'s quantity (piecesInput)', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);
    const item = quotation.items[0];

    const { quotation: updated } = await api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      items: [{ ...item, id: item.id, piecesInput: 20 }],
    });
    expect(updated.items[0].piecesInput).toBe(20);
  });
});

describe('mockApi.dealQuotations.update/restoreRemovedItem — M4(c)/(d) drop flag and restore', () => {
  it('dropping one of two linked lines is allowed and flags the header count; restoring clears it', async () => {
    const { prId } = await twoItemApprovedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);
    expect(quotation.items).toHaveLength(2);
    expect(quotation.itemsRemovedFromCeoCount).toBe(0);
    const [kept, dropped] = quotation.items;

    const { quotation: afterDrop } = await api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      items: [{ ...kept, id: kept.id }],
    });
    expect(afterDrop.items).toHaveLength(1);
    expect(afterDrop.itemsRemovedFromCeoCount).toBe(1);
    expect(afterDrop.removedCeoItems).toHaveLength(1);
    expect(afterDrop.removedCeoItems[0].pricingDecisionItemId).toBe(dropped.pricingDecisionItemId);
    expect(afterDrop.removedCeoItems[0].brand).toBe(dropped.brand);

    const { quotation: restored } = await api.dealQuotations.restoreRemovedItem(
      quotation.id, dropped.pricingDecisionItemId,
    );
    expect(restored.items).toHaveLength(2);
    expect(restored.itemsRemovedFromCeoCount).toBe(0);
    expect(restored.removedCeoItems).toHaveLength(0);
    const restoredItem = restored.items.find((i) => i.id !== kept.id);
    expect(restoredItem.brand).toBe(dropped.brand);
    expect(restoredItem.id).not.toBe(dropped.id);
  });

  it('refuses restoring a line that is already present', async () => {
    const { prId } = await twoItemApprovedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);
    const item = quotation.items[0];

    await expect(api.dealQuotations.restoreRemovedItem(quotation.id, item.pricingDecisionItemId))
      .rejects.toMatchObject({ status: 409 });
  });
});

// MINOR fix (Opus review, 2026-09-20) — mock parity: listForTicket/list/counts used to include a
// PRICING_REQUEST-origin row, unlike the real DealQuotationRepository#findByTicket/#search/
// #counts, which are deliberately DEAL_DIRECT-only (see directDealSearch_doesNotIncludePricingRequestOriginRows
// on the backend IT). A PRICING_REQUEST-origin quotation has its own separate display surface
// (PricingRequestDetailPage's panel, M1) and must never leak into this one.
describe('mockApi.dealQuotations.listForTicket/list/counts — exclude PRICING_REQUEST origin (mock parity)', () => {
  it('listForTicket never returns a PRICING_REQUEST-origin row for the same ticket', async () => {
    const { ticketId, prId } = await approvedNetPricingRequest();
    await api.dealQuotations.createFromPricingRequest(prId);

    const { items } = await api.dealQuotations.listForTicket(ticketId);
    expect(items).toHaveLength(0);
  });

  it('list()/counts() never include a PRICING_REQUEST-origin row', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);

    const { items } = await api.dealQuotations.list({});
    expect(items.find((q) => q.id === quotation.id)).toBeUndefined();

    const counts = await api.dealQuotations.counts();
    // Every OTHER test in this mock instance may have left DEAL_DIRECT rows behind (the mock db
    // is process-wide, not reset between files) — this only asserts the PRICING_REQUEST row this
    // test itself just created is not among what the counts consider "all".
    const { items: allDealDirect } = await api.dealQuotations.list({});
    expect(counts.all).toBe(allDealDirect.length);
  });
});

// MINOR-3 fix (Opus re-review, 2026-09-20) — mock parity: refuse a restore once the document has
// switched away from Thai/THB, mirroring DealQuotationService#restoreRemovedItem exactly.
describe('mockApi.dealQuotations.restoreRemovedItem — MINOR-3 mock parity (language switch)', () => {
  it('refuses restoring once the document has switched to EN', async () => {
    const { prId } = await twoItemApprovedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);
    const [kept, dropped] = quotation.items;

    await api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      items: [{ ...kept, id: kept.id }],
    });
    await api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      documentLanguage: 'EN',
      items: [{ ...kept, id: kept.id }],
    });

    await expect(api.dealQuotations.restoreRemovedItem(quotation.id, dropped.pricingDecisionItemId))
      .rejects.toMatchObject({ status: 409 });
  });
});

// MINOR fix (Opus review, 2026-09-20) — the mock's dealQuotation authz helpers used to never look
// at `origin` at all, so an import/account user could GET a PRICING_REQUEST quotation (every
// ceo* field included) and a canCreateQuotation grant holder could write one under
// VITE_USE_MOCKS=true — both 403 against the real Java service (DealQuotationService
// #requireViewAccess / #requireEditAccessForQuotation). This is the "mock MORE permissive than
// production" shape CLAUDE.md names (#199): these tests only pin the mock's OWN plumbing so it
// stops actively lying in the dangerous direction while devs/QA drive it — they are NOT authz
// evidence; that is PricingRequestQuotationIntegrationTest's job against the real backend.
describe('mockApi.dealQuotations — origin=PRICING_REQUEST authz is NOT origin-blind (mock parity)', () => {
  it('import cannot GET a PRICING_REQUEST quotation (wrong-way-round: DEAL_DIRECT view roles include import)', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);

    await api.auth.login({ role: 'import' });
    await expect(api.dealQuotations.get(quotation.id)).rejects.toMatchObject({ status: 403 });
  });

  it('account cannot GET a PRICING_REQUEST quotation (wrong-way-round: DEAL_DIRECT view roles include account)', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);

    await api.auth.login({ role: 'account' });
    await expect(api.dealQuotations.get(quotation.id)).rejects.toMatchObject({ status: 403 });
  });

  it('ceo (a PRICING_REQUEST_VIEW_ROLES member) CAN still GET it — positive control for the test above', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);

    await api.auth.login({ role: 'ceo' });
    await expect(api.dealQuotations.get(quotation.id)).resolves.toBeDefined();
  });

  it('a canCreateQuotation grant holder cannot update a PRICING_REQUEST quotation (the grant bypasses DEAL_DIRECT write access, but must NOT bypass this origin\'s narrower gate)', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);

    await api.auth.login({ role: 'employee' }); // id 4, canCreateQuotation: true (see mockApi.dealQuotations.test.js's #H4 suite)
    await expect(api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      items: [{ ...quotation.items[0], id: quotation.items[0].id }],
    })).rejects.toMatchObject({ status: 403 });
  });

  it('a canCreateQuotation grant holder cannot cancel a PRICING_REQUEST quotation', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);

    await api.auth.login({ role: 'employee' });
    await expect(api.dealQuotations.cancel(quotation.id, {})).rejects.toMatchObject({ status: 403 });
  });

  it('a canCreateQuotation grant holder cannot restore a removed CEO-linked line on a PRICING_REQUEST quotation', async () => {
    const { prId } = await twoItemApprovedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);
    const [kept, dropped] = quotation.items;
    await api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      items: [{ ...kept, id: kept.id }],
    });

    await api.auth.login({ role: 'employee' });
    await expect(api.dealQuotations.restoreRemovedItem(quotation.id, dropped.pricingDecisionItemId))
      .rejects.toMatchObject({ status: 403 });
  });

  it('sales_manager (in EDIT_ROLES) CAN still update a PRICING_REQUEST quotation on a deal they do not own — positive control', async () => {
    const { prId } = await approvedNetPricingRequest();
    const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);

    await api.auth.login({ role: 'sales_manager' });
    await expect(api.dealQuotations.update(quotation.id, {
      priceMode: quotation.priceMode,
      items: [{ ...quotation.items[0], id: quotation.items[0].id }],
    })).resolves.toBeDefined();
  });
});
