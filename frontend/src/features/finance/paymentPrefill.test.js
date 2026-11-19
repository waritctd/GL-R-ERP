import { describe, expect, it } from 'vitest';
import { makeFinanceDeal } from '../../test/fixtures/financeDeal.js';
import { paymentPrefill } from './paymentPrefill.js';

// Spec 2026-11-15 §7.11 item 6 / plan task 5.5: บันทึกรับชำระ is the fallback for receipts Express could not match, so
// it opens on the document sales already issued — its outstanding amount and its kind — instead of a blank form.
const deal = (money = {}, documents = {}) => {
  const d = makeFinanceDeal();
  Object.assign(d.money, money);
  Object.assign(d.documents, documents);
  return d;
};
const notice = (o = {}) => ({
  id: 7, docNumber: 'DN-2026-0007', version: 1, status: 'ISSUED', issueDate: '2026-08-26',
  depositAmount: 40000, totalPayable: 42800, totalPayableVatBasis: 'INCLUDING_VAT', downloadPath: '/x', ...o,
});
const invoice = (o = {}) => ({
  id: 12, docNumber: 'RI-2026-0012', version: 1, status: 'ISSUED', docDate: '2026-10-01',
  grandTotal: 85600, grandTotalVatBasis: 'INCLUDING_VAT', downloadPath: '/y', ...o,
});

describe('paymentPrefill', () => {
  it('a deposit still waiting on its notice: DEPOSIT, the notice total less what was already taken as deposit', () => {
    const d = deal(
      { paymentStatus: 'DEPOSIT_NOTICE_ISSUED', amountPaid: 2800, payments: [{ id: 1, kind: 'DEPOSIT', amount: 2800 }] },
      { depositNotices: [notice()], remainingInvoices: [] },
    );
    expect(paymentPrefill(d)).toMatchObject({
      kind: 'DEPOSIT', amount: 40000, docLabel: 'ใบแจ้งมัดจำ DN-2026-0007', basisHint: '= ยอดคงค้างตามใบแจ้งมัดจำ',
    });
  });

  // Review LOW: the invoice branch is THAT invoice's own outstanding (its grand total less the balance already taken),
  // not the deal-wide figure, which also carries any unpaid deposit or credit.
  it('a balance with an issued remaining invoice: BALANCE, that invoice’s own outstanding, not the deal-wide one', () => {
    const d = deal(
      { paymentStatus: 'AWAITING_FINAL_PAYMENT', amountOutstanding: 99999,
        payments: [{ id: 1, kind: 'DEPOSIT', amount: 40000 }, { id: 2, kind: 'BALANCE', amount: 30000 }] },
      { remainingInvoices: [invoice()] },
    );
    expect(paymentPrefill(d)).toEqual({
      kind: 'BALANCE', amount: 55600, docLabel: 'ใบแจ้งหนี้ส่วนที่เหลือ RI-2026-0012', basisHint: '= ยอดคงค้างตามใบแจ้งหนี้',
      whtHint: null,
    });
  });

  it('the deposit branch counts an ADJUSTMENT (a refund / correction) against the deposit already taken', () => {
    const d = deal(
      { paymentStatus: 'DEPOSIT_NOTICE_ISSUED', payments: [{ id: 1, kind: 'DEPOSIT', amount: 10000 }, { id: 2, kind: 'ADJUSTMENT', amount: 2000 }] },
      { depositNotices: [notice()], remainingInvoices: [] },
    );
    expect(paymentPrefill(d).amount).toBe(34800);
  });
});
