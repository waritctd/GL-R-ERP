// Single source of truth for "what does Import need to do next on this deal". This module only decides WHICH
// stage the deal is at, not WHO may act on it right now — that permission check stays in the panel.

import { depositSettled } from './dealMoneyStatus.js';

export const IMPORT_ACTION_LABELS = {
  pickupPricingRequest: 'รับงาน · ขอราคา',
  markIrSent: 'ส่งคำขอนำเข้าแล้ว',
  markShipping: 'บันทึกออกเดินทาง',
  markGoodsReceived: 'ยืนยันรับเข้าคลัง',
  recordDelivery: 'บันทึกส่งมอบ',
};

// Fulfillment statuses for which the deal is delivery-ready, sitting on the same list DealFulfilmentPanel's
// `can.recordDelivery`/`openDeliveryModal` paths cover (GOODS_RECEIVED via import, FROM_STOCK via a stock
// reservation, PARTIALLY_DELIVERED mid-delivery).
const DELIVERY_READY_FULFILMENT_STATUSES = ['GOODS_RECEIVED', 'FROM_STOCK', 'PARTIALLY_DELIVERED'];

/**
 * The fulfilment-chain-only decision — mirrors DealFulfilmentPanel's
 * `issueImportRequest` / `markIrSent` / `markShipping` / `markGoodsReceived`
 * status conditions byte-for-byte (see DealFulfilmentPanel.jsx `can`).
 * `ticket` needs `status` (ticket.status) and `fulfillmentStatus`.
 *
 * Returns one of 'markIrSent' | 'markShipping' |
 * 'markGoodsReceived' | 'recordDelivery', or `null` when the deal has not
 * reached the fulfilment chain yet (still pricing) or is past it (fully
 * delivered / closed).
 */
export function nextFulfilmentActionCode(ticket) {
  const fs = ticket?.fulfillmentStatus ?? null;
  // No deal-level "issue the IR" step (W3-01/W3-04, owner ruling 2026-10-29): a deal with no
  // fulfilment status has no import to-do — sales raises the per-factory IR, and Import's lists start
  // at an ISSUED IR, exactly like /fulfilment.
  // The three in-flight steps key on fulfillmentStatus ALONE, as the backend does (TicketService
  // markIrSent/markShipping/markGoodsReceived and their availableActions never read ticket.status).
  // Once the IR is out the deal must stay advanceable: the redesigned pricing chain does not always
  // reach quotation_issued, and requiring it hid every button on such a deal.
  if (fs === 'IR_ISSUED') return 'markIrSent';
  if (fs === 'IR_SENT') return 'markShipping';
  if (fs === 'SHIPPING') return 'markGoodsReceived';
  // Stock goods leave only once the deposit is settled (the backend refuses a STOCK delivery before it).
  if (fs === 'FROM_STOCK' && !depositSettled(ticket)) return null;
  if (DELIVERY_READY_FULFILMENT_STATUSES.includes(fs)) return 'recordDelivery';
  return null;
}

/** The ONLY fulfilment-chain code the งานนำเข้า workspace (/fulfilment) still owns — stage 12,
 * DealStage.PROCUREMENT. The other three codes now route to `/import/deals/:id` (import's own per-deal page —
 * import can no longer open `/tickets/:id`, where DealFulfilmentPanel performs them). */
export const FULFILMENT_WORKSPACE_CODES = ['markIrSent'];

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
 * do on this deal right now.
 *
 * `to` is the CTA's navigation target, and it always points at THE PAGE THAT CAN
 * PERFORM THE ACTION — never at a page that merely displays it:
 *
 *   pickupPricingRequest                        -> '/pricing-requests/:id' (the oldest SUBMITTED request's own
 *                                                                         detail page, which has its รับเรื่อง button;
 *                                                                         the queue's default import tab cannot list a
 *                                                                         SUBMITTED row, so it would dead-end)
 *   markIrSent                                  -> '/fulfilment'        (งานนำเข้า — its legacy section)
 *   issueImportRequest/markShipping/
 *     markGoodsReceived                         -> '/import/deals/:id'  (import's own per-deal page, which
 *                                                                         PERFORMS the three legacy
 *                                                                         deal-level buttons for a deal with
 *                                                                         no per-factory rows —
 *                                                                         DealFulfilmentPanel, where they
 *                                                                         also live, is closed to import)
 *
 * `recordDelivery` is deliberately ABSENT from that table, even though
 * nextFulfilmentActionCode (above) still returns it for a delivery-ready
 * deal: stages 13-14 (ส่งมอบสินค้า) are Sales's now (owner ruling
 * 2026-08-17), so this resolver returns `null` on a delivery-ready deal
 * instead of routing Import anywhere for it — no more prompt, not on this
 * dashboard and not on the deal page's own sticky bar (both read this
 * function; see workState.js). Import KEEPS the write capability (additive,
 * #818): DealFulfilmentPanel's own `hasAction` gate is untouched and still
 * shows the real button to import/CEO on the deal page. salesActions.js's
 * nextSalesAction is what now calls nextFulfilmentActionCode directly to
 * surface delivery as ITS OWN worklist CTA instead.
 */
export function nextImportAction(ticket, pricingRequests = []) {
  const hasUnpickedRequest = pricingRequests.some((pr) => pr.status === 'SUBMITTED');
  if (hasUnpickedRequest) {
    // Oldest (lowest id) SUBMITTED request, whatever order the list arrives in.
    const oldest = pricingRequests
      .filter((pr) => pr.status === 'SUBMITTED')
      .reduce((a, b) => (b.id < a.id ? b : a));
    return { code: 'pickupPricingRequest', label: IMPORT_ACTION_LABELS.pickupPricingRequest, to: `/pricing-requests/${oldest.id}` };
  }
  const code = nextFulfilmentActionCode(ticket);
  // recordDelivery is excluded here on purpose (see this function's own doc comment above) even though
  // nextFulfilmentActionCode just returned it: delivery is Sales's worklist item now, not Import's.
  if (!code || code === 'recordDelivery') return null;
  // Import cannot open the whole-deal page (/tickets/:id — GET /api/tickets/{id} 403s it), so the non-workspace
  // codes land on import's OWN per-deal page instead.
  const to = FULFILMENT_WORKSPACE_CODES.includes(code) ? '/fulfilment' : `/import/deals/${ticket.id}`;
  // PR-B REVIEW ROUND 1, S8: 'markIrSent' is the code every IR-TRACKED deal sits at for its whole per-factory
  // tracking period — this label used to say "ส่งคำขอนำเข้าแล้ว", a single legacy ACTION that /fulfilment no
  // longer performs in one click; the real control there is now a per-factory 6-step tracker.
  const label = code === 'markIrSent' ? 'อัปเดตสถานะนำเข้า' : IMPORT_ACTION_LABELS[code];
  return { code, label, to };
}
