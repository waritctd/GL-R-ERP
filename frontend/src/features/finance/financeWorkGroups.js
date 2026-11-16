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
    hint: 'พร้อมปิดงาน รอฝ่ายบัญชียืนยัน',
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

// Not in the drawing. A step nextAccountAction() may one day return that no drawn group lists still has to be on the
// worklist (and in the ต้องดำเนินการ count), so it lands here, last, and only when non-empty. Its button is "เปิด".
export const OTHER_WORK_GROUP = Object.freeze({
  key: 'other',
  label: 'อื่น ๆ ที่ต้องดำเนินการ',
  hint: null,
  buttonLabel: 'เปิด',
  actionKeys: Object.freeze([]),
});

/** The group key for a nextAccountAction() result: a drawn group, 'other' for a step no group lists, or null when
 * finance has no step at all. */
export function financeWorkGroupKey(action) {
  if (action == null) return null;
  return GROUP_BY_ACTION_KEY.get(action.key) ?? OTHER_WORK_GROUP.key;
}

/** Splits already-sorted `{ ticket, action }` rows into groups, keeping their order. Always returns the three drawn
 * groups (empty ones included) plus OTHER_WORK_GROUP when it has rows; rows with no step are left out — the ทั้งหมด
 * view still lists them. Every row with a step is in exactly one group. */
export function groupFinanceWork(rows) {
  const all = [...FINANCE_WORK_GROUPS, OTHER_WORK_GROUP];
  const buckets = new Map(all.map((g) => [g.key, []]));
  (rows ?? []).forEach((row) => {
    const key = financeWorkGroupKey(row.action);
    if (key) buckets.get(key).push(row);
  });
  return all
    .filter((g) => g !== OTHER_WORK_GROUP || buckets.get(g.key).length > 0)
    .map((g) => ({ ...g, rows: buckets.get(g.key) }));
}
