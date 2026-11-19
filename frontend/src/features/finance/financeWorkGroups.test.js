import { describe, expect, it } from 'vitest';
import { nextAccountAction } from '../tickets/accountActions.js';
import { financeWorkGroupKey, groupFinanceWork } from './financeWorkGroups.js';

// Ticket fixtures shaped like TicketSummaryDto, one per state nextAccountAction distinguishes. The grouping is
// tested THROUGH the real nextAccountAction, so a fixture that stops producing its key fails here too.
const base = {
  status: 'quotation_issued', lifecycle: 'ACTIVE', depositPolicy: 'REQUIRED', fulfillmentStatus: null,
  closeConfirmedAt: null, invoiceOnFile: false, overdue: false, paymentDueDate: null, commissionRecorded: false,
};
const T = {
  chaseOverdue: { ...base, id: 1, paymentStatus: 'AWAITING_FINAL_PAYMENT', salesStage: 'DELIVERED', amountPayable: 30000, amountPaid: 0, amountOutstanding: 30000, overdue: true, paymentDueDate: '2026-09-01' },
  confirmDeposit: { ...base, id: 2, paymentStatus: 'DEPOSIT_NOTICE_ISSUED', salesStage: 'NEGOTIATION', amountPayable: 80000, amountPaid: 0, amountOutstanding: 80000 },
  confirmFinalPayment: { ...base, id: 3, paymentStatus: 'AWAITING_FINAL_PAYMENT', salesStage: 'DELIVERY_SCHEDULING', amountPayable: 120000, amountPaid: 60000, amountOutstanding: 60000 },
  confirmCloseReady: { ...base, id: 4, paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED', invoiceOnFile: true, salesStage: 'DELIVERED', amountPayable: 50000, amountPaid: 50000, amountOutstanding: 0 },
  recordInvoiceCommission: { ...base, id: 5, paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED', invoiceOnFile: true, salesStage: 'CLOSED_PAID', closeConfirmedAt: '2026-07-01T00:00:00Z', amountPayable: 90000, amountPaid: 90000, amountOutstanding: 0 },
};
const NONE = {
  idleProcurement: { ...base, id: 11, paymentStatus: 'CUSTOMER_CONFIRMED', salesStage: 'PROCUREMENT', amountPayable: 50000, amountPaid: 0, amountOutstanding: 50000 },
  finalPaymentBeforeDelivery: { ...base, id: 12, paymentStatus: 'AWAITING_FINAL_PAYMENT', salesStage: 'PROCUREMENT', amountPayable: 100000, amountPaid: 50000, amountOutstanding: 50000 },
  closeAlreadyConfirmed: { ...T.confirmCloseReady, id: 13, closeConfirmedAt: '2026-07-02T00:00:00Z' },
  commissionAlreadyRecorded: { ...T.recordInvoiceCommission, id: 14, commissionRecorded: true },
  cancelled: { ...T.confirmDeposit, id: 15, lifecycle: 'CANCELLED', paymentStatus: 'FULLY_PAID', amountOutstanding: 0 },
};

const rowOf = (ticket, role) => ({ ticket, action: nextAccountAction(ticket, role) });

describe('financeWorkGroupKey — maps nextAccountAction keys, adds no rule of its own', () => {
  it.each([
    ['chaseOverdue', 'chase'],
    ['confirmDeposit', 'chase'],
    ['confirmFinalPayment', 'chase'],
    ['confirmCloseReady', 'closeReady'],
    ['recordInvoiceCommission', 'invoice'],
  ])('a deal whose next account action is %s lands in group %s', (actionKey, groupKey) => {
    const action = nextAccountAction(T[actionKey], 'account');
    expect(action?.key).toBe(actionKey); // the fixture really is in that state
    expect(financeWorkGroupKey(action)).toBe(groupKey);
  });
});

describe('groupFinanceWork', () => {
  const all = [...Object.values(T), ...Object.values(NONE)];

  it('puts every actionable deal in exactly one group, none twice, none dropped, and no idle deal anywhere', () => {
    const rows = all.map((t) => rowOf(t, 'account'));
    const groups = groupFinanceWork(rows);
    const ids = groups.flatMap((g) => g.rows.map((r) => r.ticket.id));
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids.sort()).toEqual(Object.values(T).map((t) => t.id).sort());
    expect(Object.fromEntries(groups.map((g) => [g.key, g.rows.map((r) => r.ticket.id)]))).toEqual({
      closeReady: [4],
      chase: [1, 2, 3],
      invoice: [5],
    });
  });

  it('for the CEO, deposit/final-payment deals (account-only steps today) land in no group; overdue still does', () => {
    const rows = Object.values(T).map((t) => rowOf(t, 'ceo'));
    const groups = groupFinanceWork(rows);
    expect(groups.find((g) => g.key === 'chase').rows.map((r) => r.ticket.id)).toEqual([1]);
    const ids = groups.flatMap((g) => g.rows.map((r) => r.ticket.id));
    expect(ids).not.toContain(2);
    expect(ids).not.toContain(3);
  });
});
