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

  it('a new OPEN deposit this month shows in my-work cash as forecast and deposits expected', async () => {
    const { id } = await ownDealId();
    const inMonth = monday.slice(0, 7) === bangkokTodayIso().slice(0, 7) ? monday : plusDays(monday, 7);
    const before = (await api.salesBoard.myWork()).cash;
    await api.expectedPayments.create(id, { kind: 'DEPOSIT', amount: 1234, expectedWeek: inMonth });
    const after = (await api.salesBoard.myWork()).cash;
    expect(after.month).toBe(bangkokTodayIso().slice(0, 7));
    expect(after.forecast - before.forecast).toBe(1234);
    expect(after.depositsExpected - before.depositsExpected).toBe(1234);
    expect(after.weeks.every((w) => new Date(`${w.weekStart}T00:00:00Z`).getUTCDay() === 1)).toBe(true);
  });
});

// Slice 5 (plan task 5.5): a payment account records BY HAND marks the nearest OPEN expected payment of the matching
// kind RECEIVED, like a matched Express receipt. Mirrors TicketService#recordPaymentInternal ->
// ExpectedPaymentRepository#markNearestOpenReceived(kinds); ManualPaymentExpectedPaymentIntegrationTest is the evidence.
describe('mockApi.finance.recordPayment settles an expected payment', () => {
  async function payableDealForAccount() {
    await api.auth.login({ role: 'account' });
    for (let id = 1; id <= 400; id += 1) {
      try {
        const { deal } = await api.finance.getDeal(id);
        if (deal.lifecycle === 'ACTIVE' && deal.money.amountOutstanding >= 500
          && deal.availableActions.some((a) => a.action === 'RECORD_PAYMENT')) return deal.id;
      } catch { /* not in account scope */ }
    }
    throw new Error('no payable deal in the seed');
  }

  it('a BALANCE typed by hand marks the ON_DELIVERY row, not the DEPOSIT one of the same amount', async () => {
    const id = await payableDealForAccount();
    await api.auth.login({ role: 'ceo' });
    const deposit = await api.expectedPayments.create(id, { kind: 'DEPOSIT', amount: 250, expectedWeek: monday });
    const onDelivery = await api.expectedPayments.create(id, { kind: 'ON_DELIVERY', amount: 250, expectedWeek: plusDays(monday, 21) });
    await api.auth.login({ role: 'account' });
    await api.finance.recordPayment(id, { kind: 'BALANCE', amount: 250, receiptRef: `S5-${id}`, allowOverpayment: false });
    const rows = await api.expectedPayments.list(id);
    expect(rows.find((r) => r.id === onDelivery.id)).toMatchObject({ status: 'RECEIVED', receiptRef: `S5-${id}` });
    expect(rows.find((r) => r.id === onDelivery.id).receiptId).not.toBeNull();
    expect(rows.find((r) => r.id === deposit.id)).toMatchObject({ status: 'OPEN', receiptId: null });
  });
});
