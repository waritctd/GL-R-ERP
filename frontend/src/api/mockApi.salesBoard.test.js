import { describe, it, expect } from 'vitest';
import { api, patchTicketForTests, rankQuietForTests, receiptSourceForTests } from './mockApi.js';

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

// The authz here is the mock's approximation; TeamBoardScopeIntegrationTest is the evidence for the Java gate.
describe('mockApi.salesBoard.teamBoard', () => {
  it.each(['sales', 'account', 'import', 'hr'])('rejects %s with 403', async (role) => {
    await api.auth.login({ role });
    await expect(api.salesBoard.teamBoard()).rejects.toMatchObject({ status: 403 });
  });

  it.each(['sales_manager', 'ceo'])('gives %s every open deal of every rep, in the board order, each with its owner',
    async (role) => {
      await api.auth.login({ role });
      const team = await api.salesBoard.teamBoard();

      expect(team.quietDays).toBe(14);
      const all = (await api.tickets.list()).tickets;
      const open = all.filter((t) => t.lifecycle === 'ACTIVE' && t.salesStage !== 'CLOSED_PAID').map((t) => t.id);
      expect(team.board.map((d) => d.ticketId).sort()).toEqual(open.sort());
      expect(new Set(team.board.map((d) => d.ownerId)).size).toBeGreaterThan(1);
      for (const deal of team.board) {
        expect(deal.ownerName).toBeTruthy();
        expect(deal.boardColumn).toBeGreaterThanOrEqual(1);
      }
      // SalesBoardRepository: ORDER BY last_touched_at, ticket_id.
      const touched = team.board.map((d) => d.lastTouchedAt ?? '');
      expect(touched).toEqual([...touched].sort());
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

// Slice 6.2 / 6.3: the mock carries the new BoardDeal and TicketSummaryDto fields in the backend's shape. Shape and
// plumbing only — the values are NOT evidence (the mock's due date is its demo billing date; authz is proven by
// BoardDrawnDetailsIntegrationTest / SummaryDrawnDetailsIntegrationTest on real Postgres).
describe('mockApi drawn details (slice 6)', () => {
  it('every board row carries the card fields, each in its own type', async () => {
    await api.auth.login({ role: 'ceo' });
    const { board } = await api.salesBoard.teamBoard();

    expect(board.length).toBeGreaterThan(0);
    for (const d of board) {
      for (const key of ['quotationNumber', 'buyerName', 'expectedDepositWeek', 'paymentDueDate']) {
        expect(d).toHaveProperty(key);
      }
      expect(typeof d.depositReceived).toBe('boolean');
      expect(typeof d.overdue).toBe('boolean');
      if (d.expectedDepositWeek) expect(new Date(`${d.expectedDepositWeek}T00:00:00Z`).getUTCDay()).toBe(1);
    }
  });

  it('a board row\'s due date and overdue flag are the mock summary\'s own (one derivation, as on the server)', async () => {
    await api.auth.login({ role: 'ceo' });
    const { board } = await api.salesBoard.teamBoard();
    const rows = (await api.tickets.list()).tickets;

    // Adapted (fix round 1): only cards from รอมัดจำ (column 4) on carry the due state, as SalesBoardRepository#withPaymentDue.
    for (const d of board) {
      const row = rows.find((t) => t.id === d.ticketId);
      expect(d.paymentDueDate).toBe(d.boardColumn >= 4 ? (row.paymentDueDate ?? null) : null);
      expect(d.overdue).toBe(d.boardColumn >= 4 ? Boolean(row.overdue) : false);
    }
  });

  it('a returning paused deal past its due date reads overdue on its quiet-view card, as on finance (NEW-1)', async () => {
    // Deal 23: SALES1's, fully delivered, deposit paid, balance unpaid, due 2026-07-28 — paused, its return date today.
    await api.auth.login({ role: 'sales' });
    const today = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Bangkok' });
    patchTicketForTests(23, { lifecycle: 'ON_HOLD', pauseReturnOn: today });
    try {
      const card = (await api.salesBoard.myWork()).followUp.find((d) => d.ticketId === 23);
      await api.auth.login({ role: 'ceo' });
      const finance = (await api.tickets.list()).tickets.find((t) => t.id === 23);

      expect(card.returning).toBe(true);
      expect(finance.overdue).toBe(true);
      expect(card.overdue).toBe(true);
      expect(card.paymentDueDate).toBe(finance.paymentDueDate);
    } finally {
      patchTicketForTests(23, { lifecycle: 'ACTIVE', pauseReturnOn: null });
    }
  });

  it('a column-4 card with a due date carries it (the money chip starts at รอมัดจำ, not ส่งของ)', async () => {
    await api.auth.login({ role: 'ceo' });
    const before = (await api.tickets.list()).tickets.find((t) => t.id === 23).salesStage;
    patchTicketForTests(23, { salesStage: 'NEGOTIATION' });
    try {
      const card = (await api.salesBoard.teamBoard()).board.find((d) => d.ticketId === 23);
      expect(card.boardColumn).toBe(4);
      expect(card.paymentDueDate).not.toBeNull();
      expect(card.overdue).toBe(true);
    } finally {
      patchTicketForTests(23, { salesStage: before });
    }
  });

  it('no due date before the deal is fully delivered, as the server derives it (fix round 2)', async () => {
    await api.auth.login({ role: 'ceo' });
    const { tickets } = await api.tickets.list();
    for (const t of tickets) {
      if (t.paymentDueDate != null) expect(t.fulfillmentStatus, `deal ${t.id}`).toBe('FULLY_DELIVERED');
      if (t.overdue) expect(t.paymentDueDate, `deal ${t.id}`).not.toBeNull();
    }
    // Deal 22 carries a demo billing due date but is only reserved from stock: no due date, not overdue.
    const notDelivered = tickets.find((t) => t.id === 22);
    expect(notDelivered.fulfillmentStatus).not.toBe('FULLY_DELIVERED');
    expect(notDelivered.paymentDueDate).toBeNull();
    expect(notDelivered.overdue).toBe(false);
  });

  // Adapted (fix round 2): the RETURNING followUp rows are quiet-view cards and carry it (test above); the rest do not.
  it('the due state is never on the team\'s stale list or an ACTIVE ควรตามต่อ row (no card shows it there)', async () => {
    await api.auth.login({ role: 'ceo' });
    const { stale } = await api.salesBoard.teamOverview();
    await api.auth.login({ role: 'sales' });
    const { followUp } = await api.salesBoard.myWork();

    for (const d of [...stale, ...followUp.filter((row) => !row.returning)]) {
      expect(d.paymentDueDate).toBeNull();
      expect(d.overdue).toBe(false);
    }
  });

  it('a card\'s expected deposit week is the EARLIEST open deposit week, as MIN(expected_week) in Java (F14)', async () => {
    await api.auth.login({ role: 'sales' });
    const [first] = (await api.salesBoard.myWork()).board;
    await api.expectedPayments.create(first.ticketId, { kind: 'DEPOSIT', amount: 1000, expectedWeek: '2027-01-11' });
    await api.expectedPayments.create(first.ticketId, { kind: 'DEPOSIT', amount: 1000, expectedWeek: '2027-01-04' });
    await api.expectedPayments.create(first.ticketId, { kind: 'BALANCE', amount: 1000, expectedWeek: '2026-12-28' });

    const card = (await api.salesBoard.myWork()).board.find((d) => d.ticketId === first.ticketId);
    const earliestOpenDeposit = (await api.expectedPayments.list(first.ticketId))
      .filter((p) => p.kind === 'DEPOSIT' && p.status === 'OPEN').map((p) => p.expectedWeek).sort()[0];
    expect(card.expectedDepositWeek).toBe(earliestOpenDeposit);
    expect(card.expectedDepositWeek <= '2027-01-04').toBe(true);
  });

  it('receiptSource counts only money received: refunds (ADJUSTMENT) and zero amounts are left out', () => {
    const r = (source, kind = 'DEPOSIT', amount = 100, expressDocNo = null) => ({ source, kind, amount, expressDocNo });
    expect(receiptSourceForTests([r('EXPRESS'), r('MANUAL', 'ADJUSTMENT')])).toBe('EXPRESS');
    expect(receiptSourceForTests([r('MANUAL', 'ADJUSTMENT')])).toBeNull();
    expect(receiptSourceForTests([r('EXPRESS'), r('MANUAL', 'DEPOSIT', 0)])).toBe('EXPRESS');
    expect(receiptSourceForTests([r('MANUAL'), r('MANUAL', 'BALANCE', 100, 'RE69001')])).toBe('MIXED');
    expect(receiptSourceForTests([r('MANUAL'), r('MANUAL', 'BALANCE')])).toBe('MANUAL');
    expect(receiptSourceForTests([])).toBeNull();
  });

  it('import\'s list rows never carry expectedQuarter or receiptSource (TicketSummaryDto#withoutMoney)', async () => {
    await api.auth.login({ role: 'import' });
    const visible = (await api.tickets.list()).tickets.map((t) => t.id);
    expect(visible.length).toBeGreaterThan(0);
    // Give an import-visible ACTIVE deal a quarter, and find one with receipts, as the CEO — so a null on import's row
    // is the projection, not missing data.
    await api.auth.login({ role: 'ceo' });
    const ceoRows = (await api.tickets.list()).tickets.filter((t) => visible.includes(t.id));
    const target = ceoRows.find((t) => t.lifecycle === 'ACTIVE');
    await api.tickets.updateTracking(target.id, { expectedQuarter: '2026-Q4' });
    const withReceipts = ceoRows.find((t) => t.receiptSource != null);
    expect(withReceipts, 'fixture: an import-visible deal with receipts').toBeTruthy();
    expect((await api.tickets.list()).tickets.find((t) => t.id === target.id).expectedQuarter).toBe('2026-Q4');

    await api.auth.login({ role: 'import' });
    const { tickets } = await api.tickets.list();
    for (const t of tickets) {
      expect(t.expectedQuarter).toBeNull();
      expect(t.receiptSource).toBeNull();
    }
  });
});
