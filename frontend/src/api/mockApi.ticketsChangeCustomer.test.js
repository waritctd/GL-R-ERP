import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mirrors th.co.glr.hr.ticket.TicketService#changeCustomer. PLUMBING evidence only: that the real
// service takes the deal's lock, refuses a live pricing request, and re-snapshots the drafts in
// the same transaction is proven against Java and a real Postgres
// (TicketChangeCustomerIntegrationTest), never here.

const ONE_ITEM = {
  model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
  quantityMode: 'AREA', areaSqm: 20, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0,
  leadTimeMinDays: 30, leadTimeMaxDays: 45,
};

let seq = 0;

/** A fresh deal owned by the demo sales rep (id 6), on customer 1 / project 1. */
async function freshDeal() {
  await api.auth.login({ role: 'sales' });
  const { customers } = await api.customers.search('');
  const from = customers.find((c) => c.id === 1);
  const to = customers.find((c) => c.id === 2);
  const { projects: fromProjects } = await api.customers.projects(from.id);
  const { projects: toProjects } = await api.customers.projects(to.id);
  const { ticket } = await api.tickets.create({
    title: `change-customer-${seq += 1}`, customerName: from.name, customerId: from.id,
    projectId: fromProjects[0].id, entryChannel: 'UNSPECIFIED', priority: 'NORMAL', items: [],
  });
  return { ticketId: ticket.summary.id, from, to, fromProject: fromProjects[0], toProject: toProjects[0] };
}

describe('mock tickets.changeCustomer', () => {
  it('re-snapshots every DRAFT quotation of the deal in the same call, and leaves other deals alone', async () => {
    const { ticketId, to, toProject } = await freshDeal();
    const { quotation: draft } = await api.dealQuotations.create(ticketId, { items: [ONE_ITEM] });
    const { quotation: pendingOnOtherDeal } = await api.dealQuotations.get(2); // seeded, ticket 18, PENDING_APPROVAL
    expect(pendingOnOtherDeal.docStatus).toBe('PENDING_APPROVAL');

    await api.tickets.changeCustomer(ticketId, { customerId: to.id, projectId: toProject.id, contactId: null });

    const { quotation: after } = await api.dealQuotations.get(draft.id);
    expect(after).toMatchObject({
      customerName: to.name, customerAddress: to.address, customerTaxId: to.taxId, customerPhone: to.phone,
      projectName: toProject.name, contactId: null, contactName: null, contactPhone: null, contactEmail: null,
    });
    const { quotation: otherAfter } = await api.dealQuotations.get(2);
    expect(otherAfter).toEqual(pendingOnOtherDeal);
  });

  it('refuses a deal that has a live pricing request (409) and leaves the customer where it was', async () => {
    const { ticketId, from, to, toProject } = await freshDeal();
    await api.pricingRequests.create(ticketId, {
      recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบทดสอบ',
      clientRequestId: `77777777-7777-4777-8777-${String(seq).padStart(12, '0')}`,
      items: [{
        brand: 'Panaria', model: 'Trilogy', color: 'ขาว', texture: 'ด้าน', size: '60x60', thicknessMm: 10,
        sqmPerPiece: 0.36, quantityMode: 'PIECES', piecesInput: 10, wastageMode: 'NONE', piecesPerBox: 4,
        roundToFullBox: false, originCountry: 'ไทย-สต็อก', leadTimeMinDays: 3, leadTimeMaxDays: 7,
        quantityType: 'ESTIMATE',
      }],
    });

    await expect(api.tickets.changeCustomer(ticketId, { customerId: to.id, projectId: toProject.id, contactId: null }))
      .rejects.toMatchObject({ status: 409, message: 'เปลี่ยนลูกค้าไม่ได้ เนื่องจากดีลนี้มีคำขอราคาอยู่' });
    const { ticket } = await api.tickets.get(ticketId);
    expect(ticket.summary.customerId).toBe(from.id);
  });
});
