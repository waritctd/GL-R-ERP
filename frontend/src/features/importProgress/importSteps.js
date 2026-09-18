// The six per-factory import steps (S12–S17). Mirrors the backend th.co.glr.hr.importprogress.
// ImportStep sequence — same codes, same order. Labels are ours (Thai, for display).
export const IMPORT_STEPS = [
  { code: 'IR_SENT', s: 'S12', label: 'ส่ง IR ให้จัดซื้อ' },
  { code: 'ORDERED', s: 'S13', label: 'สั่งซื้อผู้ผลิต' },
  { code: 'PICKED_UP', s: 'S14', label: 'ขนส่งรับของ' },
  { code: 'IN_TRANSIT', s: 'S15', label: 'กำลังเดินทาง' },
  { code: 'CUSTOMS_CLEARANCE', s: 'S16', label: 'ถึงไทย รอออกของ' },
  { code: 'RECEIVED', s: 'S17', label: 'ถึงโกดัง' },
];

export const IMPORT_STEP_CODES = IMPORT_STEPS.map((step) => step.code);

export function importStepIndex(code) {
  return IMPORT_STEP_CODES.indexOf(code);
}

export function importStepMeta(code) {
  return IMPORT_STEPS.find((step) => step.code === code) ?? null;
}

/** The next step after `code`, or null when already at the last step (RECEIVED). */
export function nextImportStep(code) {
  const i = importStepIndex(code);
  return i >= 0 && i < IMPORT_STEPS.length - 1 ? IMPORT_STEPS[i + 1] : null;
}
