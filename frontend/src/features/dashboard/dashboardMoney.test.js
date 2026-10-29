import { describe, expect, it } from 'vitest';
import { financeOutstanding, forecastValue } from './dashboardMoney.js';
import { nextAccountAction } from '../tickets/accountActions.js';
import {
  closedDemoDeal, depositPendingDeal, finalPaymentDeal, lostPreOrderDeal, moneyFixtureTickets,
  preOrderBypassDeal, preOrderPlainDeal,
} from '../../test/fixtures/dashboardMoney.js';

// financeOutstanding is /finance's คงค้างรวม: Σ amountOutstanding over deals that have an account
// to-do AND sit inside account's list scope (TicketRepository.appendRoleScope("account")).
describe('financeOutstanding', () => {
  it('counts a deposit-pending deal for its whole outstanding, not the deposit', () => {
    expect(financeOutstanding([depositPendingDeal])).toBeCloseTo(213545.25, 2);
  });

  it('counts a deposit-paid deal awaiting the final payment', () => {
    expect(financeOutstanding([finalPaymentDeal])).toBe(80000);
  });

  it('does not count a closed deal that still carries an outstanding figure (DEMO-TKT-05)', () => {
    expect(closedDemoDeal.amountOutstanding).toBe(196880);
    expect(financeOutstanding([closedDemoDeal])).toBe(0);
  });

  it('does not count a pre-ขั้น 10 deal even though nextAccountAction offers it a step', () => {
    // Guard: the fixture really is one the to-do helper would offer, so 0 below is the scope rule.
    expect(nextAccountAction(preOrderBypassDeal)).not.toBeNull();
    expect(financeOutstanding([preOrderBypassDeal])).toBe(0);
  });

  it('does not count an in-scope deal that has no account to-do', () => {
    const waiting = { ...depositPendingDeal, paymentStatus: 'CUSTOMER_CONFIRMED' };
    expect(nextAccountAction(waiting)).toBeNull();
    expect(financeOutstanding([waiting])).toBe(0);
  });

  it('counts an overdue pre-ขั้น 10 deal (overdue is its own scope branch)', () => {
    const overdue = { ...preOrderPlainDeal, overdue: true, amountOutstanding: 15000 };
    expect(financeOutstanding([overdue])).toBe(15000);
  });

  it('does not count a cancelled deal whose payment status is not a pending one', () => {
    const cancelled = { ...finalPaymentDeal, lifecycle: 'CANCELLED' };
    expect(nextAccountAction(cancelled)).not.toBeNull();
    expect(financeOutstanding([cancelled])).toBe(0);
  });

  it('sums the fixture and tolerates an empty or missing list', () => {
    expect(financeOutstanding(moneyFixtureTickets)).toBeCloseTo(293545.25, 2);
    expect(financeOutstanding([])).toBe(0);
    expect(financeOutstanding(undefined)).toBe(0);
  });
});

// forecastValue is ยอดคาดการณ์: Σ amountPayable over deals before ขั้น 10 that are still live.
describe('forecastValue', () => {
  it('sums amountPayable of active deals before ขั้น 10', () => {
    expect(forecastValue([preOrderBypassDeal, preOrderPlainDeal])).toBeCloseTo(80000.25, 2);
  });

  it('leaves out deals at or past ขั้น 10, lost/cancelled lifecycles and closed/cancelled status', () => {
    expect(forecastValue([depositPendingDeal, finalPaymentDeal, closedDemoDeal, lostPreOrderDeal])).toBe(0);
    expect(forecastValue([{ ...preOrderPlainDeal, status: 'closed' }])).toBe(0);
    expect(forecastValue([{ ...preOrderPlainDeal, status: 'cancelled' }])).toBe(0);
    expect(forecastValue([{ ...preOrderPlainDeal, lifecycle: 'CANCELLED' }])).toBe(0);
  });

  it('treats a null lifecycle as active and a null amount as zero', () => {
    expect(forecastValue([{ ...preOrderPlainDeal, lifecycle: null }])).toBeCloseTo(30000.25, 2);
    expect(forecastValue([{ ...preOrderPlainDeal, amountPayable: null }])).toBe(0);
  });

  it('sums the fixture and tolerates an empty or missing list', () => {
    expect(forecastValue(moneyFixtureTickets)).toBeCloseTo(80000.25, 2);
    expect(forecastValue([])).toBe(0);
    expect(forecastValue(null)).toBe(0);
  });
});
