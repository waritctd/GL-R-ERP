// งานทั้งหมด (/tickets): which view opens, the per-user remembered choice, and the board's own search / order /
// closed-deal counts. Presentation only — every number on the cards is served (SalesBoardRepository's BoardDeal).

/**
 * URL params only the TABLE understands. A deep link carrying one (e.g. ภาพรวมทีมขาย's `/tickets?owner=…`, or the
 * board's own พักไว้ / เสียงาน / ยกเลิก links' `life=`) opens the table, because the board would silently drop the
 * filter. `q` is not here: both views search.
 */
export const TABLE_ONLY_PARAMS = ['owner', 'phase', 'life', 'flag', 'week', 'from', 'to', 'inbox'];

const VIEWS = new Set(['board', 'table']);

/** `?view=` wins; then a table-only filter means the table; then the user's last choice; else the board. */
export function resolveDealsView(searchParams, stored) {
  const explicit = searchParams.get('view');
  if (VIEWS.has(explicit)) return explicit;
  if (TABLE_ONLY_PARAMS.some((key) => searchParams.has(key))) return 'table';
  return VIEWS.has(stored) ? stored : 'board';
}

export const viewStorageKey = (user) => `glr.allDeals.view.${user?.employeeId ?? user?.id ?? 'anon'}`;

/** The remembered view, or null. Storage can be absent or throw (private window, blocked site data), and this repo's
 * Node ships a broken global `localStorage` under the suite — see quotationPrefs.test.js. */
export function readStoredView(user) {
  try {
    return globalThis.localStorage?.getItem(viewStorageKey(user)) ?? null;
  } catch {
    return null;
  }
}

export function writeStoredView(user, view) {
  try {
    globalThis.localStorage?.setItem(viewStorageKey(user), view);
  } catch {
    // A convenience only: the page works the same without it.
  }
}

/** ค้นหาโครงการ ลูกค้า ผู้ออกแบบ — the card's title too, since it is the project as the rep named it. */
export function boardDealMatches(deal, query) {
  const needle = (query ?? '').trim().toLowerCase();
  if (!needle) return true;
  return [deal.title, deal.projectName, deal.customerName, deal.designerName, ...(deal.orderCodes ?? [])]
    .filter(Boolean).join(' ').toLowerCase().includes(needle);
}

/** เรียงตามวันที่เงียบ: the longest-quiet deal first, ticket id breaking ties. Returns a new array. */
export const sortByQuiet = (deals) => [...(deals ?? [])]
  .sort((a, b) => (b.daysQuiet ?? 0) - (a.daysQuiet ?? 0) || a.ticketId - b.ticketId);

/** The paused and closed deals the board leaves out, counted over the ticket list the table would show. */
export function closedCounts(tickets) {
  const counts = { ON_HOLD: 0, DORMANT: 0, CLOSED_LOST: 0, CANCELLED: 0 };
  for (const ticket of tickets ?? []) {
    if (Object.hasOwn(counts, ticket.lifecycle)) counts[ticket.lifecycle] += 1;
  }
  return counts;
}
