import { describe, expect, it } from 'vitest';
import { accountMoneyBucket, nextAccountAction } from './accountActions.js';

function ticket(overrides = {}) {
  return {
    id: 1,
    status: 'quotation_issued',
    paymentStatus: null,
    fulfillmentStatus: null,
    lifecycle: 'ACTIVE',
    salesStage: 'NEGOTIATION',
    depositPolicy: 'REQUIRED',
    closeConfirmedAt: null,
    invoiceOnFile: false,
    amountPayable: 100000,
    amountPaid: 0,
    amountOutstanding: 100000,
    overdue: false,
    dueDate: null,
    ...overrides,
  };
}

describe('nextAccountAction', () => {
  it('returns null for a deal with no pending account step', () => {
    expect(nextAccountAction(ticket({ paymentStatus: 'CUSTOMER_CONFIRMED', depositPolicy: 'REQUIRED' }))).toBeNull();
  });

  it('returns null for null input', () => {
    expect(nextAccountAction(null)).toBeNull();
  });

  it('deposit-notice-issued -> ยืนยันรับมัดจำ, deep-links to the ticket', () => {
    const t = ticket({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' });
    const action = nextAccountAction(t);
    expect(action.key).toBe('confirmDeposit');
    expect(action.label).toBe('ยืนยันรับมัดจำ');
    expect(action.to).toBe('/tickets/1');
    expect(action.urgent).toBe(false);
  });

  it('awaiting-final-payment -> รับชำระส่วนที่เหลือ', () => {
    const t = ticket({ paymentStatus: 'AWAITING_FINAL_PAYMENT' });
    const action = nextAccountAction(t);
    expect(action.key).toBe('confirmFinalPayment');
    expect(action.label).toBe('รับชำระส่วนที่เหลือ');
    expect(action.to).toBe('/tickets/1');
  });

  it('deposit-paid also counts as final payment due (mirrors TicketService#canConfirmFinalPaymentNow)', () => {
    const t = ticket({ paymentStatus: 'DEPOSIT_PAID' });
    expect(nextAccountAction(t).key).toBe('confirmFinalPayment');
  });

  it('a deposit-bypassed deal (WAIVED policy) can go straight to final payment', () => {
    const t = ticket({ depositPolicy: 'WAIVED', paymentStatus: 'CUSTOMER_CONFIRMED' });
    expect(nextAccountAction(t).key).toBe('confirmFinalPayment');
  });

  it('an overdue balance outranks the plain deposit/final-payment wording -> ติดตามชำระ', () => {
    const t = ticket({ paymentStatus: 'AWAITING_FINAL_PAYMENT', overdue: true, amountOutstanding: 5000 });
    const action = nextAccountAction(t);
    expect(action.key).toBe('chaseOverdue');
    expect(action.label).toBe('ติดตามชำระ');
    expect(action.urgent).toBe(true);
  });

  it('overdue with nothing actually outstanding is not chased (guards against a stale flag)', () => {
    const t = ticket({
      overdue: true, amountOutstanding: 0, paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED', invoiceOnFile: true,
    });
    // Resolves to the close-ready step instead — never "ติดตามชำระ" for a $0 balance.
    expect(nextAccountAction(t).key).toBe('confirmCloseReady');
  });

  it('close-ready (fully paid + fully delivered + invoice on file, not yet confirmed) -> ยืนยันพร้อมปิดงาน', () => {
    const t = ticket({
      paymentStatus: 'FULLY_PAID',
      fulfillmentStatus: 'FULLY_DELIVERED',
      amountOutstanding: 0,
      invoiceOnFile: true,
    });
    const action = nextAccountAction(t);
    expect(action.key).toBe('confirmCloseReady');
    expect(action.label).toBe('ยืนยันพร้อมปิดงาน');
    expect(action.to).toBe('/tickets/1');
  });

  it('fully paid + fully delivered but the invoice is not on file is NOT close-ready (mirrors requireClosePrerequisites)', () => {
    const t = ticket({
      paymentStatus: 'FULLY_PAID',
      fulfillmentStatus: 'FULLY_DELIVERED',
      amountOutstanding: 0,
      invoiceOnFile: false,
    });
    expect(nextAccountAction(t)).toBeNull();
  });

  it('already close-confirmed (closeConfirmedAt set) is no longer account’s action', () => {
    const t = ticket({
      paymentStatus: 'FULLY_PAID',
      fulfillmentStatus: 'FULLY_DELIVERED',
      amountOutstanding: 0,
      invoiceOnFile: true,
      closeConfirmedAt: '2026-07-20T00:00:00.000Z',
    });
    expect(nextAccountAction(t)).toBeNull();
  });

  it('CLOSED_PAID -> บันทึกใบกำกับ + ออกค่าคอม, deep-links to the create-from-deal flow', () => {
    const t = ticket({
      id: 42,
      status: 'quotation_issued',
      paymentStatus: 'FULLY_PAID',
      fulfillmentStatus: 'FULLY_DELIVERED',
      amountOutstanding: 0,
      salesStage: 'CLOSED_PAID',
      closeConfirmedAt: '2026-07-20T00:00:00.000Z',
    });
    const action = nextAccountAction(t);
    expect(action.key).toBe('recordInvoiceCommission');
    expect(action.label).toBe('บันทึกใบกำกับ + ออกค่าคอม');
    expect(action.to).toBe('/commissions?ticketId=42');
  });

  it('a legacy (pre-dual-track) fully-paid document_issued deal is also close-ready', () => {
    const t = ticket({ status: 'document_issued', paymentStatus: 'FULLY_PAID', amountOutstanding: 0 });
    expect(nextAccountAction(t).key).toBe('confirmCloseReady');
  });
});

describe('accountMoneyBucket', () => {
  it('buckets match nextAccountAction’s own key ordering', () => {
    expect(accountMoneyBucket(ticket({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' }))).toBe('depositPending');
    expect(accountMoneyBucket(ticket({ paymentStatus: 'AWAITING_FINAL_PAYMENT' }))).toBe('finalPaymentPending');
    expect(accountMoneyBucket(ticket({ paymentStatus: 'AWAITING_FINAL_PAYMENT', overdue: true, amountOutstanding: 500 }))).toBe('overdue');
    expect(accountMoneyBucket(ticket({
      paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED', amountOutstanding: 0, invoiceOnFile: true,
    }))).toBe('closeReady');
    expect(accountMoneyBucket(ticket({ salesStage: 'CLOSED_PAID', paymentStatus: 'FULLY_PAID', amountOutstanding: 0 }))).toBe('commissionPending');
    expect(accountMoneyBucket(ticket({ paymentStatus: 'CUSTOMER_CONFIRMED' }))).toBeNull();
  });
});
