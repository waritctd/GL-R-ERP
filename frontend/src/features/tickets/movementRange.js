// Movement range for the deal list and the sales / manager / CEO dashboards. Pure helpers. The server
// decides "did the deal move in the range" (TicketSummaryDto.stageChangedInRange / activityInRange, from
// GET /api/tickets?movementFrom&movementTo); this module only does range arithmetic and the roll-ups on top.
import { bangkokTodayIso } from '../../utils/format.js';

const DAY = 86400000;
const ISO_RE = /^\d{4}-\d{2}-\d{2}$/;
const toUtc = (iso) => new Date(`${iso}T00:00:00Z`).getTime();

export const WEEK_KEYS = ['moved', 'activity', 'due', 'overdue', 'silent'];

export const shiftIso = (iso, days) => new Date(toUtc(iso) + days * DAY).toISOString().slice(0, 10);
export const defaultRange = (todayIso = bangkokTodayIso()) => ({ from: shiftIso(todayIso, -6), to: todayIso });
export const shiftRange = (range, days) => ({ from: shiftIso(range.from, days), to: shiftIso(range.to, days) });

// 'YYYY-MM-DD' that is a real calendar day (rejects 2026-02-30, 2026-13-01).
const isRealIsoDate = (iso) => {
  if (!ISO_RE.test(iso ?? '')) return false;
  const ms = new Date(`${iso}T00:00:00Z`).getTime();
  return Number.isFinite(ms) && new Date(ms).toISOString().slice(0, 10) === iso;
};

/**
 * Range from URL params. Absent params give the default range; so does anything unusable (a malformed or
 * impossible date, or from > to): the whole default range, never a repaired one, so a bad URL is never sent
 * to the API.
 */
export function rangeFromParams(from, to, todayIso = bangkokTodayIso()) {
  const def = defaultRange(todayIso);
  if (from == null && to == null) return def;
  return isRealIsoDate(from) && isRealIsoDate(to) && from <= to ? { from, to } : def;
}

/** Edit one edge; if that makes from > to the other edge snaps to the edited one. */
export function setRangeEdge(range, edge, iso) {
  if (edge === 'from') return { from: iso, to: iso > range.to ? iso : range.to };
  return { from: iso < range.from ? iso : range.from, to: iso };
}

/** Bangkok calendar date (YYYY-MM-DD) of an instant or a date string; null when absent. */
export function bangkokDateOf(value) {
  if (!value) return null;
  const s = String(value);
  return s.length === 10 ? s : bangkokTodayIso(new Date(s));
}

/** The later of the stage change and the last logged activity, as a Bangkok date; null if neither. */
export function lastMovementIso(deal) {
  const a = bangkokDateOf(deal.stageUpdatedAt);
  const b = bangkokDateOf(deal.lastActivityAt);
  return a && b ? (a > b ? a : b) : a ?? b ?? null;
}

const isActive = (deal) => deal.lifecycle === 'ACTIVE';

/** An ACTIVE deal whose next follow-up date is before today. */
export function isOverdue(deal, todayIso = bangkokTodayIso()) {
  const next = bangkokDateOf(deal.nextFollowUpAt);
  return isActive(deal) && Boolean(next) && next < todayIso;
}

/** Follow-up inside the range, plus overdue ones only when the range includes today. */
export function isDue(deal, range, todayIso = bangkokTodayIso()) {
  if (!isActive(deal)) return false;
  const next = bangkokDateOf(deal.nextFollowUpAt);
  if (!next) return false;
  const includesToday = todayIso >= range.from && todayIso <= range.to;
  return (next >= range.from && next <= range.to) || (includesToday && next < todayIso);
}

/** ACTIVE with neither a stage change nor an activity in the range. */
export const isNoMovement = (deal) => isActive(deal) && !deal.stageChangedInRange && !deal.activityInRange;

// The ONE definition behind every count and every row it links to: each signal considers ACTIVE deals only.
export const weekPredicates = {
  moved: (deal) => isActive(deal) && Boolean(deal.stageChangedInRange),
  activity: (deal) => isActive(deal) && Boolean(deal.activityInRange),
  due: isDue,
  overdue: (deal, _range, todayIso) => isOverdue(deal, todayIso),
  silent: isNoMovement,
};

export const sumAmount = (deals) => deals.reduce((s, d) => s + (Number(d.amountPayable) || 0), 0);

/**
 * One row per deal owner with at least one open (ACTIVE) deal, for the manager / CEO team panel.
 * `isOrdered(deal)` says whether a deal is at or after ORDER_RECEIVED (ordered vs expected value).
 * Sorted by ไม่เคลื่อนไหว desc, then name.
 */
export function summariseByOwner(deals, range, todayIso, isOrdered) {
  const byOwner = new Map();
  for (const deal of deals) {
    if (!isActive(deal) || deal.createdById == null) continue;
    let row = byOwner.get(deal.createdById);
    if (!row) {
      row = {
        ownerId: deal.createdById, ownerName: deal.createdByName || `#${deal.createdById}`,
        open: 0, moved: 0, activity: 0, overdue: 0, silent: 0, won: 0, expected: 0,
      };
      byOwner.set(deal.createdById, row);
    }
    row.open += 1;
    if (weekPredicates.moved(deal)) row.moved += 1;
    if (weekPredicates.activity(deal)) row.activity += 1;
    if (weekPredicates.overdue(deal, range, todayIso)) row.overdue += 1;
    if (weekPredicates.silent(deal)) row.silent += 1;
    row[isOrdered(deal) ? 'won' : 'expected'] += Number(deal.amountPayable) || 0;
  }
  return [...byOwner.values()].sort((a, b) => b.silent - a.silent || a.ownerName.localeCompare(b.ownerName, 'th'));
}
