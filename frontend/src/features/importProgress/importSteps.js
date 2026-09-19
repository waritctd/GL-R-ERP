// The six per-factory import steps (S12-S17, GLA-100/105). Mirrors
// backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestStep.java exactly — same codes,
// same order, same Thai labels (defined ONCE there and copied here, not derived from a shared
// source, since the frontend has no build-time access to the Java constants; drift has no guard —
// see CLAUDE.md's mock-constant-drift memory).
//
// Deliberately NOT Yang's original set (origin/feat/per-factory-import-tracking, 83f4fa78):
// that branch's `IR_SENT` collides in meaning with the deal-level `FulfilmentStatus.IR_SENT` /
// `TicketEventKind.IR_SENT`, and its `CUSTOMS_CLEARANCE` was a constant a prior commit (7991b9f1)
// deleted. PR-A (#1008) settled on the owner's own S12-S17 sheet instead — this file ports Yang's
// UI shape (FactoryProgressBar, the step list) onto THESE codes.
export const IMPORT_STEPS = [
  { code: 'CONTACTED', s: 'S12', label: 'ติดต่อโรงงาน' },
  { code: 'ORDERED', s: 'S13', label: 'สั่งซื้อแล้ว' },
  { code: 'PICKED_UP', s: 'S14', label: 'รับสินค้าจากโรงงาน' },
  { code: 'IN_TRANSIT', s: 'S15', label: 'ระหว่างขนส่ง' },
  { code: 'AWAITING_CUSTOMS', s: 'S16', label: 'รอผ่านพิธีการศุลกากร' },
  { code: 'RECEIVED', s: 'S17', label: 'รับสินค้าเข้าคลัง' },
];

export const IMPORT_STEP_CODES = IMPORT_STEPS.map((step) => step.code);

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

/** Every step strictly ahead of `code` — advanceStep allows a forward SKIP, not just the next one
 * (owner decision 4: "forward skips allowed, never backwards — correct via revision"), so a
 * caller picking a target needs the whole remaining list, not just nextImportStep's single row. */
export function stepsAhead(code) {
  const i = importStepIndex(code);
  return i < 0 ? IMPORT_STEPS : IMPORT_STEPS.slice(i + 1);
}
