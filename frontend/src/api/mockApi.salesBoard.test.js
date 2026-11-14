import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mirrors SalesBoardService / SalesBoardRepository (backend SalesBoardScopeIntegrationTest). The mock's authz is
// NOT evidence about the Java service — the role gates and the own-deals scope are proven only by the real-DB
// SalesBoardScopeIntegrationTest. This file pins the mock's SHAPE and the 14-day quiet ranking it mirrors.

const score = (d) => d.daysQuiet * Number(d.value ?? 0);

describe('mockApi.salesBoard.myWork', () => {
  it("serves only the caller's own ACTIVE deals, each with repCode and boardColumn", async () => {
    const { user } = await api.auth.login({ role: 'sales' });
    const work = await api.salesBoard.myWork();

    expect(work.quietDays).toBe(14);
    expect(work.board.length).toBeGreaterThan(0);
    for (const deal of work.board) {
      expect(deal.ownerId).toBe(user.id);
      expect(deal.repCode).toMatch(/^S[1-9]$/);
      expect(deal.boardColumn).toBeGreaterThanOrEqual(1);
      expect(deal.boardColumn).toBeLessThanOrEqual(5);
    }

    await api.auth.login({ role: 'ceo' });
    const all = (await api.tickets.list()).tickets;
    const expected = all.filter((t) => t.createdById === user.id && t.lifecycle === 'ACTIVE').map((t) => t.id);
    expect(work.board.map((d) => d.ticketId).sort()).toEqual(expected.sort());
  });

  it('followUp is quiet deals only, at most five, ordered by days x value then days', async () => {
    await api.auth.login({ role: 'sales' });
    const { followUp, board } = await api.salesBoard.myWork();

    expect(followUp.length).toBeLessThanOrEqual(5);
    expect(followUp.length).toBeGreaterThan(0);
    for (const deal of followUp) {
      expect(deal.quiet).toBe(true);
      expect(deal.boardColumn).toBeLessThan(5);
    }
    for (let i = 1; i < followUp.length; i += 1) {
      const [a, b] = [followUp[i - 1], followUp[i]];
      expect(score(a) > score(b) || (score(a) === score(b) && a.daysQuiet >= b.daysQuiet)).toBe(true);
    }
    const quietCount = board.filter((d) => d.quiet).length;
    expect(followUp.length).toBe(Math.min(5, quietCount));
  });

  it('logging a call clears the quiet clock (the 14-day rule counts activity)', async () => {
    await api.auth.login({ role: 'sales' });
    const [first] = (await api.salesBoard.myWork()).followUp;
    await api.tickets.addActivity(first.ticketId, { kind: 'CALL', activityDate: '2026-11-15', note: 'โทรแล้ว' });

    const after = await api.salesBoard.myWork();
    expect(after.followUp.map((d) => d.ticketId)).not.toContain(first.ticketId);
    expect(after.board.find((d) => d.ticketId === first.ticketId).quiet).toBe(false);
  });
});

describe('mockApi.salesBoard.teamOverview', () => {
  it('rejects a sales rep with 403', async () => {
    await api.auth.login({ role: 'sales' });
    await expect(api.salesBoard.teamOverview()).rejects.toMatchObject({ status: 403 });
  });

  it.each(['account', 'import'])('rejects %s on both endpoints', async (role) => {
    await api.auth.login({ role });
    await expect(api.salesBoard.myWork()).rejects.toMatchObject({ status: 403 });
    await expect(api.salesBoard.teamOverview()).rejects.toMatchObject({ status: 403 });
  });

  it('gives the sales manager one row per rep, counts matching the stale list', async () => {
    await api.auth.login({ role: 'sales_manager' });
    const team = await api.salesBoard.teamOverview();

    expect(team.quietDays).toBe(14);
    expect(team.reps.length).toBeGreaterThan(0);
    const ids = team.reps.map((r) => r.employeeId);
    expect(new Set(ids).size).toBe(ids.length);
    for (const rep of team.reps) {
      expect(rep.openDeals).toBeGreaterThan(0);
      expect(rep.quietDeals).toBeLessThanOrEqual(rep.openDeals);
    }
    expect(team.stale.length).toBeLessThanOrEqual(20);
    expect(team.stale.every((d) => d.quiet)).toBe(true);
  });
});
