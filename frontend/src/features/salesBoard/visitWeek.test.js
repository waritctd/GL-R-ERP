import { describe, it, expect } from 'vitest';
import { shortWeekday, timeLabel, visitCounts, weekDays, weekStartIso } from './visitWeek.js';

describe('visitWeek', () => {
  it('weekStartIso is the ISO Monday; Sunday belongs to the week before (VisitService.weekStart)', () => {
    expect(weekStartIso('2026-11-09')).toBe('2026-11-09');
    expect(weekStartIso('2026-11-13')).toBe('2026-11-09');
    expect(weekStartIso('2026-11-15')).toBe('2026-11-09');
    expect(weekStartIso('2026-11-16')).toBe('2026-11-16');
    expect(weekStartIso('2027-01-01')).toBe('2026-12-28');
  });

  it('weekDays lists Mon–Sun with Thai short names and marks today', () => {
    const days = weekDays('2026-11-13');
    expect(days.map((d) => d.short)).toEqual(['จ.', 'อ.', 'พ.', 'พฤ.', 'ศ.', 'ส.', 'อา.']);
    expect(days[0]).toEqual({ iso: '2026-11-09', short: 'จ.', dayOfMonth: 9, isToday: false });
    expect(days.filter((d) => d.isToday).map((d) => d.iso)).toEqual(['2026-11-13']);
    expect(shortWeekday('2026-11-12')).toBe('พฤ.');
    expect(shortWeekday('2026-11-13')).toBe('ศ.');
  });

  it('visitCounts: done of every non-cancelled visit', () => {
    expect(visitCounts([
      { status: 'DONE' }, { status: 'DONE' }, { status: 'PLANNED' }, { status: 'CANCELLED' },
    ])).toEqual({ done: 2, planned: 3 });
    expect(visitCounts(undefined)).toEqual({ done: 0, planned: 0 });
  });

  it('timeLabel trims seconds and is empty when unset', () => {
    expect(timeLabel('10:00:00')).toBe('10:00');
    expect(timeLabel('14:00')).toBe('14:00');
    expect(timeLabel(null)).toBe('');
  });
});
