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
      projectId: project.id, entryChannel: 'UNSPECIFIED', priority: 'NORMAL', items: [],
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
    });
    expect(ticket.summary.nextFollowUpAt).toBeNull();
  });
});
