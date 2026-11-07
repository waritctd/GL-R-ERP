import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Owner ruling 2026-11-07: รับชำระส่วนที่เหลือ (finance.confirmFinalPayment) is refused until the deal has
// finished money-milestone 3, i.e. reached DELIVERY_SCHEDULING or later. Pins the MOCK against drifting from
// TicketService#canConfirmFinalPaymentNow; the Java TicketServiceTest is the authority.

const EARLY = ['ORDER_RECEIVED', 'DEPOSIT_RECEIVED', 'PROCUREMENT'];
const isDead = (t) => ['CLOSED_LOST', 'CANCELLED'].includes(t.lifecycle) || t.status === 'cancelled';
const bypass = (t) => ['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'].includes(t.depositPolicy)
  && (t.paymentStatus == null || t.paymentStatus === 'CUSTOMER_CONFIRMED');
const paymentDue = (t) => ['DEPOSIT_PAID', 'AWAITING_FINAL_PAYMENT'].includes(t.paymentStatus) || bypass(t);
const live = (t) => !isDead(t) && (t.lifecycle ?? 'ACTIVE') === 'ACTIVE' && t.status === 'quotation_issued';

async function accountDeals() {
  await api.auth.login({ role: 'account' });
  return (await api.tickets.list()).tickets.filter((t) => live(t) && paymentDue(t));
}

describe('mockApi.finance.confirmFinalPayment -- stage gate', () => {
  it('refuses (409) a payment-due deal still at ORDER_RECEIVED / DEPOSIT_RECEIVED / PROCUREMENT and leaves it unchanged', async () => {
    const early = (await accountDeals()).filter((t) => EARLY.includes(t.salesStage));
    expect(early.length, 'seed must contain an early-stage payment-due deal').toBeGreaterThan(0);
    for (const t of early) {
      await expect(api.finance.confirmFinalPayment(t.id), `deal ${t.id} @ ${t.salesStage}`).rejects.toMatchObject({ status: 409 });
    }
    const after = new Map((await accountDeals()).map((t) => [t.id, t.paymentStatus]));
    for (const t of early) expect(after.get(t.id), `deal ${t.id} unchanged`).toBe(t.paymentStatus);
  });
});
