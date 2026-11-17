import { beforeEach, describe, expect, it } from 'vitest';
import { addUndoBlockerForTests, api, patchTicketForTests } from './mockApi.js';

// Checks mock quotation-to-deal promotion and the live-direct-quotation locks retained by the backend.

const LOCK_REFUSAL = 'งานนี้มีใบเสนอราคาที่ยังใช้งานอยู่ — แก้ไขรายการที่ใบเสนอราคา หรือยกเลิกใบเสนอราคาก่อน';

const TILE = {
  brand: 'SCG', model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10,
  sqmPerPiece: 0.36, quantityMode: 'PIECES', piecesInput: 10, wastageMode: 'NONE', wastageValue: 0,
  piecesPerBox: null, unitPrice: 850, discountPct: 0, leadTimeMinDays: 30, leadTimeMaxDays: 45,
};
const PLAIN = { lineType: 'PLAIN', description: 'ค่าขนส่ง', quantity: 2, unit: 'JOB', unitPrice: 500 };
const ADJUSTMENT = { lineType: 'ADJUSTMENT', adjustmentPct: 3, adjustmentDeadline: '2026-12-31' };

async function asRole(role) {
  await api.auth.login({ role });
}

async function quotationOnlyTicket() {
  await asRole('sales');
  const { ticket } = await api.tickets.create({
    entryChannel: 'DESIGNER_LED',
    title: 'ดีลใบเสนอราคา', customerName: 'ลูกค้า', projectId: 1, contactId: null,
    priority: 'NORMAL', items: [], nextFollowUpAt: '2026-10-07', quotationOnly: true,
  });
  return ticket.summary.id;
}

async function approvedQuotation(ticketId, items = [TILE, PLAIN, ADJUSTMENT]) {
  await asRole('sales');
  const { quotation: draft } = await api.dealQuotations.create(ticketId, { recipientType: 'DESIGNER', items });
  await api.dealQuotations.submit(draft.id);
  await asRole('sales_manager');
  const { quotation } = await api.dealQuotations.approve(draft.id);
  expect(quotation.docStatus).toBe('APPROVED');
  await asRole('sales');
  return quotation;
}

async function listIds() {
  const { tickets } = await api.tickets.list();
  return tickets.map((t) => t.id);
}

describe('mock quotation-first ticket (GLA-136 flag, slice-1 semantics)', () => {
  beforeEach(async () => { await asRole('sales'); });

  it('tickets.create keeps quotationOnly as provenance, and the list now SHOWS the deal (TicketRepository.PIPELINE_ONLY is gone)', async () => {
    const ghost = await quotationOnlyTicket();
    expect(await listIds()).toContain(ghost);
    const { ticket } = await api.tickets.get(ghost);
    expect(ticket.summary.quotationOnly).toBe(true);
  });

  it('a create WITHOUT the flag is an ordinary pipeline deal, listed as before', async () => {
    const { ticket } = await api.tickets.create({
      entryChannel: 'DESIGNER_LED',
      title: 'ดีลปกติ', customerName: 'ลูกค้า', projectId: 1, items: [], nextFollowUpAt: '2026-10-07',
    });
    expect(ticket.summary.quotationOnly).toBe(false);
    expect(await listIds()).toContain(ticket.summary.id);
  });

  it('entry channel / tender / stage decisions no longer refuse a quotation-first deal', async () => {
    const ghost = await quotationOnlyTicket();
    const { ticket: afterChannel } = await api.tickets.setEntryChannel(ghost, { value: 'OWNER_DIRECT' });
    expect(afterChannel.summary.entryChannel).toBe('OWNER_DIRECT');
    const { ticket: afterTender } = await api.tickets.setTenderRequirement(ghost, { value: 'NOT_REQUIRED' });
    expect(afterTender.summary.tenderRequirement).toBe('NOT_REQUIRED');
    const { stageDecisions } = await api.tickets.actions(ghost);
    expect(stageDecisions.length).toBeGreaterThan(0);
    expect(stageDecisions.some((d) => String(d.blockedReason ?? '').includes('ใบเสนอราคาเท่านั้น'))).toBe(false);
  });

  it('editItems and a pricing-request create 409 while a LIVE direct quotation exists — keyed on the quotation, not the flag', async () => {
    const { ticket: plain } = await api.tickets.create({
      entryChannel: 'DESIGNER_LED',
      title: 'ดีลปกติ', customerName: 'ลูกค้า', projectId: 1, items: [], nextFollowUpAt: '2026-10-07',
    });
    const dealId = plain.summary.id;
    // No live direct quotation yet: editItems goes through, on an ordinary AND unflagged deal.
    await expect(api.tickets.editItems(dealId, { items: [] })).resolves.toBeTruthy();

    const { quotation } = await api.dealQuotations.create(dealId, { recipientType: 'DESIGNER', items: [TILE] });
    await expect(api.tickets.editItems(dealId, { items: [] }))
      .rejects.toMatchObject({ status: 409, message: LOCK_REFUSAL });
    await expect(api.pricingRequests.create(dealId, { recipientType: 'DESIGNER', clientRequestId: crypto.randomUUID(), items: [] }))
      .rejects.toMatchObject({ status: 409, message: LOCK_REFUSAL });

    // Cancelling the direct quotation lifts the lock.
    await api.dealQuotations.cancel(quotation.id);
    await expect(api.tickets.editItems(dealId, { items: [] })).resolves.toBeTruthy();
  });
});

describe('mock dealQuotations.promoteToDeal (GLA-136)', () => {
  it('promotes: quotation_issued, ORDER_RECEIVED, CUSTOMER_CONFIRMED, goods lines as items, listed again', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);

    const { result } = await api.dealQuotations.promoteToDeal(approved.id);

    expect(result.ticketId).toBe(ghost);
    expect(result.ticket.quotationOnly).toBe(false);
    const { ticket } = await api.tickets.get(ghost);
    expect(ticket.summary.status).toBe('quotation_issued');
    expect(ticket.summary.salesStage).toBe('ORDER_RECEIVED');
    expect(ticket.summary.paymentStatus).toBe('CUSTOMER_CONFIRMED');
    // TILE + PLAIN only — the ADJUSTMENT row is money, not something to deliver.
    expect(ticket.items.map((i) => [i.brand, i.qty, i.rawUnit])).toEqual([
      ['SCG', 10, 'แผ่น'],
      ['ค่าขนส่ง', 2, 'JOB'],
    ]);
    expect(ticket.items[0].qtySqm).toBeCloseTo(3.6);
    expect(ticket.items.every((i) => i.approvedPrice == null && i.proposedPrice == null)).toBe(true);
    expect(ticket.events.filter((e) => e.kind === 'DEAL_PROMOTED_FROM_QUOTATION')).toHaveLength(1);
    expect(await listIds()).toContain(ghost);
  });

  // Owner ruling 2026-10-28 (mirror of DealQuotationService#createRevision): the quotation an order was
  // confirmed from is the agreed order and cannot be revised.
  it('refuses a revision of the confirmed quotation (409), but not before the order is confirmed', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    const { quotation: revision } = await api.dealQuotations.createRevision(approved.id);
    expect(revision.parentQuotationId).toBe(approved.id);
    await api.dealQuotations.cancel(revision.id);

    await api.dealQuotations.promoteToDeal(approved.id);

    await expect(api.dealQuotations.createRevision(approved.id)).rejects.toMatchObject({ status: 409 });
  });

  // Round 3 (mirror of DealQuotationService.submit/approve/createReorder): after confirm-order no OTHER direct
  // quotation on the deal may be cloned from the confirmed one, submitted or approved, and
  // are refused with 409.
  it('after confirm: no clone of the confirmed quotation, and a draft/pending one opened before cannot be submitted/approved', async () => {
    const ghost = await quotationOnlyTicket();
    const v1 = await approvedQuotation(ghost);
    const { quotation: clone } = await api.dealQuotations.createReorder(v1.id);
    const { quotation: revision } = await api.dealQuotations.createRevision(v1.id);
    await api.dealQuotations.submit(revision.id);
    await api.dealQuotations.promoteToDeal(v1.id);

    await expect(api.dealQuotations.createReorder(v1.id)).rejects.toMatchObject({ status: 409 });
    await expect(api.dealQuotations.submit(clone.id)).rejects.toMatchObject({ status: 409 });
    await asRole('sales_manager');
    await expect(api.dealQuotations.approve(revision.id)).rejects.toMatchObject({ status: 409 });
    const { quotation: still } = await api.dealQuotations.get(v1.id);
    expect(still.docStatus).toBe('APPROVED');
  });

  // Owner ruling 2026-10-23 (DepositNoticeRepository#foldAdjustments): the deposit draft carries no
  // qty -1 ส่วนลดพิเศษ row; a flat 95 is spread by line amount (TILE 8,500 takes 85, PLAIN 1,000
  // takes 10), so the subtotal is the quotation's 9,405. The mock computes no % adjustment amount
  // (MOCK_NOT_COMPUTED), so a % row is only dropped.
  it('the deposit draft folds a flat ส่วนลดพิเศษ into the goods lines and drops a % one', async () => {
    const ghost = await quotationOnlyTicket();
    const flat = await approvedQuotation(ghost, [TILE, PLAIN, { lineType: 'ADJUSTMENT', description: 'ส่วนลดพิเศษ', adjustmentAmount: 95 }]);
    await api.dealQuotations.promoteToDeal(flat.id);
    const { depositNotice } = await api.depositNotices.createDraft(ghost, {});
    expect(depositNotice.items.map((i) => [i.qty, i.unitPrice, i.netUnitPrice, i.discountLabel])).toEqual([
      [10, 850, 841.5, 'ส่วนลดพิเศษ 8.5 ต่อหน่วย'],
      [2, 500, 495, 'ส่วนลดพิเศษ 5 ต่อหน่วย'],
    ]);
    expect(depositNotice.subtotal).toBe(9405);

    const other = await quotationOnlyTicket();
    await api.dealQuotations.promoteToDeal((await approvedQuotation(other)).id);
    const { depositNotice: pctDraft } = await api.depositNotices.createDraft(other, {});
    expect(pctDraft.items.map((i) => i.qty)).toEqual([10, 2]);
  });

  it('a replay is idempotent — same deal, no second event', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    await api.dealQuotations.promoteToDeal(approved.id);
    const { result } = await api.dealQuotations.promoteToDeal(approved.id);
    expect(result.ticketId).toBe(ghost);
    const { ticket } = await api.tickets.get(ghost);
    expect(ticket.events.filter((e) => e.kind === 'DEAL_PROMOTED_FROM_QUOTATION')).toHaveLength(1);
  });

  it('refuses a DRAFT quotation (409) and leaves the ticket quotation-only', async () => {
    const ghost = await quotationOnlyTicket();
    const { quotation } = await api.dealQuotations.create(ghost, { recipientType: 'DESIGNER', items: [TILE] });
    await expect(api.dealQuotations.promoteToDeal(quotation.id)).rejects.toMatchObject({ status: 409 });
    const { ticket } = await api.tickets.get(ghost);
    expect(ticket.summary.quotationOnly).toBe(true);
    expect(ticket.summary.status).toBe('draft');
  });

  // Slice 1 (2ed3468e) DROPPED the quotation_only precondition: confirming the order from an APPROVED direct
  // quotation works on an ordinary deal too — which is exactly the deal-page CTA slice 2 adds
  // (CONFIRM_ORDER_DIRECT). This used to pin a 409 here.
  it('confirms the order on an ordinary pipeline deal too (quotation_only precondition dropped by slice 1)', async () => {
    const { ticket } = await api.tickets.create({
      entryChannel: 'DESIGNER_LED',
      title: 'ดีลปกติ', customerName: 'ลูกค้า', projectId: 1, items: [], nextFollowUpAt: '2026-10-07',
    });
    const approved = await approvedQuotation(ticket.summary.id);
    const { result } = await api.dealQuotations.promoteToDeal(approved.id);
    expect(result.ticketId).toBe(ticket.summary.id);
    const { ticket: after } = await api.tickets.get(ticket.summary.id);
    expect(after.summary.status).toBe('quotation_issued');
    expect(after.summary.salesStage).toBe('ORDER_RECEIVED');
    expect(after.events.find((e) => e.kind === 'DEAL_PROMOTED_FROM_QUOTATION')?.message)
      .toBe(`ยืนยันคำสั่งซื้อจากใบเสนอราคา ${approved.number}`);
  });

  it('refuses import (403) before any state check', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    await asRole('import');
    await expect(api.dealQuotations.promoteToDeal(approved.id)).rejects.toMatchObject({ status: 403 });
  });

  it('404s an unknown quotation', async () => {
    await expect(api.dealQuotations.promoteToDeal(987654)).rejects.toMatchObject({ status: 404 });
  });

  // Mirrors TicketService.applyCustomerConfirmation's policy sync (owner ruling 2026-10-22).
  it('a 0% (ไม่รับมัดจำ) quotation confirms the deal onto depositPolicy NOT_REQUIRED', async () => {
    const ghost = await quotationOnlyTicket();
    const { quotation: draft } = await api.dealQuotations.create(ghost, {
      recipientType: 'DESIGNER', depositPercent: 0, fullPaymentTerm: 'BEFORE_DELIVERY', items: [TILE, PLAIN],
    });
    await api.dealQuotations.submit(draft.id);
    await asRole('sales_manager');
    const { quotation: approved } = await api.dealQuotations.approve(draft.id);
    await asRole('sales');

    await api.dealQuotations.promoteToDeal(approved.id);

    const { ticket } = await api.tickets.get(ghost);
    expect(ticket.summary.depositPolicy).toBe('NOT_REQUIRED');
    expect(ticket.events.filter((e) => e.kind === 'POLICY_CHANGED').map((e) => e.message))
      .toEqual([expect.stringContaining(approved.number)]);
  });
});

// DealQuotationService#undoOrderConfirmation: "ยกเลิกการยืนยันคำสั่งซื้อ".
describe('mock dealQuotations.undoConfirmOrder', () => {
  const chainOf = async (ticketId) => (await api.documentChain.get(ticketId)).documentChain;

  it('round trip: confirm -> undo -> revise allowed -> confirm again; the history records the undo', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    await api.dealQuotations.promoteToDeal(approved.id);
    expect((await chainOf(ghost)).order).toMatchObject({ confirmed: true, undoable: true, confirmedFromQuotationId: approved.id });

    const { result } = await api.dealQuotations.undoConfirmOrder(approved.id, { reason: 'กดผิด' });
    expect(result.ticketId).toBe(ghost);

    const { ticket } = await api.tickets.get(ghost);
    expect(ticket.summary.status).toBe('draft');
    expect(ticket.summary.paymentStatus).toBeNull();
    expect(ticket.events.some((e) => e.kind === 'DEAL_PROMOTED_FROM_QUOTATION')).toBe(false);
    const undone = ticket.events.filter((e) => e.kind === 'ORDER_CONFIRMATION_UNDONE');
    expect(undone).toHaveLength(1);
    expect(undone[0].message).toContain('กดผิด');
    const order = (await chainOf(ghost)).order;
    expect(order).toMatchObject({ confirmed: false, undoable: false, confirmable: true });

    // the quotation can be revised again, and the order confirmed again
    const { quotation: revision } = await api.dealQuotations.createRevision(approved.id);
    await api.dealQuotations.cancel(revision.id);
    await api.dealQuotations.promoteToDeal(approved.id);
    expect((await chainOf(ghost)).order.confirmed).toBe(true);
  });

  it('replay after an undo is a 200 that writes nothing', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    await api.dealQuotations.promoteToDeal(approved.id);
    await api.dealQuotations.undoConfirmOrder(approved.id);
    await expect(api.dealQuotations.undoConfirmOrder(approved.id)).resolves.toBeTruthy();
    const { ticket } = await api.tickets.get(ghost);
    expect(ticket.events.filter((e) => e.kind === 'ORDER_CONFIRMATION_UNDONE')).toHaveLength(1);
  });

  it('409 when the order was never confirmed', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    await expect(api.dealQuotations.undoConfirmOrder(approved.id)).rejects.toMatchObject({ status: 409 });
  });

  it('409 once a deposit notice is issued, and the confirmation stays', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    await api.dealQuotations.promoteToDeal(approved.id);
    const { depositNotice } = await api.depositNotices.createDraft(ghost, {});
    await api.depositNotices.issue(depositNotice.id, { expectedUpdatedAt: depositNotice.updatedAt ?? null });
    expect((await chainOf(ghost)).order.undoable).toBe(false);
    await expect(api.dealQuotations.undoConfirmOrder(approved.id)).rejects.toMatchObject({ status: 409 });
    expect((await chainOf(ghost)).order.confirmed).toBe(true);
  });

  it('a DRAFT deposit notice is deleted by the undo', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    await api.dealQuotations.promoteToDeal(approved.id);
    await api.depositNotices.createDraft(ghost, {});
    await api.dealQuotations.undoConfirmOrder(approved.id);
    const { depositNotices } = await api.depositNotices.listByTicket(ghost);
    expect(depositNotices).toHaveLength(0);
  });

  it('403 for import, 404 for an unknown quotation', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    await api.dealQuotations.promoteToDeal(approved.id);
    await asRole('import');
    await expect(api.dealQuotations.undoConfirmOrder(approved.id)).rejects.toMatchObject({ status: 403 });
    await expect(api.dealQuotations.undoConfirmOrder(987654)).rejects.toMatchObject({ status: 404 });
  });
});

// The mock's undo must refuse and restore as the server does (DealQuotationService#undoRefusal / #undoOrderConfirmation).
describe('mock dealQuotations.undoConfirmOrder — refusals and restore parity', () => {
  const chainOf = async (ticketId) => (await api.documentChain.get(ticketId)).documentChain;
  const confirmed = async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    return { ghost, approved };
  };

  it.each([
    ['a deal that is no longer ACTIVE', (t) => patchTicketForTests(t, { lifecycle: 'ON_HOLD' })],
    ['a bid outcome recorded with the confirmation', (t) => addUndoBlockerForTests(t, 'bidOutcome')],
    ['a split child deal', (t) => addUndoBlockerForTests(t, 'splitChild')],
    ['an import request', (t) => addUndoBlockerForTests(t, 'importRequest')],
    ['a delivery record', (t) => addUndoBlockerForTests(t, 'deliveryRecord')],
    ['a manual stock / procurement decision', (t) => addUndoBlockerForTests(t, 'stockDecision')],
  ])('refuses with 409 (and the chain says not undoable) for %s', async (_name, block) => {
    const { ghost, approved } = await confirmed();
    await api.dealQuotations.promoteToDeal(approved.id);
    expect((await chainOf(ghost)).order.undoable).toBe(true);
    block(ghost);
    expect((await chainOf(ghost)).order.undoable).toBe(false);
    await expect(api.dealQuotations.undoConfirmOrder(approved.id)).rejects.toMatchObject({ status: 409 });
    expect((await chainOf(ghost)).order.confirmed).toBe(true);
  });

  it('a bid outcome recorded BEFORE a plain manual confirmation does not block the undo', async () => {
    const { ghost, approved } = await confirmed();
    addUndoBlockerForTests(ghost, 'bidOutcome');
    await api.dealQuotations.promoteToDeal(approved.id);
    expect((await chainOf(ghost)).order.undoable).toBe(true);
    await expect(api.dealQuotations.undoConfirmOrder(approved.id)).resolves.toBeTruthy();
  });

  it('removes the confirmation\'s CUSTOMER_CONFIRMED and automatic STAGE_CHANGED events, not only the promotion event', async () => {
    const { ghost, approved } = await confirmed();
    await api.dealQuotations.promoteToDeal(approved.id);
    const before = (await api.tickets.get(ghost)).ticket.events;
    const promotionId = before.find((e) => e.kind === 'DEAL_PROMOTED_FROM_QUOTATION').id;
    const auto = (e) => e.kind === 'STAGE_CHANGED' && e.message === 'อัตโนมัติจากขั้นตอนของดีล';
    expect(before.some((e) => e.kind === 'CUSTOMER_CONFIRMED')).toBe(true);
    expect(before.some((e) => auto(e) && e.id > promotionId)).toBe(true);

    await api.dealQuotations.undoConfirmOrder(approved.id);

    const after = (await api.tickets.get(ghost)).ticket.events;
    expect(after.filter((e) => e.kind === 'CUSTOMER_CONFIRMED')).toHaveLength(0);
    expect(after.filter((e) => auto(e) && e.id > promotionId)).toHaveLength(0);
    expect(after.filter((e) => e.kind === 'DEAL_PROMOTED_FROM_QUOTATION')).toHaveLength(0);
    // the stage move the QUOTATION caused (before the confirmation) is not the confirmation's: it stays
    expect(after.filter(auto).length).toBe(before.filter((e) => auto(e) && e.id < promotionId).length);
  });

  it('the history is exactly what it was before the confirmation, plus the one undo event', async () => {
    const { ghost, approved } = await confirmed();
    const beforeKinds = (await api.tickets.get(ghost)).ticket.events.map((e) => e.kind);
    await api.dealQuotations.promoteToDeal(approved.id);
    await api.dealQuotations.undoConfirmOrder(approved.id);
    const afterKinds = (await api.tickets.get(ghost)).ticket.events.map((e) => e.kind);
    expect(afterKinds).toEqual([...beforeKinds, 'ORDER_CONFIRMATION_UNDONE']);
  });

  it("a person's POLICY_CHANGED that merely quotes the quotation phrase survives the undo; the system's own is removed", async () => {
    const ghost = await quotationOnlyTicket();
    const { quotation: draft } = await api.dealQuotations.create(ghost, {
      recipientType: 'DESIGNER', depositPercent: 0, fullPaymentTerm: 'BEFORE_DELIVERY', items: [TILE, PLAIN],
    });
    await api.dealQuotations.submit(draft.id);
    await asRole('sales_manager');
    const { quotation: approved } = await api.dealQuotations.approve(draft.id);
    await asRole('sales');
    await api.dealQuotations.promoteToDeal(approved.id);
    const policyMessages = async () => (await api.tickets.get(ghost)).ticket.events
      .filter((e) => e.kind === 'POLICY_CHANGED').map((e) => e.message);
    expect(await policyMessages()).toHaveLength(1); // the system's flip
    const personsReason = `ลูกค้าขอตามใบเสนอราคา ${approved.number} ครับ`;
    await api.tickets.setDepositPolicy(ghost, { policy: 'WAIVED', reason: personsReason });

    await api.dealQuotations.undoConfirmOrder(approved.id);

    expect(await policyMessages()).toEqual([`deposit_policy → WAIVED — ${personsReason}`]);
  });

  it.each(['QUOTED', 'NEGOTIATING'])('the awarded party goes back to the status it had (%s)', async (status) => {
    await asRole('sales');
    const { ticket } = await api.tickets.create({
      entryChannel: 'DESIGNER_LED', title: 'ดีลมีผู้รับเหมา', customerName: 'ลูกค้า', customerId: 5, projectId: 1, contactId: null,
      priority: 'NORMAL', items: [], nextFollowUpAt: '2026-10-07', quotationOnly: true,
    });
    const deal = ticket.summary.id;
    const approved = await approvedQuotation(deal);
    const party = (await api.tickets.parties(deal)).parties.find((p) => p.customerId === 5);
    expect(party.status).toBe('QUOTED');
    if (status === 'NEGOTIATING') await api.tickets.updateParty(deal, party.id, { status });
    await api.dealQuotations.promoteToDeal(approved.id);
    expect((await api.tickets.parties(deal)).parties.find((p) => p.id === party.id).status).toBe('AWARDED');

    await api.dealQuotations.undoConfirmOrder(approved.id);

    expect((await api.tickets.parties(deal)).parties.find((p) => p.id === party.id).status).toBe(status);
  });
});

// The mock's undo does what DealQuotationService#undoOrderConfirmation does (read the Java, not the old mock).
describe('mock dealQuotations.undoConfirmOrder — the backend reversal, divergence by divergence', () => {
  const STOCK_TILE = { ...TILE, stockSource: 'IN_THAILAND' };
  const chainOf = async (ticketId) => (await api.documentChain.get(ticketId)).documentChain;
  const summaryOf = async (ticketId) => (await api.tickets.get(ticketId)).ticket.summary;

  async function quotationWith(ghost, { depositPercent = 50, items = [TILE, PLAIN] } = {}) {
    await asRole('sales');
    const { quotation: draft } = await api.dealQuotations.create(ghost, {
      recipientType: 'DESIGNER', depositPercent, fullPaymentTerm: 'BEFORE_DELIVERY', items,
    });
    await api.dealQuotations.submit(draft.id);
    await asRole('sales_manager');
    const { quotation } = await api.dealQuotations.approve(draft.id);
    await asRole('sales');
    return quotation;
  }

  it('deletes every line of the deal (the backend deletes all ticket_item rows), not restoring the earlier ones', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    patchTicketForTests(ghost, { items: [{ id: 1, ticketId: ghost, brand: 'เดิม', qty: 1 }] });
    await api.dealQuotations.promoteToDeal(approved.id);

    await api.dealQuotations.undoConfirmOrder(approved.id);

    // (the deal read falls back to the quotation's own lines when it has none, as the backend's does)
    expect((await api.tickets.get(ghost)).ticket.items.some((i) => i.brand === 'เดิม')).toBe(false);
  });

  it('leaves quotationOnly as the confirmation set it (the backend does not restore the provenance flag)', async () => {
    const ghost = await quotationOnlyTicket();
    expect((await summaryOf(ghost)).quotationOnly).toBe(true);
    const approved = await approvedQuotation(ghost);
    await api.dealQuotations.promoteToDeal(approved.id);
    expect((await summaryOf(ghost)).quotationOnly).toBe(false);

    await api.dealQuotations.undoConfirmOrder(approved.id);

    expect((await summaryOf(ghost)).quotationOnly).toBe(false);
  });

  it("reverses the system's own policy flip (reason cleared), but never a person's later waiver", async () => {
    const flipped = await quotationOnlyTicket();
    const zero = await quotationWith(flipped, { depositPercent: 0 });
    await api.dealQuotations.promoteToDeal(zero.id);
    expect((await summaryOf(flipped)).depositPolicy).toBe('NOT_REQUIRED');
    await api.dealQuotations.undoConfirmOrder(zero.id);
    expect(await summaryOf(flipped)).toMatchObject({ depositPolicy: 'REQUIRED', depositPolicyReason: null });

    const waived = await quotationOnlyTicket();
    const zero2 = await quotationWith(waived, { depositPercent: 0 });
    await api.dealQuotations.promoteToDeal(zero2.id);
    await api.tickets.setDepositPolicy(waived, { policy: 'WAIVED', reason: 'ลูกค้าเก่า' });
    await api.dealQuotations.undoConfirmOrder(zero2.id);
    expect(await summaryOf(waived)).toMatchObject({ depositPolicy: 'WAIVED', depositPolicyReason: 'ลูกค้าเก่า' });
  });

  it('clears the FROM_STOCK fulfilment the confirmation set on a 0% all-stock deal, and the stage goes back', async () => {
    const ghost = await quotationOnlyTicket();
    const stageBefore = (await summaryOf(ghost)).salesStage;
    const approved = await quotationWith(ghost, { depositPercent: 0, items: [STOCK_TILE] });
    const stageQuoted = (await summaryOf(ghost)).salesStage;
    await api.dealQuotations.promoteToDeal(approved.id);
    expect(await summaryOf(ghost)).toMatchObject({ fulfillmentStatus: 'FROM_STOCK', salesStage: 'DELIVERY_SCHEDULING' });
    expect((await chainOf(ghost)).order.undoable).toBe(true);

    await api.dealQuotations.undoConfirmOrder(approved.id);

    const after = await summaryOf(ghost);
    expect(after.fulfillmentStatus).toBeNull();
    expect(after.salesStage).toBe(stageQuoted);
    expect(stageQuoted).not.toBe('ORDER_RECEIVED');
    void stageBefore;
  });

  it('refuses with 409 once the deal is no longer quotation_issued / CUSTOMER_CONFIRMED, or has moved past ORDER_RECEIVED', async () => {
    const stage = await quotationOnlyTicket();
    const q1 = await approvedQuotation(stage);
    await api.dealQuotations.promoteToDeal(q1.id);
    patchTicketForTests(stage, { salesStage: 'PROCUREMENT' });
    expect((await chainOf(stage)).order.undoable).toBe(false);
    await expect(api.dealQuotations.undoConfirmOrder(q1.id)).rejects.toMatchObject({ status: 409 });

    const status = await quotationOnlyTicket();
    const q2 = await approvedQuotation(status);
    await api.dealQuotations.promoteToDeal(q2.id);
    patchTicketForTests(status, { status: 'draft' });
    expect((await chainOf(status)).order.undoable).toBe(false);
    await expect(api.dealQuotations.undoConfirmOrder(q2.id)).rejects.toMatchObject({ status: 409 });

    const routed = await quotationOnlyTicket();
    const q3 = await approvedQuotation(routed);
    await api.dealQuotations.promoteToDeal(q3.id);
    patchTicketForTests(routed, { salesStage: 'DELIVERY_SCHEDULING' }); // no FROM_STOCK: not the confirmation's route
    await expect(api.dealQuotations.undoConfirmOrder(q3.id)).rejects.toMatchObject({ status: 409 });
  });

  it('writes the backend event note: who confirmed and when, the reason, the deleted draft notice', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    await api.dealQuotations.promoteToDeal(approved.id);
    await api.depositNotices.createDraft(ghost, {});
    const buddhistYear = new Date().getFullYear() + 543;

    await api.dealQuotations.undoConfirmOrder(approved.id, { reason: ' กดผิด ' });

    const note = (await api.tickets.get(ghost)).ticket.events.find((e) => e.kind === 'ORDER_CONFIRMATION_UNDONE').message;
    expect(note).toMatch(new RegExp(
      `^ยกเลิกการยืนยันคำสั่งซื้อจากใบเสนอราคา ${approved.number} \\(ยืนยันโดย .+ เมื่อ \\d{1,2}/\\d{1,2}/${buddhistYear} \\d{2}:\\d{2}\\)`
      + ' · เหตุผล: กดผิด · ลบใบแจ้งรับมัดจำฉบับร่างแล้ว$'));

    const plain = await quotationOnlyTicket();
    const second = await approvedQuotation(plain);
    await api.dealQuotations.promoteToDeal(second.id);
    await api.dealQuotations.undoConfirmOrder(second.id);
    const bare = (await api.tickets.get(plain)).ticket.events.find((e) => e.kind === 'ORDER_CONFIRMATION_UNDONE').message;
    expect(bare).not.toContain('เหตุผล');
    expect(bare).not.toContain('ลบใบแจ้งรับมัดจำ');
  });

  it('a delayed retry naming the old confirmation is a 409 and leaves the re-confirmation; the current id is undone', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    await api.dealQuotations.promoteToDeal(approved.id);
    const firstId = (await chainOf(ghost)).order.confirmationEventId;
    expect(firstId).toEqual(expect.any(Number));
    await api.dealQuotations.undoConfirmOrder(approved.id, { confirmationEventId: firstId });
    expect((await chainOf(ghost)).order.confirmationEventId).toBeNull();
    await api.dealQuotations.promoteToDeal(approved.id);
    const secondId = (await chainOf(ghost)).order.confirmationEventId;
    expect(secondId).not.toBe(firstId);

    await expect(api.dealQuotations.undoConfirmOrder(approved.id, { confirmationEventId: firstId }))
      .rejects.toMatchObject({ status: 409 });
    expect((await chainOf(ghost)).order.confirmed).toBe(true);

    await expect(api.dealQuotations.undoConfirmOrder(approved.id, { confirmationEventId: secondId })).resolves.toBeTruthy();
    expect((await summaryOf(ghost)).status).toBe('draft');
  });

  it('a replay after the undo is still a 200 with the old id', async () => {
    const ghost = await quotationOnlyTicket();
    const approved = await approvedQuotation(ghost);
    await api.dealQuotations.promoteToDeal(approved.id);
    const id = (await chainOf(ghost)).order.confirmationEventId;
    await api.dealQuotations.undoConfirmOrder(approved.id, { confirmationEventId: id });
    await expect(api.dealQuotations.undoConfirmOrder(approved.id, { confirmationEventId: id })).resolves.toBeTruthy();
  });

  describe('the all-stock auto-route is exempt only when it was the confirmation\'s own', () => {
    it("a person's waiver after the confirmation that routes an all-stock deposit deal: 409, nothing changed, not undoable", async () => {
      const ghost = await quotationOnlyTicket();
      const approved = await quotationWith(ghost, { depositPercent: 50, items: [STOCK_TILE] });
      await api.dealQuotations.promoteToDeal(approved.id);
      expect((await summaryOf(ghost)).salesStage).toBe('ORDER_RECEIVED');
      await api.tickets.setDepositPolicy(ghost, { policy: 'WAIVED', reason: 'ลูกค้าเก่า' });
      expect(await summaryOf(ghost)).toMatchObject({ salesStage: 'DELIVERY_SCHEDULING', fulfillmentStatus: 'FROM_STOCK' });
      const before = JSON.stringify((await api.tickets.get(ghost)).ticket);

      expect((await chainOf(ghost)).order.undoable).toBe(false);
      await expect(api.dealQuotations.undoConfirmOrder(approved.id)).rejects.toMatchObject({ status: 409 });

      expect(JSON.stringify((await api.tickets.get(ghost)).ticket)).toBe(before);
    });

    it('a waiver that routed nothing does not block (the deal is still at ORDER_RECEIVED)', async () => {
      const ghost = await quotationOnlyTicket();
      const approved = await approvedQuotation(ghost);
      await api.dealQuotations.promoteToDeal(approved.id);
      await api.tickets.setDepositPolicy(ghost, { policy: 'WAIVED', reason: 'ลูกค้าเก่า' });
      expect((await chainOf(ghost)).order.undoable).toBe(true);
    });

    it('a waiver recorded BEFORE the confirmation: its auto-route is the confirmation\'s own, still undoable', async () => {
      const ghost = await quotationOnlyTicket();
      const approved = await quotationWith(ghost, { depositPercent: 50, items: [STOCK_TILE] });
      await api.tickets.setDepositPolicy(ghost, { policy: 'WAIVED', reason: 'ลูกค้าเก่า' });
      await api.dealQuotations.promoteToDeal(approved.id);
      expect((await summaryOf(ghost)).salesStage).toBe('DELIVERY_SCHEDULING');
      expect((await chainOf(ghost)).order.undoable).toBe(true);
      await expect(api.dealQuotations.undoConfirmOrder(approved.id)).resolves.toBeTruthy();
    });
  });
});
