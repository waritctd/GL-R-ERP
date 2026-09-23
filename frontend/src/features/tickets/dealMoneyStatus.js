// GLA-129: shared between DealMoneyStatusStrip and DealDocumentPipeline, which both need to answer
// "has this deal's deposit been requested/paid" — mirrors DealDepositPanel's own
// bypassesNotice/alreadyPaid predicates exactly (DealDepositPanel.jsx's `skipsNotice` check
// outranks its own paymentStatus reads, same priority order here). Extracted after review round 1
// (2026-09-23) found the strip and the pipeline had each written this independently and diverged:
// the strip checked paymentStatus alone, so a CREDIT_CUSTOMER/WAIVED/NOT_REQUIRED deal that later
// reached AWAITING_FINAL_PAYMENT (TicketService#recordPayment skips DEPOSIT_PAID entirely for a
// bypass-policy deal) showed "มัดจำ: ชำระแล้ว" — a false statement, since no deposit was ever
// requested or paid on that deal. One implementation now, so THESE TWO surfaces cannot disagree —
// the same NOT_REQUIRED/WAIVED/CREDIT_CUSTOMER triple is still independently written out in
// DealDepositPanel.jsx, DealMoneyTimeline.jsx, accountActions.js and mockApi.js (pre-existing,
// out of scope here); consolidating those is a separate cleanup, not claimed by this change.
export const DEPOSIT_PAID_STATUSES = ['DEPOSIT_PAID', 'AWAITING_FINAL_PAYMENT', 'FULLY_PAID'];

export function depositBypassed(policy) {
  return ['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'].includes(policy);
}

/** `{ bypassed, paid, noticeIssued }` — the three deposit-status facts both callers key off. */
export function depositStatus(summary) {
  const bypassed = depositBypassed(summary.depositPolicy);
  const paid = DEPOSIT_PAID_STATUSES.includes(summary.paymentStatus);
  const noticeIssued = summary.paymentStatus === 'DEPOSIT_NOTICE_ISSUED';
  return { bypassed, paid, noticeIssued };
}
