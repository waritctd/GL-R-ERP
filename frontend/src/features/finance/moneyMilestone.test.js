import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { MONEY_TRACK, isMilestoneSkipped, moneyMilestoneOf } from './moneyMilestone.js';

// Pins the mirror of th.co.glr.hr.ticket.MoneyMilestone (backend). The stage list comes from the
// guarded catalog fixture (which stageCatalog.test.js pins against DealStage.java), so a stage added
// to the backend forces a decision here instead of silently mapping to nothing.
describe('moneyMilestone (mirrors MoneyMilestone.java)', () => {
  it('the track is the five milestones, in order, with the backend labels', () => {
    expect(MONEY_TRACK.map((m) => [m.index, m.key, m.label])).toEqual([
      [1, 'ORDER_RECEIVED', 'ได้รับคำสั่งซื้อ'],
      [2, 'DEPOSIT_RECEIVED', 'ได้รับมัดจำ'],
      [3, 'PROCUREMENT', 'รอสินค้า / นำเข้า'],
      [4, 'DELIVERY', 'ส่งมอบ — รอชำระส่วนที่เหลือ'],
      [5, 'CLOSED_PAID', 'ชำระครบ ปิดงาน'],
    ]);
  });

  const EXPECTED = {
    LEAD_APPROACH: null,
    PRESENTATION: null,
    SPEC_APPROVED: null,
    QUOTE_DESIGN_SIDE: null,
    QUOTE_OWNER: null,
    OWNER_SIGNOFF: null,
    AWAITING_BUYER: null,
    QUOTE_BUYER: null,
    NEGOTIATION: null,
    ORDER_RECEIVED: 1,
    DEPOSIT_RECEIVED: 2,
    PROCUREMENT: 3,
    DELIVERY_SCHEDULING: 4,
    DELIVERED: 4,
    CLOSED_PAID: 5,
  };

  it('the expectation table covers every stage in the pipeline order (no stage left unmapped by omission)', () => {
    expect(Object.keys(EXPECTED)).toEqual(DEAL_STAGE_CATALOG.stages.map((s) => s.code));
  });

  it.each(Object.entries(EXPECTED))('%s -> milestone %s', (stage, index) => {
    const step = moneyMilestoneOf(stage);
    if (index == null) expect(step).toBeNull();
    else expect(step?.index).toBe(index);
  });

  it('an unknown or null stage has no milestone', () => {
    expect(moneyMilestoneOf('NOT_A_STAGE')).toBeNull();
    expect(moneyMilestoneOf(null)).toBeNull();
    expect(moneyMilestoneOf(undefined)).toBeNull();
  });

  it('only the deposit milestone can be skipped, and only for a bypassing policy', () => {
    const deposit = MONEY_TRACK[1];
    for (const policy of ['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER']) {
      expect(isMilestoneSkipped(deposit, policy)).toBe(true);
      expect(isMilestoneSkipped(MONEY_TRACK[0], policy)).toBe(false);
      expect(isMilestoneSkipped(MONEY_TRACK[2], policy)).toBe(false);
    }
    expect(isMilestoneSkipped(deposit, 'REQUIRED')).toBe(false);
    expect(isMilestoneSkipped(deposit, null)).toBe(false);
    expect(isMilestoneSkipped(null, 'WAIVED')).toBe(false);
  });
});
