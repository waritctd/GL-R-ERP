import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { EMPTY_STAGE_CATALOG } from './stageCatalog.js';
import { REP_STEPS, repStepIndex } from './dealSteps.js';

// The deal page's S stepper (drawing a-deal, spec 2026-11-15 §7.1): the reps' own workbook codes, S4 left out.
describe('REP_STEPS', () => {
  it('is the eight drawn steps, in order, with the drawn Thai labels', () => {
    expect(REP_STEPS.map((s) => [s.code, s.label])).toEqual([
      ['S1', 'อยู่ระหว่างเสนอสินค้า'],
      ['S2', 'ผู้ออกแบบตกลงสเปค'],
      ['S3', 'ทำใบเสนอราคาแล้ว'],
      ['S5', 'รอผลประมูล / รอผู้ซื้อ'],
      ['S6', 'ทราบผู้ซื้อแล้ว'],
      ['S7', 'ได้รับมัดจำแล้ว'],
      ['S8', 'รอส่งสินค้า'],
      ['S9', 'ส่งสินค้าครบแล้ว'],
    ]);
  });

  it('S4 is not a step (it appears in no rep workbook)', () => {
    expect(REP_STEPS.some((s) => s.code === 'S4')).toBe(false);
  });

  it('S1 and S2 are moved by the rep; S3 onward follow documents and events (spec §7.1 "Who moves it")', () => {
    expect(REP_STEPS.map((s) => s.mover)).toEqual(['sales', 'sales', 'auto', 'auto', 'auto', 'auto', 'auto', 'auto']);
    expect(REP_STEPS.map((s) => s.moverLabel)).toEqual([
      'เซลส์ย้ายเอง', 'เซลส์ย้ายเอง', 'อัตโนมัติ', 'อัตโนมัติ', 'อัตโนมัติ', 'อัตโนมัติ', 'อัตโนมัติ', 'อัตโนมัติ',
    ]);
  });
});

describe('repStepIndex — every one of the 15 stages lands on its S step (the served catalog repCode)', () => {
  const expected = {
    LEAD_APPROACH: 'S1',
    PRESENTATION: 'S1',
    SPEC_APPROVED: 'S2',
    QUOTE_DESIGN_SIDE: 'S3',
    QUOTE_OWNER: 'S3',
    OWNER_SIGNOFF: 'S3',
    AWAITING_BUYER: 'S5',
    QUOTE_BUYER: 'S6',
    NEGOTIATION: 'S6',
    ORDER_RECEIVED: 'S6',
    DEPOSIT_RECEIVED: 'S7',
    PROCUREMENT: 'S7',
    DELIVERY_SCHEDULING: 'S8',
    DELIVERED: 'S9',
    CLOSED_PAID: 'S9',
  };

  it('covers all 15 catalog stages', () => {
    expect(Object.keys(expected).sort()).toEqual(DEAL_STAGE_CATALOG.stages.map((s) => s.code).sort());
  });

  it.each(Object.entries(expected))('%s → %s', (stage, code) => {
    const index = repStepIndex(DEAL_STAGE_CATALOG, stage);
    expect(index).toBeGreaterThanOrEqual(0);
    expect(REP_STEPS[index].code).toBe(code);
  });

  it('an unknown stage, a null stage, or a catalog that has not loaded yet highlights nothing', () => {
    expect(repStepIndex(DEAL_STAGE_CATALOG, 'NOT_A_STAGE')).toBe(-1);
    expect(repStepIndex(DEAL_STAGE_CATALOG, null)).toBe(-1);
    expect(repStepIndex(EMPTY_STAGE_CATALOG, 'SPEC_APPROVED')).toBe(-1);
  });

  it('reads the mapping off the catalog the server served, not a local copy', () => {
    const served = {
      ...DEAL_STAGE_CATALOG,
      stages: DEAL_STAGE_CATALOG.stages.map((s) => (s.code === 'SPEC_APPROVED' ? { ...s, repCode: 'S3' } : s)),
    };
    expect(REP_STEPS[repStepIndex(served, 'SPEC_APPROVED')].code).toBe('S3');
  });
});
