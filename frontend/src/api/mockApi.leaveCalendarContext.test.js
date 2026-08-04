import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards mockApi.leave.calendarContext directly against the mock module (not through a UI test)
// -- see CLAUDE.md "Mock API contract": this is a SMALL FIXED FIXTURE (Mon-Fri, no real schedule
// resolution, always-empty holidays -- see the mock's own comment), so this test only pins the
// mock's OWN plumbing (date-range iteration, validation), never a claim about the real backend's
// TieredWorkScheduleResolver/HolidayCalendar behaviour -- that is
// LeaveCalendarContextIntegrationTest's job.
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
