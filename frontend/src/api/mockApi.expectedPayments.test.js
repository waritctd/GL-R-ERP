import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';
import { bangkokTodayIso } from '../utils/format.js';
import { snapToMonday } from '../features/salesBoard/cashFormat.js';

// Mirrors ExpectedPaymentService (V212) and SalesBoardService#cashForMonth. The mock's authz is NOT evidence about the
// Java service — ExpectedPaymentScopeIntegrationTest and CashForMonthIntegrationTest are. This pins the SHAPE, the
// ORDER BY (expected_week, id), the Monday rule, and that the cash card's forecast moves with a new row.

const monday = snapToMonday(bangkokTodayIso());
const plusDays = (iso, n) => {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

/** One of the logged-in rep's own ACTIVE deals (the seeded board). */
async function ownDealId() {
  const { user } = await api.auth.login({ role: 'sales' });
  const { board } = await api.salesBoard.myWork();
  expect(board.length).toBeGreaterThan(0);
  return { user, id: board[0].ticketId };
}

describe('mockApi.expectedPayments', () => {
  it('creates, lists by week then id, and refuses a non-Monday week', async () => {
    const { id } = await ownDealId();
    const later = await api.expectedPayments.create(id, { kind: 'BALANCE', amount: 500, expectedWeek: plusDays(monday, 14) });
    const sooner = await api.expectedPayments.create(id, { kind: 'deposit', amount: 300, expectedWeek: monday });
    expect(sooner).toMatchObject({ ticketId: id, kind: 'DEPOSIT', amount: 300, status: 'OPEN', receiptId: null });
    const ids = (await api.expectedPayments.list(id)).map((r) => r.id);
    expect(ids.indexOf(sooner.id)).toBeLessThan(ids.indexOf(later.id));
    await expect(api.expectedPayments.create(id, { kind: 'DEPOSIT', amount: 1, expectedWeek: plusDays(monday, 2) }))
      .rejects.toMatchObject({ status: 400 });
  });

  it('cancel drops the row from the list; a cancelled row cannot be cancelled again', async () => {
    const { id } = await ownDealId();
    const row = await api.expectedPayments.create(id, { kind: 'DEPOSIT', amount: 300, expectedWeek: monday });
    await api.expectedPayments.cancel(id, row.id);
    expect((await api.expectedPayments.list(id)).map((r) => r.id)).not.toContain(row.id);
    await expect(api.expectedPayments.cancel(id, row.id)).rejects.toMatchObject({ status: 409 });
  });

  it('a part payment typed by account keeps the row OPEN with the rest; the next completes it (review M1)', async () => {
    // A deal inside account's scope (account's own worklist), planned by a sales_manager (who may write any deal).
    await api.auth.login({ role: 'account' });
    const { tickets } = await api.tickets.list();
    const target = tickets.find((t) => (t.lifecycle ?? 'ACTIVE') === 'ACTIVE');
    expect(target).toBeTruthy();
    const id = target.id;
    await api.auth.login({ role: 'sales_manager' });
    const row = await api.expectedPayments.create(id, { kind: 'DEPOSIT', amount: 1000, expectedWeek: monday });
    await api.auth.login({ role: 'account' });
    await api.finance.recordPayment(id, { kind: 'DEPOSIT', amount: 400, allowOverpayment: true, note: 'ทดสอบ' });
    let mine = (await api.expectedPayments.list(id)).find((r) => r.id === row.id);
    expect(mine).toMatchObject({ status: 'OPEN', receivedAmount: 400 });
    await api.finance.recordPayment(id, { kind: 'DEPOSIT', amount: 600, allowOverpayment: true, note: 'ทดสอบ' });
    mine = (await api.expectedPayments.list(id)).find((r) => r.id === row.id);
    expect(mine).toMatchObject({ status: 'RECEIVED', receivedAmount: 1000 });
  });

  it('a partly received row cannot drop below what came in, nor change kind (review N3)', async () => {
    await api.auth.login({ role: 'account' });
    const { tickets } = await api.tickets.list();
    // A deal with no seeded expected rows, so the receipt lands on this row only.
    const id = tickets.find((t) => (t.lifecycle ?? 'ACTIVE') === 'ACTIVE' && ![12, 21, 23].includes(t.id)).id;
    await api.auth.login({ role: 'sales_manager' });
    const row = await api.expectedPayments.create(id, { kind: 'BALANCE', amount: 5000, expectedWeek: plusDays(monday, 70) });
    await api.auth.login({ role: 'account' });
    await api.finance.recordPayment(id, { kind: 'BALANCE', amount: 1000, allowOverpayment: true, note: 'ทดสอบ', confirmPossibleDuplicate: true });
    await api.auth.login({ role: 'sales_manager' });
    const now = (await api.expectedPayments.list(id)).find((r) => r.id === row.id);
    expect(now.receivedAmount).toBe(1000);
    await expect(api.expectedPayments.update(id, row.id, { kind: 'BALANCE', amount: 999, expectedWeek: row.expectedWeek }))
      .rejects.toMatchObject({ status: 409 });
    await expect(api.expectedPayments.update(id, row.id, { kind: 'DEPOSIT', amount: 5000, expectedWeek: row.expectedWeek }))
      .rejects.toMatchObject({ status: 409 });
  });

  // Owner ruling N5 — mirrors TicketRepository#applyReceiptToExpectedPayments (ExpectedPaymentReceiptIntegrationTest is
  // the evidence; this pins the mock's copy of the rule).
  async function plan(id, rows) {
    await api.auth.login({ role: 'sales_manager' });
    const made = [];
    for (const [kind, amount, week] of rows) made.push(await api.expectedPayments.create(id, { kind, amount, expectedWeek: week }));
    return made;
  }
  async function pay(id, kind, amount) {
    await api.auth.login({ role: 'account' });
    await api.finance.recordPayment(id, { kind, amount, allowOverpayment: true, note: 'ทดสอบ', confirmPossibleDuplicate: true });
    await api.auth.login({ role: 'sales_manager' });
    return Object.fromEntries((await api.expectedPayments.list(id)).map((r) => [r.id, r]));
  }

  it('N5: with an issued deposit notice a BALANCE receipt pays this weeks ON_DELIVERY, not the far-off DEPOSIT', async () => {
    const id = 19; // seeded with an ISSUED deposit notice
    const [onDelivery, deposit] = await plan(id, [['ON_DELIVERY', 50000, monday], ['DEPOSIT', 30000, plusDays(monday, 42)]]);
    const rows = await pay(id, 'BALANCE', 50000);
    expect(rows[onDelivery.id]).toMatchObject({ status: 'RECEIVED', receivedAmount: 50000 });
    expect(rows[deposit.id]).toMatchObject({ status: 'OPEN', receivedAmount: 0 });
  });

  it('N5: with a notice a DEPOSIT receipt fills DEPOSIT rows first, and overflow reaches the other rows by week', async () => {
    const id = 20; // seeded with an ISSUED deposit notice
    const [onDelivery, deposit] = await plan(id, [['ON_DELIVERY', 300, monday], ['DEPOSIT', 300, plusDays(monday, 14)]]);
    const rows = await pay(id, 'DEPOSIT', 500);
    expect(rows[deposit.id]).toMatchObject({ status: 'RECEIVED', receivedAmount: 300 });
    expect(rows[onDelivery.id]).toMatchObject({ status: 'OPEN', receivedAmount: 200 });
  });

  it('N5: with NO deposit notice every OPEN row fills purely by expected week, whatever its kind', async () => {
    const id = 22; // no deposit notice
    const [balance, onDelivery, deposit] = await plan(id, [
      ['BALANCE', 300, monday], ['ON_DELIVERY', 300, plusDays(monday, 7)], ['DEPOSIT', 300, plusDays(monday, 14)]]);
    const rows = await pay(id, 'BALANCE', 600);
    expect(rows[balance.id].status).toBe('RECEIVED');
    expect(rows[onDelivery.id].status).toBe('RECEIVED');
    expect(rows[deposit.id]).toMatchObject({ status: 'OPEN', receivedAmount: 0 });
  });

  it('a new OPEN deposit this month shows in my-work cash as forecast and deposits expected', async () => {
    const { id } = await ownDealId();
    // ISO rule: the week counts in the month holding its Thursday.
    const inMonth = plusDays(monday, 3).slice(0, 7) === bangkokTodayIso().slice(0, 7) ? monday : plusDays(monday, 7);
    const before = (await api.salesBoard.myWork()).cash;
    await api.expectedPayments.create(id, { kind: 'DEPOSIT', amount: 1234, expectedWeek: inMonth });
    const after = (await api.salesBoard.myWork()).cash;
    expect(after.month).toBe(bangkokTodayIso().slice(0, 7));
    expect(after.forecast - before.forecast).toBe(1234);
    expect(after.depositsExpected - before.depositsExpected).toBe(1234);
    expect(after.weeks.every((w) => new Date(`${w.weekStart}T00:00:00Z`).getUTCDay() === 1)).toBe(true);
  });
});
