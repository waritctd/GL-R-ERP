// The mock's copy of th.co.glr.hr.ticket.QuietRule (spec 2026-11-15 §7.5). The real rule runs in SQL on the
// server and every screen reads its result (TicketSummaryDto.stale, BoardDeal.quiet); only mockApi computes it here.
//
// Last touched = the latest of the deal's createdAt, any deal activity's createdAt, and a STAGE_CHANGED /
// QUOTATION_ISSUED event. Quiet = lifecycle ACTIVE, not in the deliver & collect board column (5), and last
// touched more than QUIET_DAYS days ago.
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';

export const QUIET_DAYS = 14;
export const DELIVER_AND_COLLECT_COLUMN = 5;
const DAY_MS = 86_400_000;
const TOUCH_EVENT_KINDS = new Set(['STAGE_CHANGED', 'QUOTATION_ISSUED']);

const STAGE_META = new Map(DEAL_STAGE_CATALOG.stages.map((s) => [s.code, s]));

/** DealStage.repCodeOf — null for an unknown stage. */
export function repCodeOf(stage) {
  return STAGE_META.get(stage)?.repCode ?? null;
}

/** DealStage.boardColumnOf — 0 for an unknown stage. */
export function boardColumnOf(stage) {
  return STAGE_META.get(stage)?.boardColumn ?? 0;
}

const time = (iso) => (iso ? new Date(iso).getTime() : Number.NaN);

/** QuietRule.lastTouchedSql, as epoch ms. */
export function lastTouchedMs(ticket, activities) {
  const times = [
    time(ticket?.createdAt),
    ...(activities ?? []).map((a) => time(a.createdAt)),
    ...(ticket?.events ?? []).filter((e) => TOUCH_EVENT_KINDS.has(e.kind)).map((e) => time(e.createdAt)),
  ].filter((t) => !Number.isNaN(t));
  return times.length ? Math.max(...times) : Number.NaN;
}

/** Whole days since the last touch (floor), never negative. */
export function daysQuietOf(ticket, activities, now = Date.now()) {
  const last = lastTouchedMs(ticket, activities);
  if (Number.isNaN(last)) return 0;
  return Math.max(0, Math.floor((now - last) / DAY_MS));
}

/** QuietRule.quietSql. `hasUpcomingVisit` = QuietRule.upcomingVisitSql: a PLANNED visit dated today (Bangkok) or
 * later holds the deal out of quiet; the caller (mockApi) works it out from its visits. */
export function isQuiet(ticket, activities, now = Date.now(), quietDays = QUIET_DAYS, hasUpcomingVisit = false) {
  if ((ticket?.lifecycle ?? 'ACTIVE') !== 'ACTIVE') return false;
  if (hasUpcomingVisit) return false;
  if (boardColumnOf(ticket?.salesStage) === DELIVER_AND_COLLECT_COLUMN) return false;
  const last = lastTouchedMs(ticket, activities);
  return !Number.isNaN(last) && last < now - quietDays * DAY_MS;
}
