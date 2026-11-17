import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards mockApi.leave.calendarContext directly against the mock module. This test pins the correct dates so
// that specific regression cannot come back silently. Regression case: the first implementation built
// nonWorkingDates via `cursor.toISOString().slice(0, 10)`, which reports UTC, not local, dates.
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

  // The fixture-coverage guard. That is not a visible failure: the empty state is a legitimate render, so the
  // feature's headline surface looks "working" under VITE_USE_MOCKS=true while never once constructing the state
  // it exists to show.
  it('populates holidays in the Aug-Nov window, so the upcoming-holidays panel is not empty there', async () => {
    await api.auth.login({ role: 'employee' });
    const { calendarContext } = await api.leave.calendarContext({ from: '2026-08-01', to: '2026-11-30' });
    expect(calendarContext.holidays.map((holiday) => holiday.holidayDate)).toEqual([
      '2026-08-12', '2026-10-13', '2026-10-23',
    ]);

    // The window must also contain at least one of production's genuinely LONG labels. UpcomingHolidays and the OT
    // verdict badge both render this field, so a fixture of tidy 12-char names would let a layout that cannot
    // survive a real label pass every mock-mode check.
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
