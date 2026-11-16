// The deal page's one merged timeline (drawing a-deal, "ประวัติ"): which rows each filter keeps, and which of the
// deal's quotations "สินค้าในใบล่าสุด" reads (drawing a-deal-quote). Pure functions over data the page already has.

export const TIMELINE_FILTERS = Object.freeze([
  { id: 'all', label: 'ทั้งหมด' },
  { id: 'visits', label: 'เข้าพบ' },
  { id: 'documents', label: 'เอกสาร' },
]);

// A ticked visit is written as a SITE_VISIT deal_activity (VisitService → TicketService#recordVisitActivity); a
// MEETING is the rep's own "นัดพบ / ประชุม" log. Planned visits (sales.visit) are not served per deal, so they cannot
// be in this timeline.
const VISIT_ACTIVITY_KINDS = new Set(['SITE_VISIT', 'MEETING']);

// ticket.events that record a document being made, sent, decided or replaced (EVENT_KIND_LABEL in
// DealHistoryPanel.jsx names each of them).
const DOCUMENT_EVENT_KINDS = new Set([
  'QUOTATION_ISSUED', 'QUOTATION_SENT', 'QUOTATION_ACCEPTED', 'QUOTATION_REJECTED',
  'DEAL_QUOTATION_SUBMITTED', 'DEAL_QUOTATION_APPROVED', 'DEAL_QUOTATION_REJECTED',
  'DEAL_QUOTATION_REVISION_SUBMITTED', 'DEAL_QUOTATION_REORDERED', 'DEAL_QUOTATION_SUPERSEDED',
  'DOCUMENT_ISSUED', 'DEPOSIT_NOTICE_ISSUED', 'IR_ISSUED', 'IR_SENT', 'IMPORT_REQUEST_EMAIL_SENT',
]);

/** Whether a merged-timeline row (`{ type: 'event', event }` or `{ type: 'activity', activity }`) passes `filterId`. */
export function timelineRowMatches(row, filterId) {
  if (filterId === 'visits') return row.type === 'activity' && VISIT_ACTIVITY_KINDS.has(row.activity?.kind);
  if (filterId === 'documents') return row.type === 'event' && DOCUMENT_EVENT_KINDS.has(row.event?.kind);
  return true;
}

// A price the customer was actually given: approved (direct) or issued and beyond (pricing-request origin).
const PRICED_STATUSES = new Set(['APPROVED', 'ISSUED', 'SENT', 'ACCEPTED']);
// Never "the latest": replaced, withdrawn, lapsed or turned down.
const DEAD_STATUSES = new Set(['CANCELLED', 'SUPERSEDED', 'EXPIRED', 'REJECTED']);

const newest = (rows) => rows.reduce((best, q) => (best == null || Number(q.id) > Number(best.id) ? q : best), null);

/** The deal quotation "สินค้าในใบล่าสุด" lists: the newest priced row, else the newest live one, else null. */
export function latestPricedQuotation(rows) {
  const list = rows ?? [];
  return newest(list.filter((q) => PRICED_STATUSES.has(q.docStatus)))
    ?? newest(list.filter((q) => !DEAD_STATUSES.has(q.docStatus)));
}
