import { describe, expect, it } from 'vitest';
import { PEOPLE_FIXTURE as DATA } from './peopleInsightsFixture.js';
import { personRows, summarise } from './peopleInsightsAggregate.js';

// Person 3 is time-exempt and the fixture carries a stray attendance row for them: nothing of it may be counted.
describe('summarise', () => {
  it('sums one month of leave by type and attendance, leaving the time-exempt person out of every lateness figure', () => {
    const s = summarise(DATA, null, '2026-04');
    expect(s.leaveDays).toBe(14); // 11 + 1 + 2 (exempt people keep their leave)
    expect(s.byType).toEqual({ SICK: 11, PERSONAL: 1, VACATION: 2 });
    expect(s.sickEdge).toBe(5);
    expect(s.unpaid).toBe(1);
    expect(s.scanDays).toBe(10); // 5 + 5, not +5 from the exempt row
    expect(s.late).toBe(1);
    expect(s.lateMin).toBe(20);
    expect(s.noOut).toBe(1);
    expect(s.early).toBe(2);
  });

  it('sums quota usage by type over the year, independent of the month and of the leave rows', () => {
    expect(summarise(DATA, null, null).quotaUsed).toEqual({ SICK: 13, PERSONAL: 1, VACATION: 2 });
    expect(summarise(DATA, [2], null).quotaUsed).toEqual({ PERSONAL: 1 });
    expect(summarise({ ...DATA, quotaUsage: [{ employeeId: 2, leaveTypeCode: 'PERSONAL', days: 7 }] }, [2], '2026-04').byType).toEqual({ PERSONAL: 1 });
  });
});

describe('personRows', () => {
  const rows = personRows(DATA, null, null);
  const by = (id) => rows.find((r) => r.employeeId === id);

  it('weights the average check-in by morning scan days, the only scans the average is taken over', () => {
    expect(by(1).avgIn).toBeCloseTo((480 * 1 + 540 * 5) / 6);
    // month A: 1 morning scan at 08:00 among 20; month B: 10 morning scans at 09:00 -> about 08:55, not pulled toward 08:00.
    const data = { ...DATA, attendance: [
      { employeeId: 1, month: '2026-05', scanDays: 20, lateDays: 0, lateMinutes: 0, noCheckOut: 0, earlyLeave: 0, morningScanDays: 1, avgCheckInMinutes: 480 },
      { employeeId: 1, month: '2026-06', scanDays: 10, lateDays: 0, lateMinutes: 0, noCheckOut: 0, earlyLeave: 0, morningScanDays: 10, avgCheckInMinutes: 540 },
    ] };
    expect(personRows(data, [1], null)[0].avgIn).toBeCloseTo((480 + 5400) / 11);
    expect(by(1).late).toBe(3);
  });

  it('marks the time-exempt person and gives them no lateness, scans or average', () => {
    expect(by(3)).toMatchObject({ timeExempt: true, late: 0, scanDays: 0, lateMin: 0, avgIn: null, leaveDays: 2 });
  });
});
