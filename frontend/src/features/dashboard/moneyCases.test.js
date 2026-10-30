import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { nextAccountAction } from '../tickets/accountActions.js';
import { moneyMilestoneOf } from '../finance/moneyMilestone.js';

// Anti-drift: the same deal shapes drive DashboardMoneyTotalsIntegrationTest (the SQL behind
// /api/dashboard/summary). The JS rule here is what /finance's rows use -- account's list scope
// (TicketRepository.appendRoleScope("account")) plus nextAccountAction -- so if the two sides ever
// disagree about a shape, one of the two tests goes red.
// vitest runs from frontend/ (see the repo's vitest note), so the backend resource is one level up.
const table = JSON.parse(readFileSync(
  resolve(process.cwd(), '../backend/src/test/resources/dashboard/money-cases.json'), 'utf8'));

const PENDING = new Set(['DEPOSIT_NOTICE_ISSUED', 'AWAITING_FINAL_PAYMENT']);

function ticketOf(c) {
  const outstanding = Math.max(table.payable - c.paid, 0);
  return {
    id: 1, lifecycle: c.lifecycle, status: c.status, salesStage: c.salesStage, paymentStatus: c.paymentStatus,
    depositPolicy: c.depositPolicy, overdue: c.overdue, commissionRecorded: c.commission,
    closeConfirmedAt: null, invoiceOnFile: false, fulfillmentStatus: null,
    amountPayable: table.payable, amountPaid: c.paid, amountOutstanding: outstanding,
  };
}

function inAccountScope(t) {
  const live = t.lifecycle !== 'CLOSED_LOST' && t.lifecycle !== 'CANCELLED' && t.status !== 'cancelled'
    && moneyMilestoneOf(t.salesStage) != null;
  return live || PENDING.has(t.paymentStatus) || t.overdue === true;
}
const countsInOutstanding = (t) => inAccountScope(t) && nextAccountAction(t) != null;

describe('dashboard money case table (JS side)', () => {
  it('has the cases the SQL test builds', () => {
    expect(table.cases.length).toBeGreaterThanOrEqual(23);
  });

  it.each(table.cases.map((c) => [c.name, c]))('%s', (_name, c) => {
    const t = ticketOf(c);
    // Outstanding only: the JS side of this rule is production code (nextAccountAction). The forecast rule has
    // no production JS twin any more (the server sums it), so DashboardMoneyTotalsIntegrationTest alone pins it.
    expect(countsInOutstanding(t), 'countsInOutstanding').toBe(c.countsInOutstanding);
  });
});
