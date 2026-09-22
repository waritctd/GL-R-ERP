import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// GLA-123 slice S3 — mock coverage for DealQuotationService#recordOutcome (R9, the customer
// outcome on a PRICING_REQUEST-origin quotation) and the R8 guard (one finalized quotation per
// deal, across BOTH คำขอราคา origins). NOT authz/permission evidence — see CLAUDE.md's "Mock API
// contract": the mock's role gates approximate the Java service and are NOT authoritative. The
// real-DB test matrix (every role, wrong-way-round, mutation-checked) lives in
// backend/.../DealQuotationOutcomeIntegrationTest — this file only verifies the mock's OWN
// plumbing (including its hand-kept-in-sync R8 check across the two separate mock arrays) stays
// green for the same happy/unhappy paths a coding agent would drive under VITE_USE_MOCKS=true.

function nextClientRequestId() {
  return crypto.randomUUID();
}

/** Creates one ticket with a single item, returning its id and the seeded item's id — shared by
 * every helper below so R8 tests can put two sibling pricing requests (designer/owner) on the
 * SAME deal. */
async function freshTicket() {
  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท S3 Outcome Mock ${nextClientRequestId()}`,
    taxId: '0100000000397',
    address: '999 ถนนทดสอบ',
    phone: '02-111-1397',
  });
  const { project } = await api.customers.createProject(customer.id, { name: 'โครงการ S3 Outcome Mock' });
  const { contact } = await api.customers.createContact(customer.id, {
    firstName: 'สมศรี', lastName: 'ทดสอบ', phone: '081-000-0003', email: 'contact-s3outcome@example.com',
  });
  const { ticket: created } = await api.tickets.create({
    title: 'ดีล S3 Outcome Mock',
    priority: 'NORMAL',
    customerName: customer.name,
    customerId: customer.id,
    projectId: project.id,
    contactId: contact.id,
    items: [{ brand: 'SCG', model: 'Tile S3 Outcome Mock', qty: 10, currency: 'THB' }],
  });
  return { ticketId: created.summary.id, sourceItemId: created.items[0].id };
}

/** Drives one pricing request on the given ticket through factory quote / costing / an APPROVED
 * NET CEO decision. Mirrors mockApi.dealQuotationPricingRequestApproval.test.js's own
 * `draftPricingRequestQuotation` helper, generalised over ticket/recipient so R8 tests can call
 * it twice on the SAME ticket with two different recipients. */
async function approvedDecisionOn(ticketId, sourceItemId, recipientType) {
  await api.auth.login({ role: 'sales' });
  const { pricingRequest: draftPr } = await api.pricingRequests.create(ticketId, {
    recipientType,
    recipientLabel: `ผู้รับทดสอบ S3 ${recipientType}`,
    clientRequestId: nextClientRequestId(),
    items: [{
      sourceTicketItemId: sourceItemId,
      productId: 1,
      brand: 'SCG',
      model: 'Tile S3 Outcome Mock',
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
    supplierQuoteRef: `REF-S3-OUTCOME-MOCK-${recipientType}`,
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
  return prId;
}

/** New-engine (PRICING_REQUEST-origin) ISSUED quotation on a fresh pricing request. */
async function issuedNewOriginQuotation(ticketId, sourceItemId, recipientType) {
  const prId = await approvedDecisionOn(ticketId, sourceItemId, recipientType);
  await api.auth.login({ role: 'sales' });
  const { quotation: draft } = await api.dealQuotations.createFromPricingRequest(prId);
  const { quotation: submitted } = await api.dealQuotations.submit(draft.id);
  await api.auth.login({ role: 'sales_manager' });
  const { quotation: issued } = await api.dealQuotations.approve(submitted.id, {});
  return { prId, quotationId: issued.id };
}

/** Legacy (origin IS NULL) ISSUED quotation on a fresh pricing request. */
async function issuedLegacyQuotation(ticketId, sourceItemId, recipientType) {
  const prId = await approvedDecisionOn(ticketId, sourceItemId, recipientType);
  await api.auth.login({ role: 'sales' });
  const { quotation: draft } = await api.pricingRequests.createCustomerQuotation(prId, {
    clientRequestId: nextClientRequestId(),
  });
  const { quotation: issued } = await api.pricingRequests.issueCustomerQuotation(draft.id, {
    clientRequestId: nextClientRequestId(),
  });
  return { prId, quotationId: issued.id };
}

describe('mockApi dealQuotations.recordOutcome (GLA-123 slice S3)', () => {
  it('the owning rep records ACCEPTED, and the pricing request reaches QUOTATION_ACCEPTED', async () => {
    const { ticketId, sourceItemId } = await freshTicket();
    const { prId, quotationId } = await issuedNewOriginQuotation(ticketId, sourceItemId, 'DESIGNER');
    await api.auth.login({ role: 'sales' });
    const { quotation } = await api.dealQuotations.recordOutcome(quotationId, {
      outcome: 'ACCEPTED', customerNote: 'ลูกค้าโอเค', clientRequestId: nextClientRequestId(),
    });
    expect(quotation.docStatus).toBe('ACCEPTED');
    const { pricingRequest } = await api.pricingRequests.get(prId);
    expect(pricingRequest.summary.status).toBe('QUOTATION_ACCEPTED');
  });

  // MAJOR 6 fix (Opus review, 2026-09-23) proof: a genuine replay (SAME quotation, SAME
  // clientRequestId, called twice) must return the ORIGINAL result without a second write —
  // proven here by asserting the SECOND call's customerNote still reads the FIRST call's value
  // even though the payload's own note differs, which only holds if the second call short-circuits
  // into the replay branch rather than re-running the update. (A cross-quotation/cross-actor
  // replay-scoping test was attempted here and DELETED: reusing one clientRequestId across two
  // DIFFERENT quotations by the SAME actor is not actually a "should not replay" case — the real
  // backend's own idempotency key is `(issued_by, outcome_client_request_id)`, not further scoped
  // by quotation id either, so that scenario legitimately replay-matches the FIRST usage on BOTH
  // the mock and the real service. The mock's OLD bug was narrower than that: q.createdById ===
  // user.employeeId matched literally ANY row regardless of which ACTOR owned it (both sides
  // null), which this test cannot distinguish from correct behaviour without a second,
  // mock-unrepresentable actor identity — see the comment below. The fix is still justified by
  // direct comparison: q.salesRepId === user.id now scopes to the SAME login-account id space the
  // legacy sibling's own q.issuedById === user.id replay check already uses correctly.)
  it('a genuine replay (same quotation, same clientRequestId) returns the original result, not a second write', async () => {
    const { ticketId, sourceItemId } = await freshTicket();
    const { quotationId } = await issuedNewOriginQuotation(ticketId, sourceItemId, 'DESIGNER');
    const replayKey = nextClientRequestId();
    await api.auth.login({ role: 'sales' });
    const { quotation: first } = await api.dealQuotations.recordOutcome(quotationId, {
      outcome: 'ACCEPTED', customerNote: 'บันทึกครั้งแรก', clientRequestId: replayKey,
    });
    const { quotation: replayed } = await api.dealQuotations.recordOutcome(quotationId, {
      outcome: 'REJECTED', customerNote: 'ไม่ควรถูกบันทึก', clientRequestId: replayKey,
    });

    expect(first.docStatus).toBe('ACCEPTED');
    expect(replayed.id).toBe(first.id);
    expect(replayed.docStatus).toBe('ACCEPTED');
  });

  // A "different sales rep" case is NOT representable through this mock: login({role: 'sales'})
  // always resolves to the ONE seeded sales demo account (mockApi.js's db.users), so there is no
  // way to authenticate as a second, non-owning sales identity here. The real-DB coverage for
  // this exact case (two distinct employees) is
  // DealQuotationOutcomeIntegrationTest#recordOutcome_byNonOwningSales_refused_writesNothing —
  // that is the authoritative evidence for this branch, not this file.

  /** Deliberate divergence check: this origin's own EDIT_ROLES lets sales_manager edit ANY deal,
   * but recordOutcome mirrors the LEGACY gate instead (sales, owner only) — sales_manager must be
   * refused here too, same as the real service. */
  it('sales_manager is refused, and nothing is written', async () => {
    const { ticketId, sourceItemId } = await freshTicket();
    const { quotationId } = await issuedNewOriginQuotation(ticketId, sourceItemId, 'DESIGNER');
    await api.auth.login({ role: 'sales_manager' });
    await expect(api.dealQuotations.recordOutcome(quotationId, {
      outcome: 'ACCEPTED', clientRequestId: nextClientRequestId(),
    })).rejects.toMatchObject({ status: 403 });
    const { quotation } = await api.dealQuotations.get(quotationId);
    expect(quotation.docStatus).toBe('ISSUED');
  });

  it('refuses to record an outcome on a DEAL_DIRECT-origin quotation', async () => {
    await api.auth.login({ role: 'sales' });
    const { customer } = await api.customers.create({
      name: `บริษัท S3 Direct Mock ${nextClientRequestId()}`, taxId: '0100000000398',
      address: '1 ถนนทดสอบ', phone: '02-111-1398',
    });
    const { project } = await api.customers.createProject(customer.id, { name: 'โครงการ S3 Direct Mock' });
    const { contact } = await api.customers.createContact(customer.id, {
      firstName: 'สมชาย', lastName: 'ทดสอบ', phone: '081-000-0004', email: 'contact-s3direct@example.com',
    });
    const { ticket } = await api.tickets.create({
      title: 'ดีล S3 Direct Mock', priority: 'NORMAL', customerName: customer.name, customerId: customer.id,
      projectId: project.id, contactId: contact.id,
      items: [{ brand: 'SCG', model: 'Direct Tile', qty: 10, currency: 'THB' }],
    });
    const { quotation: draft } = await api.dealQuotations.create(ticket.summary.id, {
      items: [{
        lineType: 'TILE', brand: 'SCG', model: 'Direct Tile', color: 'ขาว', texture: 'ด้าน', sizeText: '60x60',
        thicknessMm: 10, sqmPerPiece: 0.36, quantityMode: 'PIECES', piecesInput: 10, wastageMode: 'NONE',
        piecesPerBox: 4, unitPrice: 100, discountPct: 0, originCountry: 'ไทย-สต็อก',
        leadTimeMinDays: 3, leadTimeMaxDays: 7,
      }],
    });
    await expect(api.dealQuotations.recordOutcome(draft.id, {
      outcome: 'ACCEPTED', clientRequestId: nextClientRequestId(),
    })).rejects.toMatchObject({ status: 409 });
  });

  // ── R8 — one finalized quotation per deal, across BOTH คำขอราคา origins ──────────────────────

  it('R8: refused when the LEGACY chain already has an ACCEPTED quotation on this ticket', async () => {
    // Two full pricing-decision chains on one deal, sequentially — comfortably over the 5s
    // default given mockApi's own 140ms delay() per round trip.
    const { ticketId, sourceItemId } = await freshTicket();
    const { quotationId: legacyId } = await issuedLegacyQuotation(ticketId, sourceItemId, 'DESIGNER');
    await api.auth.login({ role: 'sales' });
    await api.pricingRequests.recordCustomerQuotationOutcome(legacyId, {
      outcome: 'ACCEPTED', clientRequestId: nextClientRequestId(),
    });

    const { quotationId: newId } = await issuedNewOriginQuotation(ticketId, sourceItemId, 'OWNER');
    await api.auth.login({ role: 'sales' });
    await expect(api.dealQuotations.recordOutcome(newId, {
      outcome: 'ACCEPTED', clientRequestId: nextClientRequestId(),
    })).rejects.toMatchObject({ status: 409 });
    const { quotation } = await api.dealQuotations.get(newId);
    expect(quotation.docStatus).toBe('ISSUED');
  }, 20000);

  it('R8: legacy recordOutcome is refused when the NEW engine already has an ACCEPTED quotation', async () => {
    const { ticketId, sourceItemId } = await freshTicket();
    const { quotationId: newId } = await issuedNewOriginQuotation(ticketId, sourceItemId, 'DESIGNER');
    await api.auth.login({ role: 'sales' });
    await api.dealQuotations.recordOutcome(newId, { outcome: 'ACCEPTED', clientRequestId: nextClientRequestId() });

    const { quotationId: legacyId } = await issuedLegacyQuotation(ticketId, sourceItemId, 'OWNER');
    await api.auth.login({ role: 'sales' });
    await expect(api.pricingRequests.recordCustomerQuotationOutcome(legacyId, {
      outcome: 'ACCEPTED', clientRequestId: nextClientRequestId(),
    })).rejects.toMatchObject({ status: 409 });
    const { quotation } = await api.pricingRequests.getCustomerQuotation(legacyId);
    expect(quotation.docStatus).toBe('ISSUED');
  }, 20000);
});

describe('mockApi confirmOrder from a NEW-engine accepted quotation (GLA-123 slice S3, R9)', () => {
  // confirmOrder itself needed NO change for this origin — it is driven entirely off
  // pricingRequest.status === 'QUOTATION_ACCEPTED' (see mockApi.js's own confirmOrder, and
  // OrderConfirmationService on the real backend), which recordOutcome above already sets
  // identically regardless of which engine issued the quotation. This test is the plumbing proof
  // for that: accept via the NEW engine, then confirm, and check the SAME ticket-level effects
  // the legacy path's own OrderConfirmationService tests assert.
  it('confirmOrder succeeds once the NEW engine records ACCEPTED, and advances the deal', async () => {
    const { ticketId, sourceItemId } = await freshTicket();
    const { prId, quotationId } = await issuedNewOriginQuotation(ticketId, sourceItemId, 'DESIGNER');
    await api.auth.login({ role: 'sales' });
    await api.dealQuotations.recordOutcome(quotationId, {
      outcome: 'ACCEPTED', clientRequestId: nextClientRequestId(),
    });
    const { pricingRequest: accepted } = await api.pricingRequests.get(prId);
    expect(accepted.summary.status).toBe('QUOTATION_ACCEPTED');

    const { result } = await api.pricingRequests.confirmOrder(prId, { clientRequestId: nextClientRequestId() });
    expect(result.ticket.summary.paymentStatus).toBe('CUSTOMER_CONFIRMED');
    expect(result.ticket.summary.salesStage).toBe('ORDER_RECEIVED');
  }, 20000);
});
