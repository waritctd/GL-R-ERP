// The document chain's WORDS — the one place the quotation page's chain card, the deal page's เงิน tab and its
// ขั้นต่อไป card, and the quotation list's chain mark turn the server's codes (DocumentChainRules: states, reason codes,
// list stages) into Thai. Nothing here decides a state; every input is a field of the server's summary.

export const CHAIN_STEP_TITLES = ['ใบเสนอราคา', 'ยืนยันคำสั่งซื้อ', 'ใบแจ้งยอดเงินรับมัดจำ', 'ใบแจ้งหนี้ส่วนที่เหลือ'];

/** Why a step cannot be taken yet (DocumentChainRules' reason codes). */
export const CHAIN_BLOCKED_TEXT = {
  ORDER_NOT_CONFIRMED: 'ยืนยันคำสั่งซื้อก่อน',
  DEPOSIT_NOT_RECEIVED: 'ออกได้หลังฝ่ายบัญชียืนยันรับมัดจำ',
  GOODS_NOT_READY: 'ออกได้เมื่อสินค้าถึงขั้นรับ/ส่งมอบแล้ว',
  DEAL_NOT_ACTIVE: 'งานพักหรือปิดอยู่ — ทำต่อได้เมื่องานกลับมาดำเนินการ',
};

export function blockedText(code) {
  return code ? CHAIN_BLOCKED_TEXT[code] ?? null : null;
}

// ── the deal page's เงิน tab step badges and hints ──────────────────────────────────────────────

const DEPOSIT_BADGE = {
  NOT_REQUIRED: { label: 'ไม่เก็บมัดจำ', tone: 'neutral' },
  NOT_YET: { label: 'ยังไม่ถึงขั้นตอน', tone: 'neutral' },
  TO_CREATE: { label: 'ยังไม่สร้าง', tone: 'warning' },
  DRAFT: { label: 'ร่าง', tone: 'warning' },
  ISSUED: { label: 'รอชำระ', tone: 'warning' },
  PAID: { label: 'ชำระแล้ว', tone: 'success' },
};

const REMAINING_BADGE = {
  WAITING: { label: 'รอรับมัดจำก่อน', tone: 'neutral' },
  TO_CREATE: { label: 'ยังไม่ออก', tone: 'warning' },
  DRAFT: { label: 'ร่าง', tone: 'warning' },
  ISSUED: { label: 'รอชำระ', tone: 'warning' },
  PAID: { label: 'ชำระแล้ว', tone: 'success' },
  NOT_NEEDED: { label: 'ไม่ต้องออก — ชำระครบจากมัดจำ', tone: 'neutral' },
};

export function depositBadge(chain) {
  return DEPOSIT_BADGE[chain?.deposit?.state] ?? DEPOSIT_BADGE.NOT_YET;
}

export function remainingBadge(chain) {
  // Blocked on the ORDER (the deal is not quotation_issued): there is nothing — no deposit — to wait for yet (fix
  // round 1, F-4: an unconfirmed no-deposit deal used to read "รอรับมัดจำก่อน").
  if (chain?.remaining?.blockedReason === 'ORDER_NOT_CONFIRMED') return DEPOSIT_BADGE.NOT_YET;
  return REMAINING_BADGE[chain?.remaining?.state] ?? REMAINING_BADGE.WAITING;
}

/**
 * The deposit step's one-line hint. `mainButtonCreatesNotice`: the deal page's main button IS the create button,
 * so the step points at it instead of carrying a second one.
 */
export function depositHint(chain, { mainButtonCreatesNotice = false } = {}) {
  const { state, blockedReason } = chain?.deposit ?? {};
  if (blockedReason) return blockedText(blockedReason);
  if (state === 'NOT_REQUIRED') return 'ข้ามขั้นตอนนี้ — ไม่ต้องเก็บมัดจำสำหรับงานนี้';
  if (state === 'NOT_YET') return 'สร้างได้เมื่อลูกค้ายืนยันคำสั่งซื้อ';
  if (state === 'ISSUED') return 'รอลูกค้าชำระ — ฝ่ายบัญชียืนยันรับเงินที่หน้าการเงินของฝ่ายบัญชี';
  if (state === 'TO_CREATE') {
    if (mainButtonCreatesNotice) return 'ลูกค้ายืนยันคำสั่งซื้อแล้ว — กดปุ่ม "สร้างใบแจ้งยอดเงินรับมัดจำ" ด้านบน';
    return chain.viewer?.mayWriteDepositNotice
      ? 'ลูกค้ายืนยันคำสั่งซื้อแล้ว — สร้างจากใบเสนอราคาที่ยอมรับได้เลย'
      : 'ลูกค้ายืนยันคำสั่งซื้อแล้ว — รอฝ่ายขายสร้าง';
  }
  return null;
}

/** The remaining-invoice step's one-line hint. */
export function remainingHint(chain) {
  const { state, blockedReason } = chain?.remaining ?? {};
  if (blockedReason) return blockedText(blockedReason);
  if (state === 'TO_CREATE' && !chain.viewer?.mayWriteRemainingInvoice) return 'รอฝ่ายขายออกเอกสาร';
  return null;
}

// ── whose turn it is ─────────────────────────────────────────────────────────────────────────────

/**
 * The chain's next step, for BOTH pages: `{ index, waitingOn, viewerCanAct, line }`.
 *   index       0-3 — the card's step the chain is at (null when nothing is left or nothing applies)
 *   waitingOn   'sales' | 'account' | 'goods' | 'deal' | 'customer' | null — who or what the step waits on
 *   viewerCanAct  the viewer may take this step now (the server's flag AND nothing blocks it)
 *   line        one sentence naming the step and who takes it; null when the chain is complete
 */
export function chainNextStep(chain) {
  if (!chain) return { index: null, waitingOn: null, viewerCanAct: false, line: null };
  const { order, deposit, remaining, viewer = {} } = chain;
  const step = (index, waitingOn, viewerCanAct, line) => ({ index, waitingOn, viewerCanAct, line });

  if (!order?.confirmed) {
    if (!order?.confirmable) return step(null, 'deal', false, null);
    return step(1, 'sales', Boolean(viewer.mayConfirmOrder), 'ขั้นถัดไป: ฝ่ายขายเจ้าของงานยืนยันคำสั่งซื้อ');
  }
  if (deposit?.state === 'TO_CREATE' || deposit?.state === 'DRAFT') {
    if (deposit.blockedReason) return step(2, 'deal', false, `ขั้นถัดไป: ${blockedText(deposit.blockedReason)}`);
    return step(2, 'sales', Boolean(viewer.mayWriteDepositNotice),
      'ขั้นถัดไป: ฝ่ายขายเจ้าของงานออกใบแจ้งยอดเงินรับมัดจำ');
  }
  switch (remaining?.state) {
    case 'WAITING':
      return step(3, 'account', false, 'ขั้นถัดไป: ฝ่ายบัญชียืนยันรับมัดจำที่หน้าการเงิน');
    case 'TO_CREATE':
      if (remaining.blockedReason === 'GOODS_NOT_READY') {
        return step(3, 'goods', false, 'ขั้นถัดไป: รอสินค้าถึงขั้นรับ/ส่งมอบ แล้วออกใบแจ้งหนี้ส่วนที่เหลือ');
      }
      if (remaining.blockedReason) return step(3, 'deal', false, `ขั้นถัดไป: ${blockedText(remaining.blockedReason)}`);
      return step(3, 'sales', Boolean(viewer.mayWriteRemainingInvoice),
        'ขั้นถัดไป: ฝ่ายขายเจ้าของงานออกใบแจ้งหนี้ส่วนที่เหลือ');
    case 'DRAFT':
      return step(3, 'sales', Boolean(viewer.mayWriteRemainingInvoice),
        'ขั้นถัดไป: ฝ่ายขายเจ้าของงานออกใบแจ้งหนี้ส่วนที่เหลือ');
    case 'ISSUED':
      return step(null, 'customer', false, 'ออกเอกสารครบแล้ว — รอลูกค้าชำระส่วนที่เหลือ');
    default:
      return step(null, null, false, 'ออกเอกสารครบแล้ว');
  }
}

// ── the quotation list's per-row mark (DocumentChainRules#listStage) ──────────────────────────────

export const CHAIN_LIST_MARK = {
  AWAITING_ORDER: { label: 'รอยืนยันคำสั่งซื้อ', tone: 'warning' },
  AWAITING_DEPOSIT_NOTICE: { label: 'ยืนยันแล้ว · รอใบแจ้งมัดจำ', tone: 'warning' },
  NO_DEPOSIT: { label: 'ยืนยันแล้ว · ไม่รับมัดจำ', tone: 'neutral' },
  DEPOSIT_NOTICE_ISSUED: { label: 'ออกใบแจ้งมัดจำแล้ว', tone: 'info' },
  REMAINING_INVOICE_ISSUED: { label: 'ออกใบแจ้งหนี้แล้ว', tone: 'success' },
};

export function chainListMark(stage) {
  return stage ? CHAIN_LIST_MARK[stage] ?? null : null;
}
