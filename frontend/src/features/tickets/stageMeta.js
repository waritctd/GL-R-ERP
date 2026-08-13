// Thai display copy for the deal pipeline — and nothing else.
//
// WHAT LIVES HERE: labels. Wording is presentation, and moving it to the backend would couple a
// copy edit to a backend deploy, so it stays.
//
// WHAT NO LONGER LIVES HERE, and must never come back:
//   * the stage list, their numbers, phases and write gates — GET /api/meta/deal-stages, read via
//     stageCatalog.js. The literal that used to be here was a copy of DealStage.ORDER plus a copy
//     of TicketService's three target-stage sets, and it had silently gone stale.
//   * canSetStage / allowedTargetStages / canMarkLost / isDealOwner — those were a second
//     implementation of TicketService.requireStageWriteAccess and requireDealOwnership. The
//     backend now ships a verdict per stage on GET /api/tickets/{id}/actions (`stageDecisions`),
//     and MARK_LOST is already an entry in `availableActions`. A UI that re-derives an
//     authorization rule is a UI that can disagree with the server, and this one did.
//   * isRoutineBackwardMove and the `distance > 1` skip test — one decision, DealStage
//     .requiresJustification, reported per stage as `requiresReason`. The copies here demanded a
//     written reason for three of the business's four normal routes, which the backend had already
//     stopped requiring.
//
// The rule for anything added below: if the backend also decides it, it does not belong here.

import { hasDealStageLabel } from '../../utils/format.js';

export const GATE_LABEL = {
  sales: 'ฝ่ายขาย',
  import: 'ฝ่ายนำเข้า',
  account: 'ฝ่ายบัญชี',
};

/**
 * Thai wording for the auto-advanced stages — "where does this stage come from, if I cannot just
 * click into it?". Keyed by code; the *set* of auto stages is the catalog's `auto` flag, served by
 * the backend, so this map only supplies words for the ones that have it.
 */
export const AUTO_STAGE_HINT = {
  ORDER_RECEIVED:   'อัปเดตอัตโนมัติเมื่อฝ่ายขายยืนยันใบสั่งซื้อของดีลนี้',
  DEPOSIT_RECEIVED: 'อัปเดตอัตโนมัติเมื่อฝ่ายบัญชียืนยันรับมัดจำของดีลนี้',
  PROCUREMENT:      'อัปเดตอัตโนมัติเมื่อฝ่ายนำเข้าออกใบขอซื้อ (IR) ของดีลนี้',
  CLOSED_PAID:      'อัปเดตอัตโนมัติเมื่อฝ่ายบัญชียืนยันรับเงินครบของดีลนี้',
};

export const LOST_REASON_LABEL = {
  PRODUCT_FIT:       'ไม่มีสินค้าที่ลูกค้าต้องการ',
  PRICE:             'ราคา',
  LEAD_TIME:         'ระยะเวลาส่งมอบ (leadtime)',
  PAYMENT_TERMS:     'เงื่อนไขการชำระเงิน (payment term)',
  RELATIONSHIP:      'ความสัมพันธ์ / connection',
  PROJECT_ON_HOLD:   'โครงการหยุดก่อสร้างชั่วคราว',
  PROJECT_CANCELLED: 'โครงการหยุดก่อสร้างถาวร',
  ALREADY_PURCHASED: 'ลูกค้าสั่งซื้อกระเบื้องไปหมดแล้ว',
};

/**
 * Why the opportunity went away (V56) — distinct from LOST_REASON_LABEL, which is why we lost a
 * deal we were competing for. The code set comes from the catalog (DealCancelReason.ORDER).
 */
export const CANCEL_REASON_LABEL = {
  OWNER_CANCELLED:   'เจ้าของยกเลิกโครงการ',
  PROJECT_SUSPENDED: 'โครงการถูกระงับไม่มีกำหนด',
  BUDGET_CANCELLED:  'งบประมาณถูกยกเลิก',
  OTHER:             'อื่น ๆ (ระบุในหมายเหตุ)',
};

// Fulfilment sub-steps rendered inside the PROCUREMENT stage — these come from the deal's own
// fulfillment_status, never separate stages.
//
// Deliberately NOT served by /api/meta/deal-stages: FulfilmentStatus.java publishes an import
// SEQUENCE (IR_ISSUED → IR_SENT → SHIPPING → GOODS_RECEIVED) and a delivery-complete SET, not one
// ordered whole, so there is no single canonical list for the backend to serve and inventing one
// would be a new defect rather than the removal of one. Known gap, stated rather than guessed at.
//
// The codes below are exactly FulfilmentStatus.java's seven. `PICKED_UP` and `CUSTOMS_CLEARANCE`
// used to sit at positions 3 and 5 of this list; both were deleted from FulfilmentStatus.java by
// 7991b9f1 as "dead constants (zero references anywhere)", and no code path has ever written
// either into sales.ticket.fulfillment_status. Leaving them here was not inert: SubstepChips marks
// every entry before the current one as done, so a deal on SHIPPING rendered "รับจากผู้ผลิตแล้ว"
// as a completed step, and one on GOODS_RECEIVED rendered "รอออกของ" as completed — two steps the
// business never performed and the column cannot express. Keep this list in step with that file.
export const PROCUREMENT_SUBSTEPS = [
  { code: 'IR_ISSUED',      label: 'ออกใบขอซื้อ (IR) แล้ว' },
  { code: 'IR_SENT',        label: 'สั่งซื้อไปยังผู้ผลิตแล้ว' },
  { code: 'SHIPPING',       label: 'สินค้าอยู่ระหว่างเดินทาง' },
  { code: 'GOODS_RECEIVED', label: 'สินค้าถึงโกดังแล้ว' },
  { code: 'FROM_STOCK',     label: 'สินค้าจากสต็อก' },
  { code: 'PARTIALLY_DELIVERED', label: 'ส่งมอบบางส่วน' },
  { code: 'FULLY_DELIVERED', label: 'ส่งมอบครบแล้ว' },
];

// Payment sub-steps (keyed by paymentStatus) — the inner journey of the back-half stages.
// Same reason as PROCUREMENT_SUBSTEPS for staying client-side: PaymentTrack.path(policy) returns a
// DIFFERENT sequence on a deposit-bypass policy, so "the ordered list of payment states" is not a
// single thing the backend can serve.
export const PAYMENT_SUBSTEPS = [
  { code: 'CUSTOMER_CONFIRMED',    label: 'ลูกค้ายืนยัน' },
  { code: 'DEPOSIT_NOTICE_ISSUED', label: 'ออกใบแจ้งมัดจำ' },
  { code: 'DEPOSIT_PAID',          label: 'รับมัดจำแล้ว' },
  { code: 'AWAITING_FINAL_PAYMENT', label: 'รอชำระส่วนที่เหลือ' },
  { code: 'FULLY_PAID',            label: 'ชำระครบแล้ว' },
];

/** Reason lists, labelled from a backend-supplied code set. Unknown codes render their own code. */
export function labelledReasons(codes, labels) {
  return (codes ?? []).map((code) => ({ code, label: labels[code] ?? code }));
}

/**
 * THE LABEL GUARD.
 *
 * Every stage code the backend serves must have Thai display copy on this side. A code without one
 * is not a cosmetic problem — it is the signal that the backend's pipeline moved and this side did
 * not notice, which is exactly what happened when V143 added QUOTE_OWNER: the stage rendered as
 * the raw string `QUOTE_OWNER` on the deal page and nothing anywhere complained. #704 could not be
 * deployed because of it.
 *
 * Called from stageCatalog.js's `select`, so it runs the instant the catalog lands, on every page
 * that reads it — not only on the one deal that happens to be sitting on the unlabelled stage.
 *
 * Loud means loud, but never at the cost of the page:
 *   * dev/test — throws. A missing label fails the suite and fails a local click-through.
 *   * production — console.error. A shipped build must still render the deal; the fallback in
 *     dealStageLabel shows the raw code, which is ugly on purpose.
 */
export function assertStageLabelsComplete(codes) {
  const missing = (codes ?? []).filter((code) => !hasDealStageLabel(code));
  if (missing.length === 0) return;
  const message = `[stageMeta] ขั้นตอนการขายไม่มีคำแปลภาษาไทย: ${missing.join(', ')} — `
    + 'add it to dealStageLabel() in utils/format.js (see stageMeta.js\'s label guard)';
  if (import.meta.env?.DEV || import.meta.env?.MODE === 'test') {
    throw new Error(message);
  }
  console.error(message);
}
