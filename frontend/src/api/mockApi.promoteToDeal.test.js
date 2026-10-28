import { beforeEach, describe, expect, it } from 'vitest';
import { api } from './mockApi.js';

// GLA-136 (owner ruling 2026-09-30) — the MOCK's mirror of the quotation-only container ticket
// (V193) and DealQuotationService#promoteToDeal, AS RE-GATED BY SLICE 1 (backend 2ed3468e,
// IA §7): the flag is provenance only (the list no longer hides such a deal; stage / entry-channel /
// tender no longer refuse it), the two remaining refusals (editItems, pricing-request create) are
// keyed on "a live DEAL_DIRECT quotation exists" (DirectQuotationLocks), and the confirm-order
// endpoint (still `promoteToDeal` in this tree's hrApi) no longer requires quotation_only. This pins
// the mock's own plumbing so demo/QA/agents driving VITE_USE_MOCKS=true see the real flow. It is
// NOT evidence about authorization: the mock's permission gates are not authoritative (CLAUDE.md) —
// DealQuotationConfirmOrderIntegrationTest against real Postgres is.

const LOCK_REFUSAL = 'ดีลนี้มีใบเสนอราคาตรงที่ยังใช้งานอยู่ — แก้ไขรายการที่ใบเสนอราคา หรือยกเลิกใบเสนอราคาก่อน';

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

  // Slice 1 (2ed3468e) DROPPED the quotation_only precondition: confirming the order from an APPROVED
  // direct quotation works on an ordinary deal too — which is exactly the deal-page CTA slice 2 adds
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
