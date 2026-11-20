// The delivery-rounds read, shared by the panel and by the deal page (which puts the panel's primary action in its sticky bar).
import { api } from '../../../api/index.js';
import { queryKeys } from '../../../api/queryKeys.js';

const OPEN = ['DRAFT', 'SENT', 'RETURNED', 'FORWARDED'];

export function deliveryRoundsQueryOptions(ticketId) {
  return {
    queryKey: queryKeys.deliveryRequests(ticketId),
    queryFn: () => api.deliveryRequests.list(ticketId),
    enabled: Boolean(ticketId),
  };
}

/** The open round the rep works with: the sales-side one (DRAFT / SENT / RETURNED) before a FORWARDED one waiting on the issuer. */
export function openRoundOf(data) {
  const open = data?.rounds?.filter((r) => OPEN.includes(r.status)) ?? [];
  return open.find((r) => r.status !== 'FORWARDED') ?? open[0] ?? null;
}

/** Every round that is not finished, oldest first: all of them are rendered (a voided round can wait beside a newer one). */
export function openRoundsOf(data) {
  return (data?.rounds ?? []).filter((r) => OPEN.includes(r.status)).sort((a, b) => a.roundNo - b.roundNo);
}

/** Whether this viewer has "ขอออกใบกำกับ / ส่งของ" to press: a round may start, or the open one is still the rep's. */
export function canRequestDelivery(data) {
  return Boolean(data && (data.canStart || openRoundOf(data)?.viewer.canEdit));
}

export const REQUEST_DELIVERY_LABEL = 'ขอออกใบกำกับ / ส่งของ';

/** The rep's one action on the order: send the request, or send a returned one again. */
export function requestActionLabel(data) {
  const round = openRoundOf(data);
  return round?.status === 'RETURNED' && round.viewer.canEdit ? 'แก้ไขแล้วส่งอีกครั้ง' : REQUEST_DELIVERY_LABEL;
}

/** Everything an INVOICED round covers has been delivered: it is finished (nothing left to record). */
export function isDelivered(round) {
  return round.status === 'INVOICED' && round.lines.length > 0
    && round.lines.every((l) => Number(l.deliveredQty || 0) >= Number(l.coveredQty));
}

/**
 * The deal page's one primary on the ส่งของ tab: บันทึกการส่งสินค้า on the oldest round the viewer may record, or else the
 * request action. A sales-side round older than that round (still the rep's to send) keeps the primary instead.
 */
export function shipPrimaryOf(data) {
  const record = (data?.rounds ?? []).filter((r) => r.viewer?.canRecordDelivery).sort((a, b) => a.roundNo - b.roundNo)[0] ?? null;
  const sales = openRoundOf(data);
  if (record && !(sales?.viewer?.canEdit && sales.roundNo < record.roundNo)) return { kind: 'record', round: record };
  return canRequestDelivery(data) ? { kind: 'request' } : null;
}
