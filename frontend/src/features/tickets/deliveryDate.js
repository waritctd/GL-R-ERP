import { addDaysIso, bangkokTodayIso } from '../../utils/format.js';

// วันที่ส่ง (slice 6d): how far back a delivery may be dated. The server decides (TicketService
// #backDatedDeliveryInstant, 400 outside the window); this mirrors it so the date control offers only days it accepts.

/** วันที่ส่ง may go back at most this many days (TicketService.MAX_DELIVERY_BACK_DAYS). */
export const MAX_DELIVERY_BACK_DAYS = 7;

const latest = (events, kind) => events
  .filter((e) => e?.kind === kind && e.createdAt)
  .reduce((best, e) => (!best || new Date(e.createdAt) > new Date(best.createdAt) ? e : best), null);

/**
 * The Asia/Bangkok day the order was confirmed, or null. Mirrors TicketRepository.ORDER_CONFIRMED_AT_SQL (the document
 * chain's "order confirmed"): the latest DEAL_PROMOTED_FROM_QUOTATION event, else the latest CUSTOMER_CONFIRMED.
 */
export function orderConfirmedOn(events) {
  if (!Array.isArray(events)) return null;
  const event = latest(events, 'DEAL_PROMOTED_FROM_QUOTATION') ?? latest(events, 'CUSTOMER_CONFIRMED');
  return event ? bangkokTodayIso(new Date(event.createdAt)) : null;
}

/**
 * `{ min, max, confirmedOn }` for the วันที่ส่ง control: max is today; min is the later of today − 7 and the day the
 * order was confirmed (fix round 1, L1). `confirmedOn` is that day only when it is what sets the minimum, else null.
 */
export function deliveryDateBounds(todayIso, events) {
  const windowStart = addDaysIso(todayIso, -MAX_DELIVERY_BACK_DAYS);
  const confirmed = orderConfirmedOn(events);
  const governs = Boolean(confirmed) && confirmed > windowStart;
  return { min: governs ? confirmed : windowStart, max: todayIso, confirmedOn: governs ? confirmed : null };
}
