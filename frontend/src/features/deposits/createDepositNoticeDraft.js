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
