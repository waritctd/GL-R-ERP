// The money figures the account, CEO and sales-manager dashboards share, so each reads the same
// number /finance does. Presentation only: the server stays the authority on every figure.
import { nextAccountAction } from '../tickets/accountActions.js';
import { moneyMilestoneOf } from '../finance/moneyMilestone.js';

const ACCOUNT_PENDING_PAYMENT_STATUSES = new Set(['DEPOSIT_NOTICE_ISSUED', 'AWAITING_FINAL_PAYMENT']);

const amount = (value) => Number(value ?? 0);

// Mirrors TicketRepository.appendRoleScope("account"): the deals account's list can contain.
function inAccountScope(ticket) {
  const live = ticket.lifecycle !== 'CLOSED_LOST' && ticket.lifecycle !== 'CANCELLED'
    && ticket.status !== 'cancelled' && moneyMilestoneOf(ticket.salesStage) != null;
  return live || ACCOUNT_PENDING_PAYMENT_STATUSES.has(ticket.paymentStatus) || ticket.overdue === true;
}

/** /finance's คงค้างรวม: outstanding on deals that are in account's scope and have an account to-do. */
export function financeOutstanding(tickets) {
  return (tickets ?? [])
    .filter((t) => inAccountScope(t) && nextAccountAction(t) != null)
    .reduce((sum, t) => sum + amount(t.amountOutstanding), 0);
}

/** ยอดคาดการณ์: payable on live deals that have not reached ขั้น 10 yet. */
export function forecastValue(tickets) {
  return (tickets ?? [])
    .filter((t) => moneyMilestoneOf(t.salesStage) == null
      && (t.lifecycle ?? 'ACTIVE') === 'ACTIVE'
      && t.status !== 'closed' && t.status !== 'cancelled')
    .reduce((sum, t) => sum + amount(t.amountPayable), 0);
}
