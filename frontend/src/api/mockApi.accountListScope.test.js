import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// H1: mirrors TicketRepository#appendRoleScope's account branch -- every live order from
// ORDER_RECEIVED (S10) through CLOSED_PAID, UNIONED with the older pending-payment / overdue rule.
// The Java real-DB TicketScopeIntegrationTest is the authoritative evidence; this only pins that the
// mock's list has not drifted back to the payment-status-only scope that hid a PROCUREMENT deal
// sitting at DEPOSIT_PAID (the S15 bug).

const STAGE_ORDER = [
  'LEAD_APPROACH', 'PRESENTATION', 'SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF',
  'AWAITING_BUYER', 'QUOTE_BUYER', 'NEGOTIATION', 'ORDER_RECEIVED', 'DEPOSIT_RECEIVED', 'PROCUREMENT',
  'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID',
];
const FLOOR = STAGE_ORDER.indexOf('ORDER_RECEIVED');
const PENDING = ['DEPOSIT_NOTICE_ISSUED', 'AWAITING_FINAL_PAYMENT'];

const isDead = (t) => ['CLOSED_LOST', 'CANCELLED'].includes(t.lifecycle) || t.status === 'cancelled';

describe('mockApi.tickets.list -- account scope (H1)', () => {
  it('returns every live order from S10 on, plus the old pending/overdue rows, and nothing else', async () => {
    await api.auth.login({ role: 'ceo' });
    const all = (await api.tickets.list()).tickets;
    await api.auth.login({ role: 'account' });
    const seen = (await api.tickets.list()).tickets;
    const seenIds = new Set(seen.map((t) => t.id));

    // The seed must actually exercise the new disjunct, or this test proves nothing.
    const liveOrders = all.filter((t) => !isDead(t) && STAGE_ORDER.indexOf(t.salesStage) >= FLOOR);
    expect(liveOrders.length).toBeGreaterThan(0);
    const procurementDepositPaid = all.filter((t) => t.salesStage === 'PROCUREMENT' && !isDead(t));
    expect(procurementDepositPaid.length).toBeGreaterThan(0);

    for (const t of liveOrders) expect(seenIds.has(t.id), `deal ${t.id} at ${t.salesStage}`).toBe(true);

    // Wrong way round: nothing below S10 unless the OLD rule admits it, and no dead deal unless pending/overdue.
    for (const t of seen) {
      const stageIn = !isDead(t) && STAGE_ORDER.indexOf(t.salesStage) >= FLOOR;
      const oldRule = PENDING.includes(t.paymentStatus) || t.overdue === true;
      expect(stageIn || oldRule, `deal ${t.id} at ${t.salesStage}/${t.lifecycle}`).toBe(true);
    }
  });

  it('excludes a lost deal at PROCUREMENT that has no pending payment', async () => {
    await api.auth.login({ role: 'ceo' });
    const all = (await api.tickets.list()).tickets;
    await api.auth.login({ role: 'account' });
    const seenIds = new Set((await api.tickets.list()).tickets.map((t) => t.id));
    const deadLate = all.filter((t) => isDead(t) && STAGE_ORDER.indexOf(t.salesStage) >= FLOOR
      && !PENDING.includes(t.paymentStatus) && t.overdue !== true);
    for (const t of deadLate) expect(seenIds.has(t.id)).toBe(false);
  });
});
