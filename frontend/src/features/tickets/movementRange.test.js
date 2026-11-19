import { describe, expect, it } from 'vitest';
import {
  isOverdue,
  isNoMovement,
  lastMovementIso,
  rangeFromParams,
  
  shiftRange,
} from './movementRange.js';

const TODAY = '2026-11-13';
const active = (extra = {}) => ({ lifecycle: 'ACTIVE', stageChangedInRange: false, activityInRange: false, ...extra });

describe('movementRange: range arithmetic', () => {
  it('shift moves both dates and keeps the length, in either direction and across a month edge', () => {
    expect(shiftRange({ from: '2026-11-06', to: '2026-11-12' }, 1)).toEqual({ from: '2026-11-07', to: '2026-11-13' });
    expect(shiftRange({ from: '2026-11-06', to: '2026-11-12' }, -1)).toEqual({ from: '2026-11-05', to: '2026-11-11' });
    expect(shiftRange({ from: '2026-10-30', to: '2026-11-01' }, 1)).toEqual({ from: '2026-10-31', to: '2026-11-02' });
  });
});

describe('movementRange: rangeFromParams', () => {
  it('rejects impossible calendar dates and from > to: the whole default range, not a repaired one', () => {
    const def = { from: '2026-11-07', to: '2026-11-13' };
    expect(rangeFromParams('2026-02-30', '2026-03-01', TODAY)).toEqual(def);
    expect(rangeFromParams('2026-11-01', '2026-13-01', TODAY)).toEqual(def);
    expect(rangeFromParams('2026-11-12', '2026-11-06', TODAY)).toEqual(def);
    expect(rangeFromParams('garbage', '2026-11-06', TODAY)).toEqual(def);
  });
});

describe('movementRange: deal rules', () => {
  it('overdue = ACTIVE with a follow-up before today (today itself and closed deals are not overdue)', () => {
    expect(isOverdue(active({ nextFollowUpAt: '2026-11-12' }), TODAY)).toBe(true);
    expect(isOverdue(active({ nextFollowUpAt: TODAY }), TODAY)).toBe(false);
    expect(isOverdue(active({ nextFollowUpAt: null }), TODAY)).toBe(false);
    expect(isOverdue(active({ nextFollowUpAt: '2026-11-01', lifecycle: 'CLOSED_LOST' }), TODAY)).toBe(false);
  });

  it('no movement = ACTIVE with neither a stage change nor an activity in range; non-ACTIVE deals are ignored', () => {
    expect(isNoMovement(active())).toBe(true);
    expect(isNoMovement(active({ stageChangedInRange: true }))).toBe(false);
    expect(isNoMovement(active({ activityInRange: true }))).toBe(false);
    for (const lifecycle of ['CLOSED_LOST', 'CANCELLED', 'ON_HOLD', 'DORMANT', 'COMPLETED']) {
      expect(isNoMovement(active({ lifecycle }))).toBe(false);
    }
  });

  it('last movement reads the stage timestamp as a Bangkok day (17:30Z is already tomorrow in Bangkok)', () => {
    expect(lastMovementIso({ stageUpdatedAt: '2026-11-08T17:30:00.000Z', lastActivityAt: null })).toBe('2026-11-09');
  });
});
