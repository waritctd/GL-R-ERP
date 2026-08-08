import { afterEach, describe, expect, it } from 'vitest';
import {
  addDaysIso,
  attendanceSourceLabel,
  bangkokMonthStartIso,
  factoryQuoteStatusLabel,
  formatAddress,
  formatMoney,
  formatShortDate,
  formatThaiMonthYearFromMonthInputValue,
  greetingName,
  overtimeStatusLabel,
  pricingCostingStatusLabel,
  pricingDecisionStatusLabel,
  quotationStatusLabel,
  specialMoneyStatusLabel,
  SPECIAL_MONEY_STATUSES,
} from './format.js';

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

// Issue #395: every dashboard greeting used to prepend "คุณ" unconditionally,
// which doubled up whenever the resolved name already carried an honorific —
// "สวัสดี คุณคุณสมหมาย ขายดี" for a `user.name` seeded as "คุณสมหมาย ขายดี".
// Root cause was inconsistent name data (some rows already have a leading
// honorific, some don't), not a case for stripping/rewriting the stored
// name — so the greeting now detects an existing honorific instead.
describe('greetingName', () => {
  it('prepends "คุณ" for a name with no honorific (the common case: an employee nickname)', () => {
    expect(greetingName('ภูมิ')).toBe('คุณภูมิ');
    expect(greetingName('สมชาย ใจดี')).toBe('คุณสมชาย ใจดี');
  });

  it('does not double "คุณ" when the name already starts with it', () => {
    expect(greetingName('คุณสมหมาย ขายดี')).toBe('คุณสมหมาย ขายดี');
  });

  it('does not add "คุณ" on top of a นาย/นาง/นางสาว/ดร. title already in the name', () => {
    expect(greetingName('นายสมชาย ใจดี')).toBe('นายสมชาย ใจดี');
    expect(greetingName('นางสมหญิง ใจดี')).toBe('นางสมหญิง ใจดี');
    expect(greetingName('นางสาวปิยะนุช รุ่งเรือง')).toBe('นางสาวปิยะนุช รุ่งเรือง');
    expect(greetingName('ดร.วิชัย ธนาคาร')).toBe('ดร.วิชัย ธนาคาร');
  });

  it('returns an empty string for a missing name instead of a bare "คุณ"', () => {
    expect(greetingName('')).toBe('');
    expect(greetingName(null)).toBe('');
    expect(greetingName(undefined)).toBe('');
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

describe('pricing workflow status labels', () => {
  it('maps backend pricing workflow codes to user-facing Thai labels', () => {
    expect(factoryQuoteStatusLabel('READY_FOR_COSTING')).toMatchObject({ label: 'พร้อมคำนวณต้นทุน', tone: 'success' });
    expect(pricingDecisionStatusLabel('RETURNED')).toMatchObject({ label: 'ตีกลับให้แก้ไข', tone: 'danger' });
    expect(quotationStatusLabel('REVISION_REQUESTED')).toMatchObject({ label: 'ลูกค้าขอแก้ไข', tone: 'warning' });
  });

  it('keeps stale costing visible without exposing the raw backend code', () => {
    expect(pricingCostingStatusLabel('CALCULATED', { stale: true }))
      .toMatchObject({ label: 'คำนวณแล้ว · ต้องคำนวณใหม่', tone: 'warning' });
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

describe('specialMoneyStatusLabel', () => {
  // Welfare is CEO-only in a single stage (#482 removed the manager stage), so
  // SUBMITTED means "waiting for the CEO". It used to read 'รอผู้จัดการ', which
  // named a holder that no longer exists in the flow -- a wrong next-actor is
  // worse than a vague one, because the employee chases the wrong person.
  it('says the CEO holds a SUBMITTED request, not a manager', () => {
    expect(specialMoneyStatusLabel('SUBMITTED').label).toBe('รอ CEO อนุมัติ');
    expect(specialMoneyStatusLabel('SUBMITTED').label).not.toContain('ผู้จัดการ');
  });

  // MANAGER_APPROVED is unreachable for new requests but still in the enum and
  // the chk_smr_status constraint, and SpecialMoneyService still clears such
  // rows. It sits in the same queue as SUBMITTED, so it must not read as a
  // further-along step -- only as an older request in the same place.
  it('puts the legacy MANAGER_APPROVED state in the same CEO queue', () => {
    expect(specialMoneyStatusLabel('MANAGER_APPROVED').label).toContain('รอ CEO');
    expect(specialMoneyStatusLabel('MANAGER_APPROVED').tone)
      .toBe(specialMoneyStatusLabel('SUBMITTED').tone);
  });

  // The panel's status filter renders straight from this list. Guards against
  // the drift that caused this bug: an inline copy of the options in the panel
  // that nobody updated when the flow changed.
  it('covers every SpecialMoneyStatus enum value with a real Thai label', () => {
    expect(SPECIAL_MONEY_STATUSES)
      .toEqual(['SUBMITTED', 'MANAGER_APPROVED', 'APPROVED', 'REJECTED', 'CANCELLED']);
    SPECIAL_MONEY_STATUSES.forEach((status) => {
      expect(specialMoneyStatusLabel(status).label).not.toBe(status);
    });
  });
});

// A1 (OT UAT defect #1): unlike welfare above, overtime has TWO real approval routes per request
// (see OvertimeRepository#resolvePendingApproverRole) -- SUBMITTED means "waiting for a division
// manager" on most requests, but "waiting for the CEO directly" when the employee's ฝ่าย has no
// manager or the requester IS one. A status-only map cannot tell the two apart, so it used to label
// EVERY SUBMITTED row 'รอผู้จัดการ' even on the manager-less route -- the exact mislabel
// specialMoneyStatusLabel already fixed for welfare, except welfare could use one static string
// because ALL its requests are CEO-only, and overtime's route varies per row.
describe('overtimeStatusLabel', () => {
  it('reads รอผู้จัดการ for a SUBMITTED request with no role given, so existing 1-arg callers keep working', () => {
    expect(overtimeStatusLabel('SUBMITTED').label).toBe('รอผู้จัดการ');
  });

  it('reads รอผู้จัดการ for a SUBMITTED request explicitly routed through a manager', () => {
    expect(overtimeStatusLabel('SUBMITTED', 'manager').label).toBe('รอผู้จัดการ');
  });

  it('reads รอ CEO, not รอผู้จัดการ, for a SUBMITTED request with no manager stage', () => {
    expect(overtimeStatusLabel('SUBMITTED', 'ceo').label).toBe('รอ CEO');
    expect(overtimeStatusLabel('SUBMITTED', 'ceo').label).not.toContain('ผู้จัดการ');
    // Same tone as MANAGER_APPROVED -- from the employee's point of view both states mean the CEO
    // holds this now.
    expect(overtimeStatusLabel('SUBMITTED', 'ceo').tone).toBe(overtimeStatusLabel('MANAGER_APPROVED').tone);
  });

  it('leaves every other status unaffected by pendingApproverRole', () => {
    expect(overtimeStatusLabel('MANAGER_APPROVED', 'ceo')).toEqual(overtimeStatusLabel('MANAGER_APPROVED'));
    expect(overtimeStatusLabel('APPROVED', 'ceo')).toEqual(overtimeStatusLabel('APPROVED'));
    expect(overtimeStatusLabel('REJECTED', 'ceo')).toEqual(overtimeStatusLabel('REJECTED'));
  });
});

// Attendance date stepper bug: `new Date(iso + 'T00:00:00+07:00').toISOString().slice(0,10)`
// reads the UTC calendar day off a Bangkok-midnight instant, which is one day behind the Bangkok
// date intended -- netting a 2-day back-step and a stuck forward-step on the attendance page.
// addDaysIso replaces that with pure YYYY-MM-DD calendar-field arithmetic.
describe('addDaysIso', () => {
  it('stepping +1 then -1 returns the original date', () => {
    expect(addDaysIso(addDaysIso('2026-08-15', 1), -1)).toBe('2026-08-15');
  });

  it('steps a single day forward and backward with no timezone-induced skew', () => {
    expect(addDaysIso('2026-08-15', 1)).toBe('2026-08-16');
    expect(addDaysIso('2026-08-15', -1)).toBe('2026-08-14');
  });

  it('crosses a month boundary', () => {
    expect(addDaysIso('2026-08-31', 1)).toBe('2026-09-01');
    expect(addDaysIso('2026-09-01', -1)).toBe('2026-08-31');
  });

  it('crosses a year boundary', () => {
    expect(addDaysIso('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDaysIso('2027-01-01', -1)).toBe('2026-12-31');
  });
});

// bangkokMonthStartIso's monthsBack param (attendance page's 3-month-back browsable window) walks
// calendar months on the Bangkok wall-clock date via a flat "months since epoch" count, so a
// year rollover falls out of the Math.floor/modulo for free -- these pin that specific claim.
describe('bangkokMonthStartIso monthsBack', () => {
  it('defaults to the exact current month with no monthsBack argument', () => {
    expect(bangkokMonthStartIso(new Date('2026-08-15T10:00:00Z'))).toBe('2026-08-01');
  });

  it('walks back within the same year when it does not cross a boundary', () => {
    expect(bangkokMonthStartIso(new Date('2026-08-15T10:00:00Z'), 2)).toBe('2026-06-01');
  });

  it('rolls a February start back 3 months into the previous November', () => {
    expect(bangkokMonthStartIso(new Date('2026-02-10T10:00:00Z'), 3)).toBe('2025-11-01');
  });

  it('rolls back across two year boundaries', () => {
    expect(bangkokMonthStartIso(new Date('2026-01-15T10:00:00Z'), 14)).toBe('2024-11-01');
  });
});
