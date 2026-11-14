// When ใบแจ้งหนี้ส่วนที่เหลือ (remaining invoice) becomes available — shared by TicketDetailPage's
// `can.downloadRemainingInvoice` and DealDocumentRegister's ready/รอขั้นตอน row, which used to carry two
// inline copies of the same `fulfillmentStatus === 'GOODS_RECEIVED'` check.
export const REMAINING_INVOICE_READY_FULFILMENT_STATUSES = Object.freeze([
  'GOODS_RECEIVED',
  'FROM_STOCK',
  'PARTIALLY_DELIVERED',
  'FULLY_DELIVERED',
]);

/** `summary` needs `status` and `fulfillmentStatus`. Null-safe. */
export function isRemainingInvoiceReady(summary) {
  return summary?.status === 'quotation_issued'
    && REMAINING_INVOICE_READY_FULFILMENT_STATUSES.includes(summary?.fulfillmentStatus);
}

/** The ticket's ISSUED remaining invoice, else its DRAFT, else null. */
export function pickRemainingInvoice(rows = []) {
  return rows.find((r) => r.status === 'ISSUED') ?? rows.find((r) => r.status === 'DRAFT') ?? null;
}
