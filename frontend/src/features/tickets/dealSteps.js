// The deal page's S stepper (owner-approved drawing a-deal, 2026-11-16; spec 2026-11-15 §7.1): the reps' own
// workbook codes over the 15 pipeline stages. Display only — the stage itself, and every rule about moving it, stay
// on the server.
import { findStage } from './stageCatalog.js';

// S4 appears in no rep workbook, so it is not a step (spec §7.1).
//
// Who moves each step is DealStage.java's own fact about the stage the step is entered at (fix round 1 — the
// drawing said "อัตโนมัติ" from S3 on, which the backend contradicts):
//   - "ย้ายตามเอกสาร" (document): the entry stage is in DealStage.AUTO_ADVANCED — S3 QUOTE_DESIGN_SIDE / QUOTE_OWNER
//     ("advanceStageForCustomerQuotationIssue … by recipient"), S6 QUOTE_BUYER (same) and ORDER_RECEIVED
//     ("confirmCustomer"), S7 DEPOSIT_RECEIVED ("reconcilePaymentStatus") and PROCUREMENT ("issueImportRequest").
//   - "เซลส์ย้ายเอง" (sales): everything else. S1/S2 are the rep's picks; S5 AWAITING_BUYER is not in AUTO_ADVANCED
//     and nothing auto-advances into it; S8 DELIVERY_SCHEDULING and S9 DELIVERED are "deliberately absent … the rep
//     also reaches both by hand … presenting them as 'the system does this for you' would be wrong".
export const MOVER_LABEL = Object.freeze({ sales: 'เซลส์ย้ายเอง', document: 'ย้ายตามเอกสาร' });
export const REP_STEPS = Object.freeze([
  { code: 'S1', label: 'อยู่ระหว่างเสนอสินค้า', mover: 'sales' },
  { code: 'S2', label: 'ผู้ออกแบบตกลงสเปค', mover: 'sales' },
  { code: 'S3', label: 'ทำใบเสนอราคาแล้ว', mover: 'document' },
  { code: 'S5', label: 'รอผลประมูล / รอผู้ซื้อ', mover: 'sales' },
  { code: 'S6', label: 'ทราบผู้ซื้อแล้ว', mover: 'document' },
  { code: 'S7', label: 'ได้รับมัดจำแล้ว', mover: 'document' },
  { code: 'S8', label: 'รอส่งสินค้า', mover: 'sales' },
  { code: 'S9', label: 'ส่งสินค้าครบแล้ว', mover: 'sales' },
].map((step) => Object.freeze({ ...step, moverLabel: MOVER_LABEL[step.mover] })));

/**
 * Which REP_STEPS entry `salesStage` sits on, or -1. The stage→code mapping is the served catalog's `repCode`
 * (DealStageMetaController, from DealStage.REP_CODE) — never a copy kept here — so -1 also covers a catalog that
 * has not loaded yet.
 */
export function repStepIndex(catalog, salesStage) {
  const repCode = salesStage ? findStage(catalog, salesStage)?.repCode : null;
  return repCode ? REP_STEPS.findIndex((step) => step.code === repCode) : -1;
}
