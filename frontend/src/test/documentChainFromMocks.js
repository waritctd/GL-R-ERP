// TEST HELPER. A page test that mocks api.tickets.get / api.depositNotices.listByTicket /
// api.storedRemainingInvoices.listForTicket can answer api.documentChain.get with the summary the server would
// compute from those same fixtures — through the mock's mirror of DocumentChainRules (pinned to the Java rules by
// documentChainCases.test.js). It reads the mocks' IMPLEMENTATIONS, never calls them, so no test's call counts move.
import {
  chainDepositBlockedReason, chainDepositState, chainRemainingBlockedReason, chainRemainingState,
} from '../api/mockDocumentChainRules.js';

async function implValue(fn, ...args) {
  const impl = fn?.getMockImplementation?.();
  if (!impl) return null;
  try {
    return await impl(...args);
  } catch {
    return null;
  }
}

/** `user` decides the viewer flags the way the Java gates do (owner/keyer sales may write; sales_manager may confirm). */
export async function documentChainFromMocks(api, ticketId, user) {
  const ticketRes = await implValue(api.tickets?.get, ticketId);
  const summary = ticketRes?.ticket?.summary ?? {};
  const notices = (await implValue(api.depositNotices?.listByTicket, ticketId))?.depositNotices ?? [];
  const invoices = (await implValue(api.storedRemainingInvoices?.listForTicket, ticketId))?.remainingInvoices ?? [];
  const f = {
    status: summary.status, lifecycle: summary.lifecycle ?? 'ACTIVE', depositPolicy: summary.depositPolicy ?? 'REQUIRED',
    paymentStatus: summary.paymentStatus ?? null, fulfillmentStatus: summary.fulfillmentStatus ?? null,
    noticeDraft: notices.some((d) => d.status === 'DRAFT'), noticeIssued: notices.some((d) => d.status === 'ISSUED'),
    invoiceDraft: invoices.some((r) => r.status === 'DRAFT'), invoiceIssued: invoices.some((r) => r.status === 'ISSUED'),
  };
  const owns = user?.role === 'sales'
    && (Number(summary.createdById) === Number(user.id) || Number(summary.keyedById) === Number(user.id));
  const remainingState = chainRemainingState(f);
  const remainingReason = chainRemainingBlockedReason(f);
  const issued = notices.filter((d) => d.status === 'ISSUED').sort((a, b) => (b.version ?? 0) - (a.version ?? 0))[0] ?? null;
  return {
    documentChain: {
      ticketId: Number(ticketId), dealCode: summary.code ?? null, dealStatus: f.status, lifecycle: f.lifecycle,
      order: { confirmed: f.paymentStatus != null, confirmable: f.status === 'draft' && f.lifecycle === 'ACTIVE', confirmedFromQuotationId: null, confirmedFromQuotationNumber: null, confirmedAt: null },
      deposit: {
        policy: f.depositPolicy, required: !['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'].includes(f.depositPolicy), percent: null,
        state: chainDepositState(f), blockedReason: chainDepositBlockedReason(f),
        noticeId: (notices.find((d) => d.status === 'DRAFT') ?? issued)?.id ?? null, issuedNoticeId: issued?.id ?? null,
        docNumber: issued?.docNumber ?? null, issueDate: issued?.issueDate ?? null, draftExists: f.noticeDraft,
        received: chainDepositState(f) === 'PAID',
      },
      remaining: { state: remainingState, blockedReason: remainingReason, canCreate: remainingState === 'TO_CREATE' && remainingReason == null, invoiceId: null, docNumber: null, docDate: null, draftExists: f.invoiceDraft },
      viewer: { mayConfirmOrder: owns || user?.role === 'sales_manager', mayWriteDepositNotice: owns, mayWriteRemainingInvoice: owns },
    },
  };
}
