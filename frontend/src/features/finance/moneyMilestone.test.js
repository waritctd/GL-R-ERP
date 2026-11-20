import { describe, expect, it } from 'vitest';
import { MONEY_TRACK, isMilestoneSkipped, moneyMilestoneOf } from './moneyMilestone.js';

// Pins the mirror of th.co.glr.hr.ticket.MoneyMilestone (backend). The stage list comes from the guarded
// catalog fixture (which stageCatalog.test.js pins against DealStage.java), so a stage added to the backend
// forces a decision here instead of silently mapping to nothing.
describe('moneyMilestone (mirrors MoneyMilestone.java)', () => {
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

  it.each(Object.entries(EXPECTED))('%s -> milestone %s', (stage, index) => {
    const step = moneyMilestoneOf(stage);
    if (index == null) expect(step).toBeNull();
    else expect(step?.index).toBe(index);
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
