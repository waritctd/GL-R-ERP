import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// F1 (Opus final-pass review, Quotation v2): mockApi's deal-entry writes (tickets.create,
// customers.create/createContact/createProject) used to gate on hasRole('sales') alone, which
// was STRICTER than the real backend gate -- DealEntryAccess.canEnterDeal (role sales OR
// sales_manager OR a live canCreateQuotation grant). That made the inline deal-creation flow
// (TicketCreateModal / /quotations/new with no ?ticket=, "บันทึกลูกค้าใหม่") untestable under
// VITE_USE_MOCKS=true for sales_manager and a granted employee, even though production accepts
// both -- see DealEntryAccess's own Javadoc for the ruling. requireDealEntry() (write sites) and
// requireCustomerViewer() (the three customer reads, which OR the same grant into
// CUSTOMER_VIEWER_ROLES) now mirror that gate. These cases are written wrong-way-round per
// CLAUDE.md's "Permission changes must ship evidence" section: they assert the roles that must
// still be REFUSED, not just that the intended roles succeed.

async function loginAs(email) {
  return api.auth.login({ email, password: 'demo1234' });
}

async function expectForbidden(promise) {
  await expect(promise).rejects.toMatchObject({ status: 403 });
}

describe('mockApi deal-entry gate -- mirrors DealEntryAccess.canEnterDeal', () => {
  it('sales_manager can create a customer, a project on it, and a ticket', async () => {
    await loginAs('sales.manager@glr.co.th');
    const { customer } = await api.customers.create({ name: `ลูกค้าทดสอบ sales_manager ${Date.now()}` });
    expect(customer.id).toBeTruthy();
    const { project } = await api.customers.createProject(customer.id, { name: 'โครงการทดสอบ' });
    expect(project.id).toBeTruthy();
    const { ticket } = await api.tickets.create({
      title: customer.name, customerName: customer.name, customerId: customer.id,
      projectId: project.id, entryChannel: 'UNSPECIFIED', priority: 'NORMAL', items: [],
    });
    expect(ticket.summary.id).toBeTruthy();
  });

  it('a canCreateQuotation-granted employee can create a customer, a project, and a ticket', async () => {
    await loginAs('employee@glr.co.th'); // seed's grant holder — demoData.js id 4
    const { customer } = await api.customers.create({ name: `ลูกค้าทดสอบ grant ${Date.now()}` });
    expect(customer.id).toBeTruthy();
    const { project } = await api.customers.createProject(customer.id, { name: 'โครงการทดสอบ' });
    expect(project.id).toBeTruthy();
    const { ticket } = await api.tickets.create({
      title: customer.name, customerName: customer.name, customerId: customer.id,
      projectId: project.id, entryChannel: 'UNSPECIFIED', priority: 'NORMAL', items: [],
    });
    expect(ticket.summary.id).toBeTruthy();
  });

  it('a granted employee can also create a contact on an existing customer', async () => {
    await loginAs('employee@glr.co.th');
    const { customer } = await api.customers.create({ name: `ลูกค้าทดสอบ contact ${Date.now()}` });
    const { contact } = await api.customers.createContact(customer.id, { name: 'ผู้ติดต่อทดสอบ' });
    expect(contact.id).toBeTruthy();
    expect(contact.customerId).toBe(customer.id);
  });

  it.each([
    ['ceo', 'ceo@glr.co.th'],
    ['import', 'import@glr.co.th'],
    ['account', 'account@glr.co.th'],
    ['an ungranted employee', 'warehouse.manager@glr.co.th'],
  ])('%s cannot create a customer, contact, project, or ticket (wrong-way-round)', async (_label, email) => {
    await loginAs(email);
    await expectForbidden(api.customers.create({ name: 'ห้ามสร้าง' }));
    await expectForbidden(api.customers.createContact(1, { name: 'ห้ามสร้าง' }));
    await expectForbidden(api.customers.createProject(1, { name: 'ห้ามสร้าง' }));
    await expectForbidden(api.tickets.create({ projectId: 1 }));
  });
});
