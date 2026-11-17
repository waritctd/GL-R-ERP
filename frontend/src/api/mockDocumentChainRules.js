// Mirrors th.co.glr.hr.documentchain.DocumentChainRules (Java) — the document chain's states as pure functions of a
// deal's facts. MOCK ONLY: the app never derives these in the browser; it reads them off GET
// /tickets/{id}/document-chain. This copy exists so mockApi.documentChain.get can answer, and it is pinned to the
// SAME state table as the Java rules (features/documentChain/documentChainCases.json, read by DocumentChainRulesTest
// and documentChainCases.test.js). Mock-driven tests of the chain are therefore evidence about the plumbing, never
// about these rules (CLAUDE.md "mock mirrors a backend computation") — DocumentChainRulesTest is.

const BYPASS_POLICIES = ['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER']; // DepositPolicy.bypassesDepositNotice
const DEPOSIT_PAID_STATUSES = ['DEPOSIT_PAID', 'AWAITING_FINAL_PAYMENT', 'FULLY_PAID'];
const READY_FULFILMENT = ['GOODS_RECEIVED', 'FROM_STOCK', 'PARTIALLY_DELIVERED', 'FULLY_DELIVERED'];

// DocumentChainRules#dead / #active: a lost / cancelled lifecycle or the legacy cancelled status is dead; active means
// an ACTIVE lifecycle on a deal that is not dead.
const dead = (f) => ['CLOSED_LOST', 'CANCELLED'].includes(f.lifecycle) || f.status === 'cancelled';
const active = (f) => f.lifecycle === 'ACTIVE' && !dead(f);

export function chainOrderConfirmed(f) {
  return f.paymentStatus != null;
}


export function chainDepositState(f) {
  if (BYPASS_POLICIES.includes(f.depositPolicy)) return 'NOT_REQUIRED';
  if (DEPOSIT_PAID_STATUSES.includes(f.paymentStatus)) return 'PAID';
  if (f.noticeIssued || f.paymentStatus === 'DEPOSIT_NOTICE_ISSUED') return 'ISSUED';
  if (f.noticeDraft) return 'DRAFT';
  if (chainOrderConfirmed(f)) return 'TO_CREATE';
  return 'NOT_YET';
}

export function chainDepositBlockedReason(f) {
  const state = chainDepositState(f);
  return (state === 'TO_CREATE' || state === 'DRAFT') && !active(f) ? 'DEAL_NOT_ACTIVE' : null;
}

export function chainRemainingState(f) {
  const fullyPaid = f.paymentStatus === 'FULLY_PAID';
  if (f.invoiceIssued) return fullyPaid ? 'PAID' : 'ISSUED';
  if (f.invoiceDraft) return 'DRAFT';
  if (fullyPaid) return 'NOT_NEEDED';
  const deposit = chainDepositState(f);
  return deposit === 'NOT_REQUIRED' || deposit === 'PAID' ? 'TO_CREATE' : 'WAITING';
}

export function chainRemainingBlockedReason(f) {
  const state = chainRemainingState(f);
  const quotationIssued = f.status === 'quotation_issued';
  if (state === 'WAITING') {
    if (dead(f)) return 'DEAL_NOT_ACTIVE';
    return quotationIssued ? 'DEPOSIT_NOT_RECEIVED' : 'ORDER_NOT_CONFIRMED';
  }
  if (state === 'DRAFT') return active(f) ? null : 'DEAL_NOT_ACTIVE';
  if (state !== 'TO_CREATE') return null;
  if (!active(f)) return 'DEAL_NOT_ACTIVE';
  if (!quotationIssued) return 'ORDER_NOT_CONFIRMED';
  return READY_FULFILMENT.includes(f.fulfillmentStatus) ? null : 'GOODS_NOT_READY';
}

export function chainListStage(f, promoted, confirmedQuotationId, quotationId) {
  if (dead(f)) return null;
  if (!promoted) return f.status === 'draft' && active(f) ? 'AWAITING_ORDER' : null;
  if (confirmedQuotationId == null || Number(confirmedQuotationId) !== Number(quotationId)) return null;
  const remaining = chainRemainingState(f);
  if (remaining === 'ISSUED' || remaining === 'PAID') return 'REMAINING_INVOICE_ISSUED';
  const deposit = chainDepositState(f);
  if (deposit === 'ISSUED' || deposit === 'PAID') return 'DEPOSIT_NOTICE_ISSUED';
  if (deposit === 'NOT_REQUIRED') return 'NO_DEPOSIT';
  return 'AWAITING_DEPOSIT_NOTICE';
}
