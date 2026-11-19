import { describe, expect, it } from 'vitest';

import { LEAVE_HOURS_PER_DAY, formatDays } from './leaveFormatting.js';

// LeaveService's own literal, TRANSCRIBED rather than imported from the module under test. Hardcoded, a wrong
// workday length fails all 96. If LeaveService's STANDARD_WORKDAY_MINUTES ever changes, this line changes by
// hand.
const BACKEND_WORKDAY_MINUTES = 8 * 60;

// Mirrors LeaveService#computeTotalDays' sub-day branch: `minutes / STANDARD_WORKDAY_MINUTES`, HALF_UP to 2dp.
// The round trip below is therefore measured against what the backend actually stores, not against this
// module's own arithmetic run backwards.
function storeAsDayCount(minutes) {
  const exact = (minutes / BACKEND_WORKDAY_MINUTES) * 100;
  // HALF_UP, not JS `Math.round` -- they differ on negatives, and a value landing exactly on .5 is where a naive
  // round drifts. Day counts here are positive, but the mirror should be the backend's rule rather than a
  // coincidence that happens to agree with it.
  return Math.floor(exact + 0.5) / 100;
}

// Every expectation below is written with ordinary spaces and run through `d`, which re-glues each number to
// its unit with the no-break space `formatDays` uses. So the assertions stay readable AND stay exact about
// WHERE the string may wrap -- which is half the point of the change.
const NB = '\u00A0';
const d = (text) => text.replace(/(\d) /g, `$1${NB}`);

describe('formatDays', () => {
  it('renders the reported row: 0.38 used, 6.37 remaining', () => {
    // The exact pair from the bug report ("0.38 วัน · เหลือ 6.37 วัน") -- a flat three-hour ลากิจ against a 7-day
    // quota. 0.38 IS three hours; 6.37 is six days and three hours.
    expect(formatDays(0.38)).toBe(d('3 ชั่วโมง'));
    expect(formatDays(6.37)).toBe(d('6 วัน 3 ชั่วโมง'));
  });

  // THE derivation test. `DISPLAY_MINUTE_STEP = 5` is only defensible if a five-minute leave survives the trip
  // through the backend's 2dp day storage and back -- otherwise the readout invents or loses minutes the
  // requester actually entered.
  it('round-trips every five-minute leave through 2dp day storage', () => {
    // Guards the transcription above: LEAVE_HOURS_PER_DAY is what `formatDays` divides by, and this suite is only
    // evidence about the backend's day counts while the two agree.
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
});

