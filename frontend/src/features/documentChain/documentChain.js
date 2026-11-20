import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

// The deal's document chain summary (GET /tickets/{id}/document-chain, DocumentChainService): the ONE source the
// quotation page's chain card, the deal page's เงิน tab and its ขั้นต่อไป card read. Nothing here derives a state.

/**
 * The summary query. `staleTime: 0` + `refetchOnWindowFocus` (the app's default turns focus refetch off): a rep who
 * keeps a page open while ฝ่ายบัญชี confirms the deposit elsewhere sees it on returning to the tab (TanStack's focus
 * manager also fires on visibilitychange), and every mount of a reader refetches it. No polling.
 */
export function documentChainQueryOptions(ticketId) {
  return {
    queryKey: queryKeys.documentChain(ticketId),
    queryFn: () => api.documentChain.get(ticketId).then((r) => r.documentChain),
    staleTime: 0,
    refetchOnWindowFocus: true,
  };
}

const DEAL_CLOSED_TEXT = {
  COMPLETED: 'ปิดงานแล้ว', ON_HOLD: 'พักงานไว้', DORMANT: 'พักงานไว้', CLOSED_LOST: 'เสียงานแล้ว', CANCELLED: 'ยกเลิกแล้ว',
};

/** Why a deal's notices and invoices cannot be edited (the server refuses with 409 unless the lifecycle is ACTIVE,
 * DepositNoticeService#requireActiveLifecycle); null when they can. A cancelled status counts as CANCELLED. */
export function dealDocumentsLockedReason(lifecycle, status) {
  const state = status === 'cancelled' ? 'CANCELLED' : lifecycle;
  if (!state || state === 'ACTIVE') return null;
  return `งานนี้${DEAL_CLOSED_TEXT[state] ?? 'ไม่ได้อยู่ในสถานะดำเนินการ'} แก้ไขเอกสารไม่ได้`;
}

/** `enabled` false skips the read (no deal, or a viewer the page already knows the gate refuses). */
export function useDocumentChain(ticketId, { enabled = true } = {}) {
  return useQuery({
    ...documentChainQueryOptions(ticketId),
    enabled: enabled && ticketId != null,
    retry: false,
  });
}

/**
 * Every mutation in the chain — confirm order, create / save / issue the deposit notice, create / save / issue /
 * revise the remaining invoice, and ฝ่ายบัญชี's confirm-deposit — calls this, so the quotation page and the deal page
 * never show a stale step after an action on either. Returns the invalidation promise.
 *
 *   documentChain(ticketId)            both pages' chain
 *   depositNotices(ticketId)           the deal page's / dialog's notice rows
 *   storedRemainingInvoices(ticketId)  the invoice rows
 *   ticketDetail(ticketId) [prefix]    the deal page (stage, payment status) — every suffixed ticketDetail key too
 *   ticketActions(ticketId)            the deal page's server-gated actions
 *   ['tickets', 'list']                deal lists / boards
 *   ['dealQuotations']                 the quotation list's chain marks, the quotation detail, the deal's quotations
 *   ['depositNotices', 'list' | 'counts'], ['remainingInvoices']   the เอกสารขาย document tabs
 */
export function invalidateDealDocuments(queryClient, ticketId) {
  // depositNotices / storedRemainingInvoices are not Number()-coerced: the deal page keys them by its route-param
  // string, the quotation page by its numeric ticketId, so both spellings are invalidated — each key ONCE (a second
  // invalidation of the same key cancels and re-sends its in-flight refetch).
  const keys = [
    queryKeys.documentChain(ticketId),
    queryKeys.depositNotices(String(ticketId)), queryKeys.depositNotices(Number(ticketId)),
    queryKeys.storedRemainingInvoices(String(ticketId)), queryKeys.storedRemainingInvoices(Number(ticketId)),
    queryKeys.ticketDetail(ticketId),
    queryKeys.ticketActions(ticketId),
    ['tickets', 'list'],
    ['dealQuotations'],
    // เอกสารขาย's cross-deal lists and their counts (queryKeys.depositNoticeList / ...Counts, remainingInvoiceList / ...Counts).
    ['depositNotices', 'list'], ['depositNotices', 'counts'], ['remainingInvoices'],
  ];
  return Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
}
