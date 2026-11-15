import { describe, it, expect } from 'vitest';
import {
  displayStatus, isOverdue, shortWeekday, timeLabel, visitCounts, weekDays, weekRangeLabel, weekStartIso,
} from './visitWeek.js';

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

  it('weekRangeLabel within and across a month', () => {
    expect(weekRangeLabel('2026-11-09')).toBe('9–15 พ.ย.');
    expect(weekRangeLabel('2026-11-30')).toBe('30 พ.ย.–6 ธ.ค.');
  });

  it('overdue = PLANNED and dated before today only', () => {
    const today = '2026-11-12';
    expect(isOverdue({ status: 'PLANNED', plannedDate: '2026-11-11' }, today)).toBe(true);
    expect(isOverdue({ status: 'PLANNED', plannedDate: today }, today)).toBe(false);
    expect(isOverdue({ status: 'DONE', plannedDate: '2026-11-11' }, today)).toBe(false);
    expect(displayStatus({ status: 'CANCELLED', plannedDate: '2026-11-11' }, today)).toBe('CANCELLED');
    expect(displayStatus({ status: 'PLANNED', plannedDate: '2026-11-11' }, today)).toBe('OVERDUE');
  });

  it('weekDays of another week marks no day as today', () => {
    expect(weekDays('2026-11-16', '2026-11-12').some((d) => d.isToday)).toBe(false);
  });
});
