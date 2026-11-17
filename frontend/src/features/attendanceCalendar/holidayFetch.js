// Pure helpers for the วันหยุด tab's "ดึงข้อมูลวันหยุดจาก ธปท." (BOT fetch) affordance.

/** Turns `POST /api/holidays/fetch`'s `{ outcomes: FetchOutcome[] }` body (`FetchOutcome(year, holidayCount,
 * applied)` — see BotHolidayFetchService.java) into render-ready rows. */
export function summarizeFetchOutcomes(outcomes) {
  const rows = (Array.isArray(outcomes) ? outcomes : []).map((outcome) => {
    const holidayCount = Number.isFinite(outcome?.holidayCount) ? outcome.holidayCount : 0;
    const applied = !!outcome?.applied;
    return {
      year: outcome?.year ?? null,
      holidayCount,
      applied,
      ok: applied && holidayCount > 0,
    };
  });
  return {
    rows,
    anySuccess: rows.some((row) => row.ok),
    allZero: rows.length === 0 || rows.every((row) => !row.ok),
  };
}

// BotHolidayFetchService#fetchNow's 429 message is
// "รอสักครู่ก่อนเรียกดึงข้อมูลวันหยุดจากธนาคารแห่งประเทศไทยอีกครั้ง (ประมาณ N วินาที)" — this pulls N back out so
// the UI can run its own live countdown instead of just echoing a number that goes stale the instant it's rendered.
const COOLDOWN_SECONDS_PATTERN = /(\d+)\s*วินาที/;

/** The remaining-seconds figure embedded in a 429 cooldown message, or `null` if none is found (e.g. the
 * message shape changes) — callers should fall back to a sane default, never crash. */
export function parseCooldownSeconds(message) {
  const match = COOLDOWN_SECONDS_PATTERN.exec(typeof message === 'string' ? message : '');
  if (!match) return null;
  const value = Number(match[1]);
  return Number.isFinite(value) && value > 0 ? value : null;
}
