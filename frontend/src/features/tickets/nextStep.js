// The "ขั้นต่อไป" card's stage table (owner-approved drawing a-deal, 2026-11-16). It NAMES the next step, says how
// the deal moves on from here (spec 2026-11-15 §7.1, worded as the drawing words it) and which tab holds that
// stage's work. It never decides who may act: the card's one button is the action the page already resolves for
// this viewer (TicketDetailPage's server-gated primary, or DealStagePanel's resume / reopen), so a viewer the
// server gives nothing to sees the step named and no button.

const QUOTE_FOLLOW_UP = { title: 'ติดตามผลใบเสนอราคา', hint: 'บันทึกผลแล้วงานย้ายไป S5 / S6 เอง', tab: 'quote' };
const PRESENTING = { title: 'นำเสนอสินค้าจนผู้ออกแบบตกลงสเปค', hint: 'ตกลงสเปคแล้ว เซลส์ย้ายงานไป S2 เอง', tab: 'overview' };
const BUYER_ORDER = { title: 'ยืนยันคำสั่งซื้อกับผู้ซื้อ', hint: 'ยืนยันคำสั่งซื้อแล้วออกใบแจ้งมัดจำได้', tab: 'quote' };

const BY_STAGE = {
  LEAD_APPROACH: PRESENTING,
  PRESENTATION: PRESENTING,
  SPEC_APPROVED: { title: 'ทำใบเสนอราคา', hint: 'ทำใบเสนอราคาแล้วงานย้ายไป S3 เอง', tab: 'quote' },
  QUOTE_DESIGN_SIDE: QUOTE_FOLLOW_UP,
  QUOTE_OWNER: QUOTE_FOLLOW_UP,
  OWNER_SIGNOFF: QUOTE_FOLLOW_UP,
  AWAITING_BUYER: { title: 'ติดตามผลประมูล', hint: 'ทราบผู้ซื้อแล้วงานย้ายไป S6 เอง', tab: 'quote' },
  QUOTE_BUYER: BUYER_ORDER,
  NEGOTIATION: BUYER_ORDER,
  ORDER_RECEIVED: { title: 'ออกใบแจ้งมัดจำและรอรับมัดจำ', hint: 'ฝ่ายบัญชียืนยันรับมัดจำแล้วงานย้ายไป S7 เอง', tab: 'money' },
  DEPOSIT_RECEIVED: { title: 'จัดซื้อสินค้า', hint: 'ออกใบขอซื้อ (IR) แล้วฝ่ายนำเข้าติดตามของต่อ', tab: 'ship' },
  PROCUREMENT: { title: 'ติดตามการนำเข้า', hint: 'ของถึงโกดังแล้วนัดส่งสินค้าได้ (S8)', tab: 'ship' },
  DELIVERY_SCHEDULING: { title: 'ส่งสินค้าให้ลูกค้า', hint: 'ส่งครบแล้วงานย้ายไป S9 เอง', tab: 'ship' },
  DELIVERED: { title: 'เก็บเงินส่วนที่เหลือ', hint: 'ฝ่ายบัญชียืนยันรับเงินครบแล้วปิดงาน', tab: 'money' },
};

const RESUME_HINT = 'กด “ดำเนินการต่อ” เมื่อพร้อมทำงานนี้ต่อ ขั้นเดิมยังอยู่';

// A deal that is not ACTIVE has no stage work to do: the lifecycle names the card, whatever stage it was left at.
const BY_LIFECYCLE = {
  ON_HOLD: { kind: 'resume', title: 'งานนี้พักไว้ชั่วคราว', hint: RESUME_HINT, tab: null },
  DORMANT: { kind: 'resume', title: 'งานนี้พักยาว (dormant)', hint: RESUME_HINT, tab: null },
  CLOSED_LOST: { kind: 'reopen', title: 'เสียงานแล้ว', hint: 'เปิดงานอีกครั้งได้ ขั้นเดิมยังอยู่', tab: null },
  CANCELLED: { kind: 'closed', title: 'ยกเลิกงานแล้ว', hint: null, tab: null },
  COMPLETED: { kind: 'closed', title: 'งานเสร็จสมบูรณ์', hint: null, tab: null },
};

const NONE = Object.freeze({ kind: 'none', title: null, hint: null, tab: null });

/**
 * The next step for a deal: `{ kind, title, hint, tab }`. kind is
 *   'step'   — an ACTIVE deal with stage work ahead (tab = where that work lives),
 *   'done'   — CLOSED_PAID: nothing left to do,
 *   'resume' — ON_HOLD / DORMANT (the card offers ดำเนินการต่อ when the server lists RESUME),
 *   'reopen' — CLOSED_LOST (the card offers เปิดงานอีกครั้ง when the server lists REOPEN),
 *   'closed' — CANCELLED / COMPLETED,
 *   'none'   — an unknown stage: named nothing rather than guessed.
 */
export function nextStepFor({ salesStage, lifecycle } = {}) {
  const byLifecycle = BY_LIFECYCLE[lifecycle];
  if (byLifecycle) return { ...byLifecycle };
  if (salesStage === 'CLOSED_PAID') return { kind: 'done', title: 'ปิดการขายแล้ว — เก็บเงินครบ', hint: null, tab: 'money' };
  const step = BY_STAGE[salesStage];
  return step ? { kind: 'step', ...step } : { ...NONE };
}
