import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

// One copy of "create this deal's deposit-notice draft", shared by DepositNoticePage's สร้าง button
// and the deal page's main button (owner rulings 2026-10-28).
//
// Prefer the pricing-request-chain endpoint (createDepositNoticeFromQuotation) when this deal has a
// pricing request whose customer quotation was accepted — it sources items from the accepted
// quotation and autofills the header from the customer master + ticket project. No pricing requests at
// all (a direct-quotation deal) takes the ticket-level route; a FAILED lookup rejects instead of guessing
// the route (release review 2026-10-29) — the caller's onError shows it and nothing is created.
// depositPercent is not sent on either branch: the backend picks the new draft's percent
// (DepositNoticeService.createDraftFromQuotation; findGoverningQuotation, #1170).
// `noteTemplates` (already loaded) are optional.
export async function createDepositNoticeDraft(ticketId, noteTemplates) {
  const { items: pricingRequests } = await api.pricingRequests.listForTicket(ticketId);
  const acceptedPricingRequestId = (pricingRequests ?? [])
    .find((pr) => pr.status === 'QUOTATION_ACCEPTED')?.id ?? null;
  if (acceptedPricingRequestId != null) {
    return api.pricingRequests.createDepositNoticeFromQuotation(acceptedPricingRequestId, {});
  }
  // No templates (the deal page): omit `notes` and the backend applies the default notes.
  return api.depositNotices.createDraft(ticketId, noteTemplates
    ? { notes: noteTemplates.filter((t) => t.defaultSelected).map((t) => t.text) }
    : {});
}

/** The ticket's deposit-notice list: one key + fetcher shared by the deal page's main button and its reads. */
export const depositNoticesQueryOptions = (ticketId) => ({
  queryKey: queryKeys.depositNotices(ticketId),
  queryFn: () => api.depositNotices.listByTicket(ticketId).then((r) => r.depositNotices ?? []),
});

/** The ticket's DRAFT deposit notice, else its latest ISSUED one, else null. */
export function pickDepositNotice(docs = []) {
  const draft = docs.find((d) => d.status === 'DRAFT');
  const latestIssued = [...docs]
    .filter((d) => d.status === 'ISSUED')
    .sort((a, b) => (b.version ?? 0) - (a.version ?? 0))[0];
  return draft ?? latestIssued ?? null;
}

/**
 * The deal's quotation list WITH `orderConfirmedQuotationId` — the same key and fetcher DealDocumentRegister reads, so a
 * second reader is served from cache. Advisory reads only: a failed one (e.g. no view access) just yields no quotation.
 */
export const dealQuotationsWithConfirmedQueryOptions = (ticketId) => ({
  queryKey: queryKeys.dealQuotationsByTicketWithConfirmed(Number(ticketId)),
  queryFn: () => api.dealQuotations.listForTicket(Number(ticketId))
    .then((r) => ({ items: r.items ?? [], orderConfirmedQuotationId: r.orderConfirmedQuotationId ?? null })),
  retry: false,
});

/**
 * The quotation a deposit notice stands against: the list item the order was confirmed from; if the response names none,
 * the item whose number is the notice's reference (the reference field also takes a PO number); else null.
 */
export function resolveNoticeQuotation(listResponse, reference) {
  const items = listResponse?.items ?? [];
  const confirmedId = listResponse?.orderConfirmedQuotationId ?? null;
  const confirmed = confirmedId != null ? items.find((q) => q.id === confirmedId) : null;
  if (confirmed) return confirmed;
  return reference ? items.find((q) => q.number === reference) ?? null : null;
}

/** True when both figures are known and differ at 2 dp. */
export function subtotalsDiffer(noticeSubtotal, quotationSubtotal) {
  if (noticeSubtotal == null || quotationSubtotal == null || noticeSubtotal === '') return false;
  return Math.round(Number(noticeSubtotal) * 100) !== Math.round(Number(quotationSubtotal) * 100);
}
