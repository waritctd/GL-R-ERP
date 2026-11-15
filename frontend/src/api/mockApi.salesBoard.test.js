import { describe, it, expect } from 'vitest';
import { api, rankQuietForTests } from './mockApi.js';

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
    const expected = all
      .filter((t) => t.createdById === user.id && t.lifecycle === 'ACTIVE' && t.salesStage !== 'CLOSED_PAID')
      .map((t) => t.id);
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

// Final review: values in different currencies are not comparable, so ranking weights THB only.
describe('quiet ranking currency', () => {
  const deal = (ticketId, daysQuiet, value, currency) => ({ ticketId, daysQuiet, value, currency, quiet: true });

  it('a USD 1,000 deal does not outrank a THB 900,000 deal of equal days', () => {
    const ranked = rankQuietForTests([deal(1, 20, 1000, 'USD'), deal(2, 20, 900000, 'THB')]);
    expect(ranked.map((d) => d.ticketId)).toEqual([2, 1]);
  });

  it('a non-THB deal ranks by days alone, below THB-weighted ones but above fewer days', () => {
    const ranked = rankQuietForTests([deal(1, 30, 5, 'USD'), deal(2, 20, 900000, 'THB'), deal(3, 25, 1000000, 'EUR')]);
    expect(ranked.map((d) => d.ticketId)).toEqual([2, 1, 3]);
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

// ── review fixes mirrored in the mock (LOW: updateTracking expectedQuarter, ORDER BY mirror; M1/M2/M3 shape) ──
describe('mockApi review-fix mirrors', () => {
  it('updateTracking keeps expectedQuarter when absent, clears it on "", and rejects a malformed one', async () => {
    await api.auth.login({ role: 'sales' });
    const [deal] = (await api.salesBoard.myWork()).board;
    await api.tickets.updateTracking(deal.ticketId, { expectedQuarter: '2027-Q1' });
    const quarterOf = async () => (await api.salesBoard.myWork()).board.find((d) => d.ticketId === deal.ticketId)
      .expectedQuarter;
    expect(await quarterOf()).toBe('2027-Q1');

    await api.tickets.updateTracking(deal.ticketId, { designerName: 'คุณเอ' });
    expect(await quarterOf()).toBe('2027-Q1');

    await expect(api.tickets.updateTracking(deal.ticketId, { expectedQuarter: '2027Q1' }))
      .rejects.toMatchObject({ status: 400 });
    await api.tickets.updateTracking(deal.ticketId, { expectedQuarter: '' });
    expect(await quarterOf()).toBeNull();
  });

  it('board rows come in the repository order: last touched ascending, then ticket id', async () => {
    await api.auth.login({ role: 'sales' });
    const { board } = await api.salesBoard.myWork();
    for (let i = 1; i < board.length; i += 1) {
      const [a, b] = [board[i - 1], board[i]];
      const ordered = a.lastTouchedAt < b.lastTouchedAt || (a.lastTouchedAt === b.lastTouchedAt && a.ticketId < b.ticketId);
      expect(ordered, `${a.ticketId} before ${b.ticketId}`).toBe(true);
    }
  });

  it('every board deal carries currency / valueExpired / last activity, and CLOSED_PAID is not open', async () => {
    await api.auth.login({ role: 'ceo' });
    const paid = (await api.tickets.list()).tickets.filter((t) => t.salesStage === 'CLOSED_PAID').map((t) => t.id);
    await api.auth.login({ role: 'sales_manager' });
    const team = await api.salesBoard.teamOverview();
    await api.auth.login({ role: 'sales' });
    const { board } = await api.salesBoard.myWork();

    for (const d of board) {
      expect(d).toHaveProperty('currency');
      expect(typeof d.valueExpired).toBe('boolean');
      expect(d).toHaveProperty('lastActivityKind');
      expect(paid).not.toContain(d.ticketId);
    }
    for (const r of team.reps) {
      expect(r).toHaveProperty('role');
      expect(r).toHaveProperty('otherCurrencyValues');
    }
  });
});
