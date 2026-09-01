import { describe, expect, it } from 'vitest';

import { LEAVE_HOURS_PER_DAY, formatDays, formatDaysOrDash } from './leaveFormatting.js';

// LeaveService's own literal, TRANSCRIBED rather than imported from the module under test.
//
// This matters and was caught by mutation-checking, not by reading: the first cut of this file
// derived the fixture from `LEAVE_HOURS_PER_DAY`, so flipping that constant to 9 moved BOTH sides
// of the round trip and the suite still passed 88 of 96 cases -- the mock-mirrors-the-computation
// failure shape CLAUDE.md records for `computeDraftEtag`. Hardcoded, a wrong workday length fails
// all 96. If LeaveService's STANDARD_WORKDAY_MINUTES ever changes, this line changes by hand.
const BACKEND_WORKDAY_MINUTES = 8 * 60;

// Mirrors LeaveService#computeTotalDays' sub-day branch: `minutes / STANDARD_WORKDAY_MINUTES`,
// HALF_UP to 2dp. The round trip below is therefore measured against what the backend actually
// stores, not against this module's own arithmetic run backwards.
function storeAsDayCount(minutes) {
  const exact = (minutes / BACKEND_WORKDAY_MINUTES) * 100;
  // HALF_UP, not JS `Math.round` -- they differ on negatives, and a value landing exactly on .5 is
  // where a naive round drifts. Day counts here are positive, but the mirror should be the
  // backend's rule rather than a coincidence that happens to agree with it.
  return Math.floor(exact + 0.5) / 100;
}

// Every expectation below is written with ordinary spaces and run through `d`, which re-glues each
// number to its unit with the no-break space `formatDays` uses. So the assertions stay readable AND
// stay exact about WHERE the string may wrap -- which is half the point of the change. Losing the
// glue fails every one of them, not just the one test that mentions it.
const NB = '\u00A0';
const d = (text) => text.replace(/(\d) /g, `$1${NB}`);

describe('formatDays', () => {
  it('glues each number to its unit with a no-break space, and only there', () => {
    // The wrap contract, spelled out once without the `d` helper in the way: break opportunities
    // exist BETWEEN units and nowhere else, so "6 วัน / 3 ชั่วโมง" can happen and "6 / วัน" cannot.
    expect(formatDays(6.37)).toBe(`6${NB}วัน 3${NB}ชั่วโมง`);
    expect(formatDays(6.37).split(' ')).toEqual([`6${NB}วัน`, `3${NB}ชั่วโมง`]);
    expect(formatDays(7)).toBe(`7${NB}วัน`);
    expect(formatDays(7)).not.toContain('7 วัน');
  });

  it('renders a whole day count exactly as it did before this change', () => {
    expect(formatDays(7)).toBe(d('7 วัน'));
    expect(formatDays(1)).toBe(d('1 วัน'));
    expect(formatDays(98)).toBe(d('98 วัน'));
    expect(formatDays(366)).toBe(d('366 วัน'));
  });

  it('renders the reported row: 0.38 used, 6.37 remaining', () => {
    // The exact pair from the bug report ("0.38 วัน · เหลือ 6.37 วัน") -- a flat three-hour ลากิจ
    // against a 7-day quota. 0.38 IS three hours; 6.37 is six days and three hours.
    expect(formatDays(0.38)).toBe(d('3 ชั่วโมง'));
    expect(formatDays(6.37)).toBe(d('6 วัน 3 ชั่วโมง'));
  });

  it('renders a day-and-hours balance', () => {
    expect(formatDays(1.63)).toBe(d('1 วัน 5 ชั่วโมง'));
    expect(formatDays(0.5)).toBe(d('4 ชั่วโมง'));
    expect(formatDays(6.5)).toBe(d('6 วัน 4 ชั่วโมง'));
  });

  it('renders minutes, and combines all three units', () => {
    expect(formatDays(storeAsDayCount(30))).toBe(d('30 นาที'));
    expect(formatDays(storeAsDayCount(105))).toBe(d('1 ชั่วโมง 45 นาที'));
    expect(formatDays(1 + storeAsDayCount(345))).toBe(d('1 วัน 5 ชั่วโมง 45 นาที'));
  });

  it('drops zero-valued units instead of padding them', () => {
    expect(formatDays(2)).not.toContain('ชั่วโมง');
    expect(formatDays(0.38)).not.toContain('วัน');
    expect(formatDays(6.37)).not.toContain('นาที');
  });

  // THE derivation test. `DISPLAY_MINUTE_STEP = 5` is only defensible if a five-minute leave
  // survives the trip through the backend's 2dp day storage and back -- otherwise the readout
  // invents or loses minutes the requester actually entered. All 96 possible five-minute leaves
  // (0:05 .. 8:00, which subsumes every quarter- and half-hour) are asserted, not a sample.
  //
  // Mutation-checked, measured not assumed: DISPLAY_MINUTE_STEP = 1 fails this on 76 of the 96
  // cases (7 of the file's 10 tests go red), and LEAVE_HOURS_PER_DAY = 9 fails it on all 96.
  it('round-trips every five-minute leave through 2dp day storage', () => {
    // Guards the transcription above: LEAVE_HOURS_PER_DAY is what `formatDays` divides by, and this
    // suite is only evidence about the backend's day counts while the two agree.
    expect(LEAVE_HOURS_PER_DAY * 60).toBe(BACKEND_WORKDAY_MINUTES);
    const failures = [];
    for (let minutes = 5; minutes <= BACKEND_WORKDAY_MINUTES; minutes += 5) {
      const hours = Math.floor(minutes / 60);
      const rest = minutes % 60;
      const expected = minutes === BACKEND_WORKDAY_MINUTES
        ? d('1 วัน')
        : [hours > 0 ? d(`${hours} ชั่วโมง`) : null, rest > 0 ? d(`${rest} นาที`) : null].filter(Boolean).join(' ');
      const actual = formatDays(storeAsDayCount(minutes));
      if (actual !== expected) failures.push({ minutes, expected, actual });
    }
    expect(failures).toEqual([]);
  });

  it('reads a null/undefined/empty day count as zero, and never emits NaN', () => {
    expect(formatDays(0)).toBe(d('0 วัน'));
    expect(formatDays(null)).toBe(d('0 วัน'));
    expect(formatDays(undefined)).toBe(d('0 วัน'));
    expect(formatDays('')).toBe(d('0 วัน'));
    // Previously "NaN วัน" reached the DOM here.
    expect(formatDays('ไม่ทราบ')).toBe(d('0 วัน'));
    expect(formatDays(Number.NaN)).toBe(d('0 วัน'));
  });

  it('keeps the sign on an over-quota (negative) balance', () => {
    expect(formatDays(-0.5)).toBe(d('-4 ชั่วโมง'));
    expect(formatDays(-1.5)).toBe(d('-1 วัน 4 ชั่วโมง'));
  });

  it('accepts the string day counts the API actually returns', () => {
    // NUMERIC(5,2) arrives over JSON as a string in some client paths; the old implementation
    // coerced too, so this pins that it still does.
    expect(formatDays('6.37')).toBe(d('6 วัน 3 ชั่วโมง'));
    expect(formatDays('0.38')).toBe(d('3 ชั่วโมง'));
  });
});

describe('formatDaysOrDash', () => {
  it('still distinguishes "not known" from "genuinely zero"', () => {
    expect(formatDaysOrDash(null)).toBe('-');
    expect(formatDaysOrDash(undefined)).toBe('-');
    expect(formatDaysOrDash(0)).toBe(d('0 วัน'));
    expect(formatDaysOrDash(0.38)).toBe(d('3 ชั่วโมง'));
  });
});
