import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mirrors th.co.glr.hr.ticket.TicketRepository.create: the INSERT writes `next_follow_up_at` straight from
// CreateTicketRequest.nextFollowUpAt. Both TicketCreateModal and the inline deal step on /quotations/new send
// it, and the stage-advance readiness gate needs it.

async function anyCustomerAndProject() {
  await api.auth.login({ role: 'sales' });
  const { customers } = await api.customers.search('');
  const customer = customers[0];
  let { projects } = await api.customers.projects(customer.id);
  if (!projects?.length) {
    const { project } = await api.customers.createProject(customer.id, { name: 'โครงการทดสอบ' });
    projects = [project];
  }
  return { customer, project: projects[0] };
}

describe('mock tickets.create -- nextFollowUpAt is persisted like the real INSERT', () => {
  it('carries a supplied nextFollowUpAt onto the created summary', async () => {
    const { customer, project } = await anyCustomerAndProject();
    const { ticket } = await api.tickets.create({
      title: customer.name, customerName: customer.name, customerId: customer.id,
      projectId: project.id, entryChannel: 'DESIGNER_LED', priority: 'NORMAL', items: [],
      nextFollowUpAt: '2026-09-17',
    });
    expect(ticket.summary.nextFollowUpAt).toBe('2026-09-17');
    const { ticket: reread } = await api.tickets.get(ticket.summary.id);
    expect(reread.summary.nextFollowUpAt).toBe('2026-09-17');
  });

  it('stores null when the caller sends none (matches the 9-arg CreateTicketRequest shape)', async () => {
    const { customer, project } = await anyCustomerAndProject();
    const { ticket } = await api.tickets.create({
      title: customer.name, customerId: customer.id, projectId: project.id, items: [],
      entryChannel: 'DESIGNER_LED',
    });
    expect(ticket.summary.nextFollowUpAt).toBeNull();
  });
});

// Mirrors th.co.glr.hr.ticket.CreateTicketRequest + TicketService.create. Since the owner-approved short create form
// (2026-11-16) an ABSENT channel is no longer refused: it is stored UNSPECIFIED. A channel that IS sent must still be
// real: UNSPECIFIED (valid as STORED, invalid as a create INPUT) and unknown values are refused. The three tests that
// used to pin "no / null / blank channel -> 400" now pin the opposite.
describe('mock tickets.create -- the entry channel is optional, but a stated one must be real', () => {
  async function createWith(channelFields) {
    const { customer, project } = await anyCustomerAndProject();
    return api.tickets.create({
      title: customer.name, customerId: customer.id, projectId: project.id, items: [],
      ...channelFields,
    });
  }

  it('accepts a request with no entryChannel and stores UNSPECIFIED', async () => {
    const { ticket } = await createWith({});
    expect(ticket.summary.entryChannel).toBe('UNSPECIFIED');
  });

  it('accepts a null entryChannel and stores UNSPECIFIED', async () => {
    const { ticket } = await createWith({ entryChannel: null });
    expect(ticket.summary.entryChannel).toBe('UNSPECIFIED');
  });

  it('accepts an empty and a whitespace-only entryChannel as not stated', async () => {
    expect((await createWith({ entryChannel: '' })).ticket.summary.entryChannel).toBe('UNSPECIFIED');
    expect((await createWith({ entryChannel: '   ' })).ticket.summary.entryChannel).toBe('UNSPECIFIED');
  });

  it('refuses UNSPECIFIED, 400, with a Thai message', async () => {
    await expect(createWith({ entryChannel: 'UNSPECIFIED' })).rejects.toMatchObject({
      status: 400,
      message: expect.stringContaining('ช่องทาง'),
    });
  });

  it('refuses an unknown channel value, 400 (mirrors EntryChannel.isValid)', async () => {
    await expect(createWith({ entryChannel: 'WALK_IN' })).rejects.toMatchObject({ status: 400 });
  });

  it.each(['DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT'])('accepts %s and stores it', async (channel) => {
    const { ticket } = await createWith({ entryChannel: channel });
    expect(ticket.summary.entryChannel).toBe(channel);
  });

  it('writes nothing when it refuses', async () => {
    const before = (await api.tickets.list()).tickets.length;
    await expect(createWith({ entryChannel: 'UNSPECIFIED' })).rejects.toMatchObject({ status: 400 });
    expect((await api.tickets.list()).tickets.length).toBe(before);
  });
});

// The short create form's three new optional fields (CreateTicketRequest designerName / estimatedValue /
// startStage). Shape only: who may start at S2 is TicketService's stage write gate (DealLifecycleDialogsIntegrationTest).
describe('mock tickets.create -- the short form fields', () => {
  async function createWith(fields) {
    const { customer, project } = await anyCustomerAndProject();
    return api.tickets.create({ title: 'โครงการสั้น', customerId: customer.id, projectId: project.id, items: [], ...fields });
  }

  it('stores the designer name and the estimate (shown on the board as the deal value until a quotation)', async () => {
    const { ticket } = await createWith({ designerName: 'เทพสตูดิโอ ดีไซน์', estimatedValue: 1200000 });
    expect(ticket.summary.designerName).toBe('เทพสตูดิโอ ดีไซน์');
    const { board } = await api.salesBoard.myWork();
    const row = board.find((d) => d.ticketId === ticket.summary.id);
    expect(row).toMatchObject({ value: 1200000, currency: 'THB', designerName: 'เทพสตูดิโอ ดีไซน์' });
  });

  it('starts at S1 by default and at S2 when asked, with a STAGE_CHANGED event', async () => {
    expect((await createWith({})).ticket.summary.salesStage).toBe('LEAD_APPROACH');
    const { ticket } = await createWith({ startStage: 'SPEC_APPROVED' });
    expect(ticket.summary.salesStage).toBe('SPEC_APPROVED');
    expect(ticket.events.some((e) => e.kind === 'STAGE_CHANGED' && e.toStatus === 'SPEC_APPROVED')).toBe(true);
  });

  it('refuses any other start stage and a negative estimate, 400', async () => {
    await expect(createWith({ startStage: 'QUOTE_BUYER' })).rejects.toMatchObject({ status: 400 });
    await expect(createWith({ estimatedValue: -1 })).rejects.toMatchObject({ status: 400 });
  });
});
