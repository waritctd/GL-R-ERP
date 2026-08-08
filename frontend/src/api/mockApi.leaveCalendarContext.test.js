import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards mockApi.leave.calendarContext directly against the mock module (not through a UI test)
// -- see CLAUDE.md "Mock API contract": this is a SMALL FIXED FIXTURE (Mon-Fri, no real schedule
// resolution; `holidays` is sourced from the small fixed MOCK_HOLIDAY_DATES calendar, not the real
// hr.holiday store -- see the mock's own comment), so this test only pins the mock's OWN plumbing
// (date-range iteration, validation), never a claim about the real backend's
// TieredWorkScheduleResolver/HolidayCalendar behaviour -- that is
// LeaveCalendarContextIntegrationTest's job. None of the ranges below overlap MOCK_HOLIDAY_DATES,
// so `holidays` stays irrelevant to what this file pins (nonWorkingDates/validation only); the
// holiday-population behaviour itself is covered by OvertimePage.test.jsx's
// "OvertimePage holiday verdict" suite.
//
// Regression case: the first implementation built nonWorkingDates via
// `cursor.toISOString().slice(0, 10)`, which reports UTC, not local, dates. In a positive-UTC-offset
// environment (the app targets Asia/Bangkok, UTC+7) that silently shifts every reported date back
// by one day -- caught live in a browser check (selecting Sat 2026-08-08/Sun 2026-08-09 rendered
// "7 ส.ค., 8 ส.ค." instead), not by lint/build. This test pins the correct dates so that specific
// regression cannot come back silently.
describe('mockApi.leave.calendarContext', () => {
  it('reports the actual selected Saturday/Sunday, not a UTC-shifted date', async () => {
    await api.auth.login({ role: 'employee' });
    // 2026-08-03 is a Monday; 2026-08-08/09 are the Saturday/Sunday inside this range.
    const { calendarContext } = await api.leave.calendarContext({ from: '2026-08-03', to: '2026-08-09' });
    expect(calendarContext.nonWorkingDates).toEqual(['2026-08-08', '2026-08-09']);
  });

  it('a range with no weekend day reports no non-working dates', async () => {
    await api.auth.login({ role: 'employee' });
    // Mon 2026-08-03 .. Fri 2026-08-07: an ordinary Mon-Fri work week, no Sat/Sun inside it.
    const { calendarContext } = await api.leave.calendarContext({ from: '2026-08-03', to: '2026-08-07' });
    expect(calendarContext.nonWorkingDates).toEqual([]);
  });

  // The fixture-coverage guard. `UpcomingHolidays` shows a rolling ~90-day forward window, so a
  // MOCK_HOLIDAY_DATES whose entries all sit in Jan-May and December leaves that panel EMPTY for
  // most of the year. That is not a visible failure: the empty state is a legitimate render, so
  // the feature's headline surface looks "working" under VITE_USE_MOCKS=true while never once
  // constructing the state it exists to show. Found exactly that way -- the panel rendered its
  // empty state and was reported as expected behaviour.
  //
  // Asserting on a WINDOW rather than a specific date is deliberate: pinning `today + 90` would
  // make this test's own result depend on the day it runs. This asserts the property that matters
  // (Aug-Nov, previously a 7-month hole, is populated) without a moving reference point.
  it('populates holidays in the Aug-Nov window, so the upcoming-holidays panel is not empty there', async () => {
    await api.auth.login({ role: 'employee' });
    const { calendarContext } = await api.leave.calendarContext({ from: '2026-08-01', to: '2026-11-30' });
    expect(calendarContext.holidays.map((holiday) => holiday.holidayDate)).toEqual([
      '2026-08-12', '2026-10-13', '2026-10-23',
    ]);

    // The window must also contain at least one of production's genuinely LONG labels. Production's
    // 19 rows average 35 chars but reach 149, and four exceed 60 -- which is why V129 had to widen
    // name_th from VARCHAR(120) to TEXT after a real BOT response overflowed it. UpcomingHolidays
    // and the OT verdict badge both render this field, so a fixture of tidy 12-char names would let
    // a layout that cannot survive a real label pass every mock-mode check.
    // Asserting the LENGTH PROPERTY rather than the literal string on purpose: the exact wording is
    // BOT's to change, the "labels here are long" constraint is ours to keep.
    const augustHoliday = calendarContext.holidays[0];
    expect(augustHoliday.nameTh).toMatch(/^วันเฉลิมพระชนมพรรษาสมเด็จพระนางเจ้าสิริกิติ์/);
    expect(augustHoliday.nameTh.length).toBeGreaterThan(60);
  });

  it('rejects a missing from/to the same shape as the real endpoint', async () => {
    await api.auth.login({ role: 'employee' });
    await expect(api.leave.calendarContext({ to: '2026-08-09' })).rejects.toThrow();
    await expect(api.leave.calendarContext({ from: '2026-08-03' })).rejects.toThrow();
  });

  it('rejects to before from', async () => {
    await api.auth.login({ role: 'employee' });
    await expect(api.leave.calendarContext({ from: '2026-08-09', to: '2026-08-03' })).rejects.toThrow();
  });
});
