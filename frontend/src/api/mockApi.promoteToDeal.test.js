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
    title: 'ดีลใบเสนอราคา', customerName: 'ลูกค้า', projectId: 1, contactId: null,
    priority: 'NORMAL', items: [], nextFollowUpAt: '2026-10-07', quotationOnly: true,
  });
  return ticket.summary.id;
}

async function approvedQuotation(ticketId) {
  await asRole('sales');
  const { quotation: draft } = await api.dealQuotations.create(ticketId, { recipientType: 'DESIGNER', items: [TILE, PLAIN, ADJUSTMENT] });
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
});
