import { afterEach, describe, expect, it } from 'vitest';
import { attendanceSourceLabel, formatAddress, formatMoney, formatShortDate, formatThaiMonthYearFromMonthInputValue } from './format.js';

describe('formatAddress', () => {
  it('joins all four parts, not just line1', () => {
    expect(formatAddress({
      line1: '18/9 ซอย 9 ถ.สุขุมวิท',
      district: 'บางนา',
      province: 'กรุงเทพมหานคร',
      postalCode: '10111',
    })).toBe('18/9 ซอย 9 ถ.สุขุมวิท บางนา กรุงเทพมหานคร 10111');
  });

  // mockApi.createEmployee seeds district/province/postalCode as '' — joining
  // blindly would produce "18/9 ถ.สุขุมวิท   " with trailing separators.
  it('drops empty parts instead of leaving stray separators', () => {
    expect(formatAddress({ line1: '18/9 ถ.สุขุมวิท', district: '', province: '', postalCode: '' }))
      .toBe('18/9 ถ.สุขุมวิท');
  });

  it('trims whitespace-only parts', () => {
    expect(formatAddress({ line1: '18/9', district: '   ', province: 'นนทบุรี', postalCode: null }))
      .toBe('18/9 นนทบุรี');
  });

  it('falls back to a dash when there is no address at all', () => {
    expect(formatAddress(null)).toBe('-');
    expect(formatAddress(undefined)).toBe('-');
    expect(formatAddress({})).toBe('-');
    expect(formatAddress({ line1: '', district: '', province: '', postalCode: '' })).toBe('-');
  });
});

describe('attendanceSourceLabel', () => {
  it('maps each site_code to its display label', () => {
    expect(attendanceSourceLabel({ site_code: 'SHOWROOM' })).toBe('Showroom');
    expect(attendanceSourceLabel({ site_code: 'WAREHOUSE' })).toBe('Warehouse');
    expect(attendanceSourceLabel({ site_code: 'WFH' })).toBe('WFH');
  });

  it('returns null for a day with no record so the caller can render a dash', () => {
    expect(attendanceSourceLabel({ site_code: null })).toBeNull();
    expect(attendanceSourceLabel({})).toBeNull();
    expect(attendanceSourceLabel(null)).toBeNull();
  });

  it('falls through to the raw code for an unknown site', () => {
    expect(attendanceSourceLabel({ site_code: 'FACTORY' })).toBe('FACTORY');
  });
});

describe('formatMoney', () => {
  it('always renders Thai baht with exactly two fraction digits', () => {
    expect(formatMoney(180000)).toBe('฿180,000.00');
    expect(formatMoney(786.5)).toBe('฿786.50');
    expect(formatMoney(73002.08)).toBe('฿73,002.08');
  });

  it('rounds display to satang instead of leaking arbitrary precision', () => {
    expect(formatMoney(17624.9994)).toBe('฿17,625.00');
  });

  it('falls back to a dash for empty or non-finite input', () => {
    expect(formatMoney(null)).toBe('-');
    expect(formatMoney(undefined)).toBe('-');
    expect(formatMoney('')).toBe('-');
    expect(formatMoney('not money')).toBe('-');
    expect(formatMoney(Number.NaN)).toBe('-');
  });
});

// F5: this function had no test coverage at all before this fix. It is NOT the same
// `formatThaiMonthYear` exported by `features/specialmoney/specialMoneyRules.js` (that one takes a
// `Date` and returns a 2-digit BE year) — see the rename/collision note on the export in format.js.
describe('formatThaiMonthYearFromMonthInputValue', () => {
  it('formats a valid "YYYY-MM" month-input value with a 4-digit Buddhist-era year', () => {
    expect(formatThaiMonthYearFromMonthInputValue('2026-07')).toBe('ก.ค. 2569');
    expect(formatThaiMonthYearFromMonthInputValue('2026-01')).toBe('ม.ค. 2569');
    expect(formatThaiMonthYearFromMonthInputValue('2026-12')).toBe('ธ.ค. 2569');
  });

  it('rejects an out-of-range month', () => {
    expect(formatThaiMonthYearFromMonthInputValue('2026-00')).toBe('-');
    expect(formatThaiMonthYearFromMonthInputValue('2026-13')).toBe('-');
  });

  it('rejects a month without a leading zero (must be exactly "YYYY-MM")', () => {
    expect(formatThaiMonthYearFromMonthInputValue('2026-7')).toBe('-');
  });

  it('falls back to a dash for empty/nullish input', () => {
    expect(formatThaiMonthYearFromMonthInputValue('')).toBe('-');
    expect(formatThaiMonthYearFromMonthInputValue(null)).toBe('-');
    expect(formatThaiMonthYearFromMonthInputValue(undefined)).toBe('-');
  });

  it('rejects a full date, not just a month ("YYYY-MM-DD" is not "YYYY-MM")', () => {
    expect(formatThaiMonthYearFromMonthInputValue('2026-07-01')).toBe('-');
  });

  it('rejects non-date garbage, full-width digits, and leading whitespace', () => {
    expect(formatThaiMonthYearFromMonthInputValue('abc')).toBe('-');
    // Full-width (zenkaku) digits are not matched by \d in a non-unicode-flag regex.
    expect(formatThaiMonthYearFromMonthInputValue('２０２６-０７')).toBe('-');
    expect(formatThaiMonthYearFromMonthInputValue(' 2026-07')).toBe('-');
  });
});

describe('formatShortDate', () => {
  // `process` isn't in this project's eslint browser globals (see eslint.config.js) even though it's
  // available at runtime under vitest/Node — go through `globalThis` rather than the bare identifier
  // so lint doesn't need a per-line exception.
  const originalTz = globalThis.process.env.TZ;

  afterEach(() => {
    globalThis.process.env.TZ = originalTz;
  });

  it('falls back to a dash for empty/nullish/invalid input', () => {
    expect(formatShortDate('')).toBe('-');
    expect(formatShortDate(null)).toBe('-');
    expect(formatShortDate(undefined)).toBe('-');
    expect(formatShortDate('abc')).toBe('-');
  });

  it('formats a date-only "YYYY-MM-DD" value as DD/MM/BE-year', () => {
    expect(formatShortDate('2026-07-26')).toBe('26/07/2569');
    expect(formatShortDate('2026-01-05')).toBe('05/01/2569');
  });

  // F6: `new Date('2026-07-26')` parses a date-only ISO string as UTC midnight, so reading it back
  // with local getters rolled to the previous day in negative-offset zones. The fix parses the
  // "YYYY-MM-DD" prefix directly instead of going through `new Date()`, so this must hold regardless
  // of the runtime's timezone -- pin it down under a negative-offset zone specifically (measured
  // wrong before the fix: America/New_York and Pacific/Honolulu both showed 25/07/2569).
  it('is timezone-independent for a date-only value, incl. under a negative UTC offset', () => {
    globalThis.process.env.TZ = 'Pacific/Honolulu';
    expect(formatShortDate('2026-07-26')).toBe('26/07/2569');

    globalThis.process.env.TZ = 'America/New_York';
    expect(formatShortDate('2026-07-26')).toBe('26/07/2569');

    globalThis.process.env.TZ = 'Asia/Bangkok';
    expect(formatShortDate('2026-07-26')).toBe('26/07/2569');
  });

  // A value that IS a real instant (has a time/offset component, e.g. an attendance punch
  // timestamp) is deliberately NOT parsed via the date-only fast path above -- it keeps going
  // through `new Date()` + local getters, which is correct: an instant should display in whatever
  // zone is viewing it, not the zone it was recorded in.
  it('reads a full ISO datetime (a real instant) back in the local zone, unchanged from before', () => {
    globalThis.process.env.TZ = 'Asia/Bangkok';
    expect(formatShortDate('2026-07-02T08:11:00+07:00')).toBe('02/07/2569');

    // Same instant, viewed from a zone far enough behind UTC that the calendar date rolls back --
    // this is expected/correct for a real timestamp, unlike the date-only case above.
    globalThis.process.env.TZ = 'Pacific/Honolulu';
    expect(formatShortDate('2026-07-02T08:11:00+07:00')).toBe('01/07/2569');
  });
});
