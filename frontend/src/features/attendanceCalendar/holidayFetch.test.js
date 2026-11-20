import { describe, expect, it } from 'vitest';
import { parseCooldownSeconds, summarizeFetchOutcomes } from './holidayFetch.js';

describe('summarizeFetchOutcomes', () => {
  it('is honest about a MIXED result — one year imported, the next not yet published — rather than letting the good year hide the bad one', () => {
    const result = summarizeFetchOutcomes([
      { year: 2026, holidayCount: 16, applied: true },
      { year: 2027, holidayCount: 0, applied: false },
    ]);
    expect(result.anySuccess).toBe(true);
    expect(result.allZero).toBe(false);
    expect(result.rows).toEqual([
      { year: 2026, holidayCount: 16, applied: true, ok: true },
      { year: 2027, holidayCount: 0, applied: false, ok: false },
    ]);
  });
});

describe('parseCooldownSeconds', () => {
  it('extracts the seconds figure from the real BotHolidayFetchService 429 message', () => {
    expect(parseCooldownSeconds(
      'รอสักครู่ก่อนเรียกดึงข้อมูลวันหยุดจากธนาคารแห่งประเทศไทยอีกครั้ง (ประมาณ 587 วินาที)',
    )).toBe(587);
  });
});
