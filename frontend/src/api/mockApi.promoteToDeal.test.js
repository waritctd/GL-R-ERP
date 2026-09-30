import { beforeEach, describe, expect, it } from 'vitest';
import { api } from './mockApi.js';

// GLA-136 (owner ruling 2026-09-30) — the MOCK's mirror of the quotation-only container ticket
// (V193) and DealQuotationService#promoteToDeal. This pins the mock's own plumbing (list
// exclusion, the preconditions' ORDER, the effects) so demo/QA/agents driving VITE_USE_MOCKS=true
// see the real flow. It is NOT evidence about authorization: the mock's permission gates are not
// authoritative (CLAUDE.md) — DealQuotationPromoteIntegrationTest against real Postgres is.

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
  const { quotation: draft } = await api.dealQuotations.create(ticketId, { items: [TILE, PLAIN, ADJUSTMENT] });
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

describe('mock quotation-only ticket (GLA-136)', () => {
  beforeEach(async () => { await asRole('sales'); });

  it('tickets.create honours quotationOnly; the list hides it while get() still serves it', async () => {
    const ghost = await quotationOnlyTicket();
    expect(await listIds()).not.toContain(ghost);
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

  it('manual pipeline writes 409 on it', async () => {
    const ghost = await quotationOnlyTicket();
    await expect(api.tickets.updateStage(ghost, { stage: 'SPEC_APPROVED', note: 'x' }))
      .rejects.toMatchObject({ status: 409 });
    await expect(api.tickets.setEntryChannel(ghost, { value: 'OWNER_DIRECT' }))
      .rejects.toMatchObject({ status: 409 });
    const { ticket } = await api.tickets.get(ghost);
    expect(ticket.summary.salesStage).toBe('LEAD_APPROACH');
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
    const { quotation } = await api.dealQuotations.create(ghost, { items: [TILE] });
    await expect(api.dealQuotations.promoteToDeal(quotation.id)).rejects.toMatchObject({ status: 409 });
    const { ticket } = await api.tickets.get(ghost);
    expect(ticket.summary.quotationOnly).toBe(true);
    expect(ticket.summary.status).toBe('draft');
  });

  it('refuses an ordinary pipeline deal (409), never a silent success', async () => {
    const { ticket } = await api.tickets.create({
      title: 'ดีลปกติ', customerName: 'ลูกค้า', projectId: 1, items: [], nextFollowUpAt: '2026-10-07',
    });
    const approved = await approvedQuotation(ticket.summary.id);
    await expect(api.dealQuotations.promoteToDeal(approved.id)).rejects.toMatchObject({ status: 409 });
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
