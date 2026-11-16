// The deal page's S stepper (owner-approved drawing a-deal, 2026-11-16; spec 2026-11-15 §7.1): the reps' own
// workbook codes over the 15 pipeline stages. Display only — the stage itself, and every rule about moving it, stay
// on the server.
import { findStage } from './stageCatalog.js';

// S4 appears in no rep workbook, so it is not a step (spec §7.1). Who moves each step follows the spec's display
// table: เสนอสินค้า and สเปคผ่าน are the rep's own moves; from เสนอราคาแล้ว on, the deal follows its documents and
// operational events (a quotation, the order, the deposit, the IR, the delivery).
export const REP_STEPS = Object.freeze([
  { code: 'S1', label: 'อยู่ระหว่างเสนอสินค้า', mover: 'sales' },
  { code: 'S2', label: 'ผู้ออกแบบตกลงสเปค', mover: 'sales' },
  { code: 'S3', label: 'ทำใบเสนอราคาแล้ว', mover: 'auto' },
  { code: 'S5', label: 'รอผลประมูล / รอผู้ซื้อ', mover: 'auto' },
  { code: 'S6', label: 'ทราบผู้ซื้อแล้ว', mover: 'auto' },
  { code: 'S7', label: 'ได้รับมัดจำแล้ว', mover: 'auto' },
  { code: 'S8', label: 'รอส่งสินค้า', mover: 'auto' },
  { code: 'S9', label: 'ส่งสินค้าครบแล้ว', mover: 'auto' },
].map((step) => Object.freeze({ ...step, moverLabel: step.mover === 'sales' ? 'เซลส์ย้ายเอง' : 'อัตโนมัติ' })));

/**
 * Which REP_STEPS entry `salesStage` sits on, or -1. The stage→code mapping is the served catalog's `repCode`
 * (DealStageMetaController, from DealStage.REP_CODE) — never a copy kept here — so -1 also covers a catalog that
 * has not loaded yet.
 */
export function repStepIndex(catalog, salesStage) {
  const repCode = salesStage ? findStage(catalog, salesStage)?.repCode : null;
  return repCode ? REP_STEPS.findIndex((step) => step.code === repCode) : -1;
}
