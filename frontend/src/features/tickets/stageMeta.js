// Thai display copy for the deal pipeline — and nothing else. WHAT NO LONGER LIVES HERE, and must never come
// back: the stage list, their numbers, phases and write gates — GET /api/meta/deal-stages, read via
// stageCatalog.js.

import { entryChannelLabel, hasDealStageLabel } from '../../utils/format.js';

export const GATE_LABEL = {
  sales: 'ฝ่ายขาย',
  import: 'ฝ่ายนำเข้า',
  account: 'ฝ่ายบัญชี',
};

/**
 * Display-only owner override (owner ruling 2026-10-29): the sales rep issues the IR, so the pipeline
 * card names ฝ่ายขาย at ขั้น 12. The catalog's write gate (DealStage.gateOf) is a permission and is NOT changed.
 */
export const STAGE_OWNER_DISPLAY = { PROCUREMENT: 'sales' };

/**
 * Label of the manual move to the next stage. ขั้น 12 advances by itself when the IR is issued, so moving
 * on by hand there is a deliberate skip (secondary button), not the normal เลื่อนไป (W3-09).
 */
export function manualMoveLabel(salesStage, next, stageLabel) {
  return salesStage === 'PROCUREMENT'
    ? `ข้ามไปขั้น ${next.no} ด้วยตนเอง`
    : `เลื่อนไป: ${next.no}. ${stageLabel}`;
}

/** Thai wording for the auto-advanced stages — "where does this stage come from, if I cannot just click into
 * it?". Keyed by code; the *set* of auto stages is the catalog's `auto` flag, served by the backend, so this
 * map only supplies words for the ones that have it. */
export const AUTO_STAGE_HINT = {
  // Operational — a transition on the deal itself.
  ORDER_RECEIVED:    'อัปเดตอัตโนมัติเมื่อฝ่ายขายยืนยันใบสั่งซื้อของดีลนี้',
  DEPOSIT_RECEIVED:  'อัปเดตอัตโนมัติเมื่อฝ่ายบัญชียืนยันรับมัดจำของดีลนี้',
  PROCUREMENT:       'อัปเดตอัตโนมัติเมื่อฝ่ายขายออกเลขใบขอซื้อ (IR) ของดีลนี้ · ถ้าสินค้ามาจากสต็อกทั้งหมด จะข้ามไปขั้นนัดส่งสินค้า',
  CLOSED_PAID:       'อัปเดตอัตโนมัติเมื่อฝ่ายบัญชียืนยันรับเงินครบของดีลนี้',
  // Document-driven (owner ruling 2026-10-16) — a quotation addressed to that party. Each names
  // the DOCUMENT rather than a department, because unlike the four above the way in is something
  // this rep does themselves: the stage arrives when they quote that party.
  QUOTE_DESIGN_SIDE: 'อัปเดตอัตโนมัติเมื่อสร้างใบเสนอราคาถึงผู้ออกแบบของดีลนี้',
  QUOTE_OWNER:       'อัปเดตอัตโนมัติเมื่อสร้างใบเสนอราคาถึงเจ้าของโครงการของดีลนี้',
  QUOTE_BUYER:       'อัปเดตอัตโนมัติเมื่อสร้างใบเสนอราคาถึงผู้ซื้อ / ผู้รับเหมาของดีลนี้',
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

/** Why the opportunity went away (V56) — distinct from LOST_REASON_LABEL, which is why we lost a deal we were
 * competing for. The code set comes from the catalog (DealCancelReason.ORDER). */
export const CANCEL_REASON_LABEL = {
  OWNER_CANCELLED:   'เจ้าของยกเลิกโครงการ',
  PROJECT_SUSPENDED: 'โครงการถูกระงับไม่มีกำหนด',
  BUDGET_CANCELLED:  'งบประมาณถูกยกเลิก',
  OTHER:             'อื่น ๆ (ระบุในหมายเหตุ)',
};

// Fulfilment sub-steps rendered inside the PROCUREMENT stage — these come from the deal's own
// fulfillment_status, never separate stages. Known gap, stated rather than guessed at. The codes below are
// exactly FulfilmentStatus.java's seven.
export const PROCUREMENT_SUBSTEPS = [
  { code: 'IR_ISSUED',      label: 'ออกใบขอซื้อ (IR) แล้ว' },
  { code: 'IR_SENT',        label: 'สั่งซื้อไปยังผู้ผลิตแล้ว' },
  { code: 'SHIPPING',       label: 'สินค้าอยู่ระหว่างเดินทาง' },
  { code: 'GOODS_RECEIVED', label: 'สินค้าถึงโกดังแล้ว' },
  { code: 'FROM_STOCK',     label: 'สินค้าจากสต็อก' },
  { code: 'PARTIALLY_DELIVERED', label: 'ส่งมอบบางส่วน' },
  { code: 'FULLY_DELIVERED', label: 'ส่งมอบครบแล้ว' },
];

/** The SAME seven codes, split into the shape FulfilmentStatus.java actually publishes — because the flat list
 * above is a lookup table, not a path, and rendering it as one caused issue #730. `IMPORT_SEQUENCE`
 * (FulfilmentStatus.java:33) is an ordered chain. */
const IMPORT_SEQUENCE = ['IR_ISSUED', 'IR_SENT', 'SHIPPING', 'GOODS_RECEIVED'];
const DELIVERY_STATES = ['PARTIALLY_DELIVERED', 'FULLY_DELIVERED'];

export function procurementPath(currentCode, fromStock = null) {
  const byCode = (code) => PROCUREMENT_SUBSTEPS.find((s) => s.code === code);
  if (currentCode === 'FROM_STOCK' || fromStock === true) return ['FROM_STOCK', ...DELIVERY_STATES].map(byCode);
  if (fromStock === false) return [...IMPORT_SEQUENCE, ...DELIVERY_STATES].map(byCode);
  // fromStock unknown AND the deal has moved on to a DELIVERY state: its procurement status has been
  // overwritten, so fulfillmentStatus alone can no longer say which journey got it here.
  if (DELIVERY_STATES.includes(currentCode)) return DELIVERY_STATES.map(byCode);
  return [...IMPORT_SEQUENCE, ...DELIVERY_STATES].map(byCode);
}

// Payment sub-steps (keyed by paymentStatus) — the inner journey of the back-half stages.
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

/** Reports backend stage codes missing Thai display labels. */
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

/** Headline overrides for DealStagePanel, for stages whose own label does not say who the stage is dealing
 * with. S4-S8 already do ("เสนอราคาผู้ออกแบบ", "เสนอราคาเจ้าของโครงการ", ...), so only ORDER_RECEIVED needs
 * one. */
export const STAGE_HEADLINE = {
  ORDER_RECEIVED: 'ได้รับใบสั่งซื้อจากผู้รับเหมา/ผู้ซื้อ',
};

/** The channels that HAVE a route (DealRoute.java's three). UNSPECIFIED, an unknown value and an absent channel
 * have none — every stage is reachable for them — so nothing may name a route for them. Wording only: which
 * stages each route visits is the backend's, served per stage as `onRoute`. */
const ROUTE_CHANNELS = ['DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT'];

/** The Thai name of the deal's route ("เจ้าของติดต่อโดยตรง"), or null when the channel has no route. null means
 * "render nothing": a panel must never invent a route for a deal that has none. */
export function routeName(entryChannel) {
  return ROUTE_CHANNELS.includes(entryChannel) ? entryChannelLabel(entryChannel).label : null;
}
