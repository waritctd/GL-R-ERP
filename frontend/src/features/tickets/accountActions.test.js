import { describe, expect, it } from 'vitest';
import { accountMoneyBucket, accountOwesStep, awaitingGoodsForFinalPayment, nextAccountAction } from './accountActions.js';

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

  it('deposit-notice-issued -> ยืนยันรับมัดจำ, deep-links to the finance deal page', () => {
    const t = ticket({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' });
    const action = nextAccountAction(t);
    expect(action.key).toBe('confirmDeposit');
    expect(action.label).toBe('ยืนยันรับมัดจำ');
    expect(action.to).toBe('/finance/deals/1');
    expect(action.urgent).toBe(false);
  });

  it('awaiting-final-payment -> รับชำระส่วนที่เหลือ', () => {
    const t = ticket({ paymentStatus: 'AWAITING_FINAL_PAYMENT', salesStage: 'DELIVERY_SCHEDULING' });
    const action = nextAccountAction(t);
    expect(action.key).toBe('confirmFinalPayment');
    expect(action.label).toBe('รับชำระส่วนที่เหลือ');
    expect(action.to).toBe('/finance/deals/1');
  });

  it('deposit-paid also counts as final payment due (mirrors TicketService#canConfirmFinalPaymentNow)', () => {
    const t = ticket({ paymentStatus: 'DEPOSIT_PAID', salesStage: 'DELIVERY_SCHEDULING' });
    expect(nextAccountAction(t).key).toBe('confirmFinalPayment');
  });

  it('a deposit-bypassed deal (WAIVED policy) can go straight to final payment', () => {
    const t = ticket({ depositPolicy: 'WAIVED', paymentStatus: 'CUSTOMER_CONFIRMED', salesStage: 'DELIVERY_SCHEDULING' });
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
    expect(action.to).toBe('/finance/deals/1');
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

  it('CLOSED_PAID -> บันทึกใบกำกับ, deep-links to the finance deal page (invoice is recorded there)', () => {
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
    expect(action.label).toBe('บันทึกใบกำกับ');
    expect(action.to).toBe('/finance/deals/42');
  });

  // Issue #736 pair, wrong-way-round: commissionRecorded:true is the interesting half — the
  // "still yields recordInvoiceCommission" half alone would have passed before this change too,
  // since a CLOSED_PAID deal always resolved to step 5 regardless of whether the commission
  // already existed.
  it('CLOSED_PAID with commissionRecorded:true is no longer account’s action', () => {
    const t = ticket({
      id: 42,
      status: 'quotation_issued',
      paymentStatus: 'FULLY_PAID',
      fulfillmentStatus: 'FULLY_DELIVERED',
      amountOutstanding: 0,
      salesStage: 'CLOSED_PAID',
      closeConfirmedAt: '2026-07-20T00:00:00.000Z',
      commissionRecorded: true,
    });
    expect(nextAccountAction(t)).toBeNull();
  });

  it('CLOSED_PAID with commissionRecorded:false still yields บันทึกใบกำกับ', () => {
    const t = ticket({
      id: 42,
      status: 'quotation_issued',
      paymentStatus: 'FULLY_PAID',
      fulfillmentStatus: 'FULLY_DELIVERED',
      amountOutstanding: 0,
      salesStage: 'CLOSED_PAID',
      closeConfirmedAt: '2026-07-20T00:00:00.000Z',
      commissionRecorded: false,
    });
    const action = nextAccountAction(t);
    expect(action.key).toBe('recordInvoiceCommission');
    expect(action.label).toBe('บันทึกใบกำกับ');
    expect(action.to).toBe('/finance/deals/42');
  });

  it('a legacy (pre-dual-track) fully-paid document_issued deal is also close-ready', () => {
    const t = ticket({ status: 'document_issued', paymentStatus: 'FULLY_PAID', amountOutstanding: 0 });
    expect(nextAccountAction(t).key).toBe('confirmCloseReady');
  });

  // Owner ruling 2026-11-07: รับชำระส่วนที่เหลือ stays closed until money-milestone 3 (PROCUREMENT) is
  // done, i.e. the deal reached DELIVERY_SCHEDULING or later. Mirrors TicketService#canConfirmFinalPaymentNow.
  describe('final payment is gated until the deal reaches DELIVERY_SCHEDULING', () => {
    const early = ['ORDER_RECEIVED', 'DEPOSIT_RECEIVED', 'PROCUREMENT'];
    const late = ['DELIVERY_SCHEDULING', 'DELIVERED'];
    const shapes = [
      ['DEPOSIT_PAID', { paymentStatus: 'DEPOSIT_PAID' }],
      ['AWAITING_FINAL_PAYMENT', { paymentStatus: 'AWAITING_FINAL_PAYMENT' }],
      ['deposit-bypass + CUSTOMER_CONFIRMED', { depositPolicy: 'WAIVED', paymentStatus: 'CUSTOMER_CONFIRMED' }],
    ];
    const cases = (stages) => stages.flatMap((stage) => shapes.map(([label, o]) => [`${label} @ ${stage}`, { ...o, salesStage: stage }]));

    it.each(cases(early))('%s: no final-payment CTA, no finalPaymentPending bucket', (_n, o) => {
      const t = ticket(o);
      expect(nextAccountAction(t, 'account')).toBeNull();
      expect(nextAccountAction(t)).toBeNull();
      expect(accountMoneyBucket(t)).toBeNull();
    });

    it.each(cases(late))('%s: still offered (regression guard)', (_n, o) => {
      const t = ticket(o);
      expect(nextAccountAction(t, 'account').key).toBe('confirmFinalPayment');
      expect(accountMoneyBucket(t)).toBe('finalPaymentPending');
    });

    it('an early-stage deal with a balance still owed keeps its overdue chase (outstanding is not hidden)', () => {
      const t = ticket({ paymentStatus: 'DEPOSIT_PAID', salesStage: 'PROCUREMENT', overdue: true, amountOutstanding: 5000 });
      expect(nextAccountAction(t, 'account').key).toBe('chaseOverdue');
      expect(accountMoneyBucket(t)).toBe('overdue');
    });
  });

  // GLA-118 (owner ruling 2026-09-20, part A): recording a payment — confirmDeposit/
  // confirmFinalPayment — is account only now, no CEO fallback. The CEO still reaches /finance
  // (canConfirmPayments is unchanged), so the worklist must stop offering those two CTAs to
  // anyone but account, or it hands the CEO a button the server now refuses with 403.
  describe('viewerRole (GLA-118 part A: CEO no longer records payments)', () => {
    it('deposit-notice-issued: ceo gets no CTA (no other step applies), account still gets ยืนยันรับมัดจำ', () => {
      const t = ticket({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' });
      expect(nextAccountAction(t, 'ceo')).toBeNull();
      expect(nextAccountAction(t, 'account').key).toBe('confirmDeposit');
    });

    it('awaiting-final-payment: ceo gets no CTA, account still gets รับชำระส่วนที่เหลือ', () => {
      const t = ticket({ paymentStatus: 'AWAITING_FINAL_PAYMENT', salesStage: 'DELIVERED' });
      expect(nextAccountAction(t, 'ceo')).toBeNull();
      expect(nextAccountAction(t, 'account').key).toBe('confirmFinalPayment');
    });

    it('omitting viewerRole entirely keeps the pre-existing (account-assumed) behaviour', () => {
      // workState.js calls nextAccountAction(deal) with no second argument, but only inside a
      // `role === 'account'` branch — this pins that the one-argument call site still resolves
      // exactly as before, so that caller needs no change.
      const t = ticket({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' });
      expect(nextAccountAction(t).key).toBe('confirmDeposit');
    });

    it('every viewer (account, ceo, omitted) is sent to the finance deal page; the commission step keeps /commissions', () => {
      const t = ticket({ id: 7, paymentStatus: 'AWAITING_FINAL_PAYMENT', overdue: true, amountOutstanding: 5000 });
      expect(nextAccountAction(t, 'ceo').to).toBe('/finance/deals/7');
      expect(nextAccountAction(t, 'account').to).toBe('/finance/deals/7');
      expect(nextAccountAction(t).to).toBe('/finance/deals/7');
    });

    it('overdue chase (a link to the ticket, not a payment submission) is unaffected by role', () => {
      const t = ticket({ paymentStatus: 'AWAITING_FINAL_PAYMENT', overdue: true, amountOutstanding: 5000 });
      expect(nextAccountAction(t, 'ceo').key).toBe('chaseOverdue');
    });

    it('the commission-recording step (unrelated to payment recording) is unaffected by role', () => {
      const t = ticket({
        id: 42, paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED',
        amountOutstanding: 0, salesStage: 'CLOSED_PAID', closeConfirmedAt: '2026-07-20T00:00:00.000Z',
      });
      expect(nextAccountAction(t, 'ceo').key).toBe('recordInvoiceCommission');
    });
  });
});

// ค้างรับ's SQL (ACCOUNT_HAS_TODO) has no stage condition, so accountOwesStep must not depend on the stage
// for an AWAITING_FINAL_PAYMENT / DEPOSIT_PAID deal, whatever stage it sits at (incl. null).
describe('accountOwesStep (ค้างรับ: owed regardless of the final-payment stage gate)', () => {
  const stages = ['QUOTE_BUYER', 'NEGOTIATION', 'ORDER_RECEIVED', 'DEPOSIT_RECEIVED', 'PROCUREMENT',
    'DELIVERY_SCHEDULING', 'DELIVERED', null, undefined];
  it.each(stages.map((s) => [String(s), s]))('AWAITING_FINAL_PAYMENT @ %s is owed', (_n, salesStage) => {
    expect(accountOwesStep(ticket({ paymentStatus: 'AWAITING_FINAL_PAYMENT', salesStage }))).toBe(true);
  });
  it.each(stages.map((s) => [String(s), s]))('DEPOSIT_PAID @ %s is owed', (_n, salesStage) => {
    expect(accountOwesStep(ticket({ paymentStatus: 'DEPOSIT_PAID', salesStage }))).toBe(true);
  });
  it('nothing pending is not owed, and null input is not owed', () => {
    expect(accountOwesStep(ticket({ paymentStatus: 'CUSTOMER_CONFIRMED' }))).toBe(false);
    expect(accountOwesStep(null)).toBe(false);
  });
});

describe('accountMoneyBucket', () => {
  it('buckets match nextAccountAction’s own key ordering', () => {
    expect(accountMoneyBucket(ticket({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' }))).toBe('depositPending');
    expect(accountMoneyBucket(ticket({ paymentStatus: 'AWAITING_FINAL_PAYMENT', salesStage: 'DELIVERED' }))).toBe('finalPaymentPending');
    expect(accountMoneyBucket(ticket({ paymentStatus: 'AWAITING_FINAL_PAYMENT', overdue: true, amountOutstanding: 500 }))).toBe('overdue');
    expect(accountMoneyBucket(ticket({
      paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED', amountOutstanding: 0, invoiceOnFile: true,
    }))).toBe('closeReady');
    expect(accountMoneyBucket(ticket({ salesStage: 'CLOSED_PAID', paymentStatus: 'FULLY_PAID', amountOutstanding: 0 }))).toBe('commissionPending');
    expect(accountMoneyBucket(ticket({ paymentStatus: 'CUSTOMER_CONFIRMED' }))).toBeNull();
  });

  // Issue #736, same wrong-way-round pair as nextAccountAction's above.
  it('commissionRecorded:true removes a CLOSED_PAID deal from the commissionPending bucket', () => {
    const closedPaid = { salesStage: 'CLOSED_PAID', paymentStatus: 'FULLY_PAID', amountOutstanding: 0 };
    expect(accountMoneyBucket(ticket({ ...closedPaid, commissionRecorded: true }))).toBeNull();
    expect(accountMoneyBucket(ticket({ ...closedPaid, commissionRecorded: false }))).toBe('commissionPending');
  });
});

// The รอสินค้าเข้า panel: owed money the final-payment stage gate is holding (not overdue, no CTA yet).
describe('awaitingGoodsForFinalPayment', () => {
  const held = (o = {}) => ticket({ paymentStatus: 'DEPOSIT_PAID', salesStage: 'PROCUREMENT', ...o });

  it.each([
    ['DEPOSIT_PAID', { paymentStatus: 'DEPOSIT_PAID' }],
    ['AWAITING_FINAL_PAYMENT', { paymentStatus: 'AWAITING_FINAL_PAYMENT' }],
    ['deposit-bypass + CUSTOMER_CONFIRMED', { depositPolicy: 'WAIVED', paymentStatus: 'CUSTOMER_CONFIRMED' }],
  ])('%s before DELIVERY_SCHEDULING is held', (_n, o) => {
    const t = held(o);
    expect(nextAccountAction(t, 'account')).toBeNull(); // the premise: no CTA yet
    expect(awaitingGoodsForFinalPayment(t)).toBe(true);
  });

  it.each(['DELIVERY_SCHEDULING', 'DELIVERED'])('once the deal is at %s the gate is open, so it is not held', (salesStage) => {
    expect(awaitingGoodsForFinalPayment(held({ salesStage }))).toBe(false);
  });

  it('an overdue balance is chased, not held', () => {
    expect(awaitingGoodsForFinalPayment(held({ overdue: true, amountOutstanding: 5000 }))).toBe(false);
  });

  it('a deal with no final-payment state (deposit notice only, nothing pending, not issued) is not held', () => {
    expect(awaitingGoodsForFinalPayment(held({ paymentStatus: 'DEPOSIT_NOTICE_ISSUED' }))).toBe(false);
    expect(awaitingGoodsForFinalPayment(held({ paymentStatus: 'CUSTOMER_CONFIRMED' }))).toBe(false);
    expect(awaitingGoodsForFinalPayment(held({ status: 'draft' }))).toBe(false);
  });

  it('null input is not held', () => {
    expect(awaitingGoodsForFinalPayment(null)).toBe(false);
  });
});
