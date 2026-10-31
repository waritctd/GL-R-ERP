// Shared answers to "has this deal's deposit been requested/paid" (DealPaymentSection and others).
// A bypass policy (CREDIT_CUSTOMER/WAIVED/NOT_REQUIRED) outranks the paymentStatus reads: TicketService#recordPayment
// skips DEPOSIT_PAID entirely for those deals, so a paid-looking status there never means a deposit was paid.
// The same triple is still independently written out in accountActions.js and mockApi.js (pre-existing).
export const DEPOSIT_PAID_STATUSES = ['DEPOSIT_PAID', 'AWAITING_FINAL_PAYMENT', 'FULLY_PAID'];

export function depositBypassed(policy) {
  return ['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'].includes(policy);
}

/**
 * May an import request (ใบขอซื้อ) be issued yet? Mirrors TicketService#requireImportRequestIssuable
 * (summary, allowAdvancedPayment = true) exactly: notice issued, deposit paid or later, or a
 * not-required deposit policy that has reached CUSTOMER_CONFIRMED.
 */
export function depositReadyForIr(summary) {
  const { paymentStatus, depositPolicy } = summary ?? {};
  return paymentStatus === 'DEPOSIT_NOTICE_ISSUED'
    || DEPOSIT_PAID_STATUSES.includes(paymentStatus)
    || (depositBypassed(depositPolicy) && paymentStatus === 'CUSTOMER_CONFIRMED');
}

/**
 * Is the deposit in hand? Paid (or later), or a no-deposit policy that has reached CUSTOMER_CONFIRMED - a deposit
 * NOTICE alone is not money. Mirrors TicketService#depositSettled; stock goods are delivered only after this.
 */
export function depositSettled(summary) {
  const { paymentStatus, depositPolicy } = summary ?? {};
  return DEPOSIT_PAID_STATUSES.includes(paymentStatus)
    || (depositBypassed(depositPolicy) && paymentStatus === 'CUSTOMER_CONFIRMED');
}

/** `{ bypassed, paid, noticeIssued }` — the three deposit-status facts both callers key off. */
export function depositStatus(summary) {
  const bypassed = depositBypassed(summary.depositPolicy);
  const paid = DEPOSIT_PAID_STATUSES.includes(summary.paymentStatus);
  const noticeIssued = summary.paymentStatus === 'DEPOSIT_NOTICE_ISSUED';
  return { bypassed, paid, noticeIssued };
}
