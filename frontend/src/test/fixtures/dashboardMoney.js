// One ticket list + the figures it must produce, shared by the CEO and sales-manager dashboard
// tests so both are pinned to the SAME three numbers (เงินรับแล้ว / ค้างรับ / ยอดคาดการณ์).
import { expect } from 'vitest';
import { screen } from '@testing-library/react';

const base = {
  lifecycle: 'ACTIVE', status: 'quotation_issued', paymentStatus: null, fulfillmentStatus: null,
  depositPolicy: 'REQUIRED', closeConfirmedAt: null, invoiceOnFile: false, commissionRecorded: false,
  overdue: false, amountPaid: 0,
};

// Deposit notice issued (ขั้น 10): the whole deal is outstanding.
export const depositPendingDeal = {
  ...base, id: 1, code: 'DL-1', customerName: 'ดีลรอมัดจำ', salesStage: 'ORDER_RECEIVED',
  paymentStatus: 'DEPOSIT_NOTICE_ISSUED', amountPayable: 213545.25, amountOutstanding: 213545.25,
  depositDue: 85418.10,
};
// Deposit paid, final payment next (ขั้น 18).
export const finalPaymentDeal = {
  ...base, id: 2, code: 'DL-2', customerName: 'ดีลรอชำระส่วนที่เหลือ', salesStage: 'DELIVERY_SCHEDULING',
  paymentStatus: 'DEPOSIT_PAID', amountPayable: 120000, amountPaid: 40000, amountOutstanding: 80000,
};
// DEMO-TKT-05 shape: finished deal that still carries an outstanding figure. Not account's work.
export const closedDemoDeal = {
  ...base, id: 3, code: 'DEMO-TKT-05', customerName: 'ดีลปิดแล้ว', status: 'closed',
  salesStage: 'CLOSED_PAID', commissionRecorded: true, amountPayable: 196880, amountOutstanding: 196880,
};
// Before ขั้น 10, deposit not required: nextAccountAction offers a step, but account's list never has it.
export const preOrderBypassDeal = {
  ...base, id: 4, code: 'DL-4', customerName: 'ดีลก่อนสั่งซื้อ-ไม่เก็บมัดจำ', salesStage: 'QUOTE_BUYER',
  depositPolicy: 'NOT_REQUIRED', amountPayable: 50000, amountOutstanding: 50000,
};
// Before ขั้น 10, no account step at all.
export const preOrderPlainDeal = {
  ...base, id: 5, code: 'DL-5', customerName: 'ดีลก่อนสั่งซื้อ', salesStage: 'QUOTE_BUYER',
  amountPayable: 30000.25, amountOutstanding: 30000.25,
};
// Before ขั้น 10 but lost: not in the forecast.
export const lostPreOrderDeal = {
  ...base, id: 6, code: 'DL-6', customerName: 'ดีลที่แพ้', lifecycle: 'CLOSED_LOST', salesStage: 'QUOTE_BUYER',
  amountPayable: 77777, amountOutstanding: 77777,
};

export const moneyFixtureTickets = [
  depositPendingDeal, finalPaymentDeal, closedDemoDeal, preOrderBypassDeal, preOrderPlainDeal, lostPreOrderDeal,
];

export const CASH_RECEIVED_THIS_MONTH = 125000.5;
// Per-rep split of CASH_RECEIVED_THIS_MONTH, served ascending on purpose: the panels must sort it themselves.
export const CASH_BY_REP = [
  { repName: 'สมหญิง ขายเก่ง', amount: 35000 },
  { repName: 'สมชาย ขายดี', amount: 90000.5 },
];
export const EXPECTED = {
  cash: '฿125,000.50',
  outstanding: '฿293,545.25', // 213,545.25 + 80,000 — not the closed, pre-order or lost deals
  forecast: '฿80,000.25', // 50,000 + 30,000.25 — pre-ขั้น 10, ACTIVE, not closed
};

/** Text of every row/tile carrying `label`: a StatCard, or a <dt>/<dd> pair in a <dl>. */
export function figureRows(label) {
  return screen.queryAllByText(label).map((el) => el.closest('.stat-card, dl > div')?.textContent ?? '');
}

export function expectFigure(label, value) {
  const rows = figureRows(label);
  expect(rows.length, `"${label}" is shown`).toBeGreaterThan(0);
  rows.forEach((text) => expect(text).toContain(value));
}
