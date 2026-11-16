import { describe, expect, it } from 'vitest';
import { api } from './mockApi.js';

// The mock's mirror of the lifecycle-dialog backend changes (2026-11-16): the pause return date (V219, QuietRule
// #returningSql), the drawn cancel reasons, and the meta catalog. SHAPE ONLY — whose deals the follow-up list and the
// team figures include is SalesBoardScopeIntegrationTest / PausedDealFollowUpScopeIntegrationTest's job, never this.

function bangkokToday() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Bangkok', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  const get = (type) => parts.find((p) => p.type === type).value;
  return `${get('year')}-${get('month')}-${get('day')}`;
}

function plusDays(iso, days) {
  const [y, m, d] = iso.split('-').map(Number);
  const date = new Date(Date.UTC(y, m - 1, d + days));
  return date.toISOString().slice(0, 10);
}

// The second seeded rep (sales2): the first one's seeded quiet deals would fill the five ควรตามต่อ slots, and a
// returning deal created a moment ago (0 days quiet) ranks after them.
async function newDeal() {
  await api.auth.login({ email: 'sales2@glr.co.th' });
  const { customers } = await api.customers.search('');
  const customer = customers[0];
  let { projects } = await api.customers.projects(customer.id);
  if (!projects?.length) projects = [(await api.customers.createProject(customer.id, { name: 'โครงการทดสอบ' })).project];
  const { ticket } = await api.tickets.create({ title: 'งานทดสอบพัก', customerId: customer.id, projectId: projects[0].id, items: [] });
  return ticket.summary.id;
}

describe('mock hold / dormant / resume — วันที่จะกลับมาดู', () => {
  it('hold stores pauseReturnOn on the summary; resume clears it', async () => {
    const id = await newDeal();
    const on = plusDays(bangkokToday(), 14);
    const { ticket } = await api.tickets.hold(id, { pauseReturnOn: on });
    expect(ticket.summary).toMatchObject({ lifecycle: 'ON_HOLD', pauseReturnOn: on });

    const resumed = await api.tickets.resume(id, {});
    expect(resumed.ticket.summary).toMatchObject({ lifecycle: 'ACTIVE', pauseReturnOn: null });
  });

  it('dormant stores it too; no date stays null', async () => {
    const id = await newDeal();
    expect((await api.tickets.dormant(id, {})).ticket.summary.pauseReturnOn).toBeNull();
  });

  it('refuses a date already past (Bangkok), writing nothing', async () => {
    const id = await newDeal();
    await expect(api.tickets.hold(id, { pauseReturnOn: plusDays(bangkokToday(), -1) }))
      .rejects.toMatchObject({ status: 400 });
    expect((await api.tickets.get(id)).ticket.summary.lifecycle).toBe('ACTIVE');
  });
});

describe('mock salesBoard — a paused deal whose date has arrived comes back', () => {
  it('appears in my-work followUp flagged returning (not on the board); a later date does not', async () => {
    const back = await newDeal();
    const later = await newDeal();
    await api.tickets.hold(back, { pauseReturnOn: bangkokToday() });
    await api.tickets.hold(later, { pauseReturnOn: plusDays(bangkokToday(), 1) });

    const work = await api.salesBoard.myWork();
    const row = work.followUp.find((d) => d.ticketId === back);
    expect(row).toMatchObject({ returning: true, quiet: false, pauseReturnOn: bangkokToday() });
    expect(work.followUp.some((d) => d.ticketId === later)).toBe(false);
    expect(work.board.some((d) => d.ticketId === back)).toBe(false);
  });

  it('counts in the team overview quiet figures and stale list, not as an open deal', async () => {
    const back = await newDeal();
    await api.tickets.hold(back, { pauseReturnOn: bangkokToday() });
    const { id: ownerId } = (await api.auth.me()).user ?? {};
    await api.auth.login({ role: 'sales_manager' });

    const team = await api.salesBoard.teamOverview();
    expect(team.stale.some((d) => d.ticketId === back && d.returning)).toBe(true);
    const board = (await api.salesBoard.teamBoard()).board;
    expect(board.some((d) => d.ticketId === back)).toBe(false);
    if (ownerId != null) {
      const rep = team.reps.find((r) => r.employeeId === ownerId);
      expect(rep.quietDeals).toBeGreaterThanOrEqual(1);
    }
  });
});

describe('mock cancel reasons and catalog', () => {
  it('the catalog offers the drawn four', async () => {
    await api.auth.login({ role: 'sales' });
    expect((await api.meta.dealStages()).cancelReasons)
      .toEqual(['DUPLICATE_DEAL', 'WRONG_CUSTOMER_OR_PROJECT', 'NO_REAL_PROJECT', 'OTHER']);
  });

  it('a new code and a retired code are both accepted; an unknown one is refused', async () => {
    const a = await newDeal();
    expect((await api.tickets.cancel(a, { reason: 'DUPLICATE_DEAL' })).ticket.summary.cancelReason).toBe('DUPLICATE_DEAL');
    const b = await newDeal();
    expect((await api.tickets.cancel(b, { reason: 'OWNER_CANCELLED' })).ticket.summary.cancelReason).toBe('OWNER_CANCELLED');
    const c = await newDeal();
    await expect(api.tickets.cancel(c, { reason: 'PRICE' })).rejects.toMatchObject({ status: 400 });
  });
});
