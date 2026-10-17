import { beforeEach, describe, expect, it } from 'vitest';
import { api, setPricingRequestStatusForTests } from './mockApi.js';

// Slice 2 — flow A (SLICE-2-FLOW-A.md Part 1, S2-B1…S2-B4): pins the MOCK's mirror of the slice-2
// backend contract so VITE_USE_MOCKS=true drives the same flow the Java service will serve —
//   S2-B1  recipientType on create/update (required on a DEAL_DIRECT create; DRAFT-only; refused on
//          a PRICING_REQUEST-origin row), persisted as recipientType + a Thai recipientLabel
//   S2-B2  LANDED (owner ruling 2026-10-16, .design/quote-stage-gates D-2): the recipient MOVES the
//          stage, at create time — and the manual move into a quote stage is fact-gated (D-3)
//   S2-B3  N6: a second create while a live DEAL_DIRECT quotation exists -> 409 naming the live one
//   S2-B4  TicketSummaryDto.liveDirectQuotation on list rows AND get()
// This is evidence about the mock's own plumbing ONLY. It is not evidence about the Java service
// (DealQuotationRecipientStageIntegrationTest / TicketListLiveDirectQuotationIntegrationTest are),
// and it is not authz evidence of any kind (CLAUDE.md "Mock API contract").

const TILE = {
  model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
  quantityMode: 'AREA', areaSqm: 20, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 3, unitPrice: 850,
  discountPct: 0, leadTimeMinDays: 30, leadTimeMaxDays: 45,
};

async function asRole(role) {
  await api.auth.login({ role });
}

/** A fresh ordinary pipeline deal (not quotation-only) owned by the mock `sales` user, at S1. */
async function freshDeal(entryChannel = 'DESIGNER_LED') {
  await asRole('sales');
  const { ticket } = await api.tickets.create({
    entryChannel,
    title: 'ดีลทดสอบ slice 2', customerName: 'ลูกค้า slice 2', projectId: 1, contactId: null,
    priority: 'NORMAL', items: [], nextFollowUpAt: '2026-10-07',
  });
  return ticket.summary.id;
}

async function summaryOf(ticketId) {
  const { ticket } = await api.tickets.get(ticketId);
  return ticket.summary;
}

async function eventsOf(ticketId) {
  const { ticket } = await api.tickets.get(ticketId);
  return ticket.events;
}

async function listRow(ticketId) {
  const { tickets } = await api.tickets.list();
  return tickets.find((t) => t.id === ticketId);
}

async function createDirect(ticketId, recipientType) {
  const { quotation } = await api.dealQuotations.create(ticketId, { recipientType, items: [TILE] });
  return quotation;
}

/** The mock's stageDecisions, the one place it decides anything stage-shaped (D-3's gate lives here). */
async function decisionsOf(ticketId) {
  return (await api.tickets.actions(ticketId)).stageDecisions;
}

describe('mock dealQuotations — recipientType (S2-B1)', () => {
  beforeEach(async () => { await asRole('sales'); });

  it('a DEAL_DIRECT create without recipientType is refused 400 with the Thai reason, and nothing is stored', async () => {
    const ticketId = await freshDeal();
    await expect(api.dealQuotations.create(ticketId, { items: [TILE] }))
      .rejects.toMatchObject({ status: 400, message: 'ต้องระบุผู้รับใบเสนอราคา' });
    const { items } = await api.dealQuotations.listForTicket(ticketId);
    expect(items).toHaveLength(0);
  });

  it('refuses a value outside DESIGNER/OWNER/BUYER (UNSPECIFIED is never offered)', async () => {
    const ticketId = await freshDeal();
    await expect(api.dealQuotations.create(ticketId, { recipientType: 'UNSPECIFIED', items: [TILE] }))
      .rejects.toMatchObject({ status: 400 });
  });

  it.each([
    ['DESIGNER', 'ผู้ออกแบบ'],
    ['OWNER', 'เจ้าของโครงการ'],
    ['BUYER', 'ผู้ซื้อ / ผู้รับเหมา'],
  ])('persists %s with its Thai label, on create and on every read', async (recipientType, label) => {
    const ticketId = await freshDeal();
    const created = await createDirect(ticketId, recipientType);
    expect(created).toMatchObject({ recipientType, recipientLabel: label });
    const { quotation } = await api.dealQuotations.get(created.id);
    expect(quotation).toMatchObject({ recipientType, recipientLabel: label });
  });

  it('update changes it while DRAFT', async () => {
    const ticketId = await freshDeal();
    const created = await createDirect(ticketId, 'DESIGNER');
    const { quotation } = await api.dealQuotations.update(created.id, { recipientType: 'OWNER', items: [TILE] });
    expect(quotation.recipientType).toBe('OWNER');
  });

  it('update WITHOUT recipientType keeps the stored value (omitted = unchanged)', async () => {
    const ticketId = await freshDeal();
    const created = await createDirect(ticketId, 'BUYER');
    const { quotation } = await api.dealQuotations.update(created.id, { items: [TILE] });
    expect(quotation.recipientType).toBe('BUYER');
  });

  it('a recipient change on a PENDING_APPROVAL quotation is refused 409 (DRAFT-only)', async () => {
    const ticketId = await freshDeal();
    const created = await createDirect(ticketId, 'DESIGNER');
    await api.dealQuotations.submit(created.id);
    await expect(api.dealQuotations.update(created.id, { recipientType: 'OWNER', items: [TILE] }))
      .rejects.toMatchObject({ status: 409 });
  });
});

/** A PRICING_REQUEST-origin DRAFT quotation (recipient DESIGNER, set on its คำขอราคา). Same drive as
 * mockApi.dealQuotationPricingRequestApproval.test.js's own helper, trimmed to what this file needs. */
async function pricingRequestOriginDraft() {
  await asRole('sales');
  const { customer } = await api.customers.create({
    name: `บริษัท slice 2 PR ${crypto.randomUUID()}`, taxId: '0100000000397', address: '1 ถนนทดสอบ', phone: '02-111-1397',
  });
  const { project } = await api.customers.createProject(customer.id, { name: 'โครงการ slice 2 PR' });
  const { ticket: created } = await api.tickets.create({
    entryChannel: 'DESIGNER_LED',
    title: 'ดีล slice 2 PR', priority: 'NORMAL', customerName: customer.name, customerId: customer.id,
    projectId: project.id, contactId: null,
    items: [{ brand: 'SCG', model: 'Tile slice 2', qty: 10, currency: 'THB' }],
  });
  const ticketId = created.summary.id;
  const { pricingRequest: draftPr } = await api.pricingRequests.create(ticketId, {
    recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ slice 2', clientRequestId: crypto.randomUUID(),
    items: [{
      sourceTicketItemId: created.items[0].id, productId: 1, brand: 'SCG', model: 'Tile slice 2', factory: 'Panaria SpA',
      color: 'ขาว', texture: 'ด้าน', size: '60x60', thicknessMm: 10, sqmPerPiece: 0.36, quantityMode: 'PIECES',
      piecesInput: 10, wastageMode: 'NONE', piecesPerBox: 4, roundToFullBox: false, originCountry: 'ไทย-สต็อก',
      leadTimeMinDays: 3, leadTimeMaxDays: 7, quantityType: 'ESTIMATE',
    }],
  });
  const prId = draftPr.summary.id;
  await api.pricingRequests.submit(prId);
  await asRole('import');
  await api.pricingRequests.pickup(prId);
  const { items: quotes } = await api.pricingRequests.generateFactoryEmailDrafts(prId);
  // CR-1 (B-R2): price entry is locked until the factory is marked ติดต่อโรงงานแล้ว.
  for (const q of quotes) {
    await api.pricingRequests.markFactoryQuoteContacted(q.id, {
      contactedOn: new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Bangkok' }).format(new Date()),
    });
  }
  await api.pricingRequests.receiveFactoryQuote(quotes[0].id, {
    clientRequestId: crypto.randomUUID(), supplierQuoteRef: 'REF-S2', defaultCurrency: 'THB', paymentTerms: '30 days',
    leadTimeText: '45 days',
    items: [{
      pricingRequestItemId: quotes[0].items[0].pricingRequestItemId, quotedQuantity: 10, quotedUnit: 'PER_PIECE',
      unitBasis: 'PER_PIECE', rawUnitPrice: 100, currency: 'THB',
    }],
  });
  await api.pricingRequests.markFactoryQuoteReady(quotes[0].id);
  const { costing } = await api.pricingRequests.createCosting(prId, {});
  await api.pricingRequests.recalculateCosting(costing.id, {});
  await api.pricingRequests.submitCosting(costing.id, {});
  await asRole('ceo');
  const { decision: started } = await api.pricingRequests.startPricingDecision(prId, { defaultMarginPct: 0.2 });
  const { decision } = await api.pricingRequests.updatePricingDecision(started.id, {
    priceMode: 'NET', items: [{ pricingDecisionItemId: started.items[0].id, discountPct: 10 }],
  });
  await api.pricingRequests.approvePricingDecision(decision.id, {});
  await asRole('sales');
  const { quotation } = await api.dealQuotations.createFromPricingRequest(prId);
  return { ticketId, quotation };
}

describe('mock dealQuotations — a PRICING_REQUEST-origin row takes its recipient from the คำขอราคา (S2-B1)', () => {
  it('reads DESIGNER off its pricing request, refuses a recipientType on update (409), and does not count for N6', async () => {
    const { ticketId, quotation } = await pricingRequestOriginDraft();
    // recipient_label on this origin is the คำขอราคา's own free-text label (develop's Round 8, #1084).
    expect(quotation).toMatchObject({ origin: 'PRICING_REQUEST', recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ slice 2' });

    const items = quotation.items.map((it) => ({ ...it }));
    await expect(api.dealQuotations.update(quotation.id, { recipientType: 'OWNER', items }))
      .rejects.toMatchObject({ status: 409 });
    // The ordinary save (the editor never sends the field for this origin) still works.
    await expect(api.dealQuotations.update(quotation.id, { items })).resolves.toBeTruthy();

    // N6 is about DEAL_DIRECT rows only — this deal carries no live DIRECT quotation.
    expect((await summaryOf(ticketId)).liveDirectQuotation).toBeNull();
  });
});

// S2-B2 LANDED (owner ruling 2026-10-16, .design/quote-stage-gates D-2), and these two tests are
// the 2026-09-30 pins turned around rather than deleted.
//
// The 2026-09-30 reduction took the recipient → stage rule out of slice 2 and handed it to another
// session, and this describe block pinned "a direct quotation moves NOTHING". That session never
// filled the two hooks; the ruling has now been implemented, and the stage moves at CREATE time.
//
// Why the mock must move it too, rather than being left permissive: the backend has simultaneously
// CLOSED the manual move into the three quote stages. A mock that still refuses to advance on
// create, while also mirroring that refusal, would leave mock-mode QA with no way to reach ขั้น
// 4/5/8 at all — the stage would be unreachable in the mock stack and reachable in production.
describe('mock dealQuotations — the recipient MOVES the deal stage at create (D-2)', () => {
  beforeEach(async () => { await asRole('sales'); });

  it('create with OWNER on a LEAD_APPROACH deal moves it to QUOTE_OWNER, with a STAGE_CHANGED', async () => {
    const ticketId = await freshDeal();
    expect((await summaryOf(ticketId)).salesStage).toBe('LEAD_APPROACH');
    await createDirect(ticketId, 'OWNER');
    expect((await summaryOf(ticketId)).salesStage).toBe('QUOTE_OWNER');
    expect((await eventsOf(ticketId)).filter((e) => e.kind === 'STAGE_CHANGED')).toHaveLength(1);
  });

  it('a DRAFT recipient change (DESIGNER -> BUYER) moves the stage with it', async () => {
    const ticketId = await freshDeal();
    const created = await createDirect(ticketId, 'DESIGNER');
    expect((await summaryOf(ticketId)).salesStage).toBe('QUOTE_DESIGN_SIDE');

    const { quotation } = await api.dealQuotations.update(created.id, { recipientType: 'BUYER', items: [TILE] });

    expect(quotation.recipientType).toBe('BUYER');
    expect((await summaryOf(ticketId)).salesStage).toBe('QUOTE_BUYER');
    expect((await eventsOf(ticketId)).filter((e) => e.kind === 'STAGE_CHANGED')).toHaveLength(2);
  });

  // The mock's autoAdvanceStage was missing the Java's ROUTE check, which this ruling makes
  // reachable on a common path: OWNER_DIRECT's route skips ขั้น 4 (no designer to quote), so a
  // designer-addressed quotation there must move NOTHING and still be created. Without the check
  // the mock advanced to an off-route stage production refuses to enter — mock looser than
  // production, the direction that matters.
  it('does not advance to a stage off the deal route, and still creates the quotation', async () => {
    const ticketId = await freshDeal('OWNER_DIRECT');
    expect((await summaryOf(ticketId)).salesStage).toBe('LEAD_APPROACH');

    const created = await createDirect(ticketId, 'DESIGNER');

    expect(created.recipientType).toBe('DESIGNER');
    expect((await summaryOf(ticketId)).salesStage).toBe('LEAD_APPROACH');
    expect((await eventsOf(ticketId)).filter((e) => e.kind === 'STAGE_CHANGED')).toHaveLength(0);
  });

  it('is forward-only: re-addressing BUYER -> DESIGNER leaves the deal on QUOTE_BUYER', async () => {
    const ticketId = await freshDeal();
    const created = await createDirect(ticketId, 'BUYER');
    expect((await summaryOf(ticketId)).salesStage).toBe('QUOTE_BUYER');

    await api.dealQuotations.update(created.id, { recipientType: 'DESIGNER', items: [TILE] });

    expect((await summaryOf(ticketId)).salesStage).toBe('QUOTE_BUYER');
    expect((await eventsOf(ticketId)).filter((e) => e.kind === 'STAGE_CHANGED')).toHaveLength(1);
  });
});

// The other half of the same ruling (D-3): the mock's stage-decision gate must refuse a MANUAL move
// into a quote stage until the document exists, and allow it once it does. A mock that stayed
// permissive here is a drift with no guard — the #742 shape, for the quote stages this time.
describe('mock stageDecisions — the quote stages are fact-gated on a quotation to that party', () => {
  beforeEach(async () => { await asRole('sales'); });

  it('refuses QUOTE_OWNER on a deal with no quotation, naming the missing document', async () => {
    const ticketId = await freshDeal();

    const decision = (await decisionsOf(ticketId)).find((d) => d.stage === 'QUOTE_OWNER');

    expect(decision.allowed).toBe(false);
    expect(decision.blockedReason).toContain('ยังไม่มีใบเสนอราคาถึง');
  });

  it('opens QUOTE_OWNER once a quotation is addressed to the owner', async () => {
    const ticketId = await freshDeal();
    await createDirect(ticketId, 'OWNER');

    const decisions = await decisionsOf(ticketId);

    // The create already advanced the deal ONTO QUOTE_OWNER, so the stage now reports "already in
    // this stage" rather than the fact refusal — which is the real point: the fact gate is no
    // longer what stands in the way. A party NOT quoted still is.
    expect(decisions.find((d) => d.stage === 'QUOTE_OWNER').blockedReason)
      .not.toContain('ยังไม่มีใบเสนอราคาถึง');
    expect(decisions.find((d) => d.stage === 'QUOTE_BUYER').blockedReason)
      .toContain('ยังไม่มีใบเสนอราคาถึง');
  });

  it('reads the RECIPIENT, not merely "this deal has a quotation"', async () => {
    const ticketId = await freshDeal();
    await createDirect(ticketId, 'DESIGNER');

    const decisions = await decisionsOf(ticketId);

    expect(decisions.find((d) => d.stage === 'QUOTE_OWNER').blockedReason)
      .toContain('ยังไม่มีใบเสนอราคาถึง');
  });
});

describe('mock dealQuotations — N6: one live direct quotation per deal (S2-B3)', () => {
  beforeEach(async () => { await asRole('sales'); });

  it('a second create while a DRAFT is live is refused 409, and the error names the live quotation', async () => {
    const ticketId = await freshDeal();
    const live = await createDirect(ticketId, 'DESIGNER');
    const error = await api.dealQuotations.create(ticketId, { recipientType: 'OWNER', items: [TILE] })
      .then(() => null, (e) => e);
    expect(error).not.toBeNull();
    expect(error.status).toBe(409);
    expect(error.message).toBe(
      `ดีลนี้มีใบเสนอราคาตรงที่ใช้งานอยู่ (${live.number}) — แก้ไขฉบับนั้น หรือสร้างฉบับแก้ไขแทนการออกเลขใหม่`,
    );
    expect(error.details).toMatchObject({ liveQuotationId: live.id, number: live.number, docStatus: 'DRAFT' });
    // The refused create stored nothing: still exactly the one live row.
    expect((await api.dealQuotations.listForTicket(ticketId)).items).toHaveLength(1);
  });

  it('PENDING_APPROVAL and APPROVED count as live too', async () => {
    const ticketId = await freshDeal();
    const live = await createDirect(ticketId, 'DESIGNER');
    await api.dealQuotations.submit(live.id);
    await expect(api.dealQuotations.create(ticketId, { recipientType: 'DESIGNER', items: [TILE] }))
      .rejects.toMatchObject({ status: 409, details: { liveQuotationId: live.id, docStatus: 'PENDING_APPROVAL' } });
    await asRole('sales_manager');
    await api.dealQuotations.approve(live.id);
    await asRole('sales');
    await expect(api.dealQuotations.create(ticketId, { recipientType: 'DESIGNER', items: [TILE] }))
      .rejects.toMatchObject({ status: 409, details: { liveQuotationId: live.id, docStatus: 'APPROVED' } });
  });

  it('allowed again once the live one is CANCELLED', async () => {
    const ticketId = await freshDeal();
    const first = await createDirect(ticketId, 'DESIGNER');
    await api.dealQuotations.cancel(first.id);
    const second = await createDirect(ticketId, 'DESIGNER');
    expect(second.id).not.toBe(first.id);
  });
});

describe('mock tickets — liveDirectQuotation on the summary (S2-B4)', () => {
  beforeEach(async () => { await asRole('sales'); });

  it('is null on a deal with no direct quotation — on the list row and on get()', async () => {
    const ticketId = await freshDeal();
    expect((await listRow(ticketId)).liveDirectQuotation).toBeNull();
    expect((await summaryOf(ticketId)).liveDirectQuotation).toBeNull();
  });

  it('carries { id, number, docStatus, recipientType } for a DRAFT direct quotation, on both reads', async () => {
    const ticketId = await freshDeal();
    const draft = await createDirect(ticketId, 'OWNER');
    const expected = { id: draft.id, number: draft.number, docStatus: 'DRAFT', recipientType: 'OWNER' };
    expect((await listRow(ticketId)).liveDirectQuotation).toEqual(expected);
    expect((await summaryOf(ticketId)).liveDirectQuotation).toEqual(expected);
  });

  it('follows the quotation through PENDING_APPROVAL', async () => {
    const ticketId = await freshDeal();
    const draft = await createDirect(ticketId, 'DESIGNER');
    await api.dealQuotations.submit(draft.id);
    expect((await listRow(ticketId)).liveDirectQuotation).toMatchObject({ id: draft.id, docStatus: 'PENDING_APPROVAL' });
  });

  it('picks the NEWEST live one when a revision chain holds two (APPROVED parent + DRAFT child)', async () => {
    const ticketId = await freshDeal();
    const draft = await createDirect(ticketId, 'DESIGNER');
    await api.dealQuotations.submit(draft.id);
    await asRole('sales_manager');
    await api.dealQuotations.approve(draft.id);
    await asRole('sales');
    const { quotation: child } = await api.dealQuotations.createRevision(draft.id, {});
    expect((await listRow(ticketId)).liveDirectQuotation).toMatchObject({ id: child.id, docStatus: 'DRAFT' });
  });

  it('ignores a CANCELLED one (back to null)', async () => {
    const ticketId = await freshDeal();
    const draft = await createDirect(ticketId, 'DESIGNER');
    await api.dealQuotations.cancel(draft.id);
    expect((await listRow(ticketId)).liveDirectQuotation).toBeNull();
    expect((await summaryOf(ticketId)).liveDirectQuotation).toBeNull();
  });
});

// Owner ruling 2026-09-30 — ONE PRICING ROUTE PER DEAL, both directions. Slice 1 refuses a คำขอราคา
// while a live direct quotation exists; this pins the reverse on dealQuotations.create: while the
// deal has a LIVE pricing request — ANY status but CANCELLED / SUPERSEDED (DRAFT and
// QUOTATION_ACCEPTED count) — a direct quotation is refused 409. Checked after the recipient
// validation (a malformed request is a 400 whatever the deal holds) and before N6.
// Mock plumbing only — the Java mirror is DealQuotationService#create (slice-2 backend).
describe('mock dealQuotations.create — one pricing route per deal (a live pricing request blocks)', () => {
  const LIVE_PR_MESSAGE = 'ดีลนี้มีคำขอราคาที่ยังดำเนินการอยู่ — ใช้ใบเสนอราคาจากคำขอราคา หรือยกเลิกคำขอราคาก่อน';

  /** A deal owned by `sales` with one line, plus a DRAFT pricing request on it. */
  async function dealWithDraftPricingRequest() {
    await asRole('sales');
    const { customer } = await api.customers.create({
      name: `บริษัท one-route ${crypto.randomUUID()}`, taxId: '0100000000398', address: '1 ถนนทดสอบ', phone: '02-111-1398',
    });
    const { project } = await api.customers.createProject(customer.id, { name: 'โครงการ one-route' });
    const { ticket: created } = await api.tickets.create({
      entryChannel: 'DESIGNER_LED',
      title: 'ดีล one-route', priority: 'NORMAL', customerName: customer.name, customerId: customer.id,
      projectId: project.id, contactId: null,
      items: [{ brand: 'SCG', model: 'Tile one-route', qty: 10, currency: 'THB' }],
    });
    const ticketId = created.summary.id;
    const { pricingRequest } = await api.pricingRequests.create(ticketId, {
      recipientType: 'OWNER', recipientLabel: 'เจ้าของ one-route', clientRequestId: crypto.randomUUID(),
      items: [{
        sourceTicketItemId: created.items[0].id, productId: 1, brand: 'SCG', model: 'Tile one-route', factory: 'Panaria SpA',
        color: 'ขาว', texture: 'ด้าน', size: '60x60', thicknessMm: 10, sqmPerPiece: 0.36, quantityMode: 'PIECES',
        piecesInput: 10, wastageMode: 'NONE', piecesPerBox: 4, roundToFullBox: false, originCountry: 'ไทย-สต็อก',
        leadTimeMinDays: 3, leadTimeMaxDays: 7, quantityType: 'ESTIMATE',
      }],
    });
    return { ticketId, prId: pricingRequest.summary.id };
  }

  beforeEach(async () => { await asRole('sales'); });

  it('a DRAFT pricing request blocks: 409 with the Thai reason, and nothing is stored', async () => {
    const { ticketId } = await dealWithDraftPricingRequest();
    await expect(api.dealQuotations.create(ticketId, { recipientType: 'OWNER', items: [TILE] }))
      .rejects.toMatchObject({ status: 409, message: LIVE_PR_MESSAGE });
    const { items } = await api.dealQuotations.listForTicket(ticketId);
    expect(items).toHaveLength(0);
  });

  it('a SUBMITTED one blocks too (real submit)', async () => {
    const { ticketId, prId } = await dealWithDraftPricingRequest();
    await api.pricingRequests.submit(prId);
    await expect(api.dealQuotations.create(ticketId, { recipientType: 'OWNER', items: [TILE] }))
      .rejects.toMatchObject({ status: 409, message: LIVE_PR_MESSAGE });
  });

  it('QUOTATION_ACCEPTED still counts as live (test seam for the status)', async () => {
    const { ticketId, prId } = await dealWithDraftPricingRequest();
    setPricingRequestStatusForTests(prId, 'QUOTATION_ACCEPTED');
    await expect(api.dealQuotations.create(ticketId, { recipientType: 'OWNER', items: [TILE] }))
      .rejects.toMatchObject({ status: 409, message: LIVE_PR_MESSAGE });
  });

  it('the recipient 400 still wins over the pricing-request 409 (validated first)', async () => {
    const { ticketId } = await dealWithDraftPricingRequest();
    await expect(api.dealQuotations.create(ticketId, { items: [TILE] }))
      .rejects.toMatchObject({ status: 400, message: 'ต้องระบุผู้รับใบเสนอราคา' });
  });

  it('wrong-way-round: a CANCELLED pricing request (real cancel) does NOT block', async () => {
    const { ticketId, prId } = await dealWithDraftPricingRequest();
    await api.pricingRequests.cancel(prId, { reason: 'ลูกค้าขอราคาเอง' });
    const created = await createDirect(ticketId, 'OWNER');
    expect(created).toMatchObject({ ticketId, origin: 'DEAL_DIRECT', docStatus: 'DRAFT' });
  });

  it('wrong-way-round: a SUPERSEDED pricing request does NOT block (test seam for the status)', async () => {
    const { ticketId, prId } = await dealWithDraftPricingRequest();
    setPricingRequestStatusForTests(prId, 'SUPERSEDED');
    const created = await createDirect(ticketId, 'OWNER');
    expect(created).toMatchObject({ ticketId, docStatus: 'DRAFT' });
  });

  it('another deal\'s pricing request never blocks this one', async () => {
    await dealWithDraftPricingRequest();
    const ticketId = await freshDeal();
    await expect(createDirect(ticketId, 'OWNER')).resolves.toMatchObject({ ticketId });
  });
});

// B-4 (owner ruling 2026-10-17): issuing supersedes the deal's other live offer, whatever its origin.
// Mirrors DealQuotationRepository#supersedeOtherLiveOnTicket. Two independent pricing-route rows on one
// deal are not reachable through this helper (one pricing request per deal), so the reachable pin is a
// revision: before B-4 its parent was flipped silently by the ancestor walk; now the sweep logs it.
describe('mock dealQuotations — issuing a pricing-route quotation supersedes the live one, with an event (B-4)', () => {
  it('issuing a revision flips the ISSUED parent to SUPERSEDED and writes exactly one DEAL_QUOTATION_SUPERSEDED', async () => {
    const { ticketId, quotation } = await pricingRequestOriginDraft();
    await api.dealQuotations.update(quotation.id, { validityDays: 30, items: quotation.items.map((it) => ({ ...it })) });
    await api.dealQuotations.submit(quotation.id);
    await asRole('sales_manager');
    const issued = (await api.dealQuotations.approve(quotation.id, {})).quotation;
    expect(issued.docStatus).toBe('ISSUED');

    await asRole('sales');
    const { quotation: revision } = await api.dealQuotations.createRevision(issued.id, {});
    await api.dealQuotations.submit(revision.id);
    await asRole('sales_manager');
    const reissued = (await api.dealQuotations.approve(revision.id, {})).quotation;

    expect(reissued.docStatus).toBe('ISSUED');
    expect((await api.dealQuotations.get(issued.id)).quotation.docStatus).toBe('SUPERSEDED');
    const events = (await eventsOf(ticketId)).filter((e) => e.kind === 'DEAL_QUOTATION_SUPERSEDED');
    expect(events.map((e) => e.message)).toEqual([`ใบ ${issued.number} ถูกแทนที่ด้วย ${reissued.number}`]);
  });
});
