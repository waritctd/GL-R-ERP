// Pure helpers shared by the commission page's receipt ledger, receipt chain and review table.
// Nothing here recomputes a commission: the only arithmetic is the DISPLAY-ONLY "เข้าฐาน"
// contribution (ex-VAT amount × the weight payroll actually uses), and the page says in words that
// a single sale has no baht commission of its own because the ladder is marginal over the month.

// Manual commission entries (feat/commission-manual-adjustments): ALL FOUR kinds are hand-typed
// for now — owner decision: manual across the UI until the CEO-confirmed auto-config lands to
// prefill suggestions for specific ones later (not implemented here, no auto-computation exists
// anywhere in this form). Mirrors backend/.../commission/CommissionKind.java's four constants.
export const MANUAL_KIND_LABELS = {
  ADJUSTMENT: 'ปรับปรุง/รับช่วงงาน',
  MANAGER: 'ค่าคอมผู้จัดการ/ทีม',
  STOCK_BONUS: 'โบนัสขายสต็อก',
  INCENTIVE: 'Incentive ตามเป้า',
};
export const MANUAL_KINDS = Object.keys(MANUAL_KIND_LABELS);

export function isManualKind(kind) {
  return Object.prototype.hasOwnProperty.call(MANUAL_KIND_LABELS, kind);
}

export function kindLabel(kind) {
  if (kind === 'CLAWBACK') return 'คืน/ยกเลิก';
  if (isManualKind(kind)) return MANUAL_KIND_LABELS[kind];
  return 'ขาย';
}

// V148 (per-item stock-commission weighting): a record's weight has two possible sources — the
// frozen, blended per-item weight (effectiveWeightMultiplier, non-null only for a SALE/CLAWBACK
// whose ticket had priced, stock-covered items at creation time) when one exists, else the plain
// manager-set weightMultiplier fallback. When the frozen weight is present it is authoritative for
// payroll (CommissionRepository#sumActiveWeightedActualReceived's COALESCE), so every figure on the
// page uses `recordWeight`, never weightMultiplier directly.
export const hasItemDerivedWeight = (record) => record?.effectiveWeightMultiplier !== null && record?.effectiveWeightMultiplier !== undefined;

export function recordWeight(record) {
  const value = Number(hasItemDerivedWeight(record) ? record.effectiveWeightMultiplier : record?.weightMultiplier);
  return Number.isFinite(value) ? value : 1;
}

export function formatWeight(value) {
  const number = Number(value);
  return `×${Number.isInteger(number) ? number : number.toFixed(2)}`;
}

// Why this receipt is weighted — one line, or null when there is nothing to explain.
export function weightExplanation(record) {
  if (hasItemDerivedWeight(record)) return 'น้ำหนักถ่วงจากรายการสต็อกของดีล (ของสั่งคิด ×1)';
  if (Number(record?.weightMultiplier) !== 1) return 'น้ำหนักที่ผู้จัดการกำหนด';
  return null;
}

// Does this record put anything into the month's base? A rejected or voided receipt never does, and a
// manual entry never does (it is added on top of the ladder, not run through it). The API gives the
// status, so this is a display rule, not a computation.
const NON_COUNTING_STATUSES = ['REJECTED', 'VOID'];
export const countsTowardBase = (record) => !isManualKind(record?.kind) && !NON_COUNTING_STATUSES.includes(record?.status);

// Display-only: what this receipt puts into the month's base. Rounded to satang per receipt, which
// is why the ledger carries a note that the column's sum can differ from ฐานคิดค่าคอม by a few satang.
export function baseContribution(record) {
  return Math.round(Number(record.commissionableBase || 0) * recordWeight(record) * 100) / 100;
}

// Badge copy for the manager review table: null when the weight is exactly 1.
export function describeCommissionWeight(record) {
  const itemDerived = hasItemDerivedWeight(record);
  const value = recordWeight(record);
  if (!(value > 1)) return null;
  const formatted = Number.isInteger(value) ? String(value) : value.toFixed(2);
  return {
    itemDerived,
    label: itemDerived ? `น้ำหนักจากรายการสินค้า ${formatted} เท่า` : `น้ำหนักฐานคอม ${formatted} เท่า`,
    compactLabel: itemDerived ? `${formatted}x (รายการ)` : `${formatted}x`,
  };
}
