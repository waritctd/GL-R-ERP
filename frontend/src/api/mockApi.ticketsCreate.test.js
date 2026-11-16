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

// Mirrors th.co.glr.hr.ticket.CreateTicketRequest (@NotBlank entryChannel, enforced by TicketController's
// @Valid @RequestBody -> 400) plus TicketService.create's explicit refusal of UNSPECIFIED (valid as STORED,
// invalid as a create INPUT -- see th.co.glr.hr.ticket.EntryChannel).
describe('mock tickets.create -- a new deal must state a real entry channel', () => {
  async function createWith(channelFields) {
    const { customer, project } = await anyCustomerAndProject();
    return api.tickets.create({
      title: customer.name, customerId: customer.id, projectId: project.id, items: [],
      ...channelFields,
    });
  }

  it('refuses a request with no entryChannel, 400', async () => {
    await expect(createWith({})).rejects.toMatchObject({ status: 400 });
  });

  it('refuses a null entryChannel, 400', async () => {
    await expect(createWith({ entryChannel: null })).rejects.toMatchObject({ status: 400 });
  });

  it('refuses an empty and a whitespace-only entryChannel, 400', async () => {
    await expect(createWith({ entryChannel: '' })).rejects.toMatchObject({ status: 400 });
    await expect(createWith({ entryChannel: '   ' })).rejects.toMatchObject({ status: 400 });
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

// The short create form's optional fields (CreateTicketRequest designerName / estimatedValue / startStage, 2026-11-16;
// fix round 1: the estimate is its own column, estimated_value). Shape only: who may start at S2 is TicketService's
// stage write gate (DealLifecycleDialogsIntegrationTest).
describe('mock tickets.create -- the short form fields', () => {
  async function createWith(fields) {
    const { customer, project } = await anyCustomerAndProject();
    return api.tickets.create({
      title: 'โครงการสั้น', customerId: customer.id, projectId: project.id, items: [], entryChannel: 'DESIGNER_LED',
      ...fields,
    });
  }

  it('stores the designer name and the estimate (shown on the board as the last value fallback)', async () => {
    const { ticket } = await createWith({ designerName: 'เทพสตูดิโอ ดีไซน์', estimatedValue: 1200000 });
    expect(ticket.summary).toMatchObject({ designerName: 'เทพสตูดิโอ ดีไซน์', estimatedValue: 1200000 });
    const { board } = await api.salesBoard.myWork();
    const row = board.find((d) => d.ticketId === ticket.summary.id);
    expect(row).toMatchObject({ value: 1200000, currency: 'THB' });
  });

  it('starts at S1 by default and at S2 when asked, with a STAGE_CHANGED event', async () => {
    expect((await createWith({})).ticket.summary.salesStage).toBe('LEAD_APPROACH');
    const { ticket } = await createWith({ startStage: 'SPEC_APPROVED' });
    expect(ticket.summary.salesStage).toBe('SPEC_APPROVED');
    expect(ticket.events.some((e) => e.kind === 'STAGE_CHANGED' && e.toStatus === 'SPEC_APPROVED')).toBe(true);
  });

  it('refuses any other start stage, and an estimate that is not > 0 within NUMERIC(14,2), 400', async () => {
    await expect(createWith({ startStage: 'QUOTE_BUYER' })).rejects.toMatchObject({ status: 400 });
    for (const bad of [-1, 0, 1e12, 1.005]) {
      await expect(createWith({ estimatedValue: bad })).rejects.toMatchObject({ status: 400 });
    }
  });
});
