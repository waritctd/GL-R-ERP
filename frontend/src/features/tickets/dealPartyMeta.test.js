import { describe, expect, it } from 'vitest';
import {
  childDealCode, isAwaitingBidOutcome, isBiddingParty, partyStatusLabel,
} from './dealPartyMeta.js';

describe('dealPartyMeta', () => {
  it('labels a NOT_AWARDED party with its reason', () => {
    expect(partyStatusLabel('NOT_AWARDED', 'OTHER_BIDDER').label).toBe('ไม่ได้งาน · ผู้รับเหมารายอื่นได้งาน');
    expect(partyStatusLabel('QUOTED')).toEqual({ label: 'เสนอราคาแล้ว', tone: 'neutral' });
    expect(partyStatusLabel('AWARDED').tone).toBe('success');
  });

  it('a bidding party is a BUYER in QUOTED / NEGOTIATING', () => {
    expect(isBiddingParty({ role: 'BUYER', status: 'NEGOTIATING' })).toBe(true);
    expect(isBiddingParty({ role: 'BUYER', status: 'ACTIVE' })).toBe(false);
    expect(isBiddingParty({ role: 'DESIGNER', status: 'QUOTED' })).toBe(false);
  });

  it('awaits an outcome only with two or more buyers and none awarded', () => {
    const b = (status) => ({ role: 'BUYER', status });
    expect(isAwaitingBidOutcome([b('QUOTED'), b('QUOTED')])).toBe(true);
    expect(isAwaitingBidOutcome([b('QUOTED')])).toBe(false);
    expect(isAwaitingBidOutcome([b('AWARDED'), b('QUOTED')])).toBe(false);
  });

  it('names child deals -A, -B', () => {
    expect(childDealCode('DL-2026-0280', 0)).toBe('DL-2026-0280-A');
    expect(childDealCode('DL-2026-0280', 1)).toBe('DL-2026-0280-B');
  });
});
