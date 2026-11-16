import { describe, it, expect } from 'vitest';
import {
  dayLineLabel, displayStatus, isOverdue, nextVisit, shortWeekday, timeLabel, visitCounts, weekDays, weekRangeLabel,
  weekStartIso,
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

  it('weekRangeLabel within and across a month, and with the year outside this year', () => {
    expect(weekRangeLabel('2027-01-04', '2026-11-12')).toBe('4–10 ม.ค. 2570');
    expect(weekRangeLabel('2026-12-28', '2026-11-12')).toBe('28 ธ.ค.–3 ม.ค. 2570');
    expect(weekRangeLabel('2026-11-16', '2026-11-12')).toBe('16–22 พ.ย.');
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

  it('dayLineLabel is the long Thai weekday, the day and the short month', () => {
    expect(dayLineLabel('2026-11-12')).toBe('พฤหัสบดี 12 พ.ย.');
    expect(dayLineLabel('2026-11-16')).toBe('จันทร์ 16 พ.ย.');
    expect(dayLineLabel('2026-11-15')).toBe('อาทิตย์ 15 พ.ย.');
  });
});

describe('nextVisit — the phone\'s นัดถัดไป (today first, else later this week, else none)', () => {
  const today = '2026-11-12';
  const v = (over) => ({ id: 1, status: 'PLANNED', plannedDate: today, plannedTime: null, ...over });

  it('picks the first unticked visit today, timed before untimed, then by id', () => {
    const visits = [
      v({ id: 1, plannedDate: '2026-11-10' }), // overdue — never "next"
      v({ id: 2, plannedTime: null }),
      v({ id: 3, plannedTime: '14:00:00' }),
      v({ id: 4, plannedTime: '09:30:00', status: 'DONE' }),
      v({ id: 5, plannedDate: '2026-11-13', plannedTime: '08:00:00' }),
    ];
    expect(nextVisit(visits, today)).toEqual({ visit: visits[2], isToday: true });
  });

  it('falls back to the next one later this week when nothing is left today', () => {
    const visits = [
      v({ id: 1, status: 'DONE' }),
      v({ id: 2, status: 'CANCELLED' }),
      v({ id: 7, plannedDate: '2026-11-14', plannedTime: '10:00:00' }),
      v({ id: 6, plannedDate: '2026-11-13' }),
    ];
    expect(nextVisit(visits, today)).toEqual({ visit: visits[3], isToday: false });
  });

  // Tie-break (VisitRepository ORDER BY planned_date, planned_time NULLS LAST, visit_id): two visits at the same time
  // on the same day go in id order, the lower id (booked first) being next — whatever order they arrive in.
  it('breaks a same-time tie by the lower id', () => {
    const visits = [v({ id: 9, plannedTime: '14:00:00' }), v({ id: 3, plannedTime: '14:00:00' })];
    expect(nextVisit(visits, today).visit.id).toBe(3);
    expect(nextVisit([...visits].reverse(), today).visit.id).toBe(3);
  });

  // The phone looks ahead across the week boundary (fix round 1 ruling 5): up to `lastIso` inclusive.
  it('looks ahead into next week, but not past the horizon', () => {
    const sunday = '2026-11-15';
    const monday = v({ id: 4, plannedDate: '2026-11-16', plannedTime: '09:00:00' });
    expect(nextVisit([monday], sunday, '2026-11-22')).toEqual({ visit: monday, isToday: false });
    expect(nextVisit([v({ id: 5, plannedDate: '2026-11-23' })], sunday, '2026-11-22')).toBeNull();
  });

  it('is null when nothing is planned from today on', () => {
    expect(nextVisit([v({ plannedDate: '2026-11-10' }), v({ id: 2, status: 'DONE' })], today)).toBeNull();
    expect(nextVisit(null, today)).toBeNull();
  });
});
