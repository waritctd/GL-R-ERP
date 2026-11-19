import { describe, it, expect } from 'vitest';
import { weekStartIso } from './visitWeek.js';

describe('visitWeek', () => {
  it('weekStartIso is the ISO Monday; Sunday belongs to the week before (VisitService.weekStart)', () => {
    expect(weekStartIso('2026-11-09')).toBe('2026-11-09');
    expect(weekStartIso('2026-11-13')).toBe('2026-11-09');
    expect(weekStartIso('2026-11-15')).toBe('2026-11-09');
    expect(weekStartIso('2026-11-16')).toBe('2026-11-16');
    expect(weekStartIso('2027-01-01')).toBe('2026-12-28');
  });
});
