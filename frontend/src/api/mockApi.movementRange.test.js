import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mirrors TicketController/TicketRepository (backend TicketListMovementRangeIntegrationTest): the list takes
// movementFrom/movementTo, each row carries stageChangedInRange / activityInRange (false with no params) and
// lastActivityAt (latest deal_activity date, null if none); from > to is a 400.
describe('mockApi.tickets.list -- movement range', () => {
  it('flags activity inside the range only, serves lastActivityAt, and rejects from > to', async () => {
    await api.auth.login({ role: 'sales' });
    const mine = (await api.tickets.list()).tickets;
    expect(mine.length).toBeGreaterThan(1);
    const [a, b] = mine;
    // far-future dates so seed activities cannot interfere
    await api.tickets.addActivity(a.id, { activityDate: '2099-03-05', kind: 'CALL' });
    await api.tickets.addActivity(a.id, { activityDate: '2099-03-10', kind: 'CALL' });
    await api.tickets.addActivity(b.id, { activityDate: '2099-03-20', kind: 'CALL' });

    const ranged = (await api.tickets.list({ movementFrom: '2099-03-08', movementTo: '2099-03-12' })).tickets;
    const rowA = ranged.find((t) => t.id === a.id);
    const rowB = ranged.find((t) => t.id === b.id);
    expect(rowA.activityInRange).toBe(true);
    expect(rowB.activityInRange).toBe(false);
    expect(typeof rowA.stageChangedInRange).toBe('boolean');
    expect(rowA.lastActivityAt).toBe('2099-03-10');
    expect(rowB.lastActivityAt).toBe('2099-03-20');
    // edge days are inclusive
    const edge = (await api.tickets.list({ movementFrom: '2099-03-10', movementTo: '2099-03-10' })).tickets;
    expect(edge.find((t) => t.id === a.id).activityInRange).toBe(true);

    // no params: both flags present and false, lastActivityAt still served
    const plain = (await api.tickets.list()).tickets;
    const plainA = plain.find((t) => t.id === a.id);
    expect(plainA.activityInRange).toBe(false);
    expect(plainA.stageChangedInRange).toBe(false);
    expect(plainA.lastActivityAt).toBe('2099-03-10');

    await expect(api.tickets.list({ movementFrom: '2099-03-12', movementTo: '2099-03-08' }))
      .rejects.toMatchObject({ status: 400 });
  });

  // CANNOT REACH: range params do not widen the sales own-deals scope.
  it('keeps a sales rep to their own deals when the range is given', async () => {
    await api.auth.login({ role: 'ceo' });
    const all = (await api.tickets.list()).tickets;
    await api.auth.login({ role: 'sales' });
    const own = (await api.tickets.list({ movementFrom: '2099-03-08', movementTo: '2099-03-12' })).tickets;
    expect(own.length).toBeLessThan(all.length);
    const plainOwn = (await api.tickets.list()).tickets;
    expect(own.map((t) => t.id).sort()).toEqual(plainOwn.map((t) => t.id).sort());
  });

  // CANNOT REACH: account / import cannot read deal activities, so their rows carry none of it.
  it.each(['account', 'import'])('%s rows carry no activity facts, even with the range', async (role) => {
    await api.auth.login({ role: 'sales' });
    const [a] = (await api.tickets.list()).tickets;
    await api.tickets.addActivity(a.id, { activityDate: '2099-03-10', kind: 'CALL' });
    await api.auth.login({ role });
    const rows = (await api.tickets.list({ movementFrom: '2099-03-08', movementTo: '2099-03-12' })).tickets;
    expect(rows.length).toBeGreaterThan(0);
    for (const t of rows) {
      expect(t.lastActivityAt, `${role} deal ${t.id}`).toBeNull();
      expect(t.activityInRange).toBe(false);
      expect(t.stageChangedInRange).toBe(false);
    }
  });
});
