import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { REP_STEPS, repStepIndex } from './dealSteps.js';

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

  it('reads the mapping off the catalog the server served, not a local copy', () => {
    const served = {
      ...DEAL_STAGE_CATALOG,
      stages: DEAL_STAGE_CATALOG.stages.map((s) => (s.code === 'SPEC_APPROVED' ? { ...s, repCode: 'S3' } : s)),
    };
    expect(REP_STEPS[repStepIndex(served, 'SPEC_APPROVED')].code).toBe('S3');
  });
});
