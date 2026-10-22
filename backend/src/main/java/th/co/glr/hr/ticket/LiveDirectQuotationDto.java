package th.co.glr.hr.ticket;

/**
 * Quotation ↔ deal linking slice 2 (S2-B4) — the deal's NEWEST live direct quotation, served on
 * {@link TicketSummaryDto#liveDirectQuotation} so the deal page's CTA cascade and the
 * {@code /quotations/new} deal picker can key on it without a second request.
 *
 * <p>"Live" is the ONE definition shared with {@link DirectQuotationLocks} and N6: a
 * {@code DEAL_DIRECT} row whose {@code doc_status} is {@code DRAFT}, {@code PENDING_APPROVAL} or
 * {@code APPROVED} ({@link TicketRepository#findLiveDirectQuotation}). {@code recipientType} is the
 * raw {@code sales.quotation.recipient_type} — {@code UNSPECIFIED} on every pre-slice-2 row.
 *
 * <p>Disclosure: number / status / recipient only — never an amount. Any ticket reader already
 * reaches the same document through {@code GET /api/tickets/{id}/deal-quotations}.
 */
public record LiveDirectQuotationDto(long id, String number, String docStatus, String recipientType) {
}
