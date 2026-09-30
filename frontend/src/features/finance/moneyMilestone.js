// Mirrors th.co.glr.hr.ticket.MoneyMilestone
//
// The five money milestones finance tracks a deal through, derived from `salesStage`. This exists
// ONLY for the /finance list, whose rows (TicketSummaryDto) carry salesStage but no milestone. The
// deal page must NOT use it: FinanceDealDto ships `milestoneTrack` / `moneyMilestone` already
// computed by the server, and that is what it renders.
//
//   1 ORDER_RECEIVED    S10        ได้รับคำสั่งซื้อ
//   2 DEPOSIT_RECEIVED  S11        ได้รับมัดจำ            (skipped when the deposit policy bypasses the notice)
//   3 PROCUREMENT       S12-S17    รอสินค้า / นำเข้า
//   4 DELIVERY          S18-S19    ส่งมอบ — รอชำระส่วนที่เหลือ
//   5 CLOSED_PAID       S20        ชำระครบ ปิดงาน
//
// A stage below ORDER_RECEIVED has no money milestone yet. moneyMilestone.test.js pins this table
// against every stage in the pipeline order.

export const MONEY_TRACK = Object.freeze([
  { key: 'ORDER_RECEIVED', index: 1, label: 'ได้รับคำสั่งซื้อ' },
  { key: 'DEPOSIT_RECEIVED', index: 2, label: 'ได้รับมัดจำ' },
  { key: 'PROCUREMENT', index: 3, label: 'รอสินค้า / นำเข้า' },
  { key: 'DELIVERY', index: 4, label: 'ส่งมอบ — รอชำระส่วนที่เหลือ' },
  { key: 'CLOSED_PAID', index: 5, label: 'ชำระครบ ปิดงาน' },
].map(Object.freeze));

const [ORDER_RECEIVED, DEPOSIT_RECEIVED, PROCUREMENT, DELIVERY, CLOSED_PAID] = MONEY_TRACK;

const BY_STAGE = {
  ORDER_RECEIVED,
  DEPOSIT_RECEIVED,
  PROCUREMENT,
  DELIVERY_SCHEDULING: DELIVERY,
  DELIVERED: DELIVERY,
  CLOSED_PAID,
};

// DepositPolicy.bypassesDepositNotice: the three policies that never issue a deposit notice.
const DEPOSIT_BYPASS_POLICIES = new Set(['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER']);

/** The milestone a deal at `salesStage` is on; null below S10 or for an unknown/null code. */
export function moneyMilestoneOf(salesStage) {
  if (salesStage == null) return null;
  return Object.hasOwn(BY_STAGE, salesStage) ? BY_STAGE[salesStage] : null;
}

/** Only the deposit milestone can be skipped, and only when the policy bypasses the deposit notice. */
export function isMilestoneSkipped(step, depositPolicy) {
  return step != null && step.index === DEPOSIT_RECEIVED.index
    && depositPolicy != null && DEPOSIT_BYPASS_POLICIES.has(depositPolicy);
}
