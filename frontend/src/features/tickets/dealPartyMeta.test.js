import { describe, expect, it } from 'vitest';
import {
  isAwaitingBidOutcome,
} from './dealPartyMeta.js';

describe('dealPartyMeta', () => {
  it('awaits an outcome only with two or more buyers and none awarded', () => {
    const b = (status) => ({ role: 'BUYER', status });
    expect(isAwaitingBidOutcome([b('QUOTED'), b('QUOTED')])).toBe(true);
    expect(isAwaitingBidOutcome([b('QUOTED')])).toBe(false);
    expect(isAwaitingBidOutcome([b('AWARDED'), b('QUOTED')])).toBe(false);
  });
});
