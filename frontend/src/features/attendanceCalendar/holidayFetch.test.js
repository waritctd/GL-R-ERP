import { describe, expect, it } from 'vitest';
import { parseCooldownSeconds, summarizeFetchOutcomes } from './holidayFetch.js';

describe('summarizeFetchOutcomes', () => {
  it('reports allZero and no success for an empty outcomes array (nothing attempted at all)', () => {
    const result = summarizeFetchOutcomes([]);
    expect(result.rows).toEqual([]);
    expect(result.anySuccess).toBe(false);
    expect(result.allZero).toBe(true);
  });

  it('reports allZero and no success for undefined/null (defensive — never crash on a malformed body)', () => {
    expect(summarizeFetchOutcomes(undefined).allZero).toBe(true);
    expect(summarizeFetchOutcomes(null).allZero).toBe(true);
  });

  // The production incident this branch's brief cites by name: HTTP 200, but the BOT token was
  // unset, so BotHolidayFetchService#runFetch returned List.of() before ever calling BOT.
  it('treats { applied: false, holidayCount: 0 } as a zero-row result, not a success', () => {
    const result = summarizeFetchOutcomes([{ year: 2026, holidayCount: 0, applied: false }]);
    expect(result.rows).toEqual([{ year: 2026, holidayCount: 0, applied: false, ok: false }]);
    expect(result.anySuccess).toBe(false);
    expect(result.allZero).toBe(true);
  });

  it('treats a genuine import as ok', () => {
    const result = summarizeFetchOutcomes([{ year: 2026, holidayCount: 16, applied: true }]);
    expect(result.rows[0].ok).toBe(true);
    expect(result.anySuccess).toBe(true);
    expect(result.allZero).toBe(false);
  });

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

  it('defensively treats applied:true with holidayCount:0 as not-ok even though the real service never produces that pair', () => {
    const result = summarizeFetchOutcomes([{ year: 2026, holidayCount: 0, applied: true }]);
    expect(result.rows[0].ok).toBe(false);
    expect(result.allZero).toBe(true);
  });
});

describe('parseCooldownSeconds', () => {
  it('extracts the seconds figure from the real BotHolidayFetchService 429 message', () => {
    expect(parseCooldownSeconds(
      'รอสักครู่ก่อนเรียกดึงข้อมูลวันหยุดจากธนาคารแห่งประเทศไทยอีกครั้ง (ประมาณ 587 วินาที)',
    )).toBe(587);
  });

  it('handles a single-digit remainder', () => {
    expect(parseCooldownSeconds('... (ประมาณ 1 วินาที)')).toBe(1);
  });

  it('returns null for a message with no embedded seconds figure, no digits, or empty/non-string input', () => {
    expect(parseCooldownSeconds('ข้อความอื่นที่ไม่เกี่ยวข้อง')).toBeNull();
    expect(parseCooldownSeconds('')).toBeNull();
    expect(parseCooldownSeconds(undefined)).toBeNull();
    expect(parseCooldownSeconds(null)).toBeNull();
  });

  it('returns null for a zero-seconds match rather than a falsy-but-truthy 0', () => {
    expect(parseCooldownSeconds('(ประมาณ 0 วินาที)')).toBeNull();
  });
});
