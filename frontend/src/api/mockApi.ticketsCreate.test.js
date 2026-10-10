import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mirrors th.co.glr.hr.ticket.TicketRepository.create: the INSERT writes `next_follow_up_at`
// straight from CreateTicketRequest.nextFollowUpAt. The mock's tickets.create used to build its
// ticket literal without ever reading that field, so a caller-supplied date was silently dropped
// on creation while the other two ticket-mutation handlers kept it — the "mock drops an argument
// the real API honours" shape CLAUDE.md's Mock API contract names. Both TicketCreateModal and the
// inline deal step on /quotations/new send it, and the stage-advance readiness gate needs it.

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

// Mirrors th.co.glr.hr.ticket.CreateTicketRequest (@NotBlank entryChannel, enforced by
// TicketController's @Valid @RequestBody -> 400) plus TicketService.create's explicit refusal of
// UNSPECIFIED (valid as STORED, invalid as a create INPUT -- see th.co.glr.hr.ticket.EntryChannel).
// Owner ruling 2026-09-30: a NEW deal must state a real ช่องทางดีล, because the channel decides its
// route. A mock more permissive than production is the dangerous direction (CLAUDE.md, issue #199).
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
