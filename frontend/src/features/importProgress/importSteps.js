// The six per-factory import steps (S12-S17, GLA-100/105). Drift between this file's labels and the backend's
// has no guard either way — see CLAUDE.md's mock-constant-drift memory.
export const IMPORT_STEPS = [
  { code: 'CONTACTED', s: 'S12', label: 'ส่ง IR ให้จัดซื้อ' },
  { code: 'ORDERED', s: 'S13', label: 'สั่งซื้อผู้ผลิต' },
  { code: 'PICKED_UP', s: 'S14', label: 'ขนส่งรับของ' },
  { code: 'IN_TRANSIT', s: 'S15', label: 'กำลังเดินทาง' },
  { code: 'AWAITING_CUSTOMS', s: 'S16', label: 'ถึงไทย รอออกของ' },
  { code: 'RECEIVED', s: 'S17', label: 'ถึงโกดัง' },
];

const IMPORT_STEP_CODES = IMPORT_STEPS.map((step) => step.code);

export function importStepIndex(code) {
  return IMPORT_STEP_CODES.indexOf(code);
}

export function importStepMeta(code) {
  return IMPORT_STEPS.find((step) => step.code === code) ?? null;
}

/** The next step after `code`, or null when already at the last step (RECEIVED) or unset. */
export function nextImportStep(code) {
  const i = importStepIndex(code);
  return i >= 0 && i < IMPORT_STEPS.length - 1 ? IMPORT_STEPS[i + 1] : null;
}

/** Every step strictly ahead of `code` — advanceStep allows a forward SKIP, not just the next one (owner
 * decision 4: "forward skips allowed, never backwards — correct via revision"), so a caller picking a target
 * needs the whole remaining list, not just nextImportStep's single row. */
export function stepsAhead(code) {
  const i = importStepIndex(code);
  return i < 0 ? IMPORT_STEPS : IMPORT_STEPS.slice(i + 1);
}

// PARTIALLY_DELIVERED is deliberately not "arrived": stock lines went out while the imports are still in transit.
const GOODS_ARRIVED = new Set(['GOODS_RECEIVED', 'FULLY_DELIVERED', 'FROM_STOCK']);
export const goodsArrived = (fulfillmentStatus) => GOODS_ARRIVED.has(fulfillmentStatus);

/** W3-09: the warning for moving on from ขั้น 12 by hand (`no` = the target stage's number). */
export const goodsNotArrivedWarning = (no) => `สินค้าจากโรงงานยังมาไม่ครบ — เลื่อนไปขั้น ${no} ก่อนสินค้าถึงโกดังเฉพาะกรณีจำเป็น`;

// Deal rollups at or past goods-received: the deal-level fulfilment label already says it.
const PAST_IMPORT = new Set(['GOODS_RECEIVED', 'PARTIALLY_DELIVERED', 'FULLY_DELIVERED', 'FROM_STOCK']);

/**
 * The deal header's การนำเข้า chip text from the per-factory IR steps (QA-22): the SLOWEST live tracked
 * factory's step label, with " · ถึงโกดัง X/Y" appended when more than one factory is tracked. null means
 * "keep the deal-level fulfilment label" — no live tracked IR, or the rollup is already past import.
 */
export function importChipLabel(importRequests, fulfillmentStatus) {
  if (PAST_IMPORT.has(fulfillmentStatus)) return null;
  const tracked = (importRequests ?? []).filter((r) => r.status === 'ISSUED' && importStepIndex(r.importStep) >= 0);
  if (tracked.length === 0) return null;
  const slowest = tracked.reduce((a, b) => (importStepIndex(b.importStep) < importStepIndex(a.importStep) ? b : a));
  const label = importStepMeta(slowest.importStep).label;
  if (tracked.length === 1) return label;
  const received = tracked.filter((r) => r.importStep === 'RECEIVED').length;
  return received === tracked.length ? label : `${label} · ถึงโกดัง ${received}/${tracked.length}`;
}
