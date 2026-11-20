import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  addDaysIso,
  bangkokMonthStartIso,
  depositNoticeStatusLabel,
  formatMoney,
  formatShortDate,
  formatThaiMonthYearFromMonthInputValue,
  greetingName,
  overtimeStatusLabel,
  paymentStageLabel,
  specialMoneyStatusLabel,
} from './format.js';

// A name that already carries an honorific must not get "คุณ" prepended again
// ("สวัสดี คุณคุณสมหมาย ขายดี"): some stored names have a leading honorific and some don't,
// so the greeting detects an existing one instead of rewriting the data.
describe('greetingName', () => {
  it('prepends "คุณ" for a name with no honorific (the common case: an employee nickname)', () => {
    expect(greetingName('ภูมิ')).toBe('คุณภูมิ');
    expect(greetingName('สมชาย ใจดี')).toBe('คุณสมชาย ใจดี');
  });

  it('does not double "คุณ" when the name already starts with it', () => {
    expect(greetingName('คุณสมหมาย ขายดี')).toBe('คุณสมหมาย ขายดี');
  });
});

// sales.deposit_notice.status only ever holds DRAFT (default) / ISSUED (#issue) / SUPERSEDED
// (#supersede) -- see format.js's comment on depositNoticeStatusLabel. Guards against an inline
// `status === 'ISSUED' ? 'ออกแล้ว' : 'ฉบับร่าง'` ternary, which rendered SUPERSEDED (and anything
// else) as an untouched draft.
describe('depositNoticeStatusLabel', () => {
  it('maps SUPERSEDED to ถูกแทนที่, distinct from DRAFT — this is the bug GLA-117 fixes', () => {
    expect(depositNoticeStatusLabel('SUPERSEDED')).toMatchObject({ label: 'ถูกแทนที่', tone: 'neutral' });
  });

  it('falls through to the raw status code for an unmapped value, never silently to ฉบับร่าง', () => {
    expect(depositNoticeStatusLabel('CANCELLED')).toMatchObject({ label: 'CANCELLED', tone: 'neutral' });
    expect(depositNoticeStatusLabel('SOME_FUTURE_STATUS')).toMatchObject({ label: 'SOME_FUTURE_STATUS', tone: 'neutral' });
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

// Not the same `formatThaiMonthYear` exported by `features/specialmoney/specialMoneyRules.js`
// (that one takes a `Date` and returns a 2-digit BE year); see the note on the export in format.js.
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
});

describe('formatShortDate', () => {
  // `process` isn't in this project's eslint browser globals even though it's available under
  // vitest/Node, so go through `globalThis` to avoid a per-line lint exception.
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

  // `new Date('2026-07-26')` parses a date-only ISO string as UTC midnight, so reading it back
  // with local getters rolls to the previous day in negative-offset zones. The date-only prefix is
  // parsed directly, so this must hold in any timezone; pin it under a negative-offset zone
  // (America/New_York and Pacific/Honolulu).
  it('is timezone-independent for a date-only value, incl. under a negative UTC offset', () => {
    globalThis.process.env.TZ = 'Pacific/Honolulu';
    expect(formatShortDate('2026-07-26')).toBe('26/07/2569');

    globalThis.process.env.TZ = 'America/New_York';
    expect(formatShortDate('2026-07-26')).toBe('26/07/2569');

    globalThis.process.env.TZ = 'Asia/Bangkok';
    expect(formatShortDate('2026-07-26')).toBe('26/07/2569');
  });

  // A value that IS a real instant is deliberately NOT parsed via the date-only fast path above -- it keeps
  // going through `new Date` + local getters, which is correct: an instant should display in whatever zone is
  // viewing it, not the zone it was recorded in.
  it('reads a full ISO datetime (a real instant) back in the local zone, unchanged from before', () => {
    globalThis.process.env.TZ = 'Asia/Bangkok';
    expect(formatShortDate('2026-07-02T08:11:00+07:00')).toBe('02/07/2569');

    // Same instant, viewed from a zone far enough behind UTC that the calendar date rolls back -- this is
    // expected/correct for a real timestamp, unlike the date-only case above.
    globalThis.process.env.TZ = 'Pacific/Honolulu';
    expect(formatShortDate('2026-07-02T08:11:00+07:00')).toBe('01/07/2569');
  });
});

describe('specialMoneyStatusLabel', () => {
  // Welfare is CEO-only in a single stage, so SUBMITTED means "waiting for the CEO"; naming a
  // manager would send the employee chasing the wrong person.
  it('says the CEO holds a SUBMITTED request, not a manager', () => {
    expect(specialMoneyStatusLabel('SUBMITTED').label).toBe('รอ CEO อนุมัติ');
    expect(specialMoneyStatusLabel('SUBMITTED').label).not.toContain('ผู้จัดการ');
  });
});

// Unlike welfare above, overtime has TWO real approval routes per request (see
// OvertimeRepository#resolvePendingApproverRole): SUBMITTED means "waiting for a division
// manager" on most requests, but "waiting for the CEO directly" when the employee's ฝ่าย has no
// manager or the requester IS one. A status-only map cannot tell the two apart, whereas welfare
// can use one static string because ALL its requests are CEO-only.
describe('overtimeStatusLabel', () => {
  it('reads รอ CEO, not รอผู้จัดการ, for a SUBMITTED request with no manager stage', () => {
    expect(overtimeStatusLabel('SUBMITTED', 'ceo').label).toBe('รอ CEO');
    expect(overtimeStatusLabel('SUBMITTED', 'ceo').label).not.toContain('ผู้จัดการ');
    // Same tone as MANAGER_APPROVED -- from the employee's point of view both states mean the CEO holds this now.
    expect(overtimeStatusLabel('SUBMITTED', 'ceo').tone).toBe(overtimeStatusLabel('MANAGER_APPROVED').tone);
  });
});

// `new Date(iso + 'T00:00:00+07:00').toISOString().slice(0,10)` reads the UTC calendar day off a
// Bangkok-midnight instant, one day behind the intended Bangkok date (a 2-day back-step and a
// stuck forward-step on the attendance page). addDaysIso uses pure YYYY-MM-DD calendar arithmetic.
describe('addDaysIso', () => {
  it('steps a single day forward and backward with no timezone-induced skew', () => {
    expect(addDaysIso('2026-08-15', 1)).toBe('2026-08-16');
    expect(addDaysIso('2026-08-15', -1)).toBe('2026-08-14');
  });

  it('crosses a year boundary', () => {
    expect(addDaysIso('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDaysIso('2027-01-01', -1)).toBe('2026-12-31');
  });
});

// bangkokMonthStartIso's monthsBack param (attendance page's 3-month-back browsable window) walks calendar
// months on the Bangkok wall-clock date via a flat "months since epoch" count, so a year rollover falls out of
// the Math.floor/modulo for free -- these pin that specific claim.
describe('bangkokMonthStartIso monthsBack', () => {
  it('rolls a February start back 3 months into the previous November', () => {
    expect(bangkokMonthStartIso(new Date('2026-02-10T10:00:00Z'), 3)).toBe('2025-11-01');
  });

  it('rolls back across two year boundaries', () => {
    expect(bangkokMonthStartIso(new Date('2026-01-15T10:00:00Z'), 14)).toBe('2024-11-01');
  });
});

// Two neutral payment-chip values, derived by the backend.
describe('paymentStageLabel — QA-16 chip values', () => {

  // statusCatalog.test.js does not cover PaymentStage, so this reads PaymentStage.java directly
  // (the technique stageCatalog.test.js uses): every backend value must have its own label.
  it('has a label for every value in PaymentStage.java (never the raw code)', () => {
    let dir = process.cwd();
    let source = null;
    for (let depth = 0; depth < 8 && source === null; depth += 1) {
      const candidate = resolve(dir, 'backend/src/main/java/th/co/glr/hr/ticket/PaymentStage.java');
      if (existsSync(candidate)) source = readFileSync(candidate, 'utf8');
      dir = dirname(dir);
    }
    if (source === null) throw new Error('could not find PaymentStage.java above the working directory');
    const codes = [...source.matchAll(/public static final String \w+ = "([A-Z_]+)";/g)].map((m) => m[1]);
    expect(codes.length).toBeGreaterThanOrEqual(6);
    for (const code of codes) {
      expect(paymentStageLabel(code).label, code).not.toBe(code);
    }
  });
});
