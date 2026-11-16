import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { nextAccountAction } from '../tickets/accountActions.js';
import { FINANCE_WORK_GROUPS, financeWorkGroupKey, groupFinanceWork } from './financeWorkGroups.js';

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

describe('FINANCE_WORK_GROUPS (drawing a-fin-land, verbatim)', () => {
  it('is exactly the three drawn groups, in the drawn order, with the drawn labels, hints and row buttons', () => {
    expect(FINANCE_WORK_GROUPS.map((g) => [g.key, g.label, g.hint, g.buttonLabel])).toEqual([
      ['closeReady', 'ยืนยันพร้อมปิดงาน', 'พร้อมปิดงาน รอฝ่ายบัญชียืนยัน', 'ยืนยันพร้อมปิดงาน'],
      ['chase', 'ติดตามชำระ', 'ออกใบแจ้งหนี้แล้ว ยังไม่พบเงินในไฟล์ล่าสุด', 'เปิด'],
      ['invoice', 'บันทึกใบกำกับ', 'ใช้คำนวณคำขอค่าคอม (เหมือนเดิม)', 'บันทึกใบกำกับ'],
    ]);
  });
});

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

  it.each(Object.keys(NONE))('a deal finance has nothing to do on (%s) lands in no group', (name) => {
    const action = nextAccountAction(NONE[name], 'account');
    expect(action).toBeNull();
    expect(financeWorkGroupKey(action)).toBeNull();
  });

  it('an action key it does not know is never dropped: it lands in the fallback group "other"', () => {
    expect(financeWorkGroupKey({ key: 'somethingNew', label: 'ขั้นใหม่' })).toBe('other');
    expect(financeWorkGroupKey(undefined)).toBeNull();
    expect(financeWorkGroupKey(null)).toBeNull();
  });

  // Exhaustiveness. accountActions.js exports no key list, so the keys are read from nextAccountAction's own body
  // (every `key: '…'` it can return). A new key there that no drawn group lists turns this red.
  it('every key nextAccountAction can return is mapped to a drawn group (not to the fallback)', () => {
    const src = readFileSync(`${process.cwd()}/src/features/tickets/accountActions.js`, 'utf8');
    const body = src.slice(src.indexOf('export function nextAccountAction'), src.indexOf('export function accountMoneyBucket'));
    const keys = [...body.matchAll(/key:\s*'(\w+)'/g)].map((m) => m[1]);
    // Pinned as well, so the scan itself cannot quietly find nothing.
    expect([...keys].sort()).toEqual(['chaseOverdue', 'confirmCloseReady', 'confirmDeposit', 'confirmFinalPayment', 'recordInvoiceCommission']);
    const mapped = FINANCE_WORK_GROUPS.flatMap((g) => g.actionKeys);
    keys.forEach((k) => expect([k, mapped.includes(k)]).toEqual([k, true]));
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

  it('keeps the incoming row order inside each group (the page sorts once, before grouping)', () => {
    const rows = [T.confirmFinalPayment, T.chaseOverdue, T.confirmDeposit].map((t) => rowOf(t, 'account'));
    expect(groupFinanceWork(rows).find((g) => g.key === 'chase').rows.map((r) => r.ticket.id)).toEqual([3, 1, 2]);
  });

  it('a row with an unknown action key goes to a last "อื่น ๆ ที่ต้องดำเนินการ" group (button เปิด), so group counts sum to the actionable rows', () => {
    const rows = [
      rowOf(T.confirmDeposit, 'account'),
      { ticket: { id: 99 }, action: { key: 'somethingNew', label: 'ขั้นใหม่' } },
      rowOf(NONE.idleProcurement, 'account'),
    ];
    const groups = groupFinanceWork(rows);
    const other = groups[groups.length - 1];
    expect([other.key, other.label, other.buttonLabel]).toEqual(['other', 'อื่น ๆ ที่ต้องดำเนินการ', 'เปิด']);
    expect(other.rows.map((r) => r.ticket.id)).toEqual([99]);
    const total = groups.reduce((n, g) => n + g.rows.length, 0);
    expect(total).toBe(rows.filter((r) => r.action != null).length);
  });

  it('the fallback group is left out when it has no rows', () => {
    const groups = groupFinanceWork(Object.values(T).map((t) => rowOf(t, 'account')));
    expect(groups.map((g) => g.key)).toEqual(['closeReady', 'chase', 'invoice']);
  });

  it('always returns all three groups, empty ones included, so an empty group can say so', () => {
    const groups = groupFinanceWork([]);
    expect(groups.map((g) => [g.key, g.rows.length])).toEqual([['closeReady', 0], ['chase', 0], ['invoice', 0]]);
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
