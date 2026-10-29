import { api } from '../../api/index.js';

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
