import { describe, expect, it } from 'vitest';
import {
  defaultRange,
  isDue,
  isOverdue,
  isNoMovement,
  lastMovementIso,
  rangeFromParams,
  setRangeEdge,
  weekPredicates,
  shiftRange,
  summariseByOwner,
} from './movementRange.js';

const TODAY = '2026-11-13';
const active = (extra = {}) => ({ lifecycle: 'ACTIVE', stageChangedInRange: false, activityInRange: false, ...extra });

describe('movementRange: range arithmetic', () => {
  it('defaults to today-6 .. today (7 days)', () => {
    expect(defaultRange(TODAY)).toEqual({ from: '2026-11-07', to: '2026-11-13' });
    expect(defaultRange('2026-03-02')).toEqual({ from: '2026-02-24', to: '2026-03-02' });
  });

  it('shift moves both dates and keeps the length, in either direction and across a month edge', () => {
    expect(shiftRange({ from: '2026-11-06', to: '2026-11-12' }, 1)).toEqual({ from: '2026-11-07', to: '2026-11-13' });
    expect(shiftRange({ from: '2026-11-06', to: '2026-11-12' }, -1)).toEqual({ from: '2026-11-05', to: '2026-11-11' });
    expect(shiftRange({ from: '2026-10-30', to: '2026-11-01' }, 1)).toEqual({ from: '2026-10-31', to: '2026-11-02' });
  });

  it('editing so from > to snaps the other date to the edited one', () => {
    const range = { from: '2026-11-07', to: '2026-11-13' };
    expect(setRangeEdge(range, 'from', '2026-11-20')).toEqual({ from: '2026-11-20', to: '2026-11-20' });
    expect(setRangeEdge(range, 'to', '2026-11-01')).toEqual({ from: '2026-11-01', to: '2026-11-01' });
  });

  it('a valid edit changes only the edited edge', () => {
    const range = { from: '2026-11-07', to: '2026-11-13' };
    expect(setRangeEdge(range, 'from', '2026-11-09')).toEqual({ from: '2026-11-09', to: '2026-11-13' });
    expect(setRangeEdge(range, 'to', '2026-11-07')).toEqual({ from: '2026-11-07', to: '2026-11-07' });
  });
});

describe('movementRange: rangeFromParams', () => {
  it('uses valid from/to, falls back to the default when absent', () => {
    expect(rangeFromParams('2026-11-06', '2026-11-12', TODAY)).toEqual({ from: '2026-11-06', to: '2026-11-12' });
    expect(rangeFromParams(null, null, TODAY)).toEqual({ from: '2026-11-07', to: '2026-11-13' });
  });

  it('rejects impossible calendar dates and from > to: the whole default range, not a repaired one', () => {
    const def = { from: '2026-11-07', to: '2026-11-13' };
    expect(rangeFromParams('2026-02-30', '2026-03-01', TODAY)).toEqual(def);
    expect(rangeFromParams('2026-11-01', '2026-13-01', TODAY)).toEqual(def);
    expect(rangeFromParams('2026-11-12', '2026-11-06', TODAY)).toEqual(def);
    expect(rangeFromParams('garbage', '2026-11-06', TODAY)).toEqual(def);
  });
});

describe('movementRange: deal rules', () => {
  const range = { from: '2026-11-07', to: '2026-11-13' };

  it('due = nextFollowUpAt inside the range', () => {
    expect(isDue(active({ nextFollowUpAt: '2026-11-07' }), range, TODAY)).toBe(true);
    expect(isDue(active({ nextFollowUpAt: '2026-11-13' }), range, TODAY)).toBe(true);
    expect(isDue(active({ nextFollowUpAt: '2026-11-14' }), range, TODAY)).toBe(false);
    expect(isDue(active({ nextFollowUpAt: null }), range, TODAY)).toBe(false);
  });

  it('overdue (before today) counts as due only when the range includes today', () => {
    const overdue = active({ nextFollowUpAt: '2026-10-01' });
    expect(isDue(overdue, range, TODAY)).toBe(true);
    // a past range that does not include today: the overdue date is neither in range nor counted as overdue
    const past = { from: '2026-10-20', to: '2026-10-26' };
    expect(isDue(overdue, past, TODAY)).toBe(false);
    // an overdue date inside a past range still counts because it is in range
    expect(isDue(active({ nextFollowUpAt: '2026-10-22' }), past, TODAY)).toBe(true);
  });

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

  it('every week predicate considers ACTIVE deals only (a paused deal is never moved / active / due / overdue / silent)', () => {
    const paused = active({
      lifecycle: 'ON_HOLD', stageChangedInRange: true, activityInRange: true, nextFollowUpAt: '2026-11-01',
    });
    for (const [key, fn] of Object.entries(weekPredicates)) expect(fn(paused, range, TODAY), key).toBe(false);
    const live = { ...paused, lifecycle: 'ACTIVE' };
    expect(weekPredicates.moved(live, range, TODAY)).toBe(true);
    expect(weekPredicates.activity(live, range, TODAY)).toBe(true);
  });

  it('last movement date is the later of stageUpdatedAt and lastActivityAt', () => {
    expect(lastMovementIso({ stageUpdatedAt: '2026-11-01T03:00:00.000Z', lastActivityAt: '2026-11-05' })).toBe('2026-11-05');
    expect(lastMovementIso({ stageUpdatedAt: '2026-11-09T03:00:00.000Z', lastActivityAt: '2026-11-05' })).toBe('2026-11-09');
    expect(lastMovementIso({ stageUpdatedAt: '2026-11-09T03:00:00.000Z', lastActivityAt: null })).toBe('2026-11-09');
  });

  it('last movement reads the stage timestamp as a Bangkok day (17:30Z is already tomorrow in Bangkok)', () => {
    expect(lastMovementIso({ stageUpdatedAt: '2026-11-08T17:30:00.000Z', lastActivityAt: null })).toBe('2026-11-09');
  });
});

describe('movementRange: summariseByOwner', () => {
  const range = { from: '2026-11-07', to: '2026-11-13' };
  const isOrdered = (d) => d.salesStage === 'ORDER_RECEIVED';
  const d = (ownerId, ownerName, extra) => active({
    createdById: ownerId, createdByName: ownerName, amountPayable: 10, salesStage: 'NEGOTIATION', ...extra,
  });

  const deals = [
    d(11, 'เอ', { stageChangedInRange: true, amountPayable: 100, salesStage: 'ORDER_RECEIVED' }),
    d(11, 'เอ', { activityInRange: true, amountPayable: 50 }),
    d(11, 'เอ', { amountPayable: 25, nextFollowUpAt: '2026-11-01' }),
    d(11, 'เอ', { lifecycle: 'CLOSED_LOST', amountPayable: 999 }),
    d(12, 'บี', { amountPayable: 10 }),
    d(12, 'บี', { amountPayable: 10 }),
    d(12, 'บี', { stageChangedInRange: true, activityInRange: true, amountPayable: 10 }),
    d(13, 'ซี', { lifecycle: 'CLOSED_LOST' }),
  ];

  it('counts and sums per owner over ACTIVE deals, sorted by ไม่เคลื่อนไหว desc', () => {
    expect(summariseByOwner(deals, range, TODAY, isOrdered)).toEqual([
      { ownerId: 12, ownerName: 'บี', open: 3, moved: 1, activity: 1, overdue: 0, silent: 2, won: 0, expected: 30 },
      { ownerId: 11, ownerName: 'เอ', open: 3, moved: 1, activity: 1, overdue: 1, silent: 1, won: 100, expected: 75 },
    ]);
  });

  it('omits an owner with zero open deals', () => {
    expect(summariseByOwner(deals, range, TODAY, isOrdered).map((r) => r.ownerId)).not.toContain(13);
  });

  it('breaks ties on ไม่เคลื่อนไหว by owner name', () => {
    const tied = [d(1, 'ข', { activityInRange: true }), d(2, 'ก', { activityInRange: true })];
    expect(summariseByOwner(tied, range, TODAY, isOrdered).map((r) => r.ownerName)).toEqual(['ก', 'ข']);
  });

  it('returns no rows for no deals', () => {
    expect(summariseByOwner([], range, TODAY, isOrdered)).toEqual([]);
  });
});
