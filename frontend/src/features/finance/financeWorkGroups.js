// The งานการเงิน worklist's groups (design a-fin-land, owner-approved 2026-11-16): labels, hints, order and row
// buttons are the drawing's, verbatim.
//
// Grouping adds NO money rule. A deal's group is a pure lookup on the key nextAccountAction() already returns
// (features/tickets/accountActions.js) — the same helper the page's action column and filter have always used —
// so a deal is in a group exactly when that helper says finance has a step on it, and in no group otherwise.
// Every key maps to one group, so no deal can appear twice. The drawing puts a not-yet-overdue deposit row under
// ติดตามชำระ, which is why the two "money not in yet" steps (confirmDeposit, confirmFinalPayment) sit there beside
// chaseOverdue: in each case a bill is out and the money has not been recorded.

export const FINANCE_WORK_GROUPS = Object.freeze([
  {
    key: 'closeReady',
    label: 'ยืนยันพร้อมปิดงาน',
    hint: 'ส่งของครบและรับเงินครบแล้ว',
    buttonLabel: 'ยืนยันพร้อมปิดงาน',
    actionKeys: ['confirmCloseReady'],
  },
  {
    key: 'chase',
    label: 'ติดตามชำระ',
    hint: 'ออกใบแจ้งหนี้แล้ว ยังไม่พบเงินในไฟล์ล่าสุด',
    buttonLabel: 'เปิด',
    actionKeys: ['chaseOverdue', 'confirmDeposit', 'confirmFinalPayment'],
  },
  {
    key: 'invoice',
    label: 'บันทึกใบกำกับ',
    hint: 'ใช้คำนวณคำขอค่าคอม (เหมือนเดิม)',
    buttonLabel: 'บันทึกใบกำกับ',
    actionKeys: ['recordInvoiceCommission'],
  },
].map((g) => Object.freeze({ ...g, actionKeys: Object.freeze(g.actionKeys) })));

const GROUP_BY_ACTION_KEY = new Map(FINANCE_WORK_GROUPS.flatMap((g) => g.actionKeys.map((k) => [k, g.key])));

/** The group key for a nextAccountAction() result, or null when finance has no step (or the key is unknown). */
export function financeWorkGroupKey(action) {
  return GROUP_BY_ACTION_KEY.get(action?.key) ?? null;
}

/** Splits already-sorted `{ ticket, action }` rows into the three groups, keeping their order. Always returns all
 * three groups (empty ones included); rows with no group are left out — the ทั้งหมด view still lists them. */
export function groupFinanceWork(rows) {
  const buckets = new Map(FINANCE_WORK_GROUPS.map((g) => [g.key, []]));
  (rows ?? []).forEach((row) => {
    const key = financeWorkGroupKey(row.action);
    if (key) buckets.get(key).push(row);
  });
  return FINANCE_WORK_GROUPS.map((g) => ({ ...g, rows: buckets.get(g.key) }));
}
