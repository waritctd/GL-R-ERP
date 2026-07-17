// Canonical metadata for the 14-stage DEAL pipeline (V50): one ticket = one
// deal, and these stages are what it takes to close it. Mirrors DealStage /
// DealLostReason / TicketService stage gates (backend ticket/ package).
// Business cross-reference to the boss's S1–S20 sheet lives in DealStage.java.
//
// NOTE: gate logic here only drives which buttons/options the UI shows — the
// backend re-checks everything. Mock authz approximates the Java service and
// is NOT authoritative (see CLAUDE.md).

export const SALES_PHASES = [
  { id: 1, name: 'การเข้าถึงโครงการ', helper: 'Lead' },
  { id: 2, name: 'งานสเปค', helper: 'Specification' },
  { id: 3, name: 'ประมูลและเจรจา', helper: 'Bidding' },
  { id: 4, name: 'คำสั่งซื้อและนำเข้า', helper: 'Order & import' },
  { id: 5, name: 'ส่งมอบและปิดงาน', helper: 'Delivery & closing' },
];

// gate: who may set the stage MANUALLY (owner = the deal's sales rep, i.e.
// ticket.createdById;
// sales_manager and ceo always pass sales-gated stages; ceo passes everything).
// auto: normally advanced by the ticket flow — the UI never offers a one-click
// advance into these, and the edit modal explains where they come from.
export const SALES_STAGES = [
  { code: 'LEAD_APPROACH',       no: 1,  phase: 1, gate: 'sales',   auto: false },
  { code: 'PRESENTATION',        no: 2,  phase: 1, gate: 'sales',   auto: false },
  { code: 'SPEC_APPROVED',       no: 3,  phase: 2, gate: 'sales',   auto: false },
  { code: 'QUOTE_DESIGN_SIDE',   no: 4,  phase: 2, gate: 'sales',   auto: false },
  { code: 'OWNER_SIGNOFF',       no: 5,  phase: 2, gate: 'sales',   auto: false },
  { code: 'AWAITING_BUYER',      no: 6,  phase: 3, gate: 'sales',   auto: false },
  { code: 'QUOTE_BUYER',         no: 7,  phase: 3, gate: 'sales',   auto: false },
  { code: 'NEGOTIATION',         no: 8,  phase: 3, gate: 'sales',   auto: false },
  { code: 'ORDER_RECEIVED',      no: 9,  phase: 4, gate: 'sales',   auto: true,
    autoHint: 'อัปเดตอัตโนมัติเมื่อฝ่ายขายยืนยันใบสั่งซื้อของดีลนี้' },
  { code: 'DEPOSIT_RECEIVED',    no: 10, phase: 4, gate: 'account', auto: true,
    autoHint: 'อัปเดตอัตโนมัติเมื่อฝ่ายบัญชียืนยันรับมัดจำของดีลนี้' },
  { code: 'PROCUREMENT',         no: 11, phase: 4, gate: 'import',  auto: true,
    autoHint: 'อัปเดตอัตโนมัติเมื่อฝ่ายนำเข้าออกใบขอซื้อ (IR) ของดีลนี้' },
  { code: 'DELIVERY_SCHEDULING', no: 12, phase: 5, gate: 'sales',   auto: false },
  { code: 'DELIVERED',           no: 13, phase: 5, gate: 'sales',   auto: false },
  { code: 'CLOSED_PAID',         no: 14, phase: 5, gate: 'account', auto: true,
    autoHint: 'อัปเดตอัตโนมัติเมื่อฝ่ายบัญชียืนยันรับเงินครบของดีลนี้' },
];

export const LOST_REASONS = [
  { code: 'PRODUCT_FIT',       label: 'ไม่มีสินค้าที่ลูกค้าต้องการ' },
  { code: 'PRICE',             label: 'ราคา' },
  { code: 'LEAD_TIME',         label: 'ระยะเวลาส่งมอบ (leadtime)' },
  { code: 'PAYMENT_TERMS',     label: 'เงื่อนไขการชำระเงิน (payment term)' },
  { code: 'RELATIONSHIP',      label: 'ความสัมพันธ์ / connection' },
  { code: 'PROJECT_ON_HOLD',   label: 'โครงการหยุดก่อสร้างชั่วคราว' },
  { code: 'PROJECT_CANCELLED', label: 'โครงการหยุดก่อสร้างถาวร' },
  { code: 'ALREADY_PURCHASED', label: 'ลูกค้าสั่งซื้อกระเบื้องไปหมดแล้ว' },
];

export const GATE_LABEL = {
  sales: 'ฝ่ายขาย',
  import: 'ฝ่ายนำเข้า',
  account: 'ฝ่ายบัญชี',
};

export function stageMeta(code) {
  return SALES_STAGES.find((s) => s.code === code) ?? null;
}

export function stageIndex(code) {
  return SALES_STAGES.findIndex((s) => s.code === code);
}

export function nextStage(code) {
  const idx = stageIndex(code);
  return idx >= 0 && idx < SALES_STAGES.length - 1 ? SALES_STAGES[idx + 1] : null;
}

export function phaseOf(code) {
  const meta = stageMeta(code);
  return meta ? SALES_PHASES.find((p) => p.id === meta.phase) : null;
}

export function isDealOwner(user, deal) {
  return user?.role === 'sales' && deal?.createdById != null
    && Number(deal.createdById) === Number(user.id);
}

/** Mirrors TicketService.requireStageWriteAccess for a manual set of `code`. */
export function canSetStage(user, deal, code) {
  const meta = stageMeta(code);
  if (!meta || !user) return false;
  if (user.role === 'ceo') return true;
  if (meta.gate === 'sales') {
    return user.role === 'sales_manager' || isDealOwner(user, deal);
  }
  if (meta.gate === 'account') return user.role === 'account';
  if (meta.gate === 'import') return user.role === 'import';
  return false;
}

/** Mirrors TicketService.requireDealOwnership (lost / reopen). */
export function canMarkLost(user, deal) {
  return user?.role === 'ceo' || user?.role === 'sales_manager' || isDealOwner(user, deal);
}

/** Stages the edit-modal offers this user for this deal (current stage excluded). */
export function allowedTargetStages(user, deal) {
  return SALES_STAGES.filter(
    (s) => s.code !== deal?.salesStage && canSetStage(user, deal, s.code),
  );
}

// Fulfillment sub-steps rendered inside the PROCUREMENT stage — these come from
// the deal's own fulfillment_status, never separate stages (see handoff 65).
export const PROCUREMENT_SUBSTEPS = [
  { code: 'IR_ISSUED',      label: 'ออกใบขอซื้อ (IR) แล้ว' },
  { code: 'IR_SENT',        label: 'สั่งซื้อไปยังผู้ผลิตแล้ว' },
  { code: 'SHIPPING',       label: 'สินค้าอยู่ระหว่างเดินทาง' },
  { code: 'GOODS_RECEIVED', label: 'สินค้าถึงโกดังแล้ว' },
];

// Payment sub-steps (keyed by paymentStatus) — the inner journey of stages
// 9–14. Replaces the old standalone Track P stepper on the deal detail page.
export const PAYMENT_SUBSTEPS = [
  { code: 'CUSTOMER_CONFIRMED',    label: 'ลูกค้ายืนยัน' },
  { code: 'DEPOSIT_NOTICE_ISSUED', label: 'ออกใบแจ้งมัดจำ' },
  { code: 'DEPOSIT_PAID',          label: 'รับมัดจำแล้ว' },
  { code: 'AWAITING_FINAL_PAYMENT', label: 'รอชำระส่วนที่เหลือ' },
  { code: 'FULLY_PAID',            label: 'ชำระครบแล้ว' },
];

// Internal price workflow (keyed by ticket.status) — the inner journey of the
// quote stages (S4+S5/QUOTE_DESIGN_SIDE, QUOTE_BUYER): sales requests the price,
// Import proposes it, the CEO calculates/approves the confirmed price, and only
// then can the quotation be issued. Draft sits before step 1.
export const PRICING_SUBSTEPS = [
  { code: 'submitted',        label: 'ส่งขอราคาแล้ว' },
  { code: 'in_review',        label: 'Import กำลังเสนอราคา' },
  { code: 'price_proposed',   label: 'รอ CEO อนุมัติราคา' },
  { code: 'approved',         label: 'ราคายืนยันแล้ว' },
  { code: 'quotation_issued', label: 'ออกใบเสนอราคาแล้ว' },
];
