// CR-1 (GLA-167): pure helpers for the factory-contact flow and the lead-time change flow on the
// pricing-request page. No React, no api — everything here is unit-testable on its own.

const THAI_SHORT_MONTHS = ['ม.ค.', 'ก.พ.', 'มี.ค.', 'เม.ย.', 'พ.ค.', 'มิ.ย.', 'ก.ค.', 'ส.ค.', 'ก.ย.', 'ต.ค.', 'พ.ย.', 'ธ.ค.'];

/** Today's calendar date in Asia/Bangkok as YYYY-MM-DD — the same zone FactoryQuoteService uses to
 * decide whether a contacted-on date is "in the future", so the picker's `max` agrees with the 400. */
export function bangkokToday(now = new Date()) {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Bangkok' }).format(now);
}

/** '2026-10-01' -> '1 ต.ค.' (a bare LocalDate is parsed by hand: `new Date('2026-10-01')` is UTC midnight). */
export function formatShortThaiDay(isoDate) {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(isoDate ?? '');
  if (!match) return '';
  const month = THAI_SHORT_MONTHS[Number(match[2]) - 1];
  return month ? `${Number(match[3])} ${month}` : '';
}

export const LEAD_TIME_UNKNOWN = 'ยังไม่ระบุ';

/** 75, 90 -> '75–90' · 60, 60 -> '60' · nothing -> 'ยังไม่ระบุ' (never a bare dash). */
export function leadTimeRangeText(min, max) {
  if (min == null && max == null) return LEAD_TIME_UNKNOWN;
  if (min != null && max != null && Number(min) === Number(max)) return String(min);
  return `${min ?? '?'}–${max ?? '?'}`;
}

/** '75–90 วัน', or 'ยังไม่ระบุ' with no unit when the lead time is unknown. */
export function leadTimeWithUnit(min, max) {
  return min == null && max == null ? LEAD_TIME_UNKNOWN : `${leadTimeRangeText(min, max)} วัน`;
}

/** A DRAFT quote has not been marked ติดต่อโรงงานแล้ว yet; every other status is past that step. */
export function isFactoryContacted(quote) {
  return Boolean(quote) && quote.status !== 'DRAFT';
}

/**
 * Who marked the factory contacted. FactoryQuoteDto carries only `contactedBy` (an id), so the name
 * is read off the request's own FACTORY_CONTACTED event (or the legacy FACTORY_EMAIL_SENT one) by the same actor.
 * Null when it cannot be resolved (the chip then simply omits "· โดย ...").
 */
export function contactedByName(quote, events = []) {
  if (!quote || quote.contactedBy == null) return null;
  const byActor = [...events].reverse().filter((event) => event?.actorId === quote.contactedBy);
  const names = (event) => (!quote.factoryName || String(event.message ?? '').includes(quote.factoryName));
  // Newest evidence first: the CR-1 event for this factory, then — for a quote backfilled from the old
  // "ส่งแล้ว" step (R4) — the legacy FACTORY_EMAIL_SENT event, by the same actor.
  const hit = byActor.find((e) => e.eventKind === 'FACTORY_CONTACTED' && names(e))
    ?? byActor.find((e) => e.eventKind === 'FACTORY_EMAIL_SENT' && names(e))
    ?? byActor.find((e) => e.eventKind === 'FACTORY_EMAIL_SENT');
  return hit?.actorName ?? null;
}

/** The unit/currency pair Sales fixed on a line; null when the line is legacy (carries neither). */
export function requestedTerms(requestItem) {
  if (!requestItem?.requestedCurrency && !requestItem?.requestedPriceUnitBasis) return null;
  return {
    currency: requestItem.requestedCurrency ?? null,
    unitBasis: requestItem.requestedPriceUnitBasis ?? null,
  };
}

/** True once a line has BOTH halves of the locked terms (currency AND price unit). */
export function hasLockedTerms(requestItem) {
  return Boolean(requestItem?.requestedCurrency && requestItem?.requestedPriceUnitBasis);
}

/** Distinct "<CUR> · ต่อ <unit>" pairs across a factory's lines, in first-seen order. */
export function lockedTermsList(requestItems, unitLabelOf) {
  const seen = new Set();
  const out = [];
  for (const item of requestItems) {
    if (!hasLockedTerms(item)) continue;
    const key = `${item.requestedCurrency}|${item.requestedPriceUnitBasis}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(`${item.requestedCurrency} · ต่อ ${unitLabelOf(item.requestedPriceUnitBasis)}`);
  }
  return out;
}

/** Mirrors LeadTimeChangeService: a stock line (stockSource set) can never be in a lead-time change. */
export function isImportLine(requestItem) {
  return !requestItem?.stockSource;
}

/** Pending / decided split of one pricing request's lead-time changes, newest first. */
export function splitLeadTimeChanges(changes = []) {
  const byNewest = [...changes].sort((a, b) => b.id - a.id);
  return {
    pending: byNewest.filter((change) => change.status === 'PENDING'),
    decided: byNewest.filter((change) => change.status === 'APPROVED' || change.status === 'REJECTED'),
  };
}

export const LEAD_TIME_MAX_DAYS = 3650;

/** Client-side mirror of LeadTimeChangeService#validLines for one ticked line (server stays authoritative). */
export function isValidLeadTimeRange(min, max) {
  const lo = Number(min);
  const hi = Number(max);
  return min !== '' && max !== '' && Number.isInteger(lo) && Number.isInteger(hi)
    && lo >= 1 && lo <= hi && hi <= LEAD_TIME_MAX_DAYS;
}
