import { describe, expect, it } from 'vitest';
import { nextMondays, presetAmount, snapToMonday, weekEndLabel } from './cashFormat.js';

describe('cashFormat', () => {
  it('labels a weekly bar by its last day inside the month ("ถึง 13 พ.ย."), capped at month end', () => {
    expect(weekEndLabel('2026-11-09', '2026-11')).toBe('ถึง 13 พ.ย.');
    // ISO rule (review M6): the week of Mon 30 Nov is December's, labelled by its own Friday.
    expect(weekEndLabel('2026-11-30', '2026-12')).toBe('ถึง 4 ธ.ค.');
    // The month's last week also holds the receipts of the days after its Friday: labelled to the month end.
    expect(weekEndLabel('2026-11-23', '2026-11', true)).toBe('ถึง 30 พ.ย.');
    expect(weekEndLabel('2026-11-23', '2026-11', false)).toBe('ถึง 27 พ.ย.');
  });

  it('snaps any day to its ISO-week Monday and offers this week plus the next three', () => {
    expect(snapToMonday('2026-11-19')).toBe('2026-11-16');
    expect(snapToMonday('2026-11-22')).toBe('2026-11-16'); // Sunday belongs to the week that began Monday
    expect(snapToMonday('2026-11-16')).toBe('2026-11-16');
    expect(nextMondays('2026-11-19', 4)).toEqual(['2026-11-16', '2026-11-23', '2026-11-30', '2026-12-07']);
  });

  it('presets: a percent of the deal value, or what is left after the rows already planned', () => {
    expect(presetAmount({ preset: 30, dealValue: 1240000, planned: 0 })).toBe(372000);
    expect(presetAmount({ preset: 50, dealValue: 912300, planned: 0 })).toBe(456150);
    expect(presetAmount({ preset: 'rest', dealValue: 912300, planned: 456150 })).toBe(456150);
    expect(presetAmount({ preset: 'rest', dealValue: 100, planned: 150 })).toBe(0);
    expect(presetAmount({ preset: 30, dealValue: null, planned: 0 })).toBeNull();
    // Rounded to satang, never more than two decimals.
    expect(presetAmount({ preset: 30, dealValue: 1000.05, planned: 0 })).toBe(300.02);
  });
});
