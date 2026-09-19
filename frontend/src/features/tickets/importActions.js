// Single source of truth for "what does Import need to do next on this deal" —
// factored OUT of DealFulfilmentPanel's fulfilment-chain `can.*` status
// matching (role-scoped-views plan, Import build — docs/role-scoped-views.md)
// so ImportOverview's worklist CTA, ImportFulfilmentPage's fulfilment worklist,
// and the panel that actually performs the mutation can never disagree about
// which stage a deal is at.
//
// The third consumer was ProcurementFulfilmentPage until ebaf6888 deleted it;
// this header went on naming it for six days after it stopped existing.
//
// PR-B REVIEW ROUND 2, X1: features/fulfilment/ImportFulfilmentPage.jsx (งานนำเข้า) was
// REDESIGNED for per-factory tracking (V184) and no longer performs any of the four legacy
// deal-level transitions below — it advances a per-factory STORED ใบขอซื้อ row's own step
// instead (api.storedImportRequests.advanceStep), a different action entirely. The previous
// text here claimed it "PERFORMS the transitions rather than linking to them", which stopped
// being true the moment that redesign landed: only `markIrSent` still routes there now (its
// legacy section lists non-tracked IR_ISSUED deals with a link out); `issueImportRequest`,
// `markShipping` and `markGoodsReceived` route to the deal page (`/tickets/:id`), where
// DealFulfilmentPanel still performs them for a deal that has not started per-factory tracking.
//
// Deliberately status-only (no `hasAction`/`availableActions` check):
// DealFulfilmentPanel still gates the real button on
// `hasAction(...) && isFulfilment`, since that reflects the backend's
// per-ticket `availableActions`, which list rows (api.tickets.list) don't
// carry. This module only decides WHICH stage the deal is at, not WHO may
// act on it right now — that permission check stays in the panel.
//
// nextFulfilmentActionCode (below) is now SHARED with Sales too:
// salesActions.js's RECORD_DELIVERY bucket (stages 13-14 ส่งมอบสินค้า, owner
// ruling 2026-08-17) imports and calls it directly rather than keeping a
// second copy of the delivery-ready status list. nextImportAction itself
// stays Import-only and is NOT shared — as of that same ruling it
// deliberately returns null on a delivery-ready deal instead of a
// recordDelivery CTA (see its own doc comment below): Import keeps the
// write CAPABILITY (additive, #818), it just no longer gets PROMPTED by
// this module. See that function's comment for the full reasoning.

export const IMPORT_ACTION_LABELS = {
  pickupPricingRequest: 'รับงาน · ขอราคา',
  issueImportRequest: 'ออกคำขอนำเข้า',
  markIrSent: 'ส่งคำขอนำเข้าแล้ว',
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
 * The ONLY fulfilment-chain code the งานนำเข้า workspace (/fulfilment) still owns —
 * stage 12, DealStage.PROCUREMENT.
 *
 * PR-B REVIEW ROUND 2, X1: used to list all four legacy codes
 * (`issueImportRequest`/`markIrSent`/`markShipping`/`markGoodsReceived`), back when the
 * workspace performed each one as a single deal-level click. The per-factory redesign (V184)
 * replaced that with a row-level step tracker the page drives off `api.storedImportRequests`
 * directly — it has no way to act on the other three any more (there is no "issue"/"ship"/
 * "receive" button on that page for a deal with nothing stored yet, and it doesn't even list a
 * null-fulfillmentStatus deal, which `issueImportRequest` targets). `markIrSent` is the one
 * survivor: its own legacy section still lists a deal that reached IR_ISSUED WITHOUT going
 * through the stored aggregate (no per-factory rows), with a link out rather than an in-place
 * action — see that section's own comment in ImportFulfilmentPage.jsx for why. The other three
 * codes now route to `/tickets/:id`, where DealFulfilmentPanel still performs them.
 *
 * Exported so the workspace's OWN candidate/legacy classification (ImportFulfilmentPage.jsx)
 * can be reasoned about against the same code this module routes CTAs with, even though the
 * page no longer imports this list directly — it derives candidacy from fulfillmentStatus
 * itself (see that file's header). Kept as a single-entry list rather than inlined so the CTA
 * router below reads as "is this the workspace's code", not a magic string.
 */
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
 *   pickupPricingRequest                        -> '/pricing-requests'  (คิวขอราคา — the pickup button)
 *   markIrSent                                  -> '/fulfilment'        (งานนำเข้า — its legacy section)
 *   issueImportRequest/markShipping/
 *     markGoodsReceived                         -> '/tickets/:id'       (deal page — DealFulfilmentPanel
 *                                                                         performs these three; PR-B
 *                                                                         REVIEW ROUND 2, X1)
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
    return { code: 'pickupPricingRequest', label: IMPORT_ACTION_LABELS.pickupPricingRequest, to: '/pricing-requests' };
  }
  const code = nextFulfilmentActionCode(ticket);
  // recordDelivery is excluded here on purpose (see this function's own doc comment above) even
  // though nextFulfilmentActionCode just returned it: delivery is Sales's worklist item now, not
  // Import's. FULFILMENT_WORKSPACE_CODES already excludes it from the /fulfilment workspace for the
  // same owner ruling — this is the second, worklist-CTA half of that same exclusion.
  if (!code || code === 'recordDelivery') return null;
  const to = FULFILMENT_WORKSPACE_CODES.includes(code) ? '/fulfilment' : `/tickets/${ticket.id}`;
  // PR-B REVIEW ROUND 1, S8: 'markIrSent' (fulfillmentStatus IR_ISSUED) is the code every
  // IR-TRACKED deal sits at for its whole per-factory tracking period (V184/PR-B) — this label used
  // to say "ส่งคำขอนำเข้าแล้ว" ("mark IR sent"), a single legacy ACTION that /fulfilment no longer
  // performs in one click; the real control there is now a per-factory 6-step tracker
  // (FactoryProgressBar). This resolver has no query of its own to tell an IR-tracked deal from a
  // legacy one still on the old 4-step chain, so the neutral label is correct either way — it never
  // claims a single-click action the destination page might not offer. Both ImportOverview's
  // dashboard tile and TicketDetailPage's sticky bar read this same label via workState.js's
  // nextImportAction call, so relabeling here fixes both surfaces at once.
  const label = code === 'markIrSent' ? 'อัปเดตสถานะนำเข้า' : IMPORT_ACTION_LABELS[code];
  return { code, label, to };
}
