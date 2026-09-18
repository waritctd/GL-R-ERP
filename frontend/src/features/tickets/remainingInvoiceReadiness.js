// When ใบแจ้งหนี้ส่วนที่เหลือ (remaining invoice) becomes available — shared by TicketDetailPage's
// `can.downloadRemainingInvoice` and DealDocumentRegister's ready/รอขั้นตอน row, which used to
// carry two inline copies of the same `fulfillmentStatus === 'GOODS_RECEIVED'` check.
//
// That check made the document unreachable for every stock-sourced deal: a deal declared from
// stock goes FROM_STOCK → PARTIALLY_DELIVERED → FULLY_DELIVERED and never writes GOODS_RECEIVED,
// which only markGoodsReceived sets on the import axis (FulfilmentStatus.java). It also withdrew
// the document from an import deal the moment its first delivery was recorded, since that
// overwrites GOODS_RECEIVED with a delivery-axis value.
//
// The rule is "the goods are in hand and the balance can be billed", on either path:
//   - GOODS_RECEIVED — import goods have reached GLR's warehouse;
//   - FROM_STOCK     — the full order is covered by stock GLR already holds (the stock branch's
//                      equivalent of GOODS_RECEIVED; TicketService treats it as delivery-ready);
//   - PARTIALLY_DELIVERED / FULLY_DELIVERED — the shared tail both journeys end on.
// Not ready: no fulfilment yet, or still moving on the import axis (IR_ISSUED, IR_SENT, SHIPPING).
// FROM_STOCK is only ever written by TicketService#reserveStock when EVERY line is fully covered by
// declared stock, so a partially-covered deal stays null (not ready) and walks the import axis.
//
// ⚠️ Known imprecision — mixed stock+import deals. canRecordDelivery lets the stock portion be
// delivered while the import portion is still at IR_SENT/SHIPPING, and recordDeliveryInternal then
// writes PARTIALLY_DELIVERED unconditionally, overwriting the import-axis value. From that point
// fulfillmentStatus alone cannot say the imported goods are still in transit, so this reports
// ready early for that deal. Accepted: the old GOODS_RECEIVED-only rule had the opposite failure
// (withdrawn at the first delivery, never offered to stock deals), and the backend has no fulfilment
// gate either way. Closing it needs a server-side "all goods in hand" signal, not a longer list here.
//
// `ticket.status` stays `quotation_issued` through delivery (only closing moves it), so the status
// half of the gate is unchanged. The backend (DepositNoticeService#getRemainingInvoiceXlsx) checks
// only that status and has no fulfilment gate, so this widening introduces no frontend/backend
// disagreement — it is UI readiness, not an authorization boundary.
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
