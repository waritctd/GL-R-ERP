import { describe, expect, it } from 'vitest';
import {
  ATTENDANCE_CALENDAR_TABS,
  DEFAULT_ATTENDANCE_CALENDAR_TAB_ID,
  resolveAttendanceCalendarTab,
} from './attendanceCalendarTabs.js';

describe('attendanceCalendarTabs', () => {
  it('declares the three tabs in the brief order: holidays, workSchedules, assignments', () => {
    expect(ATTENDANCE_CALENDAR_TABS.map((tab) => tab.id)).toEqual(['holidays', 'workSchedules', 'assignments']);
  });

  it('every tab has a Thai label and a helper', () => {
    for (const tab of ATTENDANCE_CALENDAR_TABS) {
      expect(tab.label).toBeTruthy();
      expect(tab.helper).toBeTruthy();
    }
  });

  it('defaults to the holidays tab', () => {
    expect(DEFAULT_ATTENDANCE_CALENDAR_TAB_ID).toBe('holidays');
  });

  it('resolves a known tab id to itself', () => {
    expect(resolveAttendanceCalendarTab('assignments')).toBe('assignments');
    expect(resolveAttendanceCalendarTab('workSchedules')).toBe('workSchedules');
  });

  it('falls back to the default for an absent, unknown, or stale tab id', () => {
    expect(resolveAttendanceCalendarTab(undefined)).toBe('holidays');
    expect(resolveAttendanceCalendarTab(null)).toBe('holidays');
    expect(resolveAttendanceCalendarTab('')).toBe('holidays');
    expect(resolveAttendanceCalendarTab('typo-tab')).toBe('holidays');
  });
});
