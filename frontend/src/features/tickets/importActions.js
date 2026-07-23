// Single source of truth for "what does Import need to do next on this deal" —
// factored OUT of DealFulfilmentPanel's fulfilment-chain `can.*` status
// matching (role-scoped-views plan, Import build — docs/role-scoped-views.md)
// so ImportOverview's worklist CTA, ProcurementFulfilmentPage's fulfilment
// worklist, and the panel that actually performs the mutation can never
// disagree about which stage a deal is at.
//
// Deliberately status-only (no `hasAction`/`availableActions` check):
// DealFulfilmentPanel still gates the real button on
// `hasAction(...) && isFulfilment`, since that reflects the backend's
// per-ticket `availableActions`, which list rows (api.tickets.list) don't
// carry. This module only decides WHICH stage the deal is at, not WHO may
// act on it right now — that permission check stays in the panel.

export const IMPORT_ACTION_LABELS = {
  pickupPricingRequest: 'รับงาน · ขอราคา',
  issueImportRequest: 'ออก IR',
  markIrSent: 'ส่ง IR',
  markShipping: 'บันทึกออกเดินทาง',
  markGoodsReceived: 'ยืนยันรับเข้าคลัง',
  recordDelivery: 'บันทึกส่งมอบ',
};

// Fulfillment statuses for which the deal is delivery-ready, sitting on the
// same list DealFulfilmentPanel's `can.recordDelivery`/`openDeliveryModal`
// paths cover (GOODS_RECEIVED via import, FROM_STOCK via a stock reservation,
// PARTIALLY_DELIVERED mid-delivery).
const DELIVERY_READY_FULFILMENT_STATUSES = ['GOODS_RECEIVED', 'FROM_STOCK', 'PARTIALLY_DELIVERED'];

/**
 * The fulfilment-chain-only decision — mirrors DealFulfilmentPanel's
 * `issueImportRequest` / `markIrSent` / `markShipping` / `markGoodsReceived`
 * status conditions byte-for-byte (see DealFulfilmentPanel.jsx `can`).
 * `ticket` needs `status` (ticket.status) and `fulfillmentStatus`.
 *
 * Returns one of 'issueImportRequest' | 'markIrSent' | 'markShipping' |
 * 'markGoodsReceived' | 'recordDelivery', or `null` when the deal has not
 * reached the fulfilment chain yet (still pricing) or is past it (fully
 * delivered / closed).
 */
export function nextFulfilmentActionCode(ticket) {
  const st = ticket?.status;
  const fs = ticket?.fulfillmentStatus ?? null;
  if (st === 'quotation_issued' && fs == null) return 'issueImportRequest';
  if (st === 'quotation_issued' && fs === 'IR_ISSUED') return 'markIrSent';
  if (st === 'quotation_issued' && fs === 'IR_SENT') return 'markShipping';
  if (st === 'quotation_issued' && fs === 'SHIPPING') return 'markGoodsReceived';
  if (DELIVERY_READY_FULFILMENT_STATUSES.includes(fs)) return 'recordDelivery';
  return null;
}

/**
 * The full "what does Import own next" decision for a deal, including the
 * PricingRequest pickup step upstream of fulfilment. DealFulfilmentPanel has
 * no opinion on pickup (it only owns the fulfilment chain), but Import's
 * Overview worklist needs the whole picture in one place.
 *
 * `pricingRequests` is the set of PricingRequest queue summaries for THIS
 * ticket only (may be empty/undefined) — a deal with an unpicked SUBMITTED
 * request always takes priority over a fulfilment-chain action, since
 * fulfilment cannot proceed until pricing is resolved.
 *
 * Returns `{ code, label, to }` or `null` when there is nothing for Import to
 * do on this deal right now. `to` is the CTA's navigation target —
 * '/pricing-requests' (the queue page, where the actual pickup button lives)
 * for a pickup, otherwise `/tickets/:id`.
 */
export function nextImportAction(ticket, pricingRequests = []) {
  const hasUnpickedRequest = pricingRequests.some((pr) => pr.status === 'SUBMITTED');
  if (hasUnpickedRequest) {
    return { code: 'pickupPricingRequest', label: IMPORT_ACTION_LABELS.pickupPricingRequest, to: '/pricing-requests' };
  }
  const code = nextFulfilmentActionCode(ticket);
  if (!code) return null;
  return { code, label: IMPORT_ACTION_LABELS[code], to: `/tickets/${ticket.id}` };
}
